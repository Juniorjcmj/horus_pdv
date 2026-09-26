import { test, expect } from "@playwright/test";

test.describe("BLOCO 4 — Atomicidade do Caixa Offline", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/");
    await page.waitForFunction(() => (window as any).__horus_test__ !== undefined);
    await page.evaluate(async () => {
      await (window as any).__horus_test__.resetDatabase();
    });
  });

  test("Teste 15 — Open: openCashLocal produz cashSession e CASH_OPEN no Outbox na mesma transação", async ({ page }) => {
    const result = await page.evaluate(async () => {
      const { CashSessionRepository, db } = (window as any).__horus_test__;
      const statusDto = await CashSessionRepository.openCashLocal("150,00", "op-test-1", "Operador Teste");

      const sessionRecord = await db.cashSessions.get("cash-status-cache");
      const outboxEvents = await db.outbox.where("eventType").equals("CASH_OPEN").toArray();

      return {
        statusDtoCanSell: statusDto.canSell,
        sessionStatus: sessionRecord?.status,
        sessionOpeningAmount: sessionRecord?.openingAmount,
        outboxEventsCount: outboxEvents.length,
        outboxEventType: outboxEvents[0]?.eventType,
      };
    });

    expect(result.statusDtoCanSell).toBe(true);
    expect(result.sessionStatus).toBe("OPEN");
    expect(result.sessionOpeningAmount).toBe(150);
    expect(result.outboxEventsCount).toBe(1);
    expect(result.outboxEventType).toBe("CASH_OPEN");
  });

  test("Teste 16 — Close: closeCashLocal atualiza sessão e grava CASH_CLOSE no Outbox atomicamente", async ({ page }) => {
    const result = await page.evaluate(async () => {
      const { CashSessionRepository, db } = (window as any).__horus_test__;
      await CashSessionRepository.openCashLocal("100,00", "op-test-1", "Operador Teste");
      const closeDto = await CashSessionRepository.closeCashLocal("120,00", "Fechamento regular", undefined, "op-test-1", "Operador Teste");

      const sessionRecord = await db.cashSessions.get("cash-status-cache");
      const closeEvents = await db.outbox.where("eventType").equals("CASH_CLOSE").toArray();

      return {
        closeDtoCanSell: closeDto.canSell,
        sessionStatus: sessionRecord?.status,
        closingAmount: sessionRecord?.closingAmount,
        closeEventsCount: closeEvents.length,
        closeEventType: closeEvents[0]?.eventType,
      };
    });

    expect(result.closeDtoCanSell).toBe(false);
    expect(result.sessionStatus).toBe("CLOSED");
    expect(result.closingAmount).toBe(120);
    expect(result.closeEventsCount).toBe(1);
    expect(result.closeEventType).toBe("CASH_CLOSE");
  });

  test("Teste 17 — Movement: registerMovementLocal persiste movimento no caixa e enfileira CASH_MOVEMENT no Outbox", async ({ page }) => {
    const result = await page.evaluate(async () => {
      const { CashSessionRepository, db } = (window as any).__horus_test__;
      await CashSessionRepository.openCashLocal("200,00", "op-test-1", "Operador Teste");
      const status = await CashSessionRepository.registerMovementLocal("Sangria", "50,00", "Pagamento fornecedor", "op-test-1", "Operador Teste");

      const sessionRecord = await db.cashSessions.get("cash-status-cache");
      const movementEvents = await db.outbox.where("eventType").equals("CASH_MOVEMENT").toArray();

      return {
        movimentosDtoCount: status.currentSession?.movimentos?.length,
        movimentosRecordCount: sessionRecord?.movimentos?.length,
        movimentoTipo: sessionRecord?.movimentos?.[0]?.tipo,
        movimentoValor: sessionRecord?.movimentos?.[0]?.valor,
        movementEventsCount: movementEvents.length,
        movementEventType: movementEvents[0]?.eventType,
        payloadHashPresent: !!movementEvents[0]?.payloadHash,
      };
    });

    expect(result.movimentosDtoCount).toBe(1);
    expect(result.movimentosRecordCount).toBe(1);
    expect(result.movimentoTipo).toBe("Sangria");
    expect(result.movimentoValor).toBe("50,00");
    expect(result.movementEventsCount).toBe(1);
    expect(result.movementEventType).toBe("CASH_MOVEMENT");
    expect(result.payloadHashPresent).toBe(true);
  });

  test("Teste 18 — Rollback: falha forçada dentro da transação real garante zero registros parciais", async ({ page }) => {
    const result = await page.evaluate(async () => {
      const { db, OutboxRepository } = (window as any).__horus_test__;

      let caughtError = "";
      try {
        await db.transaction("rw", [db.cashSessions, db.outbox], async () => {
          // 1. Modifica o caixa dentro da transação
          await db.cashSessions.put({
            id: "cash-status-cache",
            deviceId: "dev-test",
            tenantId: "t-1",
            userId: "u-1",
            status: "OPEN",
            openingAmount: 999,
            closingAmount: null,
            openedAt: new Date().toISOString(),
            closedAt: null,
          });

          // 2. Enfileira evento no outbox dentro da transação
          await OutboxRepository.enqueueEvent({
            eventType: "CASH_OPEN",
            aggregateType: "CashSession",
            aggregateId: "cx-abort",
            payload: { openingAmount: "999,00" },
          });

          // 3. Força erro no meio da transação antes do commit
          throw new Error("Falha forçada dentro da transação atômica real!");
        });
      } catch (err: any) {
        caughtError = err.message;
      }

      const sessionAfter = await db.cashSessions.get("cash-status-cache");
      const outboxCount = await db.outbox.count();

      return {
        caughtError,
        sessionExists: !!sessionAfter,
        outboxCount,
      };
    });

    expect(result.caughtError).toBe("Falha forçada dentro da transação atômica real!");
    expect(result.sessionExists).toBe(false);
    expect(result.outboxCount).toBe(0);
  });
});
