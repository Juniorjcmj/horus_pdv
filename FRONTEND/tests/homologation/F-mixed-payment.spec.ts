/**
 * HOMOLOGAÇÃO — Categoria F: Pagamento misto
 *
 * F01 — Venda com pagamento Cartão é aceita
 * F02 — Venda com pagamento Pix é aceita
 * F03 — Venda com pagamento Dinheiro funciona
 *
 * GAP: A API aceita paymentType como string única. Para pagamento misto real
 *      (split entre formas), a API precisaria de um array de pagamentos.
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
type Product = Entity & { productCode: string };

test.describe("F — Pagamento misto", () => {
  test.describe.configure({ mode: "serial" });

  let productCode: string;

  test.beforeAll(async ({ request }) => {
    initSqlContainer();
    cleanupHomologData();
    await registerTestCompany(request);
    await loginApi(request);

    const supplier = await api<Entity>(request, "/Fornecedor", {
      method: "POST",
      body: supplierPayload("Fornecedor F"),
    });
    const payload = productPayload("ProdutoF", `${RUN_ID} Fornecedor F`, "100");
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
      body: { closingAmount: "200,00", note: `${RUN_ID} cleanup F` },
      allowFailure: true,
    });
    cleanupHomologData();
  });

  test("F01 — Venda com forma de pagamento Cartão é aceita", async ({ request }) => {
    const sale = await api<{ saleNumber: string }>(request, "/HistoricoVendas", {
      method: "POST",
      body: {
        customerName: `${RUN_ID} PagCartao`,
        customerCpf: generateCpf(),
        paymentType: "Cartão",
        totalAmount: "25,00",
        items: [{ productCode, productName: `${RUN_ID} ProdutoF`, quantity: 1 }],
      },
    });
    expect(sale.saleNumber).toBeTruthy();
  });

  test("F02 — Venda com forma de pagamento Pix é aceita", async ({ request }) => {
    const sale = await api<{ saleNumber: string }>(request, "/HistoricoVendas", {
      method: "POST",
      body: {
        customerName: `${RUN_ID} PagPix`,
        customerCpf: generateCpf(),
        paymentType: "Pix",
        totalAmount: "25,00",
        items: [{ productCode, productName: `${RUN_ID} ProdutoF`, quantity: 1 }],
      },
    });
    expect(sale.saleNumber).toBeTruthy();
  });

  test("F03 — Venda com pagamento Dinheiro funciona", async ({ request }) => {
    const sale = await api<{ saleNumber: string }>(request, "/HistoricoVendas", {
      method: "POST",
      body: {
        customerName: `${RUN_ID} PagDinheiro`,
        customerCpf: generateCpf(),
        paymentType: "Dinheiro",
        totalAmount: "25,00",
        items: [{ productCode, productName: `${RUN_ID} ProdutoF`, quantity: 1 }],
      },
    });
    expect(sale.saleNumber).toBeTruthy();
  });
});
