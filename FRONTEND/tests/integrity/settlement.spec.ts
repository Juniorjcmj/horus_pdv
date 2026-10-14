import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
import { moneyCents, productMoney, subtractMoney, validateSale, validateCredit, financialRevision } from "../../src/utils/saleMoney";
import { receiptFacts, registeredItemTotal } from "../../src/utils/saleReceiptFacts";
import { applyPromotions } from "../../src/utils/promotionEngine";
const fixtures = JSON.parse(readFileSync(new URL("../../../tests/fixtures/sale-settlements.json", import.meta.url), "utf8"));

test("shared fixtures, exact cents, JSON change and invalid values", () => {
  expect(moneyCents(1.005)).toBe(101); expect(moneyCents(-1.005)).toBe(-101);
  expect(productMoney(.125, 10)).toBe(1.25);
  expect(JSON.parse(JSON.stringify({ change: subtractMoney(10, 8.8) })).change).toBe(1.2);
  for (const fixture of fixtures) expect(() => validateSale(fixture.sale)).not.toThrow();
  const base = fixtures[0].sale;
  for (const invalid of [
    { ...base, totalAmount: "8,79" }, { ...base, payments: [{ paymentType: "pix", amount: 8.79 }] },
    { ...base, payments: [{ paymentType: "dinheiro", amount: 8.8, cashGiven: 10, changeAmount: 1.19 }] },
    { ...base, payments: [{ paymentType: "pix", amount: 8.8, changeAmount: 1 }] },
    ...[0, -1, NaN, Infinity].map(quantity => ({ ...base, items: [{ ...base.items[0], quantity }] })),
    { ...base, items: [{ ...base.items[0], desconto: 8.81 }] },
    { ...base, payments: [{ paymentType: "pix", amount: 0 }] },
    { ...base, payments: [{ paymentType: "pix", amount: NaN }] },
  ]) expect(() => validateSale(invalid)).toThrow();
});

test("available credit and zero limit rules", () => {
  expect(() => validateCredit(100, 60, 100)).toThrow(/60.00/);
  expect(() => validateCredit(100, 60, 40)).not.toThrow();
  expect(() => validateCredit(100, 60, 40.01)).toThrow();
  expect(() => validateCredit(0, 0, .01)).toThrow();
  expect(() => validateCredit(100, undefined, 1)).toThrow(/indisponíveis/);
});

test("receipt uses registered free item, mixed payments, unknown fields and discrepancy", () => {
  const row = { saleNumber: "fixture1245", customerName: "Teste", customerCpf: "-", paymentType: "fiado", operatorName: "Teste", productName: "Teste", saleDate: "2026-10-09", totalAmount: "82,48", quantity: 1, productCode: "FREE", unitPrice: "5,14", itemTotal: "0,00", desconto: 5.14 };
  expect(registeredItemTotal(row)).toBe(0);
  const facts = receiptFacts([row, { ...row, productCode: "SKU", unitPrice: "82,48", itemTotal: "82,48", desconto: 0 }]);
  expect(facts.subtotal).toBe(82.48); expect(facts.inconsistency).toBeUndefined(); expect(facts.cashGiven).toBeUndefined(); expect(facts.change).toBeUndefined();
  expect(receiptFacts([{ ...row, itemTotal: "87,62" }]).inconsistency).toContain("Divergência");
  expect(registeredItemTotal({ ...row, quantity: 2, unitPrice: "1,005", itemTotal: "", desconto: 0 })).toBe(2.01);
  expect(() => registeredItemTotal({ ...row, itemTotal: "", desconto: undefined })).toThrow(/não informados/);
  const mixed = receiptFacts([{ ...row, totalAmount: "100,00", itemTotal: "100,00" }], fixtures[1].sale.payments);
  expect(mixed.cashGiven).toBe(100); expect(mixed.change).toBe(50); expect(mixed.payments?.map(p => p.amount)).toEqual([50, 50]);
});

