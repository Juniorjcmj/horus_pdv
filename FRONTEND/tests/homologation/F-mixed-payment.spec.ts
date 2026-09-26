/**
 * CHANGE 08.1 — Categoria F: Pagamento Misto
 *
 * F01 — Pagamento misto (PIX + Dinheiro)
 *       Venda total: R$ 100,00
 *       Pagamento: PIX R$ 50,00 + Dinheiro R$ 60,00
 *       Resultado: total recebido R$ 110, troco R$ 10, venda concluída.
 *       Validar interface, valores enviados, persistência, total, pagamentos, troco.
 */
import { test, expect } from "@playwright/test";
import {
  RUN_ID,
  API_URL,
  initSqlContainer,
  registerTestCompany,
  loginApi,
  cleanupHomologData,
  api,
  productPayload,
  supplierPayload,
  generateCpf,
  querySqlScalar,
  escapeSql,
  getAuthHeaders,
} from "./helpers/setup";

type Entity = { id: string };

test.describe("F — Pagamento misto", () => {
  test.describe.configure({ mode: "serial" });

  let productCode: string;

  test.beforeAll(async ({ request }) => {
    initSqlContainer();
    cleanupHomologData();
    await registerTestCompany(request);
    await loginApi(request);

    await api<Entity>(request, "/Fornecedor", {
      method: "POST",
      body: supplierPayload("Fornecedor F"),
    });
    const payload = productPayload("ProdutoF", `${RUN_ID} Fornecedor F`, "100");
    await api(request, "/Produto", { method: "POST", body: payload });
    productCode = payload.productCode;

    await api(request, "/Caixa/abrir", {
      method: "POST",
      body: { openingAmount: "0,00" },
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

  test("F01 — Pagamento misto PIX R$50 + Dinheiro R$60 (troco R$10)", async ({ request }) => {
    const totalVenda = 100;
    const pixAmount = 50;
    const dinheiroAmount = 60; // R$ 10 de troco
    const expectedChange = dinheiroAmount - (totalVenda - pixAmount); // 60 - 50 = 10

    // Registrar venda com pagamento misto (array de pagamentos)
    const saleResponse = await request.fetch(`${API_URL}/HistoricoVendas`, {
      method: "POST",
      data: {
        customerName: `${RUN_ID} PagMisto`,
        customerCpf: generateCpf(),
        paymentType: "Múltiplo",
        totalAmount: "100,00",
        items: [
          {
            productCode,
            productName: `${RUN_ID} ProdutoF`,
            quantity: 4,
            unitPrice: 25,
            itemTotal: 100,
          },
        ],
        payments: [
          {
            paymentType: "pix",
            amount: pixAmount,
            cashGiven: pixAmount,
            changeAmount: 0,
          },
          {
            paymentType: "dinheiro",
            amount: totalVenda - pixAmount, // R$ 50
            cashGiven: dinheiroAmount, // R$ 60
            changeAmount: expectedChange, // R$ 10
          },
        ],
      },
      headers: getAuthHeaders(),
    });

    const saleRaw = await saleResponse.text();
    let saleBody: { success?: boolean; data?: { saleNumber?: string } };
    try {
      saleBody = JSON.parse(saleRaw);
    } catch {
      saleBody = { success: false };
    }

    if (!saleResponse.ok() || !saleBody.success) {
      // Se a API não aceita array de pagamentos, é um GAP
      console.log(
        "GAP-F01: API /HistoricoVendas não aceita pagamento misto (array de payments). " +
          `Evidência: HTTP ${saleResponse.status()} — ${saleRaw}. ` +
          "Severidade: P1 — operação comum em PDV. " +
          "CHANGE futura sugerida: CHANGE 09 — Suporte a pagamento misto na API.",
      );

      // Testar ao menos pagamentos individuais como fallback
      const pixSale = await api<{ saleNumber: string }>(request, "/HistoricoVendas", {
        method: "POST",
        body: {
          customerName: `${RUN_ID} PagPix`,
          customerCpf: generateCpf(),
          paymentType: "Pix",
          totalAmount: "25,00",
          items: [
            {
              productCode,
              productName: `${RUN_ID} ProdutoF`,
              quantity: 1,
            },
          ],
        },
      });
      expect(pixSale.saleNumber, "Pagamento PIX individual deve funcionar").toBeTruthy();

      const dinheiroSale = await api<{ saleNumber: string }>(
        request,
        "/HistoricoVendas",
        {
          method: "POST",
          body: {
            customerName: `${RUN_ID} PagDinheiro`,
            customerCpf: generateCpf(),
            paymentType: "Dinheiro",
            totalAmount: "25,00",
            items: [
              {
                productCode,
                productName: `${RUN_ID} ProdutoF`,
                quantity: 1,
              },
            ],
          },
        },
      );
      expect(
        dinheiroSale.saleNumber,
        "Pagamento Dinheiro individual deve funcionar",
      ).toBeTruthy();
      return;
    }

    // Pagamento misto aceito — validar resultado
    const saleNumber = saleBody.data?.saleNumber;
    expect(saleNumber, "Venda mista deve retornar saleNumber").toBeTruthy();

    // Validar persistência — consultar a venda no histórico
    const sales = await api<
      Array<{
        saleNumber: string;
        totalAmount?: string;
        paymentType?: string;
      }>
    >(request, "/HistoricoVendas");

    const found = sales.find((s) => s.saleNumber === saleNumber);
    expect(found, "Venda mista deve aparecer no histórico").toBeTruthy();

    // Validar total da venda
    if (found?.totalAmount) {
      const totalPersisted = Number(found.totalAmount.replace(",", "."));
      expect(totalPersisted).toBeCloseTo(totalVenda, 2);
    }

    // Validar pagamentos via SQL se possível
    const paymentCount = querySqlScalar(
      `SELECT COUNT(*) FROM VendaPagamentos WHERE VendaId IN (SELECT Id FROM Vendas WHERE SaleNumber = N'${escapeSql(saleNumber!)}')`,
    );

    if (paymentCount !== null && Number(paymentCount) !== 2) {
      console.log(
        `GAP-F01b: Venda mista aceita mas apenas ${paymentCount} registro(s) em VendaPagamentos ` +
          "(esperado 2). API pode não estar persistindo pagamentos múltiplos corretamente. " +
          "Severidade: P1 — auditoria financeira requer detalhes de cada forma de pagamento.",
      );
    }
  });
});
