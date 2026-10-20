import { expect, test, type Page } from "@playwright/test";

/**
 * Change offline-caixa-resiliencia: aviso permanente de modo offline, confirmação de fechamento com pendências
 * e histórico que mescla vendas locais ainda não sincronizadas (sem duplicar).
 */
const user = { id: "op-offline", companyId: "loja-offline", name: "Operador offline", email: "teste@teste.invalid",
  role: "gerente", status: "ativo", createdAt: new Date().toISOString(), lastLoginAt: new Date().toISOString(), mustChangePassword: false };

async function openPos(page: Page, baseURL: string) {
  const openedAt = new Date(Date.now() - 5 * 60_000).toISOString();
  const status = () => ({ state: "aberto", canSell: true, blockReason: "", serverNow: new Date().toISOString(), history: [],
    currentSession: { id: "sessao-offline", status: "Aberto", openedAt, openingAmount: "0,00", closingAmount: "0,00",
      operatorId: user.id, operatorName: user.name, closedById: "", closedByName: "", note: "", elapsedMinutes: 5,
      expectedCashAmount: "0,00", movimentos: [], paymentBreakdown: [] } });
  await page.route("**/*", async route => {
    const url = new URL(route.request().url());
    if (url.origin === new URL(baseURL).origin) return route.continue();
    let data: unknown = [];
    if (url.pathname.endsWith("/Auth/me")) data = user;
    else if (url.pathname.endsWith("/Empresa")) data = null;
    else if (url.pathname.endsWith("/Caixa/status")) data = status();
    return route.fulfill({ json: { success: true, data } });
  });
  await page.addInitScript(user => {
    localStorage.setItem("horuspdv.auth.user", JSON.stringify(user));
    localStorage.setItem("horuspdv.activePage", "vendas");
    Object.defineProperty(window, "quackDesktop", { value: { savePendingBackup: async () => "",
      printer: { printReceipt: async () => ({ printed: true }) } } });
  }, user);
  await page.goto("/");
  await expect(page.getByRole("textbox", { name: /^Produto:/ })).toBeVisible();
}

test("banner de modo offline aparece sem internet e some ao voltar", async ({ page, baseURL, context }) => {
  await openPos(page, baseURL!);
  await expect(page.getByTestId("offline-mode-banner")).toHaveCount(0);

  await context.setOffline(true);
  await page.evaluate(() => window.dispatchEvent(new Event("offline")));
  await expect(page.getByTestId("offline-mode-banner")).toContainText(
    "Sem internet — pode continuar vendendo. As vendas estão guardadas neste caixa.",
  );

  await context.setOffline(false);
  await page.evaluate(() => window.dispatchEvent(new Event("online")));
  await expect(page.getByTestId("offline-mode-banner")).toHaveCount(0, { timeout: 15_000 });
});

test.describe("regras puras", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/");
    await page.waitForFunction(() => (window as any).__horus_test__ !== undefined);
  });

  test("histórico mescla vendas locais pendentes sem duplicar as já presentes na nuvem", async ({ page }) => {
    const rows = await page.evaluate(() => {
      const { mergePendingSales } = (window as any).__horus_test__;
      const row = (saleNumber: string, clientSaleId: string) => ({ saleNumber, clientSaleId, productCode: "P1", customerName: "-" });
      const cloud = [row("1001", "cs-1"), row("1002", "cs-2")];
      const pending = [row("OFF-1", "cs-1"), row("OFF-9", "cs-9"), row("OFF-8", "cs-8")];
      return mergePendingSales.mergePendingLocalSales(cloud, pending).map((r: any) => ({ n: r.saleNumber, p: Boolean(r.pendingSync) }));
    });
    expect(rows).toEqual([
      { n: "OFF-9", p: true },
      { n: "OFF-8", p: true },
      { n: "1001", p: false },
      { n: "1002", p: false },
    ]);
  });

  test("confirmação de fechamento informa a quantidade de pendências", async ({ page }) => {
    const messages = await page.evaluate(() => {
      const { cashCloseConfirm } = (window as any).__horus_test__;
      return [
        cashCloseConfirm.buildCloseConfirmMessage("Fechar o caixa atual?", 0),
        cashCloseConfirm.buildCloseConfirmMessage("Fechar o caixa atual?", 66),
      ];
    });
    expect(messages[0]).toBe("Fechar o caixa atual?");
    expect(messages[1]).toContain("66 pendências");
    expect(messages[1]).toContain("quando a internet voltar");
  });
});
