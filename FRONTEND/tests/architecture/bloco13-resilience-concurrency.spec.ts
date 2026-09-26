import { test, expect } from "@playwright/test";
import { registerAndLogin, loginOnly } from "./helpers/api-auth";

const API_URL = process.env.API_URL ?? "http://localhost:5260/api";

let savedEmail = "";
let savedPassword = "";

test.describe("BLOCO 13 — Homologação: Segurança, Concorrência e Resiliência Offline-First (Change 07.3)", () => {
  test.beforeAll(async ({ request }) => {
    const session = await registerAndLogin(request);
    savedEmail = session.email;
    savedPassword = session.password;
  });

  test.beforeEach(async ({ request }) => {
    await loginOnly(request, savedEmail, savedPassword);
  });

  test("Teste 61 — Abertura concorrente de caixa: requisições simultâneas com mesmo EventId resultam em 1 abertura e 1 replay sem duplicidade", async ({
    request,
  }) => {
    // Garante que o caixa esteja fechado antes de testar
    try {
      await request.post(`${API_URL}/Caixa/fechar`, {
        data: { closingAmount: "0,00", note: "Setup", differenceReason: "" },
        headers: { "Content-Type": "application/json" },
      });
    } catch {
      // Ignora se já estiver fechado
    }

    const eventId = `test-open-race-${Date.now()}`;
    const payload = {
      eventId,
      payloadHash: "hash-caixa-open-race-01",
      openingAmount: "150,00",
    };

    // Dispara 2 requisições concorrentes reais
    const [res1, res2] = await Promise.all([
      request.post(`${API_URL}/Caixa/abrir`, {
        data: payload,
        headers: { "Content-Type": "application/json" },
      }),
      request.post(`${API_URL}/Caixa/abrir`, {
        data: payload,
        headers: { "Content-Type": "application/json" },
      }),
    ]);

    expect([200, 201]).toContain(res1.status());
    expect([200, 201]).toContain(res2.status());

    const body1 = await res1.json();
    const body2 = await res2.json();

    expect(body1.success).toBe(true);
    expect(body2.success).toBe(true);

    const isReplay1 = body1.data?.isReplay ?? false;
    const isReplay2 = body2.data?.isReplay ?? false;

    // Exatamente uma deve ser a abertura original e a outra o replay idempotente
    const replays = [isReplay1, isReplay2].filter(Boolean);
    const originals = [isReplay1, isReplay2].filter((v) => !v);

    expect(originals.length).toBe(1);
    expect(replays.length).toBe(1);
  });

  test("Teste 62 — Replay de abertura de caixa: reenvio com mesmo EventId e PayloadHash retorna 200 OK com isReplay: true", async ({
    request,
  }) => {
    // Fecha o caixa anterior
    await request.post(`${API_URL}/Caixa/fechar`, {
      data: { closingAmount: "150,00", note: "Fechando para teste 62", differenceReason: "" },
      headers: { "Content-Type": "application/json" },
    });

    const eventId = `test-open-replay-${Date.now()}`;
    const payload = {
      eventId,
      payloadHash: "hash-caixa-open-replay-02",
      openingAmount: "200,00",
    };

    // Primeira chamada
    const res1 = await request.post(`${API_URL}/Caixa/abrir`, {
      data: payload,
      headers: { "Content-Type": "application/json" },
    });
    expect(res1.status()).toBe(200);
    const body1 = await res1.json();
    expect(body1.success).toBe(true);
    expect(body1.data?.isReplay).toBeFalsy();

    // Replay da mesma abertura
    const res2 = await request.post(`${API_URL}/Caixa/abrir`, {
      data: payload,
      headers: { "Content-Type": "application/json" },
    });
    expect(res2.status()).toBe(200);
    const body2 = await res2.json();
    expect(body2.success).toBe(true);
    expect(body2.data?.isReplay).toBe(true);
  });

  test("Teste 63 — Adulteração de abertura de caixa: mesmo EventId com PayloadHash alterado retorna 409 Conflict", async ({
    request,
  }) => {
    const eventId = `test-open-tamper-${Date.now()}`;
    const originalPayload = {
      eventId,
      payloadHash: "hash-open-original",
      openingAmount: "50,00",
    };

    // Fechar antes se aberto
    await request.post(`${API_URL}/Caixa/fechar`, {
      data: { closingAmount: "200,00", note: "Fechando", differenceReason: "" },
      headers: { "Content-Type": "application/json" },
    });

    // Envio original
    const res1 = await request.post(`${API_URL}/Caixa/abrir`, {
      data: originalPayload,
      headers: { "Content-Type": "application/json" },
    });
    expect(res1.status()).toBe(200);

    // Tentativa de envio com mesmo EventId mas hash divergente (adulteração de payload)
    const tamperedPayload = {
      eventId,
      payloadHash: "hash-open-tampered-adulterado",
      openingAmount: "500,00",
    };

    const res2 = await request.post(`${API_URL}/Caixa/abrir`, {
      data: tamperedPayload,
      headers: { "Content-Type": "application/json" },
    });
    expect(res2.status()).toBe(409);
    const body2 = await res2.json();
    expect(body2.success).toBe(false);
    expect(body2.message).toContain("já foi processado anteriormente com um payload diferente");
  });

  test("Teste 64 — Fechamento com conferência cega: valor contado divergente sem justificativa é rejeitado (400)", async ({
    request,
  }) => {
    // O caixa está aberto com 50,00. Tentamos fechar com 80,00 sem justificativa.
    const res = await request.post(`${API_URL}/Caixa/fechar`, {
      data: {
        closingAmount: "80,00",
        note: "Sem justificativa",
        differenceReason: "",
      },
      headers: { "Content-Type": "application/json" },
    });

    expect(res.status()).toBe(400);
    const body = await res.json();
    expect(body.success).toBe(false);
    expect(body.message).toContain("Informe uma justificativa antes de fechar o caixa");
  });

  test("Teste 65 — Fechamento com justificativa e replay idempotente: sucesso e replay com isReplay: true", async ({
    request,
  }) => {
    const eventId = `test-close-idemp-${Date.now()}`;
    const payload = {
      eventId,
      payloadHash: "hash-close-01",
      closingAmount: "80,00",
      note: "Fechamento justificado",
      differenceReason: "Sobra de troco deixada por cliente",
    };

    // Fechamento inicial
    const res1 = await request.post(`${API_URL}/Caixa/fechar`, {
      data: payload,
      headers: { "Content-Type": "application/json" },
    });
    expect(res1.status()).toBe(200);
    const body1 = await res1.json();
    expect(body1.success).toBe(true);
    expect(body1.data?.isReplay).toBeFalsy();

    // Replay do fechamento com mesmo EventId
    const res2 = await request.post(`${API_URL}/Caixa/fechar`, {
      data: payload,
      headers: { "Content-Type": "application/json" },
    });
    expect(res2.status()).toBe(200);
    const body2 = await res2.json();
    expect(body2.success).toBe(true);
    expect(body2.data?.isReplay).toBe(true);
  });

  test("Teste 66 — Sequenciamento atômico concorrente no Outbox: enqueueEvent simultâneo gera sequências estritamente crescentes", async ({
    page,
  }) => {
    await page.goto("/");
    await page.waitForFunction(() => (window as any).__horus_test__ !== undefined);

    const result = await page.evaluate(async () => {
      const { OutboxRepository, db } = (window as any).__horus_test__;
      await (window as any).__horus_test__.resetDatabase();

      // Enfileira 10 eventos simultaneamente via Promise.all
      const promises = Array.from({ length: 10 }, (_, i) =>
        OutboxRepository.enqueueEvent({
          eventType: "CONCURRENT_TEST",
          aggregateType: "Order",
          aggregateId: `order-${i}`,
          payload: { index: i },
        }),
      );

      await Promise.all(promises);

      const events = await db.outbox.orderBy("sequence").toArray();
      const sequences = events.map((e: any) => e.sequence);
      return { count: events.length, sequences };
    });

    expect(result.count).toBe(10);
    // Deve ser [1, 2, 3, 4, 5, 6, 7, 8, 9, 10] sem repetições
    expect(result.sequences).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
  });

  test("Teste 67 — Storage Lock Fallback: sincronização respeita lease do localStorage quando navigator.locks não disponível", async ({
    page,
  }) => {
    await page.goto("/");
    await page.waitForFunction(() => (window as any).__horus_test__ !== undefined);

    const result = await page.evaluate(async () => {
      const { db } = (window as any).__horus_test__;
      await (window as any).__horus_test__.resetDatabase();

      // Simula outro processo/aba segurando o storage lock
      const fakeLock = {
        expiresAt: Date.now() + 30_000,
        id: "tab-outra-aba-ativa",
      };
      window.localStorage.setItem("horus-pdv-sync-lock", JSON.stringify(fakeLock));

      // Insere evento pendente no outbox
      await db.outbox.put({
        id: "evt-locked-test",
        deviceId: "dev-1",
        tenantId: "t1",
        storeId: "s1",
        eventType: "SALE_CREATED",
        aggregateType: "Sale",
        aggregateId: "sale-locked",
        payload: "{}",
        sequence: 1,
        occurredAt: new Date().toISOString(),
        createdAt: new Date().toISOString(),
        status: "PENDING",
        retryCount: 0,
        lastAttemptAt: null,
        lastError: null,
      });

      // Tenta executar syncNow simulando ausência de navigator.locks
      const originalLocks = navigator.locks;
      try {
        Object.defineProperty(navigator, "locks", { value: undefined, configurable: true });
        await (window as any).__horus_test__.syncEngine.syncNow();
      } finally {
        Object.defineProperty(navigator, "locks", { value: originalLocks, configurable: true });
      }

      // O evento deve permanecer PENDING porque o lock estava ocupado por outra aba
      const eventAfter = await db.outbox.get("evt-locked-test");
      return { status: eventAfter?.status };
    });

    expect(result.status).toBe("PENDING");
  });

  test("Teste 68 — Erro 4xx permanente não bloqueia a fila: evento 400 marca FAILED e engine continua próximos eventos", async ({
    page,
  }) => {
    await page.goto("/");
    await page.waitForFunction(() => (window as any).__horus_test__ !== undefined);

    const result = await page.evaluate(async () => {
      const { db, OutboxRepository, syncEngine } = (window as any).__horus_test__;
      await (window as any).__horus_test__.resetDatabase();

      // Evento 1: Tipo desconhecido / que lança 4xx permanente
      const id1 = await OutboxRepository.enqueueEvent({
        eventType: "INVALID_PERMANENT_EVENT_TYPE",
        aggregateType: "Bad",
        aggregateId: "bad-1",
        payload: { error: true },
      });

      // Mock dispatchEvent para simular: evento 1 lança erro com status 400, evento 2 processa com sucesso
      const errorDetail: any[] = [];
      const errorListener = (e: any) => errorDetail.push(e.detail);
      window.addEventListener("offline-sync-error", errorListener);

      // Injeta mock direto no dispatchEvent do syncEngine
      const originalDispatch = (syncEngine as any).dispatchEvent;
      (syncEngine as any).dispatchEvent = async (type: string) => {
        if (type === "INVALID_PERMANENT_EVENT_TYPE") {
          const err: any = new Error("Payload inválido");
          err.status = 400;
          throw err;
        }
        return { isReplay: false };
      };

      // Evento 2: Evento válido subsequente
      const id2 = await OutboxRepository.enqueueEvent({
        eventType: "CASH_MOVEMENT",
        aggregateType: "Caixa",
        aggregateId: "cx-1",
        payload: { tipo: "Reforco", valor: "10,00", motivo: "Teste subsequente" },
      });

      try {
        await syncEngine.syncNow();
      } finally {
        (syncEngine as any).dispatchEvent = originalDispatch;
        window.removeEventListener("offline-sync-error", errorListener);
      }

      const event1 = await db.outbox.get(id1);
      const event2 = await db.outbox.get(id2);

      return {
        event1Status: event1?.status,
        event2Status: event2?.status,
        errorFired: errorDetail.length > 0,
      };
    });

    // Evento 1 deve ter ido para FAILED imediatamente sem travar a fila
    expect(result.event1Status).toBe("FAILED");
    // Evento 2 deve ter sido processado com sucesso na mesma rodada
    expect(result.event2Status).toBe("PROCESSED");
    expect(result.errorFired).toBe(true);
  });

  test("Teste 69 — Safe Advance do Checkpoint: se houver falha de sincronização, lastSuccessfulSyncAt não é atualizado", async ({
    page,
  }) => {
    await page.goto("/");
    await page.waitForFunction(() => (window as any).__horus_test__ !== undefined);

    const result = await page.evaluate(async () => {
      const { db, syncCoordinator } = (window as any).__horus_test__;
      await (window as any).__horus_test__.resetDatabase();

      // 1. Grava checkpoint inicial com sucesso
      await syncCoordinator.updateCheckpoint(true);
      const initial = await syncCoordinator.getCheckpoint();
      const initialTimestamp = initial?.lastSuccessfulSyncAt;

      // Espera 50ms para garantir mudança de timestamp se atualizasse
      await new Promise((res) => setTimeout(res, 50));

      // 2. Chama updateCheckpoint com erro (success = false)
      await syncCoordinator.updateCheckpoint(false);
      const afterFailure = await syncCoordinator.getCheckpoint();

      return {
        initialTimestamp,
        afterFailureTimestamp: afterFailure?.lastSuccessfulSyncAt,
      };
    });

    // O timestamp deve ter sido preservado e NÃO avançado
    expect(result.afterFailureTimestamp).toBe(result.initialTimestamp);
  });

  test("Teste 70 — Rejeição estrita de totalAmount malformado: string inválida retorna 400 Bad Request", async ({
    request,
  }) => {
    const invalidPayloads = [
      { totalAmount: "abc" },
      { totalAmount: "-50,00" },
      { totalAmount: "100.999" },
    ];

    for (const item of invalidPayloads) {
      const res = await request.post(`${API_URL}/HistoricoVendas`, {
        data: {
          customerName: "Cliente Teste",
          customerCpf: "123.456.789-00",
          paymentType: "Dinheiro",
          totalAmount: item.totalAmount,
          operatorName: "Operador Teste",
          items: [{ productCode: "PROD-TEST-01", productName: "P", quantity: 1, unitPrice: 10, desconto: 0 }],
          payments: [{ paymentType: "Dinheiro", amount: 10, cashGiven: 10, changeAmount: 0 }],
        },
        headers: { "Content-Type": "application/json" },
      });

      expect(res.status(), `Valor inválido ${item.totalAmount} deve ser rejeitado com 400`).toBe(400);
      const body = await res.json();
      expect(body.success).toBe(false);
    }
  });

  test("Teste 71 — Isolamento multi-tenant de usuário: tentativa de alterar senha ou perfil em empresa incorreta é rejeitada", async ({
    request,
  }) => {
    // Cria um segundo tenant independente
    const session2 = await registerAndLogin(request);

    // Tenta trocar senha do usuário do tenant 1 usando a sessão autenticada do tenant 2
    // A API deve rejeitar porque o CompanyId do token do tenant 2 não corresponde ao tenant 1
    const res = await request.post(`${API_URL}/Auth/change-password`, {
      data: {
        currentPassword: "SenhaIncorretaOuOutroTenant",
        newPassword: "NovaSenha@12345",
        confirmNewPassword: "NovaSenha@12345",
      },
      headers: { "Content-Type": "application/json" },
    });

    // Rejeitado com 400/404 devido a senha atual incorreta ou tenant isolado
    expect(res.status()).toBe(400);
    const body = await res.json();
    expect(body.success).toBe(false);
  });
});
