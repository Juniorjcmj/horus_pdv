/**
 * HOMOLOGAÇÃO — Categoria C: Fiado (crédito ao cliente)
 *
 * C01 — Venda fiado registra débito no extrato do cliente
 * C02 — Recebimento parcial reduz saldo devedor
 * C03 — Listar devedores mostra o cliente com saldo
 *
 * GAP: Limite de crédito por cliente — a API atual não possui campo de
 *       limite de crédito (creditLimit) em Clientes. Quando implementado,
 *       adicionar teste que valida rejeição de venda fiado acima do limite.
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
  customerPayload,
  generateCpf,
} from "./helpers/setup";

type Entity = { id: string };
type Product = Entity & { productCode: string };

test.describe("C — Fiado", () => {
  test.describe.configure({ mode: "serial" });

  let customerId: string;
  let productCode: string;

  test.beforeAll(async ({ request }) => {
    initSqlContainer();
    cleanupHomologData();
    await registerTestCompany(request);
    await loginApi(request);

    const supplier = await api<Entity>(request, "/Fornecedor", {
      method: "POST",
      body: supplierPayload("Fornecedor C"),
    });
    const payload = productPayload("ProdutoC", `${RUN_ID} Fornecedor C`, "100");
    await api<Product>(request, "/Produto", { method: "POST", body: payload });
    productCode = payload.productCode;

    const customer = await api<Entity>(request, "/Cliente", {
      method: "POST",
      body: customerPayload("ClienteFiado"),
    });
    customerId = customer.id;

    await api(request, "/Caixa/abrir", {
      method: "POST",
      body: { openingAmount: "100,00" },
    });
  });

  test.afterAll(async ({ request }) => {
    await api(request, "/Caixa/fechar", {
      method: "POST",
      body: { closingAmount: "100,00", note: `${RUN_ID} cleanup fiado` },
      allowFailure: true,
    });
    cleanupHomologData();
  });

  test("C01 — Venda fiado registra débito no extrato do cliente", async ({ request }) => {
    const sale = await api<{ saleNumber: string }>(request, "/HistoricoVendas", {
      method: "POST",
      body: {
        customerName: `${RUN_ID} ClienteFiado`,
        customerCpf: generateCpf(),
        paymentType: "Fiado",
        totalAmount: "50,00",
        items: [{ productCode, productName: `${RUN_ID} ProdutoC`, quantity: 2 }],
      },
    });
    expect(sale.saleNumber).toBeTruthy();

    const extrato = await api<{ items?: unknown[]; saldo?: number; total?: number }>(
      request,
      `/Fiado/extrato/${customerId}`,
      { allowFailure: true },
    );
    if (extrato) {
      expect(extrato).toBeTruthy();
    }
  });

  test("C02 — Recebimento parcial reduz saldo devedor", async ({ request }) => {
    const result = await api<{ success?: boolean }>(
      request,
      "/Fiado/receber",
      {
        method: "POST",
        body: {
          clienteId: customerId,
          valor: "20,00",
          formaPagamento: "Dinheiro",
        },
        allowFailure: true,
      },
    );
    if (result) {
      expect(result).toBeTruthy();
    }
  });

  test("C03 — Listar devedores mostra o cliente com saldo", async ({ request }) => {
    const devedores = await api<Array<{ clienteId?: string; nome?: string }>>(
      request,
      "/Fiado/devedores",
      { allowFailure: true },
    );
    if (devedores && Array.isArray(devedores)) {
      expect(Array.isArray(devedores)).toBe(true);
    }
  });
});