test("promotion reconciliation: removal, lost combo and financially unchanged refresh", () => {
  const now = new Date("2026-10-10T12:00:00Z");
  const combo: any = { id: "combo", ativa: true, tipo: "leve_x_pague_y", produtoIds: ["FREE"], quantidadeLeva: 2, quantidadePaga: 1, inicioVigencia: "2026-01-01", fimVigencia: "2026-12-31" };
  const raw = [{ id: "FREE", code: "FREE", name: "Biscoito", quantity: 2, unitPrice: 5.14 }, { id: "SKU", code: "SKU", name: "Demais", quantity: 1, unitPrice: 82.48 }];
  const before = applyPromotions(raw, [combo], undefined, now);
  const afterQuantity = applyPromotions([{ ...raw[0], quantity: 1 }, raw[1]], [combo], undefined, now);
  expect(before[0].discount).toBe(5.14); expect(afterQuantity[0].discount).toBe(0);
  expect(financialRevision(afterQuantity)).not.toBe(financialRevision(before));
  expect(financialRevision(applyPromotions(raw, [{ ...combo, nome: "Nome atualizado" }], undefined, now))).toBe(financialRevision(before));
  const removed = applyPromotions(raw.filter(i => i.code !== "FREE"), [combo], undefined, now);
  expect(removed.reduce((s, i) => s + i.itemTotal!, 0)).toBe(82.48); expect(removed.some(i => i.code === "FREE")).toBe(false);
});

test.beforeEach(async ({ page }) => {
  // Every external/backend request is mocked. Only the local Vite server may receive traffic.
  await page.route("**/*", route => new URL(route.request().url()).origin === "http://127.0.0.1:5175" ? route.continue() : route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ success: true, data: [] }) }));
  await page.route("http://127.0.0.1:5175/__integrity", route => route.fulfill({ contentType: "text/html", body: "<!doctype html><html><body><div id=integrity-root></div></body></html>" }));
  await page.goto("/__integrity");
  await page.evaluate(async () => {
    const RefreshRuntime = (await import("/@react-refresh")).default;
    RefreshRuntime.injectIntoGlobalHook(window);
    (window as any).$RefreshReg$ = () => {};
    (window as any).$RefreshSig$ = () => (type: any) => type;
    (window as any).__vite_plugin_react_preamble_installed__ = true;
  });
  await page.evaluate(async () => {
    const { db } = await import("/src/infrastructure/database/dexie.ts");
    for (const table of db.tables) await table.clear();
    sessionStorage.setItem("horuspdv.auth.user", JSON.stringify({ companyId: "integrity-tenant", id: "test-user", role: "caixa", name: "Teste" }));
    await db.customers.put({ id: "customer", tenantId: "integrity-tenant", name: "Teste", cpfCnpj: "52998224725", limiteCredito: 100, saldoDevedor: 60, phone: "", email: "", updatedAt: new Date().toISOString(), version: 1 });
    await db.products.put({ id: "product", tenantId: "integrity-tenant", productCode: "SKU", barcode: "SKU", productName: "Produto", stock: 100, salePrice: 10, unitPrice: 10, unit: "UN", categoryId: null, imageUrl: "", marca: null, ncm: "21069090", cfop: "5102", active: true, controlaValidade: false, dataValidade: null, diasAlertaValidade: 0, updatedAt: new Date().toISOString(), version: 1 });
  });
});

