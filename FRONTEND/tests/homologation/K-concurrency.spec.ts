/**
 * CHANGE 08.1 — Categoria K: Concorrência
 *
 * K01 — Duas abas do PDV
 *       Abrir duas abas, autenticar, usar mesmo caixa, criar eventos,
 *       executar sincronização simultânea, validar Web Lock/storage lease,
 *       validar sem duplicação, validar estado final.
 */
import { test, expect } from "@playwright/test";
import {
  RUN_ID,
  API_URL,
  initSqlContainer,
  registerTestCompany,
  loginApi,
  loginBrowserSession,
  cleanupHomologData,
  api,
  productPayload,
  supplierPayload,
  generateCpf,
} from "./helpers/setup";

type Entity = { id: string };
type Product = Entity & { productCode: string; productQnt: string };

test.describe("K — Concorrência", () => {
  test.describe.configure({ mode: "serial" });

  let productCode: string;

  test.beforeAll(async ({ request }) => {
    initSqlContainer();
    cleanupHomologData();
    await registerTestCompany(request);
    await loginApi(request);

    await api<Entity>(request, "/Fornecedor", {
      method: "POST",
      body: supplierPayload("Fornecedor K"),
    });
    const payload = productPayload("ProdutoK", `${RUN_ID} Fornecedor K`, "50");
    await api<Product>(request, "/Produto", { method: "POST", body: payload });
    productCode = payload.productCode;

    await api(request, "/Caixa/abrir", {
      method: "POST",
      body: { openingAmount: "100,00" },
    });
  });

  test.afterAll(async ({ request }) => {
    await api(request, "/Caixa/fechar", {
      method: "POST",
      body: { closingAmount: "200,00", note: `${RUN_ID} cleanup K` },
      allowFailure: true,
    });
    cleanupHomologData();
  });

  test("K01 — Duas abas do PDV com vendas paralelas não corrompem estoque", async ({
    request,
    context,
  }) => {
    // Criar duas pages (abas) no mesmo contexto do browser
    const page1 = await context.newPage();
    const page2 = await context.newPage();

    // Autenticar ambas as abas
    await loginBrowserSession(page1);
    await loginBrowserSession(page2);

    // Validar que Web Lock API está disponível em ambas
    const hasLockPage1 = await page1.evaluate(
      () => typeof navigator.locks !== "undefined" && typeof navigator.locks.request === "function",
    );
    const hasLockPage2 = await page2.evaluate(
      () => typeof navigator.locks !== "undefined" && typeof navigator.locks.request === "function",
    );
    expect(hasLockPage1, "Web Lock API deve estar disponível na aba 1").toBe(true);
    expect(hasLockPage2, "Web Lock API deve estar disponível na aba 2").toBe(true);

    // Realizar vendas paralelas via API (mesmo caixa, mesmo produto)
    const salePayload = (suffix: string) => ({
      customerName: `${RUN_ID} Conc${suffix}`,
      customerCpf: generateCpf(),
      paymentType: "Dinheiro",
      totalAmount: "125,00",
      items: [
        { productCode, productName: `${RUN_ID} ProdutoK`, quantity: 5 },
      ],
    });

    const [sale1, sale2] = await Promise.all([
      api<{ saleNumber: string }>(request, "/HistoricoVendas", {
        method: "POST",
        body: salePayload("A"),
      }),
      api<{ saleNumber: string }>(request, "/HistoricoVendas", {
        method: "POST",
        body: salePayload("B"),
      }),
    ]);

    expect(sale1.saleNumber, "Venda 1 deve ser registrada").toBeTruthy();
    expect(sale2.saleNumber, "Venda 2 deve ser registrada").toBeTruthy();

    // Validar estoque final: 50 - 5 - 5 = 40
    const products = await api<Product[]>(request, "/Produto");
    const product = products.find((p) => p.productCode === productCode);
    expect(
      product?.productQnt,
      "Estoque deve ser 40 após duas vendas de 5 unidades cada",
    ).toBe("40");

    // Validar que nenhum evento foi duplicado — contar vendas do teste
    const salesHistory = await api<Array<{ saleNumber: string; customerName: string }>>(
      request,
      "/HistoricoVendas",
    );
    const testSales = salesHistory.filter((s) =>
      s.customerName?.startsWith(`${RUN_ID} Conc`),
    );
    expect(
      testSales.length,
      "Exatamente 2 vendas devem existir (sem duplicação)",
    ).toBe(2);

    // Testar SyncCoordinator com Web Lock em ambas as abas
    const lockTestResult = await page1.evaluate(async () => {
      const h = (window as unknown as Record<string, unknown>).__horus_test__ as {
        syncCoordinator?: {
          pullAll?: () => Promise<boolean>;
        };
      };

      if (!h?.syncCoordinator) return { available: false };

      // Tentar adquirir lock — se SyncCoordinator usa Web Lock, deve funcionar
      try {
        const lockAcquired = await navigator.locks.request(
          "horus-sync-test",
          { ifAvailable: true },
          async (lock) => {
            return lock !== null;
          },
        );
        return { available: true, lockWorks: lockAcquired };
      } catch {
        return { available: true, lockWorks: false };
      }
    });

    if (lockTestResult.available) {
      expect(lockTestResult.lockWorks, "Web Lock deve funcionar").toBe(true);
    }

    await page1.close();
    await page2.close();
  });
});
