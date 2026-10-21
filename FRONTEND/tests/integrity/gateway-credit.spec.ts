import { test, expect } from "@playwright/test";

test.beforeEach(async ({ page }) => {
  await page.route("**/*", route => new URL(route.request().url()).origin === "http://127.0.0.1:5175" ? route.continue() : route.fulfill({ status: 200, contentType: "application/json", body: "{}" }));
  await page.route("**/__credit", route => route.fulfill({ contentType: "text/html", body: "<!doctype html><body>Crédito fixture</body>" }));
  await page.goto("/__credit");
  await page.evaluate(async () => {
    sessionStorage.setItem("horuspdv.auth.user", JSON.stringify({ companyId: "credit-tenant", id: "operator", role: "caixa" }));
    localStorage.setItem("horus-credit-policy:credit-tenant:operator", JSON.stringify({ required: true, state: "ACTIVE", version: 1 }));
    localStorage.setItem("horus-credit-policy:credit-tenant:operator:permit", JSON.stringify({ permit: "fixture-permit" }));
    localStorage.setItem("horus-gateway-config", JSON.stringify({ enabled: true, url: location.origin + "/fixture-gateway", companyId: "credit-tenant", storeId: "store", terminalId: "terminal", apiKey: "fixture-key" }));
    const { db } = await import("/src/infrastructure/database/dexie.ts");
    for (const table of db.tables) await table.clear();
  });
});

test("online mixed credit uses Gateway only and does not require a deciding customer cache", async ({ page }) => {
  let reserves = 0; let commits = 0;
  await page.route("**/fixture-gateway/api/gateway/credit/**", async route => {
    const path = new URL(route.request().url()).pathname;
    const body = route.request().postDataJSON();
    let result: unknown;
    if (path.endsWith("/budget")) result = { grantId: "grant", customerId: "customer" };
    else if (path.endsWith("/reserve")) { reserves++; expect(body.amountCents).toBe(3000); expect(body.operatorPermit).toBe("fixture-permit"); result = { state: "RESERVED" }; }
    else if (path.endsWith("/commit")) { commits++; result = { state: "COMMITTED" }; }
    else { await route.fulfill({ status: 404, contentType: "application/json", body: "{}" }); return; }
    await route.fulfill({ contentType: "application/json", body: JSON.stringify(result) });
  });
  const result = await page.evaluate(async () => {
    const { queueSaleToOutbox } = await import("/src/application/sales/SaleOutboxAdapter.ts");
    const { db } = await import("/src/infrastructure/database/dexie.ts");
    const payload = { clientSaleId: "sale", eventId: "event", customerName: "Cliente", customerCpf: "123", operatorName: "Caixa", operatorId: "operator", totalAmount: "100,00", paymentType: "Múltiplo", items: [{ productCode: "SKU", productName: "Produto", quantity: 1, unitPrice: 100 }], payments: [{ paymentType: "fiado", amount: 30 }, { paymentType: "pix", amount: 70 }] };
    await queueSaleToOutbox(payload); await queueSaleToOutbox(payload);
    return { sales: await db.sales.count(), intents: await db.creditIntents.count(), outbox: await db.outbox.count(), payload: JSON.parse((await db.outbox.toArray())[0].payload) };
  });
  expect(result.sales).toBe(1); expect(result.intents).toBe(0); expect(result.outbox).toBe(1);
  expect(result.payload.gatewayCreditAuthorizationId).toBeTruthy(); expect(reserves).toBe(1); expect(commits).toBe(1);
});