test("Dexie validates before effects, aggregates debt, idempotent retry and concurrent credit", async ({ page }) => {
  const result = await page.evaluate(async () => {
    const { db } = await import("/src/infrastructure/database/dexie.ts");
    const { queueSaleToOutbox } = await import("/src/application/sales/SaleOutboxAdapter.ts");
    const payload = (id: string, amount: number) => ({ clientSaleId: id, eventId: `event-${id}`, customerName: "Teste", customerCpf: "52998224725", paymentType: "fiado", totalAmount: amount.toFixed(2).replace(".", ","), operatorName: "Teste", items: [{ productCode: "SKU", productName: "Produto", quantity: 1, unitPrice: amount, desconto: 0 }], payments: [{ paymentType: "fiado", amount: amount / 2 }, { paymentType: "fiado", amount: amount / 2 }] });
    const rejects: string[] = [];
    for (const invalid of [payload("over", 100), payload("one-cent", 40.01), { ...payload("bad-item", 20), items: [{ productCode: "SKU", productName: "Produto", quantity: 1, unitPrice: 19.99 }] }]) {
      try { await queueSaleToOutbox(invalid); } catch (error) { rejects.push(String(error)); }
    }
    const empty = [await db.sales.count(), await db.outbox.count(), (await db.products.get("product"))!.stock];
    await queueSaleToOutbox(payload("valid", 20)); await queueSaleToOutbox(payload("valid", 20));
    let conflict = false; try { await queueSaleToOutbox(payload("valid", 21)); } catch { conflict = true; }
    const concurrent = await Promise.allSettled([queueSaleToOutbox(payload("race1", 20)), queueSaleToOutbox(payload("race2", 20))]);
    return { rejects, empty, conflict, accepted: concurrent.filter(r => r.status === "fulfilled").length, debt: (await db.customers.get("customer"))!.saldoDevedor, sales: await db.sales.count(), outbox: await db.outbox.count(), movements: await db.stockMovements.count(), stock: (await db.products.get("product"))!.stock, payments: (await db.payments.toArray()).map(p => p.amount) };
  });
  expect(result.rejects).toHaveLength(3); expect(result.empty).toEqual([0, 0, 100]); expect(result.conflict).toBe(true);
  expect(result.accepted).toBe(1); expect(result.debt).toBe(100); expect(result.sales).toBe(2); expect(result.movements).toBe(2); expect(result.outbox).toBe(2); expect(result.stock).toBe(98); expect(result.payments).toEqual([10, 10, 10, 10]);
});

test("offline free item and fractional quantity persist exactly", async ({ page }) => {
  const result = await page.evaluate(async () => {
    const { db } = await import("/src/infrastructure/database/dexie.ts");
    const { queueSaleToOutbox } = await import("/src/application/sales/SaleOutboxAdapter.ts");
    await queueSaleToOutbox({ clientSaleId: "fraction-free", eventId: "fraction-free-event", customerName: "Teste", customerCpf: "-", paymentType: "pix", totalAmount: "1,25", operatorName: "Teste", items: [{ productCode: "SKU", productName: "Fracionado", quantity: .125, unitPrice: 10, desconto: 0 }, { productCode: "FREE", productName: "Grátis", quantity: 1, unitPrice: 5.14, desconto: 5.14, itemTotal: 0 }], payments: [{ paymentType: "pix", amount: 1.25 }] });
    return (await db.saleItems.toArray()).map(i => [i.quantity, i.total, i.discount]);
  });
  expect(result).toEqual([[.125, 1.25, 0], [1, 0, 5.14]]);
});

test("generated identity survives retry in the original caller payload", async ({ page }) => {
  const result = await page.evaluate(async () => {
    const { db } = await import("/src/infrastructure/database/dexie.ts"); const { queueSaleToOutbox } = await import("/src/application/sales/SaleOutboxAdapter.ts");
    const payload: any = { customerName: "Teste", customerCpf: "52998224725", paymentType: "fiado", totalAmount: "20,00", operatorName: "Teste", items: [{ productCode: "SKU", productName: "Produto", quantity: 1, unitPrice: 20 }], payments: [{ paymentType: "fiado", amount: 20 }] };
    await queueSaleToOutbox(payload); const eventId = payload.eventId; const clientSaleId = payload.clientSaleId;
    await queueSaleToOutbox(payload);
    let conflict = false; try { await queueSaleToOutbox({ ...payload, eventId: "new-identity" }); } catch { conflict = true; }
    return { eventStable: eventId === payload.eventId, clientStable: clientSaleId === payload.clientSaleId, conflict, sales: await db.sales.count(), events: await db.outbox.count(), movements: await db.stockMovements.count(), debt: (await db.customers.get("customer"))!.saldoDevedor };
  });
  expect(result).toEqual({ eventStable: true, clientStable: true, conflict: true, sales: 1, events: 1, movements: 1, debt: 80 });
});

