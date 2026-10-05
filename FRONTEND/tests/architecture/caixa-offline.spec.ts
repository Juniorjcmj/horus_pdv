import { test, expect } from "@playwright/test";

const OPENED_MINUTES_AGO = 135;

test.describe("Caixa offline: fundo de troco e tempo aberto", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/");
    await page.waitForFunction(() => (window as any).__horus_test__ !== undefined);
    await page.evaluate(async (minutesAgo) => {
      const { resetDatabase, CashSessionRepository } = (window as any).__horus_test__;
      await resetDatabase();
      await CashSessionRepository.saveCashStatus({
        state: "aberto", canSell: true, blockReason: "", serverNow: new Date().toISOString(), history: [], lastSession: null,
        currentSession: {
          id: "s-1", status: "Aberto", openedAt: new Date(Date.now() - minutesAgo * 60_000).toISOString(), closedAt: null,
          openingAmount: "100,00", closingAmount: "0,00", operatorId: "u-1", operatorName: "Maria",
          closedById: "", closedByName: "", note: "", elapsedMinutes: minutesAgo, expectedCashAmount: "100,00",
          movimentos: [], paymentBreakdown: [],
        },
      });
    }, OPENED_MINUTES_AGO);
  });

  test("status lido do IndexedDB mantém centavos do fundo de troco e o tempo aberto", async ({ page }) => {
    const session = await page.evaluate(async () => {
      const { CashSessionRepository } = (window as any).__horus_test__;
      return (await CashSessionRepository.loadCachedCashStatus()).currentSession;
    });
    expect(session.openingAmount).toBe("100,00");
    expect(session.elapsedMinutes).toBeGreaterThanOrEqual(OPENED_MINUTES_AGO);
    expect(session.elapsedMinutes).toBeLessThan(OPENED_MINUTES_AGO + 5);
  });

  test("sangria offline mantém centavos do fundo de troco e o tempo aberto", async ({ page }) => {
    const session = await page.evaluate(async () => {
      const { CashSessionRepository } = (window as any).__horus_test__;
      const status = await CashSessionRepository.registerMovementLocal("Sangria", "30,00", "Cofre", "u-1", "Maria");
      return status.currentSession;
    });
    expect(session.openingAmount).toBe("100,00");
    expect(session.elapsedMinutes).toBeGreaterThanOrEqual(OPENED_MINUTES_AGO);
    expect(session.movimentos.map((m: { valor: string }) => m.valor)).toContain("30,00");
  });
});
