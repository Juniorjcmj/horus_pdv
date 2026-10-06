import { test, expect, type Page, type Route } from "@playwright/test";

const GW = "http://gateway.test:5080";

type Captured = { body: any; headers: Record<string, string> };

async function setupGateway(page: Page, mode: { up: boolean }, captured: Captured[]) {
  await page.route(`${GW}/**`, async (route: Route) => {
    if (!mode.up) return route.abort();
    const url = route.request().url();
    if (url.endsWith("/api/gateway/status")) {
      return route.fulfill({ json: { service: "HorusGateway", bound: true, companyId: "emp-1", storeId: "loja-01", gatewayId: "gw-1", terminalAuthRequired: true } });
    }
    if (url.endsWith("/api/gateway/events") && route.request().method() === "POST") {
      captured.push({ body: route.request().postDataJSON(), headers: route.request().headers() });
      return route.fulfill({ json: { status: "accepted", ack: true } });
    }
    return route.fulfill({ status: 404, json: {} });
  });
}

async function prepare(page: Page) {
  await page.goto("/");
  await page.waitForFunction(() => (window as any).__horus_test__ !== undefined);
  await page.evaluate(async (gw) => {
    const { resetDatabase, CashSessionRepository } = (window as any).__horus_test__;
    await resetDatabase();
    localStorage.setItem(
      "horus-gateway-config",
      JSON.stringify({ enabled: true, url: gw, companyId: "emp-1", storeId: "loja-01", terminalId: "PDV-01", terminalType: "CASH", apiKey: "chave-terminal" }),
    );
    await CashSessionRepository.saveCashStatus({
      state: "aberto", canSell: true, blockReason: "", serverNow: new Date().toISOString(), history: [], lastSession: null,
      currentSession: {
        id: "s-1", status: "Aberto", openedAt: new Date().toISOString(), closedAt: null, openingAmount: "100,00", closingAmount: "0,00",
        operatorId: "usr-1", operatorName: "Maria", closedById: "", closedByName: "", note: "", elapsedMinutes: 1,
        expectedCashAmount: "100,00", movimentos: [], paymentBreakdown: [],
      },
    });
  }, GW);
}

const sale = (operatorId: string | undefined, eventId: string, paymentType = "dinheiro") => ({
  eventId, clientSaleId: `cs-${eventId}`, operatorId,
  customerName: "Maria Fiado", customerCpf: "111.222.333-44", paymentType, totalAmount: "30,00", operatorName: "Maria",
  items: [{ productCode: "P001", productName: "Arroz", quantity: 1, unitPrice: 30, desconto: 0, itemTotal: 30 }],
  payments: [{ paymentType, amount: 30, cashGiven: null, changeAmount: null }],
  payloadHash: `hash-${eventId}`,
});

test.describe("Gateway como fila: sem internet a fila vai ao Gateway da loja", () => {
  test("eventos com operador vão ao Gateway (mesmo eventId) e ficam FORWARDED; sem operador ficam PENDING", async ({ page }) => {
    const captured: Captured[] = [];
    await setupGateway(page, { up: true }, captured);
    await prepare(page);

    const r = await page.evaluate(async ([s1, s2]) => {
      const { SaleOutboxAdapter, CashSessionRepository, db } = (window as any).__horus_test__;
      await SaleOutboxAdapter.queueSaleToOutbox(s1);
      await SaleOutboxAdapter.queueSaleToOutbox(s2);
      await CashSessionRepository.registerMovementLocal("Reforco", "15,00", "Troco", "usr-1", "Maria", "ev-mov-1");
      const mod = await import("/src/infrastructure/gateway/outboxGatewayForwarder.ts" as string);
      const result = await mod.forwardPendingToGateway();
      const events = await db.outbox.orderBy("sequence").toArray();
      return { result, statuses: events.map((e: any) => `${e.id}:${e.status}`) };
    }, [sale("usr-1", "ev-venda-1"), sale(undefined, "ev-venda-antiga")]);

    expect(r.result).toMatchObject({ forwarded: 2, skipped: 1 });
    expect(r.statuses).toEqual(["ev-venda-1:FORWARDED", "ev-venda-antiga:PENDING", "ev-mov-1:FORWARDED"]);

    expect(captured.map((c) => c.body.eventId)).toEqual(["ev-venda-1", "ev-mov-1"]);
    const venda = captured[0];
    expect(venda.body).toMatchObject({ eventType: "SALE_CREATED", companyId: "emp-1", terminalId: "PDV-01", payloadHash: "hash-ev-venda-1" });
    expect(venda.body.payload.operatorId).toBe("usr-1");
    expect(venda.headers["x-terminal-id"]).toBe("PDV-01");
    expect(venda.headers["x-terminal-key"]).toBe("chave-terminal");
    expect(captured[1].body).toMatchObject({ eventType: "CASH_MOVEMENT" });
    expect(captured[1].body.payload).toMatchObject({ tipo: "Reforco", valor: "15,00", operatorId: "usr-1" });
  });

  test("FORWARDED continua na fila de envio à nuvem e protege o saldo do fiado", async ({ page }) => {
    const captured: Captured[] = [];
    await setupGateway(page, { up: true }, captured);
    await prepare(page);

    const r = await page.evaluate(async (s) => {
      const { SaleOutboxAdapter, CustomerSyncAdapter, customerService, OutboxRepository, db } = (window as any).__horus_test__;
      await db.customers.put({
        id: "cli-1", tenantId: "", name: "Maria Fiado", cpfCnpj: "111.222.333-44", phone: "", email: "",
        limiteCredito: 100, saldoDevedor: 30, updatedAt: new Date().toISOString(), version: 1,
      });
      await SaleOutboxAdapter.queueSaleToOutbox(s);
      const mod = await import("/src/infrastructure/gateway/outboxGatewayForwarder.ts" as string);
      await mod.forwardPendingToGateway();

      const paraNuvem = (await OutboxRepository.getPendingEvents()).map((e: any) => `${e.id}:${e.status}`);
      // Servidor ainda sem a venda (só o Gateway tem): saldo do servidor 30 + fiado 30 pendente = 60
      const original = customerService.list;
      customerService.list = async () => [{ id: "cli-1", customerName: "Maria Fiado", document: "111.222.333-44", limiteCredito: 100, saldoDevedor: 30 }];
      try {
        await CustomerSyncAdapter.syncCustomersFromApi();
      } finally {
        customerService.list = original;
      }
      return { paraNuvem, saldo: (await db.customers.get("cli-1"))?.saldoDevedor };
    }, sale("usr-1", "ev-fiado-1", "fiado"));

    expect(r.paraNuvem).toEqual(["ev-fiado-1:FORWARDED"]);
    expect(r.saldo).toBe(60);
  });

  test("Gateway fora do ar: nada é marcado, eventos seguem PENDING", async ({ page }) => {
    const captured: Captured[] = [];
    await setupGateway(page, { up: false }, captured);
    await prepare(page);

    const r = await page.evaluate(async (s) => {
      const { SaleOutboxAdapter, db } = (window as any).__horus_test__;
      await SaleOutboxAdapter.queueSaleToOutbox(s);
      const mod = await import("/src/infrastructure/gateway/outboxGatewayForwarder.ts" as string);
      const result = await mod.forwardPendingToGateway();
      return { result, status: (await db.outbox.get("ev-venda-2"))?.status };
    }, sale("usr-1", "ev-venda-2"));

    expect(r.result.forwarded).toBe(0);
    expect(r.status).toBe("PENDING");
    expect(captured).toHaveLength(0);
  });
});
