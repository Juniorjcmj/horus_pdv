import { test, expect } from "@playwright/test";

test.describe("BLOCO 1 — Outbox Resilience & Lifecycle", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/");
    await page.waitForFunction(() => (window as any).__horus_test__ !== undefined);
    await page.evaluate(async () => {
      await (window as any).__horus_test__.resetDatabase();
    });
  });

  test("Teste 1 — Novo evento: deve ser criado com status PENDING e campos obrigatórios", async ({ page }) => {
    const result = await page.evaluate(async () => {
      const { OutboxRepository, db } = (window as any).__horus_test__;
      const eventId = await OutboxRepository.enqueueEvent({
        eventType: "TEST_EVENT",
        aggregateType: "TestAggregate",
        aggregateId: "agg-1",
        payload: { message: "Hello Outbox" },
      });

      const event = await db.outbox.get(eventId);
      return { eventId, event };
    });

    expect(result.event).toBeDefined();
    expect(result.event.id).toBe(result.eventId);
    expect(result.event.status).toBe("PENDING");
    expect(result.event.retryCount).toBe(0);
    expect(result.event.sequence).toBeGreaterThan(0);
    expect(result.event.eventType).toBe("TEST_EVENT");
    expect(JSON.parse(result.event.payload)).toEqual({ message: "Hello Outbox" });
  });

  test("Teste 2 — Processamento normal: transição PENDING -> PROCESSING -> PROCESSED", async ({ page }) => {
    const result = await page.evaluate(async () => {
      const { OutboxRepository, db } = (window as any).__horus_test__;
      const eventId = await OutboxRepository.enqueueEvent({
        eventType: "TEST_EVENT",
        aggregateType: "TestAggregate",
        aggregateId: "agg-2",
        payload: { test: true },
      });

      await OutboxRepository.markProcessing(eventId);
      const afterProcessing = await db.outbox.get(eventId);

      await OutboxRepository.markProcessed(eventId);
      const afterProcessed = await db.outbox.get(eventId);

      return {
        processingStatus: afterProcessing.status,
        hasLastAttempt: !!afterProcessing.lastAttemptAt,
        processedStatus: afterProcessed.status,
      };
    });

    expect(result.processingStatus).toBe("PROCESSING");
    expect(result.hasLastAttempt).toBe(true);
    expect(result.processedStatus).toBe("PROCESSED");
  });

  test("Teste 3 — Falha e retry: incremento de tentativa, registro de erro e retorno a PENDING", async ({ page }) => {
    const result = await page.evaluate(async () => {
      const { OutboxRepository, db } = (window as any).__horus_test__;
      const eventId = await OutboxRepository.enqueueEvent({
        eventType: "TEST_EVENT",
        aggregateType: "TestAggregate",
        aggregateId: "agg-3",
        payload: { step: 1 },
      });

      await OutboxRepository.markProcessing(eventId);
      await OutboxRepository.markFailed(eventId, "Falha de conexão com a API");
      const event = await db.outbox.get(eventId);

      return {
        status: event.status,
        retryCount: event.retryCount,
        lastError: event.lastError,
      };
    });

    expect(result.status).toBe("PENDING");
    expect(result.retryCount).toBe(1);
    expect(result.lastError).toBe("Falha de conexão com a API");
  });

  test("Teste 4 — Lease ativo: recoverStaleProcessing preserva eventos em PROCESSING recentes", async ({ page }) => {
    const result = await page.evaluate(async () => {
      const { OutboxRepository, db } = (window as any).__horus_test__;
      const eventId = await OutboxRepository.enqueueEvent({
        eventType: "TEST_EVENT",
        aggregateType: "TestAggregate",
        aggregateId: "agg-4",
        payload: {},
      });

      await OutboxRepository.markProcessing(eventId);
      // O evento acabou de entrar em PROCESSING (lease ativo)
      const recoveredCount = await OutboxRepository.recoverStaleProcessing(120_000);
      const event = await db.outbox.get(eventId);

      return {
        recoveredCount,
        status: event.status,
      };
    });

    expect(result.recoveredCount).toBe(0);
    expect(result.status).toBe("PROCESSING");
  });

  test("Teste 5 — Lease expirado: recoverStaleProcessing reseta eventos obsoletos para PENDING", async ({ page }) => {
    const result = await page.evaluate(async () => {
      const { OutboxRepository, db } = (window as any).__horus_test__;
      const eventId = await OutboxRepository.enqueueEvent({
        eventType: "TEST_EVENT",
        aggregateType: "TestAggregate",
        aggregateId: "agg-5",
        payload: {},
      });

      // Simula evento abandonado em PROCESSING há mais de 5 minutos
      const fiveMinutesAgo = new Date(Date.now() - 300_000).toISOString();
      await db.outbox.update(eventId, {
        status: "PROCESSING",
        lastAttemptAt: fiveMinutesAgo,
      });

      const recoveredCount = await OutboxRepository.recoverStaleProcessing(120_000);
      const event = await db.outbox.get(eventId);

      return {
        recoveredCount,
        status: event.status,
      };
    });

    expect(result.recoveredCount).toBe(1);
    expect(result.status).toBe("PENDING");
  });

  test("Teste 6 — Limite de retries: transição para FAILED ao esgotar tentativas", async ({ page }) => {
    const result = await page.evaluate(async () => {
      const { OutboxRepository, db } = (window as any).__horus_test__;
      const eventId = await OutboxRepository.enqueueEvent({
        eventType: "TEST_EVENT",
        aggregateType: "TestAggregate",
        aggregateId: "agg-6",
        payload: {},
      });

      // Simula 9 falhas anteriores
      await db.outbox.update(eventId, { retryCount: 9 });

      // 10ª falha atingindo o limite de retries
      await OutboxRepository.markFailed(eventId, "Erro persistente");
      const event = await db.outbox.get(eventId);
      const pendingEvents = await OutboxRepository.getPendingEvents();

      return {
        status: event.status,
        retryCount: event.retryCount,
        isIncludedInPending: pendingEvents.some((e: any) => e.id === eventId),
      };
    });

    expect(result.status).toBe("FAILED");
    expect(result.retryCount).toBe(10);
    expect(result.isIncludedInPending).toBe(false);
  });

  test("Teste 7 — Retry manual: evento FAILED retorna a PENDING e pode ser reprocessado", async ({ page }) => {
    const result = await page.evaluate(async () => {
      const { OutboxRepository, db } = (window as any).__horus_test__;
      const eventId = await OutboxRepository.enqueueEvent({
        eventType: "TEST_EVENT",
        aggregateType: "TestAggregate",
        aggregateId: "agg-7",
        payload: {},
      });

      await db.outbox.update(eventId, {
        status: "FAILED",
        retryCount: 10,
        lastError: "Erro fatal",
      });

      // Executa retry manual
      await OutboxRepository.retryFailedEvent(eventId);
      const event = await db.outbox.get(eventId);
      const pendingEvents = await OutboxRepository.getPendingEvents();

      return {
        status: event.status,
        retryCount: event.retryCount,
        lastError: event.lastError,
        isIncludedInPending: pendingEvents.some((e: any) => e.id === eventId),
      };
    });

    expect(result.status).toBe("PENDING");
    expect(result.retryCount).toBe(0);
    expect(result.lastError).toBeNull();
    expect(result.isIncludedInPending).toBe(true);
  });
});
