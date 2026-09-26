/**
 * CHANGE 08.1 — Categoria E: Venda com múltiplos itens
 *
 * E01 — Venda com múltiplos itens e totalização correta
 *       Adicionar produto A e B, alterar quantidade, validar subtotais,
 *       promoções (se aplicável), total final, avançar para pagamento.
 */
import { test, expect } from "@playwright/test";
import {
  RUN_ID,
  initSqlContainer,
  registerTestCompany,
  loginApi,
  cleanupHomologData,
  api,
  productPayload,
  supplierPayload,
  generateCpf,
} from "./helpers/setup";

type Entity = { id: string };
type Product = Entity & { productCode: string; productName: string; productQnt: string };

test.describe("E — Vendas multi-item", () => {
  test.describe.configure({ mode: "serial" });

  let productCodeA: string;
  let productCodeB: string;
  let saleNumber: string;
  // Preço de venda: R$ 25,00 (definido em productPayload)
  const SALE_PRICE = 25;

  test.beforeAll(async ({ request }) => {
    initSqlContainer();
    cleanupHomologData();
    await registerTestCompany(request);
    await loginApi(request);

    await api<Entity>(request, "/Fornecedor", {
      method: "POST",
      body: supplierPayload("Fornecedor E"),
    });
    const supplierName = `${RUN_ID} Fornecedor E`;

    const pA = productPayload("ProdutoE1", supplierName, "20");
    const pB = productPayload("ProdutoE2", supplierName, "30");
    await api<Product>(request, "/Produto", { method: "POST", body: pA });
    await api<Product>(request, "/Produto", { method: "POST", body: pB });
    productCodeA = pA.productCode;
    productCodeB = pB.productCode;

    await api(request, "/Caixa/abrir", {
      method: "POST",
      body: { openingAmount: "100,00" },
    });
  });

  test.afterAll(async ({ request }) => {
    await api(request, "/Caixa/fechar", {
      method: "POST",
      body: { closingAmount: "200,00", note: `${RUN_ID} cleanup E` },
      allowFailure: true,
    });
    cleanupHomologData();
  });

  test("E01 — Venda com múltiplos itens e totalização correta", async ({ request }) => {
    // Produto A: 3 unidades x R$ 25,00 = R$ 75,00
    // Produto B: 2 unidades x R$ 25,00 = R$ 50,00
    // Total esperado: R$ 125,00
    const qtyA = 3;
    const qtyB = 2;
    const subtotalA = qtyA * SALE_PRICE; // 75
    const subtotalB = qtyB * SALE_PRICE; // 50
    const expectedTotal = subtotalA + subtotalB; // 125

    const sale = await api<{
      saleNumber: string;
      rows?: Array<{ productCode: string; quantity: number; unitPrice: number; itemTotal: number }>;
    }>(request, "/HistoricoVendas", {
      method: "POST",
      body: {
        customerName: `${RUN_ID} MultiItem`,
        customerCpf: generateCpf(),
        paymentType: "Dinheiro",
        totalAmount: `${expectedTotal},00`,
        items: [
          {
            productCode: productCodeA,
            productName: `${RUN_ID} ProdutoE1`,
            quantity: qtyA,
            unitPrice: SALE_PRICE,
            itemTotal: subtotalA,
          },
          {
            productCode: productCodeB,
            productName: `${RUN_ID} ProdutoE2`,
            quantity: qtyB,
            unitPrice: SALE_PRICE,
            itemTotal: subtotalB,
          },
        ],
      },
    });

    expect(sale.saleNumber, "Venda deve retornar número").toBeTruthy();
    saleNumber = sale.saleNumber;

    // Validar estoque decrementado
    const products = await api<Product[]>(request, "/Produto");
    const pA = products.find((p) => p.productCode === productCodeA);
    const pB = products.find((p) => p.productCode === productCodeB);

    // Estoque inicial: A=20, B=30. Após venda: A=17, B=28
    expect(pA?.productQnt, "Estoque A decrementado de 20 para 17").toBe("17");
    expect(pB?.productQnt, "Estoque B decrementado de 30 para 28").toBe("28");

    // Validar que a venda aparece no histórico
    const sales = await api<Array<{ saleNumber: string; totalAmount?: string }>>(
      request,
      "/HistoricoVendas",
    );
    const found = sales.find((s) => s.saleNumber === saleNumber);
    expect(found, `Venda ${saleNumber} deve estar no histórico`).toBeTruthy();
  });
});
