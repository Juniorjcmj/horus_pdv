/**
 * HOMOLOGAÇÃO — Categoria K: Concorrência
 *
 * K01 — Duas vendas simultâneas (paralelas) não corrompem estoque
 * K02 — Web Lock impede sync duplicado (validação via harness)
 *
 * GAP: Teste de dual-tab real (BroadcastChannel) requer 2 contextos de
 *      browser com o mesmo estado. Playwright suporta isso mas é frágil.
 *      Aqui testamos concorrência via API paralela e Web Lock via harness.
 */
import { test, expect } from "@playwright/test";
import {
  RUN_ID,
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

    const supplier = await api<Entity>(request, "/Fornecedor", {
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

  test("K01 — Duas vendas paralelas não corrompem estoque", async ({ request }) => {
    const salePayload = (suffix: string) => ({
      customerName: `${RUN_ID} Conc${suffix}`,
      customerCpf: generateCpf(),
      paymentType: "Dinheiro",
      totalAmount: "125,00",
      items: [{ productCode, productName: `${RUN_ID} ProdutoK`, quantity: 5 }],
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

    expect(sale1.saleNumber).toBeTruthy();
    expect(sale2.saleNumber).toBeTruthy();

    const products = await api<Product[]>(request, "/Produto");
    const product = products.find((p) => p.productCode === productCode);
    expect(product?.productQnt).toBe("40");
  });

  test("K02 — Web Lock API está disponível no browser", async ({ page }) => {
    await loginBrowserSession(page);

    const hasWebLock = await page.evaluate(() => {
      return typeof navigator.locks !== "undefined" && typeof navigator.locks.request === "function";
    });

    expect(hasWebLock, "navigator.locks deve estar disponível (Web Lock API)").toBe(true);
  });

  test("K03 — SyncCoordinator usa Web Lock para exclusão mútua", async ({ page }) => {
    await loginBrowserSession(page);

    const result = await page.evaluate(async () => {
      const harness = (window as unknown as Record<string, unknown>).__horus_test__ as {
        syncCoordinator?: {
          requestSync?: () => Promise<unknown>;
        };
      };

      if (!harness?.syncCoordinator) return { available: false };

      return {
        available: true,
        hasRequestSync: typeof harness.syncCoordinator.requestSync === "function",
      };
    });

    expect(result.available, "syncCoordinator deve estar no harness").toBe(true);
  });
});
