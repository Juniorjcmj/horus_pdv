import { test, expect } from "@playwright/test";

/**
 * Change offline-caixa-resiliencia: o PDV nunca abandona evento por falha transitória, só marca FAILED em
 * recusa de negócio, e a cadeia de caixa de um terminal fica retida atrás de um evento de caixa recusado.
 */
test.describe("Offline: entrega garantida do outbox", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/");
    await page.waitForFunction(() => (window as any).__horus_test__ !== undefined);
    await page.evaluate(async () => {
      await (window as any).__horus_test__.resetDatabase();
    });
  });

  test("falha de rede repetida não abandona o evento (continua PENDING)", async ({ page }) => {
    const result = await page.evaluate(async () => {
      const { db, OutboxRepository, syncEngine } = (window as any).__horus_test__;
      const id = await OutboxRepository.enqueueEvent({
        eventType: "CASH_MOVEMENT",
        aggregateType: "Caixa",
        aggregateId: "cx-1",
        payload: { tipo: "Reforco", valor: "10,00", motivo: "x" },
      });
      const original = (syncEngine as any).dispatchEvent;
      (syncEngine as any).dispatchEvent = async () => {
        throw new Error("Failed to fetch");
      };
      try {
        for (let i = 0; i < 15; i++) {
          await db.outbox.update(id, { lastAttemptAt: null }); // ignora o backoff para acelerar o teste
          await syncEngine.syncNow();
        }
      } finally {
        (syncEngine as any).dispatchEvent = original;
      }
      const event = await db.outbox.get(id);
      return { status: event.status, retryCount: event.retryCount };
    });

    expect(result.status).toBe("PENDING");
    expect(result.retryCount).toBeGreaterThanOrEqual(15);
  });

  test("evento FAILED por limite de tentativas de versão anterior volta à fila na inicialização", async ({ page }) => {
    const result = await page.evaluate(async () => {
      const { db, OutboxRepository } = (window as any).__horus_test__;
      const mk = (aggregateId: string) =>
        OutboxRepository.enqueueEvent({ eventType: "CASH_MOVEMENT", aggregateType: "Caixa", aggregateId, payload: {} });
      const abandoned = await mk("a");
      const rejected = await mk("b");
      await db.outbox.update(abandoned, { status: "FAILED", retryCount: 10, lastError: "Failed to fetch" });
      await db.outbox.update(rejected, { status: "FAILED", retryCount: 1, lastError: "HTTP 400: diferença" });
      const requeued = await OutboxRepository.requeueExhaustedTransientEvents();
      return {
        requeued,
        abandoned: (await db.outbox.get(abandoned)).status,
        rejected: (await db.outbox.get(rejected)).status,
      };
    });

    expect(result).toEqual({ requeued: 1, abandoned: "PENDING", rejected: "FAILED" });
  });

  test("recusa 400 marca FAILED com o motivo; 401/403/429 continuam pendentes", async ({ page }) => {
    const result = await page.evaluate(async () => {
      const { db, OutboxRepository, syncEngine } = (window as any).__horus_test__;
      const ids: Record<string, string> = {};
      for (const status of [400, 401, 403, 429, 503]) {
        ids[status] = await OutboxRepository.enqueueEvent({
          eventType: "CASH_MOVEMENT",
          aggregateType: "Caixa",
          aggregateId: `cx-${status}`,
          payload: { status },
        });
      }
      const original = (syncEngine as any).dispatchEvent;
      (syncEngine as any).dispatchEvent = async (_type: string, payload: string) => {
        const err: any = new Error(`erro ${JSON.parse(payload).status}`);
        err.status = JSON.parse(payload).status;
        throw err;
      };
      try {
        // Um ciclo para cada evento: erro transitório interrompe o ciclo, então processa um por vez.
        for (let i = 0; i < 6; i++) {
          await db.outbox.where("status").equals("PENDING").modify({ lastAttemptAt: null });
          await syncEngine.syncNow();
        }
      } finally {
        (syncEngine as any).dispatchEvent = original;
      }
      const out: Record<string, { status: string; lastError: string | null }> = {};
      for (const [status, id] of Object.entries(ids)) {
        const e = await db.outbox.get(id);
        out[status] = { status: e.status, lastError: e.lastError };
      }
      return out;
    });

    expect(result["400"].status).toBe("FAILED");
    expect(result["400"].lastError).toContain("erro 400");
    for (const code of ["401", "403", "429", "503"]) expect(result[code].status).toBe("PENDING");
  });

  test("CASH_OPEN fica retido atrás de CASH_CLOSE recusado do mesmo terminal e a venda é enviada", async ({ page }) => {
    const result = await page.evaluate(async () => {
      const { db, OutboxRepository, syncEngine } = (window as any).__horus_test__;
      const mk = (eventType: string, aggregateId: string) =>
        OutboxRepository.enqueueEvent({ eventType, aggregateType: "X", aggregateId, payload: {} });
      const close = await mk("CASH_CLOSE", "cx-old");
      const open = await mk("CASH_OPEN", "cx-new");
      const sale = await mk("SALE_CREATED", "venda-1");
      const sent: string[] = [];
      const original = (syncEngine as any).dispatchEvent;
      (syncEngine as any).dispatchEvent = async (type: string) => {
        sent.push(type);
        if (type === "CASH_CLOSE") {
          const err: any = new Error("Informe uma justificativa antes de fechar o caixa");
          err.status = 400;
          throw err;
        }
        return { isReplay: false };
      };
      try {
        await syncEngine.syncNow();
        await syncEngine.syncNow(); // 2º ciclo: o CASH_OPEN continua retido pelo FAILED do ciclo anterior
      } finally {
        (syncEngine as any).dispatchEvent = original;
      }
      return {
        sent,
        close: (await db.outbox.get(close)).status,
        open: (await db.outbox.get(open)).status,
        sale: (await db.outbox.get(sale)).status,
      };
    });

    expect(result.sent).toEqual(["CASH_CLOSE", "SALE_CREATED"]);
    expect(result.close).toBe("FAILED");
    expect(result.open).toBe("PENDING");
    expect(result.sale).toBe("PROCESSED");
  });
});
