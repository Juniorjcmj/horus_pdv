import { test, expect } from "./browser";
import { fiscalDetail, fiscalXml, saleReceipt } from "./fixtures";

const QUEUE = "horus-pdv-pending-danfe-prints";

test("fila persiste após recarga, elimina duplicatas e imprime somente a empresa atual", async ({ page }) => {
  await page.evaluate(async receipt => {
    const { enqueueDanfePrint } = await import("/src/utils/pendingDanfePrint.ts" as string);
    const login = (companyId: string) => localStorage.setItem("horuspdv.auth.user", JSON.stringify({ companyId }));
    login("EMP-A"); enqueueDanfePrint(receipt); enqueueDanfePrint(receipt);
    login("EMP-B"); enqueueDanfePrint(receipt);
    login("EMP-A");
  }, saleReceipt());
  await page.reload();

  const result = await page.evaluate(async ({ detail, queueKey }) => {
    const { flushPendingDanfePrints } = await import("/src/utils/pendingDanfePrint.ts" as string);
    const { fiscalService } = await import("/src/services/api/fiscalService.ts" as string);
    const printed: string[] = [], queries: string[] = [], notifications: string[] = [];
    (window as any).quackDesktop = { printer: { printReceipt: async (html: string) => { printed.push(html); return { printed: true }; } } };
    fiscalService.getBySaleNumber = async (number: string) => { queries.push(number); return detail; };
    const before = JSON.parse(localStorage.getItem(queueKey) || "[]");
    await flushPendingDanfePrints((n: number) => n.toFixed(2), (receipt: any) => notifications.push(receipt.saleNumber));
    return { before: before.map((item: any) => item.tenant), after: JSON.parse(localStorage.getItem(queueKey) || "[]"), printed: printed.map(html => new DOMParser().parseFromString(html, "text/html").body.textContent), queries, notifications };
  }, { detail: fiscalDetail(), queueKey: QUEUE });

  expect(result.before).toEqual(["EMP-A", "EMP-B"]);
  expect(result.after).toHaveLength(1);
  expect(result.after[0].tenant).toBe("EMP-B");
  expect(result.queries).toEqual(["VENDA-LOCAL"]);
  expect(result.notifications).toEqual(["VENDA-LOCAL"]);
  expect(result.printed).toHaveLength(1);
  expect(result.printed[0]).toContain("DANFE NFC-e");
  expect(result.printed[0]).not.toContain("Produto do cadastro");
});

test("XML indisponível e impressora sem confirmação mantêm a pendência até envio confirmado", async ({ page }) => {
  const result = await page.evaluate(async ({ receipt, detail, queueKey }) => {
    const { enqueueDanfePrint, flushPendingDanfePrints } = await import("/src/utils/pendingDanfePrint.ts" as string);
    const { fiscalService } = await import("/src/services/api/fiscalService.ts" as string);
    localStorage.setItem("horuspdv.auth.user", JSON.stringify({ companyId: "EMP-A" }));
    let available = false, confirmed = false, prints = 0;
    (window as any).quackDesktop = { printer: { printReceipt: async () => { prints++; return { printed: confirmed, error: "Impressora indisponível" }; } } };
    fiscalService.getBySaleNumber = async () => available ? detail : null;
    const queue = () => JSON.parse(localStorage.getItem(queueKey) || "[]");
    const makeDue = () => localStorage.setItem(queueKey, JSON.stringify(queue().map((item: any) => ({ ...item, retryAt: 0 }))));
    enqueueDanfePrint(receipt);
    await flushPendingDanfePrints(String);
    const missing = { pending: queue().length, attempts: queue()[0]?.attempts, prints };
    available = true; makeDue(); await flushPendingDanfePrints(String);
    const refused = { pending: queue().length, attempts: queue()[0]?.attempts, prints };
    confirmed = true; makeDue(); await flushPendingDanfePrints(String);
    return { missing, refused, confirmed: { pending: queue().length, prints } };
  }, { receipt: saleReceipt(), detail: fiscalDetail(), queueKey: QUEUE });

  expect(result.missing).toEqual({ pending: 1, attempts: 1, prints: 0 });
  expect(result.refused).toEqual({ pending: 1, attempts: 2, prints: 1 });
  expect(result.confirmed).toEqual({ pending: 0, prints: 2 });
});

