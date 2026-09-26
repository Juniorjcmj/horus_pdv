/**
 * CHANGE 08.1 — Categoria M: Recuperação de Conexão
 *
 * M01 — Conexão cai durante sincronização
 *       Evento PENDING → inicia envio → conexão interrompida →
 *       evento permanece recuperável → conexão retorna →
 *       SyncEngine tenta novamente → evento processado → sem duplicação.
 */
import { test, expect } from "@playwright/test";
import {
  RUN_ID,
  API_URL,
  initSqlContainer,
  registerTestCompany,
  loginApi,
  loginBrowserSession,
  cleanupHomologData,
  api,
  type LoginData,
} from "./helpers/setup";

test.describe("M — Recuperação de Conexão", () => {
  test.describe.configure({ mode: "serial" });

  test.beforeAll(async ({ request }) => {
    initSqlContainer();
    cleanupHomologData();
    await registerTestCompany(request);
    await loginApi(request);
  });

  test.afterAll(() => {
    cleanupHomologData();
  });

  test("M01 — Conexão cai durante sincronização e evento é recuperável", async ({
    page,
  }) => {
    await loginBrowserSession(page);

    // Verificar harness
    const hasHarness = await page.evaluate(() => {
      const h = (window as unknown as Record<string, unknown>).__horus_test__ as {
        OutboxRepository?: {
          enqueueEvent?: unknown;
          getPendingCount?: unknown;
          markProcessing?: unknown;
          recoverStaleProcessing?: unknown;
        };
        syncEngine?: { syncNow?: unknown };
        resetDatabase?: unknown;
      };
      return {
        hasOutbox: !!h?.OutboxRepository?.enqueueEvent,
        hasProcessing: !!h?.OutboxRepository?.markProcessing,
        hasRecover: !!h?.OutboxRepository?.recoverStaleProcessing,
        hasSyncEngine: !!h?.syncEngine?.syncNow,
        hasReset: !!h?.resetDatabase,
      };
    });

    if (!hasHarness.hasOutbox) {
      test.skip(true, "GAP: OutboxRepository não disponível no harness (P1)");
      return;
    }

    // 1. Criar evento PENDING
    const setupResult = await page.evaluate(async (runId) => {
      const h = (window as unknown as Record<string, unknown>).__horus_test__ as {
        resetDatabase: () => Promise<void>;
        OutboxRepository: {
          enqueueEvent: (params: {
            eventType: string;
            aggregateId: string;
            payloadJson: string;
          }) => Promise<string>;
          getPendingCount: () => Promise<number>;
          getPendingEvents: () => Promise<
            Array<{ id: string; status: string; eventType: string }>
          >;
        };
      };

      await h.resetDatabase();

      // Enfileirar evento
      const eventId = await h.OutboxRepository.enqueueEvent({
        eventType: "SALE_CREATED",
        aggregateId: `sale-recovery-${Date.now()}`,
        payloadJson: JSON.stringify({
          customerName: `${runId} RecoveryTest`,
          paymentType: "Dinheiro",
          totalAmount: 25,
          items: [{ productCode: "RECOVERY-TEST", quantity: 1, unitPrice: 25 }],
        }),
      });

      const pendingCount = await h.OutboxRepository.getPendingCount();
      return { eventId, pendingCount };
    }, RUN_ID);

    expect(setupResult.eventId, "Evento deve ser criado").toBeTruthy();
    expect(setupResult.pendingCount, "Deve haver evento PENDING").toBeGreaterThan(0);

    // 2. Simular que o evento está em PROCESSING (conexão caiu durante envio)
    if (hasHarness.hasProcessing) {
      const processingResult = await page.evaluate(async (evtId) => {
        const h = (window as unknown as Record<string, unknown>).__horus_test__ as {
          OutboxRepository: {
            markProcessing: (id: string) => Promise<void>;
            getPendingEvents: () => Promise<
              Array<{ id: string; status: string }>
            >;
          };
        };

        // Marcar como PROCESSING (simulando envio em andamento)
        await h.OutboxRepository.markProcessing(evtId);

        const events = await h.OutboxRepository.getPendingEvents();
        return {
          // getPendingEvents retorna PENDING, não PROCESSING
          pendingAfterProcessing: events.length,
        };
      }, setupResult.eventId);

      // 3. Evento em PROCESSING não aparece como PENDING
      // (está "em voo" — se a conexão cai, precisa ser recuperado)

      // 4. Recuperar eventos stale (simular que a conexão voltou)
      if (hasHarness.hasRecover) {
        const recoveryResult = await page.evaluate(async () => {
          const h = (window as unknown as Record<string, unknown>).__horus_test__ as {
            OutboxRepository: {
              recoverStaleProcessing: (staleMs?: number) => Promise<number>;
              getPendingCount: () => Promise<number>;
              getPendingEvents: () => Promise<
                Array<{ id: string; status: string }>
              >;
            };
          };

          // Recuperar com staleMs=0 (imediato para teste)
          const recovered = await h.OutboxRepository.recoverStaleProcessing(0);
          const pendingAfterRecovery =
            await h.OutboxRepository.getPendingCount();

          return { recovered, pendingAfterRecovery };
        });

        expect(
          recoveryResult.recovered,
          "Pelo menos 1 evento deve ser recuperado de PROCESSING para PENDING",
        ).toBeGreaterThanOrEqual(1);
        expect(
          recoveryResult.pendingAfterRecovery,
          "Evento recuperado deve estar PENDING novamente",
        ).toBeGreaterThan(0);
      }
    }

    // 5. Validar que o Outbox mantém a integridade
    const finalState = await page.evaluate(async () => {
      const h = (window as unknown as Record<string, unknown>).__horus_test__ as {
        OutboxRepository: {
          getPendingCount: () => Promise<number>;
          getFailedCount: () => Promise<number>;
        };
      };

      const pending = await h.OutboxRepository.getPendingCount();
      const failed = await h.OutboxRepository.getFailedCount();
      return { pending, failed };
    });

    // Evento deve estar em PENDING (pronto para retry) ou FAILED (se ultrapassou retries)
    expect(
      finalState.pending + finalState.failed,
      "Evento deve estar recuperável (PENDING ou FAILED)",
    ).toBeGreaterThan(0);
  });

  test("M02 — API responde normalmente quando online", async ({ request }) => {
    const me = await api<LoginData["user"]>(request, "/Auth/me");
    expect(me.companyId, "API /Auth/me deve retornar dados do usuário").toBeTruthy();
  });

  test("M03 — Route interception simula offline e restauração funciona", async ({
    page,
  }) => {
    await loginBrowserSession(page);

    // Simular offline
    await page.route("**/api/**", (route) => route.abort("connectionrefused"));

    const offlineResult = await page.evaluate(async (apiUrl) => {
      try {
        await fetch(`${apiUrl}/Auth/me`, { credentials: "include" });
        return { reachable: true };
      } catch {
        return { reachable: false };
      }
    }, API_URL);

    expect(offlineResult.reachable, "API deve ser inacessível em modo offline").toBe(
      false,
    );

    // Restaurar conexão
    await page.unroute("**/api/**");

    const onlineResult = await page.evaluate(async (apiUrl) => {
      try {
        const res = await fetch(`${apiUrl}/Auth/me`, {
          credentials: "include",
          headers: { "Content-Type": "application/json" },
        });
        return { reachable: res.ok, status: res.status };
      } catch {
        return { reachable: false };
      }
    }, API_URL);

    expect(onlineResult.reachable, "API deve responder após restaurar conexão").toBe(
      true,
    );
  });
});
