import { expect, test, type Page } from "@playwright/test";

const money = (value: number) => value.toLocaleString("pt-BR", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const user = { id: "op-fechamento", companyId: "loja-fechamento", name: "Operador teste", email: "teste@teste.invalid",
  role: "gerente", status: "ativo", createdAt: new Date().toISOString(), lastLoginAt: new Date().toISOString(), mustChangePassword: false };

async function openPos(page: Page, baseURL: string) {
  const state = { cash: 0, delay: 0, closes: [] as { closingAmount: string; differenceReason: string | null }[] };
  const openedAt = new Date(Date.now() - 5 * 60_000).toISOString();
  const status = () => ({ state: "aberto", canSell: true, blockReason: "", serverNow: new Date().toISOString(), history: [],
    currentSession: { id: "sessao-fechamento", status: "Aberto", openedAt, openingAmount: "0,00", closingAmount: "0,00",
      operatorId: user.id, operatorName: user.name, closedById: "", closedByName: "", note: "", elapsedMinutes: 5,
      expectedCashAmount: money(state.cash), movimentos: [],
      paymentBreakdown: state.cash ? [{ paymentType: "dinheiro", total: money(state.cash) }] : [] } });
  await page.route("**/*", async route => {
    const url = new URL(route.request().url());
    if (url.origin === new URL(baseURL).origin) return route.continue();
    const pathname = url.pathname;
    let data: unknown = [];
    if (pathname.endsWith("/Auth/me")) data = user;
    else if (pathname.endsWith("/Empresa")) data = null;
    else if (pathname.endsWith("/Produto")) data = [{ id: "produto-fechamento", productName: "Produto fechamento",
      productCode: "FECHA745", productSalePrice: "7,45", productUnitPrice: "5,00", productQnt: "9999", productSupplier: "", unidadeComercial: "UN" }];
    else if (pathname.endsWith("/Caixa/status")) {
      if (state.delay) await new Promise(resolve => setTimeout(resolve, state.delay));
      data = status();
    } else if (pathname.endsWith("/HistoricoVendas") && route.request().method() === "POST") {
      state.cash += 7.45;
      data = { saleNumber: "15041", emitirFiscal: false, fiscalQueued: false };
    } else if (pathname.endsWith("/Caixa/fechar")) {
      state.closes.push(route.request().postDataJSON());
      data = { ...status(), state: "fechado", canSell: false, currentSession: null,
        lastSession: { ...status().currentSession, status: "Fechado", closedAt: new Date().toISOString(), closingAmount: "7,45", differenceAmount: "0,00" } };
    }
    return route.fulfill({ json: { success: true, data } });
  });
  await page.addInitScript(user => {
    localStorage.setItem("horuspdv.auth.user", JSON.stringify(user));
    localStorage.setItem("horuspdv.activePage", "vendas");
    localStorage.setItem("horus-pdv-print-preview-enabled", "false");
    Object.defineProperty(window, "quackDesktop", { value: { savePendingBackup: async () => "",
      printer: { printReceipt: async () => ({ printed: true }) } } });
    window.open = () => null; // Relatório de fechamento: saída física fora do escopo deste teste.
  }, user);
  await page.goto("/");
  await expect(page.getByRole("textbox", { name: /^Produto:/ })).toBeVisible();
  return { state, status };
}

async function openCashPanel(page: Page) {
  await page.keyboard.press("F6");
  const panel = page.getByRole("dialog", { name: "Caixa", exact: true });
  await expect(panel.getByRole("button", { name: "Fechar caixa", exact: true })).toBeVisible();
  return panel;
}

test("venda em dinheiro de R$ 7,45 atualiza gaveta e fecha sem diferença falsa", async ({ page, baseURL }) => {
  const { state } = await openPos(page, baseURL!);
  const product = page.getByRole("textbox", { name: /^Produto:/ });
  await product.fill("FECHA745");
  await page.getByText("Produto fechamento", { exact: true }).first().click();
  await expect(page.getByRole("row").filter({ hasText: "Produto fechamento" }).getByRole("cell").nth(3)).toHaveText("1");
  await page.keyboard.press("F12");
  await page.getByRole("textbox", { name: "Valor entregue em dinheiro", exact: true }).fill("1000");
  await page.getByRole("button", { name: "Confirmar Venda", exact: true }).click();
  await expect(page.getByRole("row").filter({ hasText: "Produto fechamento" })).toHaveCount(0);
  const panel = await openCashPanel(page);
  await expect(panel.getByText("Nenhuma venda neste turno ainda.", { exact: true })).toHaveCount(0);
  await expect(panel.getByText("Dinheiro", { exact: true })).toBeVisible();
  await expect(panel.getByText("Dinheiro esperado na gaveta").locator("..")).toContainText("R$ 7,45");
  await panel.getByRole("button", { name: "Fechar caixa", exact: true }).click();
  await expect(panel.getByRole("textbox", { name: "Valor contado na gaveta (dinheiro)" })).toHaveValue("7,45");
  await expect(panel.getByText("Confere com o esperado.", { exact: true })).toBeVisible();
  await panel.getByRole("button", { name: "Confirmar fechamento", exact: true }).click();
  await page.getByRole("button", { name: "Sim", exact: true }).click();
  await expect.poll(() => state.closes.length).toBe(1);
  expect(state.closes[0].closingAmount).toBe("7,45");
  expect(state.closes[0].differenceReason).toBeNull();
});

test("abrir o painel atualiza status antigo e aguarda a resposta antes de oferecer fechamento", async ({ page, baseURL }) => {
  const { state } = await openPos(page, baseURL!);
  await expect(page.getByText(/Caixa aberto por/).first()).toBeVisible();
  state.cash = 7.45;
  state.delay = 400;
  await page.keyboard.press("F6");
  const panel = page.getByRole("dialog", { name: "Caixa", exact: true });
  await expect(panel.getByRole("status")).toHaveText("Atualizando totais do caixa...");
  await expect(panel.getByRole("button", { name: "Fechar caixa", exact: true })).toHaveCount(0);
  await expect(panel.getByText("Dinheiro esperado na gaveta").locator("..")).toContainText("R$ 7,45");
  await panel.getByRole("button", { name: "Fechar caixa", exact: true }).click();
  await expect(panel.getByRole("textbox", { name: "Valor contado na gaveta (dinheiro)" })).toHaveValue("7,45");
});

test("venda offline pendente aparece nas leituras locais sem duplicar após sincronização", async ({ page, baseURL }) => {
  const { status } = await openPos(page, baseURL!);
  const result = await page.evaluate(async serverStatus => {
    const repository = await import("/src/infrastructure/database/repositories/CashSessionRepository.ts" as string);
    const { db } = await import("/src/infrastructure/database/dexie.ts" as string);
    const { currentTenantId } = await import("/src/infrastructure/database/repositories/OutboxRepository.ts" as string);
    const now = new Date().toISOString();
    const tenantId = currentTenantId();
    await db.sales.put({ id: "pendente-745", deviceId: "teste", tenantId, sessionId: serverStatus.currentSession.id,
      saleNumber: "OFF-745", totalAmount: 7.45, status: "COMPLETED", createdAt: now, origin: "OFFLINE" });
    await db.payments.put({ id: "pgto-pendente", saleId: "pendente-745", paymentType: "dinheiro", amount: 7.45, cashGiven: 10, changeAmount: 2.55 });
    await db.outbox.put({ id: "ev-pendente", aggregateId: "pendente-745", tenantId, deviceId: "teste", storeId: "", eventType: "SALE_CREATED",
      aggregateType: "Sale", payload: "{}", sequence: 1, occurredAt: now, createdAt: now, status: "FORWARDED", retryCount: 0 });
    await repository.saveCashStatus(serverStatus);
    const first = await repository.loadCachedCashStatus();
    const second = await repository.loadCachedCashStatus();
    const offline = await repository.loadCachedCashStatus();
    const base = await db.cashSessions.get("cash-status-cache");
    await db.outbox.update("ev-pendente", { status: "PROCESSED" });
    await repository.saveCashStatus({ ...serverStatus,
      currentSession: { ...serverStatus.currentSession, expectedCashAmount: "7,45", paymentBreakdown: [{ paymentType: "dinheiro", total: "7,45" }] } });
    const synced = await repository.loadCachedCashStatus();
    return { first: first.currentSession, second: second.currentSession, offline: offline.currentSession, base, synced: synced.currentSession };
  }, status());
  expect(result.first.expectedCashAmount).toBe("7,45");
  expect(result.second.expectedCashAmount).toBe("7,45");
  expect(result.offline.expectedCashAmount).toBe("7,45");
  expect(result.base.paymentBreakdown).toEqual([]);
  expect(result.synced.expectedCashAmount).toBe("7,45");
});