test("offline zero limit blocks every fiado allocation and allows non-fiado", async ({ page }) => {
  const result = await page.evaluate(async () => {
    const { db } = await import("/src/infrastructure/database/dexie.ts"); const { queueSaleToOutbox } = await import("/src/application/sales/SaleOutboxAdapter.ts");
    await db.customers.update("customer", { limiteCredito: 0, saldoDevedor: 0 });
    const base = { customerName: "Teste", customerCpf: "52998224725", paymentType: "fiado", totalAmount: "10,00", operatorName: "Teste", items: [{ productCode: "SKU", productName: "Produto", quantity: 1, unitPrice: 10 }] };
    let rejected = 0;
    for (const payments of [[{ paymentType: "fiado", amount: 10 }], [{ paymentType: "pix", amount: 9 }, { paymentType: "fiado", amount: 1 }]]) {
      try { await queueSaleToOutbox({ ...base, payments }); } catch { rejected++; }
    }
    const before = await db.sales.count();
    await queueSaleToOutbox({ ...base, paymentType: "pix", payments: [{ paymentType: "pix", amount: 10 }] });
    return { rejected, before, count: await db.sales.count(), debt: (await db.customers.get("customer"))!.saldoDevedor };
  });
  expect(result).toEqual({ rejected: 2, before: 0, count: 1, debt: 0 });
});

test("cloud refresh, lost response, online debt and repayment reconcile pending once", async ({ page }) => {
  let cloudDebt = 60; let accepted: string[] = [];
  await page.route("**/credito-snapshot", route => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ success: true, data: { customers: [{ id: "customer", customerName: "Teste", document: "52998224725", limiteCredito: 100, saldoDevedor: cloudDebt }], acceptedClientSaleIds: accepted } }) }));
  await page.evaluate(async () => {
    const { queueSaleToOutbox } = await import("/src/application/sales/SaleOutboxAdapter.ts");
    await queueSaleToOutbox({ clientSaleId: "pending", eventId: "pending-event", customerName: "Teste", customerCpf: "52998224725", paymentType: "fiado", totalAmount: "20,00", operatorName: "Teste", items: [{ productCode: "SKU", productName: "Produto", quantity: 1, unitPrice: 20 }], payments: [{ paymentType: "fiado", amount: 20 }] });
  });
  const refresh = () => page.evaluate(async () => { const c = await import("/src/application/customers/CustomerSyncAdapter.ts"); await c.syncCustomersFromApi(); return c.getLocalCustomerDebt("52998224725"); });
  expect(await refresh()).toBe(80); expect(await refresh()).toBe(80);
  cloudDebt = 80; // New online debt unrelated to pending; correct local debt = 100.
  expect(await page.evaluate(async () => { const c = await import("/src/application/customers/CustomerSyncAdapter.ts"); await c.updateCustomerDebtLocal("52998224725", 80); return c.getLocalCustomerDebt("52998224725"); })).toBe(100);
  accepted = ["pending"]; // Server accepted pending response was lost: do not add it twice.
  expect(await refresh()).toBe(80); cloudDebt = 10; expect(await refresh()).toBe(10); // Repayment frees credit.
  const state = await page.evaluate(async () => { const { db } = await import("/src/infrastructure/database/dexie.ts"); const event = await db.outbox.get("pending-event"); return { id: event!.id, status: event!.status }; });
  expect(state).toEqual({ id: "pending-event", status: "PENDING" });
});

test("malformed pending and removed customer never grant offline credit", async ({ page }) => {
  await page.route("**/credito-snapshot", route => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ success: true, data: { customers: [], acceptedClientSaleIds: [] } }) }));
  const removed = await page.evaluate(async () => { const c = await import("/src/application/customers/CustomerSyncAdapter.ts"); await c.syncCustomersFromApi(); return c.getLocalCustomerDebt("52998224725"); });
  expect(removed).toBeNull();
});