test("troca de empresa durante consulta suspende impressão e preserva a venda da empresa anterior", async ({ page }) => {
  const result = await page.evaluate(async ({ receipt, detail, queueKey }) => {
    const { enqueueDanfePrint, flushPendingDanfePrints } = await import("/src/utils/pendingDanfePrint.ts" as string);
    const { fiscalService } = await import("/src/services/api/fiscalService.ts" as string);
    localStorage.setItem("horuspdv.auth.user", JSON.stringify({ companyId: "EMP-A" }));
    let prints = 0;
    (window as any).quackDesktop = { printer: { printReceipt: async () => { prints++; return { printed: true }; } } };
    fiscalService.getBySaleNumber = async () => { localStorage.setItem("horuspdv.auth.user", JSON.stringify({ companyId: "EMP-B" })); return detail; };
    enqueueDanfePrint(receipt);
    await flushPendingDanfePrints(String);
    return { prints, queue: JSON.parse(localStorage.getItem(queueKey) || "[]") };
  }, { receipt: saleReceipt(), detail: fiscalDetail(), queueKey: QUEUE });
  expect(result.prints).toBe(0);
  expect(result.queue).toHaveLength(1);
  expect(result.queue[0]).toMatchObject({ tenant: "EMP-A", attempts: 0 });
});

test("FISCAL_PENDING não imprime recibo gerencial ou consulta fiscal enquanto XML original é incerto", async ({ page }) => {
  const result = await page.evaluate(async ({ receipt, detail, queueKey }) => {
    const { enqueueDanfePrint, flushPendingDanfePrints } = await import("/src/utils/pendingDanfePrint.ts" as string);
    const { fiscalService } = await import("/src/services/api/fiscalService.ts" as string);
    const { db } = await import("/src/infrastructure/database/dexie.ts" as string);
    const { enqueueEvent } = await import("/src/infrastructure/database/repositories/OutboxRepository.ts" as string);
    localStorage.setItem("horuspdv.auth.user", JSON.stringify({ companyId: "EMP-A" }));
    let prints = 0, queries = 0;
    (window as any).quackDesktop = { printer: { printReceipt: async () => { prints++; return { printed: true }; } } };
    fiscalService.getBySaleNumber = async () => { queries++; return detail; };
    await enqueueEvent({ id: receipt.eventId, eventType: "SALE_CREATED", aggregateType: "SALE", aggregateId: receipt.saleNumber, payload: { clientSaleId: receipt.clientSaleId }, waitForFiscal: true });
    enqueueDanfePrint(receipt);
    await flushPendingDanfePrints(String);
    return { prints, queries, status: (await db.outbox.get(receipt.eventId))?.status, pending: JSON.parse(localStorage.getItem(queueKey) || "[]").length };
  }, { receipt: saleReceipt({ clientSaleId: "cs-1", eventId: "event-1" }), detail: fiscalDetail(), queueKey: QUEUE });
  expect(result).toEqual({ prints: 0, queries: 0, status: "FISCAL_PENDING", pending: 1 });
});

test("confirmação manual remove pendência apenas depois que a impressora confirma", async ({ page }) => {
  const result = await page.evaluate(async ({ receipt, queueKey }) => {
    const { enqueueDanfePrint, printReviewedReceipt } = await import("/src/utils/pendingDanfePrint.ts" as string);
    localStorage.setItem("horuspdv.auth.user", JSON.stringify({ companyId: "EMP-A" }));
    let confirmed = false;
    (window as any).quackDesktop = { printer: { printReceipt: async () => ({ printed: confirmed }) } };
    enqueueDanfePrint(receipt);
    let error = "";
    try { await printReviewedReceipt("<html>Revisado</html>", receipt.saleNumber, true); } catch (caught) { error = String(caught); }
    const refused = JSON.parse(localStorage.getItem(queueKey) || "[]").length;
    confirmed = true; await printReviewedReceipt("<html>Revisado</html>", receipt.saleNumber, true);
    return { error, refused, confirmed: JSON.parse(localStorage.getItem(queueKey) || "[]").length };
  }, { receipt: saleReceipt(), queueKey: QUEUE });
  expect(result.error).not.toBe("");
  expect(result.refused).toBe(1);
  expect(result.confirmed).toBe(0);
});

