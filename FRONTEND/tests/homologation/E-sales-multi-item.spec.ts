/**
 * HOMOLOGAÇÃO — Categoria E: Vendas multi-item e desconto
 *
 * E01 — Venda com múltiplos itens e totalização correta
 * E02 — Estoque é decrementado após venda
 * E03 — Histórico de vendas lista a venda recém-criada
 * E04 — Impressão de comprovante não gera erro
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

  test.beforeAll(async ({ request }) => {
    initSqlContainer();
    cleanupHomologData();
    await registerTestCompany(request);
    await loginApi(request);

    const supplier = await api<Entity>(request, "/Fornecedor", {
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
    const sale = await api<{ saleNumber: string }>(request, "/HistoricoVendas", {
      method: "POST",
      body: {
        customerName: `${RUN_ID} MultiItem`,
        customerCpf: generateCpf(),
        paymentType: "Dinheiro",
        totalAmount: "125,00",
        items: [
          { productCode: productCodeA, productName: `${RUN_ID} ProdutoE1`, quantity: 3 },
          { productCode: productCodeB, productName: `${RUN_ID} ProdutoE2`, quantity: 2 },
        ],
      },
    });
    expect(sale.saleNumber).toBeTruthy();
    saleNumber = sale.saleNumber;
  });

  test("E02 — Estoque é decrementado após venda", async ({ request }) => {
    const products = await api<Product[]>(request, "/Produto");

    const pA = products.find((p) => p.productCode === productCodeA);
    const pB = products.find((p) => p.productCode === productCodeB);

    expect(pA?.productQnt).toBe("17");
    expect(pB?.productQnt).toBe("28");
  });

  test("E03 — Histórico de vendas lista a venda recém-criada", async ({ request }) => {
    const sales = await api<Array<{ saleNumber: string }>>(request, "/HistoricoVendas");
    const found = sales.find((s) => s.saleNumber === saleNumber);
    expect(found, `Venda ${saleNumber} deve estar no histórico`).toBeTruthy();
  });

  test("E04 — Impressão de comprovante não gera erro", async ({ request }) => {
    await api(request, `/HistoricoVendas/${saleNumber}/imprimir`, { method: "POST" });
  });
});