test("snapshot failure after online debt blocks credit at the outbox boundary", async ({ page }) => {
  await page.route("**/credito-snapshot", route => route.fulfill({ status: 503, contentType: "application/json", body: JSON.stringify({ success: false, message: "Snapshot indisponível" }) }));
  const result = await page.evaluate(async () => {
    const c = await import("/src/application/customers/CustomerSyncAdapter.ts"); const { db } = await import("/src/infrastructure/database/dexie.ts");
    const { queueSaleToOutbox } = await import("/src/application/sales/SaleOutboxAdapter.ts");
    await c.updateCustomerDebtLocal("52998224725", 100);
    let rejected = false;
    try { await queueSaleToOutbox({ clientSaleId: "unsafe", eventId: "unsafe-event", customerName: "Teste", customerCpf: "52998224725", paymentType: "fiado", totalAmount: "1,00", operatorName: "Teste", items: [{ productCode: "SKU", productName: "Produto", quantity: 1, unitPrice: 1 }], payments: [{ paymentType: "fiado", amount: 1 }] }); } catch { rejected = true; }
    return { flag: (await db.customers.get("customer"))!.creditUnavailable, unavailable: Number.isNaN(await c.getLocalCustomerDebt("52998224725")), rejected, sales: await db.sales.count(), outbox: await db.outbox.count() };
  });
  expect(result).toEqual({ flag: true, unavailable: true, rejected: true, sales: 0, outbox: 0 });
});

test("routine refresh network failure preserves validated offline credit", async ({ page }) => {
  await page.route("**/credito-snapshot", route => route.fulfill({ status: 503, contentType: "application/json", body: JSON.stringify({ success: false, message: "Sem rede" }) }));
  const result = await page.evaluate(async () => {
    const c = await import("/src/application/customers/CustomerSyncAdapter.ts"); const { db } = await import("/src/infrastructure/database/dexie.ts"); const { queueSaleToOutbox } = await import("/src/application/sales/SaleOutboxAdapter.ts");
    try { await c.syncCustomersFromApi(); } catch { /* The previously validated cache remains usable. */ }
    await queueSaleToOutbox({ clientSaleId: "offline-valid", eventId: "offline-valid-event", customerName: "Teste", customerCpf: "52998224725", paymentType: "fiado", totalAmount: "20,00", operatorName: "Teste", items: [{ productCode: "SKU", productName: "Produto", quantity: 1, unitPrice: 20 }], payments: [{ paymentType: "fiado", amount: 20 }] });
    return { blocked: Boolean((await db.customers.get("customer"))!.creditUnavailable), debt: await c.getLocalCustomerDebt("52998224725"), count: await db.sales.count() };
  });
  expect(result).toEqual({ blocked: false, debt: 80, count: 1 });
});

test("malformed pending payload never silently releases cached credit", async ({ page }) => {
  await page.route("**/credito-snapshot", route => route.fulfill({ contentType: "application/json", body: JSON.stringify({ success: true, data: { customers: [{ id: "customer", customerName: "Teste", document: "52998224725", limiteCredito: 100, saldoDevedor: 0 }], acceptedClientSaleIds: [] } }) }));
  const result = await page.evaluate(async () => {
    const { db } = await import("/src/infrastructure/database/dexie.ts"); const { enqueueEvent } = await import("/src/infrastructure/database/repositories/OutboxRepository.ts"); const c = await import("/src/application/customers/CustomerSyncAdapter.ts");
    await enqueueEvent({ id: "malformed", clientSaleId: "malformed-sale", eventType: "SALE_CREATED", aggregateType: "Sale", aggregateId: "malformed-sale", payload: {} }); await db.outbox.update("malformed", { payload: "{broken" });
    let failed = false; try { await c.syncCustomersFromApi(); } catch { failed = true; }
    return { failed, unavailable: (await db.customers.get("customer"))!.creditUnavailable, debt: (await db.customers.get("customer"))!.saldoDevedor, payload: (await db.outbox.get("malformed"))!.payload };
  });
  expect(result).toEqual({ failed: true, unavailable: true, debt: 60, payload: "{broken" });
});