test("lost commit response and reload recover the same identity without a duplicate sale", async ({ page }) => {
  let committed = false; let authorization = ""; let commits = 0;
  await page.route("**/fixture-gateway/api/gateway/credit/**", async route => {
    const path = new URL(route.request().url()).pathname;
    const body = route.request().postDataJSON();
    if (path.endsWith("/budget")) return route.fulfill({ json: { grantId: "grant", customerId: "customer" } });
    if (path.endsWith("/reserve")) { authorization = body.authorizationId; return route.fulfill({ json: { state: "RESERVED" } }); }
    if (path.endsWith("/commit")) { commits++; committed = true; return route.abort("failed"); }
    if (committed) { expect(path.endsWith(authorization)).toBeTruthy(); return route.fulfill({ json: { state: "COMMITTED" } }); }
    return route.fulfill({ status: 404, json: {} });
  });
  const pending = await page.evaluate(async () => {
    const { queueSaleToOutbox } = await import("/src/application/sales/SaleOutboxAdapter.ts");
    const { db } = await import("/src/infrastructure/database/dexie.ts");
    let message = "";
    try { await queueSaleToOutbox({ clientSaleId: "sale", eventId: "event", customerName: "Cliente", customerCpf: "123", operatorName: "Caixa", operatorId: "operator", totalAmount: "30,00", paymentType: "fiado", items: [{ productCode: "SKU", productName: "Produto", quantity: 1, unitPrice: 30 }], payments: [{ paymentType: "fiado", amount: 30 }] }); } catch (error) { message = (error as Error).message; }
    return { message, intents: await db.creditIntents.count(), sales: await db.sales.count() };
  });
  expect(pending.message).toContain("incerto"); expect(pending.intents).toBe(1); expect(pending.sales).toBe(0);
  await page.reload();
  const result = await page.evaluate(async () => {
    const { recoverGatewayCreditSales } = await import("/src/application/sales/SaleOutboxAdapter.ts");
    const { cancelPendingCreditReservation } = await import("/src/infrastructure/gateway/gatewayCreditClient.ts");
    const { db } = await import("/src/infrastructure/database/dexie.ts");
    const cancelAllowed = await cancelPendingCreditReservation();
    await Promise.all([recoverGatewayCreditSales(), recoverGatewayCreditSales()]);
    return { sales: await db.sales.count(), outbox: await db.outbox.count(), intents: await db.creditIntents.count(), cancelAllowed };
  });
  expect(result).toEqual({ sales: 1, outbox: 1, intents: 0, cancelAllowed: false }); expect(commits).toBe(1);
});

test("known insufficient reserve clears intent and an uncertain uncommitted reserve can be cancelled", async ({ page }) => {
  let mode = "deny"; let reserved = false;
  await page.route("**/fixture-gateway/api/gateway/credit/**", async route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith("/budget")) return route.fulfill({ json: { grantId: "grant", customerId: "customer" } });
    if (path.endsWith("/reserve")) {
      if (mode === "deny") return route.fulfill({ status: 409, json: { error: "BUDGET_INSUFFICIENT" } });
      reserved = true; return route.fulfill({ json: { state: "RESERVED" } });
    }
    if (path.endsWith("/commit")) return route.abort("failed");
    if (path.endsWith("/cancel")) return route.fulfill({ json: { state: "CANCELLED" } });
    return reserved ? route.fulfill({ json: { state: "RESERVED" } }) : route.fulfill({ status: 404, json: {} });
  });
  const deny = await page.evaluate(async () => {
    const { queueSaleToOutbox } = await import("/src/application/sales/SaleOutboxAdapter.ts");
    const { db } = await import("/src/infrastructure/database/dexie.ts");
    try { await queueSaleToOutbox({ clientSaleId: "denied", eventId: "denied-event", customerName: "Cliente", customerCpf: "123", operatorName: "Caixa", operatorId: "operator", totalAmount: "30,00", paymentType: "fiado", items: [{ productCode: "SKU", productName: "Produto", quantity: 1, unitPrice: 30 }], payments: [{ paymentType: "fiado", amount: 30 }] }); } catch { /* expected */ }
    return { intents: await db.creditIntents.count(), sales: await db.sales.count() };
  });
  expect(deny).toEqual({ intents: 0, sales: 0 }); mode = "reserve";
  const cancel = await page.evaluate(async () => {
    const { queueSaleToOutbox } = await import("/src/application/sales/SaleOutboxAdapter.ts");
    const { cancelPendingCreditReservation } = await import("/src/infrastructure/gateway/gatewayCreditClient.ts");
    const { db } = await import("/src/infrastructure/database/dexie.ts");
    try { await queueSaleToOutbox({ clientSaleId: "uncertain", eventId: "uncertain-event", customerName: "Cliente", customerCpf: "123", operatorName: "Caixa", operatorId: "operator", totalAmount: "30,00", paymentType: "fiado", items: [{ productCode: "SKU", productName: "Produto", quantity: 1, unitPrice: 30 }], payments: [{ paymentType: "fiado", amount: 30 }] }); } catch { /* lost response */ }
    const pending = await db.creditIntents.count(); const allowed = await cancelPendingCreditReservation();
    return { pending, allowed, intents: await db.creditIntents.count(), sales: await db.sales.count() };
  });
  expect(cancel).toEqual({ pending: 1, allowed: true, intents: 0, sales: 0 });
});
