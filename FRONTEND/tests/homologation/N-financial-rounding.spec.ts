/**
 * HOMOLOGAÇÃO — Categoria N: Arredondamento Financeiro
 *
 * N01 — round2(0.1 + 0.2) === 0.3
 * N02 — Subtotal de múltiplos produtos mantém precisão de centavos
 * N03 — Acumulação de 100 itens de R$ 99,99 resulta em R$ 9.999,00
 * N04 — Troco calculado corretamente em venda com dinheiro
 * N05 — Desconto percentual arredondado corretamente
 *
 * Estes testes rodam no browser para validar o mesmo runtime JS que
 * o operador usa no balcão.
 */
import { test, expect } from "@playwright/test";
import {
  initSqlContainer,
  registerTestCompany,
  loginBrowserSession,
  cleanupHomologData,
} from "./helpers/setup";

test.describe("N — Arredondamento Financeiro", () => {
  test.describe.configure({ mode: "serial" });

  test.beforeAll(async ({ request }) => {
    initSqlContainer();
    cleanupHomologData();
    await registerTestCompany(request);
  });

  test.afterAll(() => {
    cleanupHomologData();
  });

  test("N01 — round2(0.1 + 0.2) resulta em 0.3", async ({ page }) => {
    await loginBrowserSession(page);

    const result = await page.evaluate(() => {
      function round2(val: number): number {
        return Math.round((val + Number.EPSILON) * 100) / 100;
      }
      return {
        raw: 0.1 + 0.2,
        rounded: round2(0.1 + 0.2),
        isExact: round2(0.1 + 0.2) === 0.3,
      };
    });

    expect(result.raw).not.toBe(0.3);
    expect(result.rounded).toBe(0.3);
    expect(result.isExact).toBe(true);
  });

  test("N02 — Subtotal de múltiplos produtos mantém precisão", async ({ page }) => {
    await loginBrowserSession(page);

    const result = await page.evaluate(() => {
      function round2(val: number): number {
        return Math.round((val + Number.EPSILON) * 100) / 100;
      }

      const items = [
        { unitPrice: 19.99, quantity: 3 },
        { unitPrice: 0.01, quantity: 1 },
        { unitPrice: 49.95, quantity: 2 },
        { unitPrice: 9999.99, quantity: 1 },
      ];

      const subtotal = round2(
        items.reduce((sum, item) => sum + round2(item.unitPrice * item.quantity), 0),
      );

      return { subtotal, expected: 10159.87 };
    });

    expect(result.subtotal).toBe(result.expected);
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
        items.reduce((sum, item) => sum + round2(item.unitPrice * item.quantity), 0),
      );

      return { subtotal, expected: 9999.0 };
    });

    expect(result.subtotal).toBe(result.expected);
  });

  test("N04 — Troco calculado corretamente", async ({ page }) => {
    await loginBrowserSession(page);

    const result = await page.evaluate(() => {
      function round2(val: number): number {
        return Math.round((val + Number.EPSILON) * 100) / 100;
      }

      const totalVenda = 73.47;
      const valorPago = 100.0;
      const troco = round2(valorPago - totalVenda);
      return { troco, expected: 26.53 };
    });

    expect(result.troco).toBe(result.expected);
  });

  test("N05 — Desconto percentual arredondado corretamente", async ({ page }) => {
    await loginBrowserSession(page);

    const result = await page.evaluate(() => {
      function round2(val: number): number {
        return Math.round((val + Number.EPSILON) * 100) / 100;
      }

      const preco = 33.33;
      const desconto = round2(preco * 0.1);
      const final_ = round2(preco - desconto);
      return { desconto, final: final_, expectedDesconto: 3.33, expectedFinal: 30.0 };
    });

    expect(result.desconto).toBe(result.expectedDesconto);
    expect(result.final).toBe(result.expectedFinal);
  });
});