test("new offline sale during cloud snapshot forces retry before replacing debt", async ({ page }) => {
  let requests = 0;
  await page.route("**/credito-snapshot", async route => {
    requests++;
    if (requests === 1) await page.evaluate(async () => {
      const { queueSaleToOutbox } = await import("/src/application/sales/SaleOutboxAdapter.ts");
      await queueSaleToOutbox({ clientSaleId: "racing", eventId: "racing-event", customerName: "Teste", customerCpf: "52998224725", paymentType: "fiado", totalAmount: "20,00", operatorName: "Teste", items: [{ productCode: "SKU", productName: "Produto", quantity: 1, unitPrice: 20 }], payments: [{ paymentType: "fiado", amount: 20 }] });
    });
    return route.fulfill({ contentType: "application/json", body: JSON.stringify({ success: true, data: { customers: [{ id: "customer", customerName: "Teste", document: "52998224725", limiteCredito: 100, saldoDevedor: 80 }], acceptedClientSaleIds: requests === 1 ? [] : ["racing"] } }) });
  });
  const debt = await page.evaluate(async () => { const c = await import("/src/application/customers/CustomerSyncAdapter.ts"); await c.syncCustomersFromApi(); return c.getLocalCustomerDebt("52998224725"); });
  expect(requests).toBe(2); expect(debt).toBe(80);
});

test("real fiscal adapter serializes gross cash and normalized change; cent errors reject", async ({ page }) => {
  const result = await page.evaluate(async () => {
    const { normalizeLocalFiscalPayload } = await import("/src/services/api/fiscalService.ts");
    const fixture = { vendaId: "sale", terminalId: "terminal", itens: [{ numero: 1, codigoProduto: "SKU", descricao: "Teste", quantidade: 1, valorUnitario: 8.8, valorTotal: 8.8, desconto: 0 }], pagamentos: [{ tipo: "01", valor: 10 }], valorTroco: 10 - 8.8 };
    const normalized = JSON.parse(JSON.stringify(normalizeLocalFiscalPayload(fixture)));
    let rejected = false; try { normalizeLocalFiscalPayload({ ...fixture, valorTroco: 1.19 }); } catch { rejected = true; }
    return { normalized, rejected };
  });
  expect(result.normalized.valorTroco).toBe(1.2); expect(result.normalized.pagamentos[0].valor).toBe(10); expect(result.rejected).toBe(true);
});

test("old inconsistent outbox preserves identity and actionable permanent error", async ({ page }) => {
  let posts = 0;
  await page.route("**/*", route => {
    if (route.request().method() === "POST" && /historicovendas/i.test(route.request().url())) {
      posts++; return route.fulfill({ status: 400, contentType: "application/json", body: JSON.stringify({ success: false, message: "Soma dos itens líquidos difere do total da venda." }) });
    }
    return route.fallback();
  });
  const result = await page.evaluate(async () => {
    const { db } = await import("/src/infrastructure/database/dexie.ts");
    const { enqueueEvent } = await import("/src/infrastructure/database/repositories/OutboxRepository.ts");
    const { syncEngine } = await import("/src/infrastructure/synchronization/SyncEngine.ts");
    const payload = { clientSaleId: "old-client", eventId: "old-event", customerName: "Teste", customerCpf: "-", paymentType: "pix", totalAmount: "8,80", operatorName: "Teste", items: [{ productCode: "SKU", quantity: 1, unitPrice: 8.79 }], payments: [{ paymentType: "pix", amount: 8.8 }] };
    await enqueueEvent({ id: "old-event", clientSaleId: "old-client", eventType: "SALE_CREATED", aggregateType: "Sale", aggregateId: "old-client", payload });
    const before = (await db.outbox.get("old-event"))!.payload;
    await (syncEngine as any).doProcessOutbox(); await (syncEngine as any).doProcessOutbox();
    const event = (await db.outbox.get("old-event"))!;
    return { id: event.id, aggregateId: event.aggregateId, payloadPreserved: event.payload === before, status: event.status, error: event.lastError, count: await db.outbox.count() };
  });
  expect(posts).toBe(1); expect(result).toMatchObject({ id: "old-event", aggregateId: "old-client", payloadPreserved: true, status: "FAILED", count: 1 }); expect(result.error).toContain("itens líquidos");
});