test("XML local de contingência é usado enquanto a venda ainda aguarda sincronização", async ({ page }) => {
  const xml = fiscalXml({ contingency: true, authorized: false });
  const result = await page.evaluate(async ({ receipt, xml, queueKey }) => {
    const { enqueueDanfePrint, flushPendingDanfePrints } = await import("/src/utils/pendingDanfePrint.ts" as string);
    const { fiscalService } = await import("/src/services/api/fiscalService.ts" as string);
    const { enqueueEvent } = await import("/src/infrastructure/database/repositories/OutboxRepository.ts" as string);
    localStorage.setItem("horuspdv.auth.user", JSON.stringify({ companyId: "EMP-A" }));
    const printed: string[] = [];
    let queries = 0;
    (window as any).quackDesktop = { printer: { printReceipt: async (html: string) => { printed.push(html); return { printed: true }; } } };
    fiscalService.getBySaleNumber = async () => { queries++; return null; };
    await enqueueEvent({ id: receipt.eventId, eventType: "SALE_CREATED", aggregateType: "SALE", aggregateId: receipt.saleNumber, payload: { xmlNfceContingencia: xml } });
    enqueueDanfePrint(receipt); await flushPendingDanfePrints(String);
    return { printed: printed.map(html => new DOMParser().parseFromString(html, "text/html").body.textContent), queries, pending: JSON.parse(localStorage.getItem(queueKey) || "[]").length };
  }, { receipt: saleReceipt({ eventId: "event-local-xml" }), xml, queueKey: QUEUE });
  expect(result.queries).toBe(0);
  expect(result.pending).toBe(0);
  expect(result.printed).toHaveLength(1);
  expect(result.printed[0]).toContain("EMITIDA EM CONTINGÊNCIA");
  expect(result.printed[0]).not.toContain("Protocolo de autorização");
});

test("duas abas usam o mesmo lock: impressão automática não duplica a confirmação manual", async ({ page }) => {
  await page.evaluate(async receipt => {
    const { enqueueDanfePrint, printReviewedReceipt } = await import("/src/utils/pendingDanfePrint.ts" as string);
    localStorage.setItem("horuspdv.auth.user", JSON.stringify({ companyId: "EMP-A" }));
    const state = (window as any).__printTest = { prints: 0, started: false, release: null, completion: null };
    (window as any).quackDesktop = { printer: { printReceipt: async () => {
      state.prints++; state.started = true;
      return new Promise(resolve => { state.release = () => resolve({ printed: true }); });
    } } };
    enqueueDanfePrint(receipt);
    state.completion = printReviewedReceipt("<html>Revisado</html>", receipt.saleNumber, true);
  }, saleReceipt());
  await page.waitForFunction(() => (window as any).__printTest.started);

  const secondPage = await page.context().newPage();
  await secondPage.goto("/__fiscal_test__");
  await secondPage.evaluate(async detail => {
    const { fiscalService } = await import("/src/services/api/fiscalService.ts" as string);
    const { flushPendingDanfePrints } = await import("/src/utils/pendingDanfePrint.ts" as string);
    const state = (window as any).__printTest = { prints: 0, queries: 0 };
    (window as any).quackDesktop = { printer: { printReceipt: async () => { state.prints++; return { printed: true }; } } };
    fiscalService.getBySaleNumber = async () => { state.queries++; return detail; };
    await flushPendingDanfePrints(String);
  }, fiscalDetail());
  await page.evaluate(async () => {
    const state = (window as any).__printTest;
    state.release(); await state.completion;
  });
  const result = await secondPage.evaluate(async queueKey => {
    const { flushPendingDanfePrints } = await import("/src/utils/pendingDanfePrint.ts" as string);
    await flushPendingDanfePrints(String);
    return { ...(window as any).__printTest, pending: JSON.parse(localStorage.getItem(queueKey) || "[]").length };
  }, QUEUE);
  expect(await page.evaluate(() => (window as any).__printTest.prints)).toBe(1);
  expect(result).toEqual({ prints: 0, queries: 0, pending: 0 });
});
