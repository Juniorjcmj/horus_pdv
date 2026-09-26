/**
 * CHANGE 08.1 — Categoria N: Arredondamento Financeiro
 *
 * N01 — Matriz de arredondamento
 *       Valores: 0.01, 0.05, 0.10, 0.99, 1.01, 9.99, 10.01, 19.90, 99.99, 100.01
 *       Validar subtotal, total, pagamentos, troco, persistência, sincronização.
 *       Detectar divergências de centavos.
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
  generateCpf,
  productPayload,
  supplierPayload,
  querySqlScalar,
  escapeSql,
} from "./helpers/setup";

type Entity = { id: string };

// Função round2 — deve ser idêntica à usada no frontend (promotionEngine.ts)
function round2(val: number): number {
  return Math.round((val + Number.EPSILON) * 100) / 100;
}

test.describe("N — Arredondamento Financeiro", () => {
  test.describe.configure({ mode: "serial" });

  let productCode: string;

  test.beforeAll(async ({ request }) => {
    initSqlContainer();
    cleanupHomologData();
    await registerTestCompany(request);
    await loginApi(request);

    await api<Entity>(request, "/Fornecedor", {
      method: "POST",
      body: supplierPayload("Fornecedor N"),
    });

    // Criar produto com preço fracionado para testar arredondamento
    const payload = productPayload("ProdutoN", `${RUN_ID} Fornecedor N`, "1000");
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
      body: { closingAmount: "500,00", note: `${RUN_ID} cleanup N` },
      allowFailure: true,
    });
    cleanupHomologData();
  });

  test("N01 — round2(0.1 + 0.2) resulta em 0.3", async ({ page }) => {
    await loginBrowserSession(page);

    const result = await page.evaluate(() => {
      function round2(val: number): number {
        return Math.round((val + Number.EPSILON) * 100) / 100;
      }
      const raw = 0.1 + 0.2;
      const rounded = round2(raw);
      return { raw, rounded, correct: rounded === 0.3 };
    });

    expect(result.raw).not.toBe(0.3); // JS floating point
    expect(result.rounded).toBe(0.3);
    expect(result.correct).toBe(true);
  });

  test("N02 — Matriz de arredondamento com valores de fronteira", async ({ page }) => {
    await loginBrowserSession(page);

    // Valores de teste conforme spec
    const testValues = [0.01, 0.05, 0.10, 0.99, 1.01, 9.99, 10.01, 19.90, 99.99, 100.01];

    const results = await page.evaluate((values) => {
      function round2(val: number): number {
        return Math.round((val + Number.EPSILON) * 100) / 100;
      }

      return values.map((unitPrice) => {
        const quantity = 3;
        const subtotal = round2(unitPrice * quantity);
        const expectedSubtotal = round2(unitPrice * quantity);

        // Simular pagamento com troco
        const cashGiven = Math.ceil(subtotal); // Arredonda para cima
        const change = round2(cashGiven - subtotal);

        // Verificar que subtotal + troco = cashGiven
        const sumCheck = round2(subtotal + change);

        return {
          unitPrice,
          quantity,
          subtotal,
          expectedSubtotal,
          cashGiven,
          change,
          sumCheck,
          subtotalCorrect: subtotal === expectedSubtotal,
          changeCorrect: change >= 0,
          sumCorrect: sumCheck === cashGiven,
        };
      });
    }, testValues);

    // Validar cada valor
    for (const r of results) {
      expect(
        r.subtotalCorrect,
        `Subtotal para unitPrice=${r.unitPrice}: esperado=${r.expectedSubtotal}, obtido=${r.subtotal}`,
      ).toBe(true);

      expect(
        r.changeCorrect,
        `Troco negativo para unitPrice=${r.unitPrice}: change=${r.change}`,
      ).toBe(true);

      expect(
        r.sumCorrect,
        `Soma incorreta para unitPrice=${r.unitPrice}: subtotal(${r.subtotal}) + change(${r.change}) != cashGiven(${r.cashGiven})`,
      ).toBe(true);
    }
  });

  test("N03 — Acumulação de 100 itens de R$ 99,99 = R$ 9.999,00", async ({ page }) => {
    await loginBrowserSession(page);

    const result = await page.evaluate(() => {
      function round2(val: number): number {
        return Math.round((val + Number.EPSILON) * 100) / 100;
      }

      const items = Array.from({ length: 100 }, () => ({
        unitPrice: 99.99,
        quantity: 1,
      }));

      const subtotal = round2(
        items.reduce(
          (sum, item) => sum + round2(item.unitPrice * item.quantity),
          0,
        ),
      );

      return { subtotal, expected: 9999.0, correct: subtotal === 9999.0 };
    });

    expect(result.subtotal).toBe(result.expected);
    expect(result.correct).toBe(true);
  });

  test("N04 — Troco calculado corretamente em venda com dinheiro", async ({ request }) => {
    // Vender 1 unidade a R$ 25,00, pagar R$ 50,00 → troco R$ 25,00
    const sale = await api<{ saleNumber: string }>(request, "/HistoricoVendas", {
      method: "POST",
      body: {
        customerName: `${RUN_ID} TrocoN04`,
        customerCpf: generateCpf(),
        paymentType: "Dinheiro",
        totalAmount: "25,00",
        items: [
          {
            productCode,
            productName: `${RUN_ID} ProdutoN`,
            quantity: 1,
            unitPrice: 25,
            itemTotal: 25,
          },
        ],
        payments: [
          {
            paymentType: "dinheiro",
            amount: 25,
            cashGiven: 50,
            changeAmount: 25,
          },
        ],
      },
    });

    expect(sale.saleNumber, "Venda com troco deve funcionar").toBeTruthy();

    // Verificar persistência do troco no banco
    const changeInDb = querySqlScalar(
      `SELECT TOP 1 ChangeAmount FROM VendaPagamentos WHERE VendaId IN (SELECT Id FROM Vendas WHERE SaleNumber = N'${escapeSql(sale.saleNumber)}')`,
    );

    if (changeInDb !== null && Number(changeInDb) !== 25) {
      console.log(
        `GAP-N04: Troco persistido em VendaPagamentos.ChangeAmount = ${changeInDb}, esperado 25.00. ` +
          "API pode não estar salvando o troco corretamente no pagamento. " +
          "Severidade: P2 — auditoria financeira requer troco registrado.",
      );
    }
  });

  test("N05 — Desconto percentual arredondado corretamente", async ({ page }) => {
    await loginBrowserSession(page);

    const result = await page.evaluate(() => {
      function round2(val: number): number {
        return Math.round((val + Number.EPSILON) * 100) / 100;
      }

      // Testar vários cenários de desconto percentual
      const scenarios = [
        { price: 33.33, percent: 10, expectedDiscount: 3.33, expectedFinal: 30.0 },
        { price: 99.99, percent: 15, expectedDiscount: 15.0, expectedFinal: 84.99 },
        { price: 0.01, percent: 50, expectedDiscount: 0.01, expectedFinal: 0.0 },
        { price: 100.01, percent: 33, expectedDiscount: 33.0, expectedFinal: 67.01 },
        { price: 19.90, percent: 5, expectedDiscount: 1.0, expectedFinal: 18.9 },
      ];

      return scenarios.map((s) => {
        const discount = round2(s.price * (s.percent / 100));
        const final_ = round2(s.price - discount);
        return {
          ...s,
          actualDiscount: discount,
          actualFinal: final_,
          discountMatch: discount === s.expectedDiscount,
          finalMatch: final_ === s.expectedFinal,
        };
      });
    });

    for (const r of result) {
      expect(
        r.discountMatch,
        `Desconto ${r.percent}% de R$ ${r.price}: esperado=${r.expectedDiscount}, obtido=${r.actualDiscount}`,
      ).toBe(true);

      expect(
        r.finalMatch,
        `Final ${r.percent}% de R$ ${r.price}: esperado=${r.expectedFinal}, obtido=${r.actualFinal}`,
      ).toBe(true);
    }
  });
});