async function mockCheckout(page: import("@playwright/test").Page, promotions: () => any[]) {
  await page.route("**/*", route => {
    const path = new URL(route.request().url()).pathname.toLowerCase();
    const response = (data: any) => route.fulfill({ contentType: "application/json", body: JSON.stringify({ success: true, data }) });
    if (path.endsWith("/ativas")) return response(promotions());
    if (path.endsWith("/status")) return response({ state: "aberto", canSell: true, blockReason: "", serverNow: new Date().toISOString(), history: [], currentSession: { id: "test-cash", status: "aberto", openedAt: new Date().toISOString(), operatorId: "test-user", operatorName: "Teste", movimentos: [], openingAmount: "0,00" } });
    if (path.includes("produto") && !path.includes("/src/") && !path.includes(".ts")) return response([{ id: "product", productCode: "SKU", productName: "Produto", productSalePrice: "10,00", productUnitPrice: "1,00", productQnt: "100,00", unidadeComercial: "UN", ncm: "21069090", cfop: "5102" }]);
    if (path.endsWith("/credito-snapshot")) return response({ customers: [{ id: "customer", customerName: "Teste", document: "52998224725", limiteCredito: 100, saldoDevedor: 60 }], acceptedClientSaleIds: [] });
    return route.fallback();
  });
  await page.evaluate(async () => { (await import("/tests/integrity/mount.tsx")).mountCheckout(); });
  await expect(page.getByRole("textbox", { name: /^Produto:/ })).toBeVisible();
  await page.getByRole("textbox", { name: /^Produto:/ }).fill("SKU");
  await page.getByRole("textbox", { name: /^Produto:/ }).press("Enter");
  await expect(page.locator("table tbody tr").filter({ hasText: "SKU" }).locator("td").last().getByRole("button")).toBeVisible();
}

test("checkout focus and polling invalidate only financial changes; reference preserves cash", async ({ page }) => {
  const promotion = { id: "promo", companyId: "integrity-tenant", nome: "Promo", ativa: true, tipo: "desconto_percentual", valorDesconto: 10, produtoIds: ["SKU"], inicioVigencia: "2026-01-01", fimVigencia: "2027-12-31" };
  let promotions: any[] = [];
  await page.clock.install();
  await mockCheckout(page, () => promotions);
  await page.getByRole("button", { name: "PAGAMENTO (F12)", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Pagamento", exact: true })).toBeVisible();
  await page.getByLabel("Valor entregue em dinheiro", { exact: true }).fill("2000");
  await page.getByRole("button", { name: "Adicionar à lista de parcelas" }).click();
  promotions = [promotion]; await page.evaluate(() => window.dispatchEvent(new Event("focus")));
  await expect(page.getByRole("alert").filter({ hasText: "Carrinho ou promoção atualizado" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Confirmar Venda", exact: true })).toBeDisabled();
  await page.getByRole("button", { name: "Conferir pagamentos novamente" }).click();
  await expect(page.getByText(/Referência de valores anteriores:.*recebido R\$ 20,00, troco R\$ 10,00/)).toBeVisible();
  promotions = [{ ...promotion, nome: "Mudou só nome" }]; await page.evaluate(() => window.dispatchEvent(new Event("focus")));
  await expect(page.getByRole("button", { name: "Confirmar Venda", exact: true })).toBeEnabled();
  promotions = [{ ...promotion, valorDesconto: 20 }]; await page.clock.runFor(60_001);
  await expect(page.getByRole("alert").filter({ hasText: "Carrinho ou promoção atualizado" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Confirmar Venda", exact: true })).toBeDisabled();
  await page.getByRole("button", { name: "Voltar", exact: true }).click();
  await page.locator("table tbody tr").filter({ hasText: "SKU" }).locator("td").last().getByRole("button").click();
  await expect(page.locator("table tbody tr").filter({ hasText: "SKU" }).locator("td").last().getByRole("button")).toHaveCount(0);
});

test("checkout duplicate click blocked and submitted payload is current snapshot", async ({ page }) => {
  let posts = 0; let payload: any;
  await mockCheckout(page, () => []);
  await page.route("**/*", async route => {
    if (route.request().method() === "POST" && /historicovendas/i.test(route.request().url())) {
      posts++; payload = route.request().postDataJSON();
      await new Promise(resolve => setTimeout(resolve, 500));
      return route.fulfill({ contentType: "application/json", body: JSON.stringify({ success: true, data: { saleNumber: "test-sale", emitirFiscal: false, isReplay: false } }) });
    }
    return route.fallback();
  });
  await page.getByRole("button", { name: "PAGAMENTO (F12)", exact: true }).click(); await expect(page.getByRole("button", { name: "Confirmar Venda", exact: true })).toBeEnabled();
  await page.evaluate(() => { const button = [...document.querySelectorAll("button")].find(b => b.textContent?.trim() === "Confirmar Venda")!; button.click(); button.click(); });
  await expect.poll(() => posts).toBe(1);
  await expect(page.getByRole("heading", { name: "Pagamento", exact: true })).toHaveCount(0);
  expect(payload.totalAmount).toBe("10,00"); expect(payload.items[0].itemTotal).toBe(10); expect(payload.payments[0]).toMatchObject({ amount: 10, cashGiven: 10, changeAmount: 0 });
});

test("history renders incomplete legacy row alongside free item without crashing", async ({ page }) => {
  const row = { saleNumber: "history-fixture", customerName: "Teste", customerCpf: "-", paymentType: "pix", totalAmount: "82,48", operatorName: "Teste", productCode: "FREE", productName: "Grátis", quantity: 1, unitPrice: "5,14", itemTotal: "0,00", desconto: 5.14, saleDate: "09/10/2026 12:00:00" };
  await page.route("**/*", route => /historicovendas/i.test(route.request().url()) ? route.fulfill({ contentType: "application/json", body: JSON.stringify({ success: true, data: [row, { ...row, saleNumber: "unknown-fixture", productName: "Legado incompleto", productCode: "UNKNOWN", itemTotal: "", desconto: undefined }] }) }) : route.fallback());
  await page.evaluate(async () => { (await import("/tests/integrity/mount.tsx")).mountHistory(); });
  await expect(page.getByText("Valor não informado", { exact: true })).toBeVisible();
  await expect(page.getByText("Grátis", { exact: true })).toBeVisible();
  await expect(page.getByText("Legado incompleto", { exact: true })).toBeVisible();
});

test("receipt HTML preserves mixed received/change and omits unknown historic cash", async ({ page }) => {
  const result = await page.evaluate(async () => {
    const { buildReceiptPrintHtml } = await import("/src/components/Admin/ReceiptPreviewModal.tsx");
    const base = { saleNumber: "receipt-fixture", issuedAt: "09/10/2026 12:00:00", company: null, customerCpf: "-", paymentType: "dinheiro", paymentLabel: "Dinheiro", operatorName: "Teste", subtotal: 100, items: [{ id: "free", code: "FREE", name: "Grátis", quantity: 1, unitPrice: 5.14, total: 0, discount: 5.14 }, { id: "other", code: "SKU", name: "Demais", quantity: 1, unitPrice: 100, total: 100 }] };
    const unknown = buildReceiptPrintHtml(base, value => value.toFixed(2));
    const mixed = buildReceiptPrintHtml({ ...base, payments: [{ paymentType: "pix", paymentLabel: "PIX", amount: 50 }, { paymentType: "dinheiro", paymentLabel: "Dinheiro", amount: 50, cashGiven: 100, changeAmount: 50 }] }, value => value.toFixed(2));
    return { unknown, mixed };
  });
  expect(result.unknown).not.toContain("Valor recebido"); expect(result.unknown).not.toContain("Troco</span>");
  expect(result.mixed).toContain("Grátis"); expect(result.mixed).toContain("0.00");
  expect(result.mixed).toContain('Valor recebido</span><span>R$ 100.00'); expect(result.mixed).toContain('Troco</span><span>R$ 50.00');
});
