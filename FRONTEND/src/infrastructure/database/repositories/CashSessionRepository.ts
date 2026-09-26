/**
 * Arquivo: src/infrastructure/database/repositories/CashSessionRepository.ts
 * Objetivo: persiste o status de caixa no IndexedDB para acesso offline.
 *           Garante atomicidade total entre a mutação de estado local e o enfileiramento no Outbox.
 */
import { db, type CashSessionRecord, type CashSessionMovementItem } from "../dexie";
import type { CashRegisterStatusDto } from "@/services/api/cashRegisterService";
import { getCachedDeviceId } from "../deviceId";
import { enqueueEvent } from "./OutboxRepository";
import { computeCashMovementPayloadHash } from "@/utils/cryptoHash";

const CACHE_KEY = "cash-status-cache";

/** Salva o status do caixa no IndexedDB e espelha no localStorage para compatibilidade. */
export async function saveCashStatus(status: CashRegisterStatusDto): Promise<void> {
  const deviceId = getCachedDeviceId() || "unknown";
  const session = status.currentSession ?? status.lastSession;

  if (session) {
    const openingNum = parseFloat(session.openingAmount.replace(/\./g, "").replace(",", ".") || "0");
    const closingNum = session.closingAmount
      ? parseFloat(session.closingAmount.replace(/\./g, "").replace(",", ".") || "0")
      : null;

    const record: CashSessionRecord = {
      id: CACHE_KEY,
      actualSessionId: session.id,
      deviceId,
      tenantId: "",
      userId: session.operatorId || "",
      operatorName: session.operatorName || "",
      status: status.canSell ? "OPEN" : "CLOSED",
      openingAmount: openingNum,
      closingAmount: closingNum,
      openedAt: session.openedAt || "",
      closedAt: session.closedAt ?? null,
      note: session.note || "",
      differenceReason: session.differenceReason || null,
      movimentos: session.movimentos || [],
    };

    await db.cashSessions.put(record);
    if (session.id && session.id !== CACHE_KEY) {
      await db.cashSessions.put({
        ...record,
        id: session.id,
      });
    }
  }

  // Também salva o DTO completo como JSON para compatibilidade de UI
  try {
    window.localStorage.setItem("horus-pdv-cash-status", JSON.stringify(status));
  } catch {
    // localStorage cheio — ignora, temos o IndexedDB
  }
}

/** Carrega o status cacheado do caixa. Tenta IndexedDB primeiro (fonte primária), depois localStorage. */
export async function loadCachedCashStatus(): Promise<CashRegisterStatusDto | null> {
  const record = await db.cashSessions.get(CACHE_KEY);
  if (record) {
    const isOpened = record.status === "OPEN";
    const sessionId = record.actualSessionId || (record.id !== CACHE_KEY ? record.id : "cx-offline");
    return {
      state: isOpened ? "aberto" : "fechado",
      canSell: isOpened,
      blockReason: isOpened ? "" : "Caixa fechado",
      serverNow: new Date().toISOString(),
      currentSession: isOpened
        ? {
            id: sessionId,
            status: "aberto",
            openedAt: record.openedAt,
            closedAt: null,
            openingAmount: String(record.openingAmount).replace(".", ","),
            closingAmount: "0,00",
            operatorId: record.userId,
            operatorName: record.operatorName || "Operador",
            closedById: "",
            closedByName: "",
            note: record.note || "",
            elapsedMinutes: 0,
            movimentos: record.movimentos || [],
          }
        : null,
      lastSession: !isOpened
        ? {
            id: sessionId,
            status: "fechado",
            openedAt: record.openedAt,
            closedAt: record.closedAt || record.openedAt,
            openingAmount: String(record.openingAmount).replace(".", ","),
            closingAmount: record.closingAmount ? String(record.closingAmount).replace(".", ",") : "0,00",
            operatorId: record.userId,
            operatorName: record.operatorName || "Operador",
            closedById: record.userId,
            closedByName: record.operatorName || "Operador",
            note: record.note || "",
            differenceReason: record.differenceReason || null,
            elapsedMinutes: 0,
            movimentos: record.movimentos || [],
          }
        : undefined,
      history: [],
    };
  }

  // Fallback: localStorage legado
  try {
    const raw = window.localStorage.getItem("horus-pdv-cash-status");
    if (raw) {
      return JSON.parse(raw) as CashRegisterStatusDto;
    }
  } catch {
    // cache corrompido
  }

  return null;
}

/** Remove o cache legado do localStorage (migração). */
export function removeLegacyCashCache(): void {
  try {
    window.localStorage.removeItem("horus-pdv-cash-status");
  } catch {
    // ignora
  }
}

/** Abre o caixa localmente quando offline e enfileira evento no outbox de forma estritamente atômica. */
export async function openCashLocal(
  openingAmount: string,
  operatorId?: string,
  operatorName?: string,
): Promise<CashRegisterStatusDto> {
  const deviceId = getCachedDeviceId() || "unknown";
  const now = new Date().toISOString();
  const sessionId = `cx-offline-${Date.now()}`;
  const openingNum = parseFloat(openingAmount.replace(/\./g, "").replace(",", ".")) || 0;

  let resultDto: CashRegisterStatusDto;

  // Transação atômica única: leitura, persistência local e outbox
  await db.transaction("rw", [db.cashSessions, db.outbox], async () => {
    const existing = await db.cashSessions.get(CACHE_KEY);
    if (existing && existing.status === "OPEN") {
      throw new Error("Já existe um caixa aberto localmente.");
    }

    const sessionRecord: CashSessionRecord = {
      id: sessionId,
      actualSessionId: sessionId,
      deviceId,
      tenantId: "",
      userId: operatorId || "",
      operatorName: operatorName || "Operador",
      status: "OPEN",
      openingAmount: openingNum,
      closingAmount: null,
      openedAt: now,
      closedAt: null,
      movimentos: [],
    };

    await db.cashSessions.put(sessionRecord);
    await db.cashSessions.put({ ...sessionRecord, id: CACHE_KEY });

    await enqueueEvent({
      id: `ev-cxopen-${Date.now()}`,
      eventType: "CASH_OPEN",
      aggregateType: "CashSession",
      aggregateId: sessionId,
      occurredAt: now,
      payload: {
        clientSessionId: sessionId,
        openingAmount,
        operatorId,
        operatorName,
        openedAt: now,
      },
    });

    resultDto = {
      state: "aberto",
      canSell: true,
      blockReason: "",
      serverNow: now,
      currentSession: {
        id: sessionId,
        status: "aberto",
        openedAt: now,
        closedAt: null,
        openingAmount,
        closingAmount: "0,00",
        operatorId: operatorId || "",
        operatorName: operatorName || "Operador",
        closedById: "",
        closedByName: "",
        note: "",
        elapsedMinutes: 0,
        movimentos: [],
      },
      history: [],
    };
  });

  // Espelho não bloqueante no localStorage após commit da transação
  try {
    window.localStorage.setItem("horus-pdv-cash-status", JSON.stringify(resultDto!));
  } catch {
    // ignora
  }

  return resultDto!;
}

/** Fecha o caixa localmente quando offline e enfileira evento no outbox de forma estritamente atômica. */
export async function closeCashLocal(
  closingAmount: string,
  note = "",
  differenceReason?: string,
  operatorId?: string,
  operatorName?: string,
): Promise<CashRegisterStatusDto> {
  const now = new Date().toISOString();
  const closingNum = parseFloat(closingAmount.replace(/\./g, "").replace(",", ".")) || 0;
  const deviceId = getCachedDeviceId() || "unknown";

  let resultDto: CashRegisterStatusDto;

  // Transação atômica única: leitura, gravação e outbox
  await db.transaction("rw", [db.cashSessions, db.outbox], async () => {
    const cached = await db.cashSessions.get(CACHE_KEY);
    const sessionId = cached?.actualSessionId || (cached?.id && cached.id !== CACHE_KEY ? cached.id : `cx-offline-${Date.now()}`);
    const openedAt = cached?.openedAt || now;
    const openingNum = cached?.openingAmount || 0;
    const existingMovimentos = cached?.movimentos || [];

    const closedRecord: CashSessionRecord = {
      id: sessionId,
      actualSessionId: sessionId,
      deviceId,
      tenantId: "",
      userId: operatorId || cached?.userId || "",
      operatorName: operatorName || cached?.operatorName || "Operador",
      status: "CLOSED",
      openingAmount: openingNum,
      closingAmount: closingNum,
      openedAt,
      closedAt: now,
      note,
      differenceReason: differenceReason || null,
      movimentos: existingMovimentos,
    };

    await db.cashSessions.put(closedRecord);
    await db.cashSessions.put({ ...closedRecord, id: CACHE_KEY });

    await enqueueEvent({
      id: `ev-cxclose-${Date.now()}`,
      eventType: "CASH_CLOSE",
      aggregateType: "CashSession",
      aggregateId: sessionId,
      occurredAt: now,
      payload: {
        clientSessionId: sessionId,
        closingAmount,
        note,
        differenceReason,
        closedAt: now,
      },
    });

    resultDto = {
      state: "fechado",
      canSell: false,
      blockReason: "Caixa fechado",
      serverNow: now,
      currentSession: null,
      lastSession: {
        id: sessionId,
        status: "fechado",
        openedAt,
        closedAt: now,
        openingAmount: String(openingNum).replace(".", ","),
        closingAmount,
        operatorId: closedRecord.userId,
        operatorName: closedRecord.operatorName || "Operador",
        closedById: operatorId || "",
        closedByName: operatorName || "",
        note,
        differenceReason: differenceReason || null,
        elapsedMinutes: 0,
        movimentos: existingMovimentos,
      },
      history: [],
    };
  });

  // Espelho não bloqueante no localStorage após commit da transação
  try {
    window.localStorage.setItem("horus-pdv-cash-status", JSON.stringify(resultDto!));
  } catch {
    // ignora
  }

  return resultDto!;
}

/** Registra movimento de caixa (sangria/reforço) offline e enfileira no outbox de forma estritamente atômica. */
export async function registerMovementLocal(
  tipo: "Reforco" | "Sangria",
  valor: string,
  motivo: string,
  operatorId?: string,
  operatorName?: string,
): Promise<CashRegisterStatusDto> {
  const now = new Date().toISOString();
  let resultDto: CashRegisterStatusDto;

  // Transação atômica única: leitura, gravação do movimento e outbox
  await db.transaction("rw", [db.cashSessions, db.outbox], async () => {
    const cached = await db.cashSessions.get(CACHE_KEY);
    if (!cached || cached.status !== "OPEN") {
      throw new Error("Não é possível registrar movimento: nenhum caixa está aberto localmente.");
    }

    const sessionId = cached.actualSessionId || (cached.id !== CACHE_KEY ? cached.id : "cx-offline");
    const movId = `mov-local-${Date.now()}`;
    const newMovement: CashSessionMovementItem = {
      id: movId,
      tipo,
      valor,
      motivo,
      createdAt: now,
      operatorName: operatorName || "Operador",
    };

    const updatedMovimentos = [...(cached.movimentos || []), newMovement];

    const updatedRecord: CashSessionRecord = {
      ...cached,
      movimentos: updatedMovimentos,
    };

    // Persiste no cache ativo e no registro histórico da sessão
    await db.cashSessions.put(updatedRecord);
    if (sessionId !== CACHE_KEY) {
      await db.cashSessions.put({
        ...updatedRecord,
        id: sessionId,
      });
    }

    const payloadHash = await computeCashMovementPayloadHash({ tipo, valor, motivo });

    // Enfileira evento de movimento no outbox dentro da mesma transação com hash de idempotência
    await enqueueEvent({
      id: `ev-cxmov-${Date.now()}`,
      eventType: "CASH_MOVEMENT",
      aggregateType: "CashSession",
      aggregateId: sessionId,
      payloadHash,
      occurredAt: now,
      payload: {
        clientSessionId: sessionId,
        tipo,
        valor,
        motivo,
        operatorId,
        operatorName,
        createdAt: now,
      },
    });

    resultDto = {
      state: "aberto",
      canSell: true,
      blockReason: "",
      serverNow: now,
      currentSession: {
        id: sessionId,
        status: "aberto",
        openedAt: cached.openedAt,
        closedAt: null,
        openingAmount: String(cached.openingAmount).replace(".", ","),
        closingAmount: "0,00",
        operatorId: cached.userId,
        operatorName: cached.operatorName || "Operador",
        closedById: "",
        closedByName: "",
        note: cached.note || "",
        elapsedMinutes: 0,
        movimentos: updatedMovimentos,
      },
      history: [],
    };
  });

  // Espelho não bloqueante no localStorage após commit da transação
  try {
    window.localStorage.setItem("horus-pdv-cash-status", JSON.stringify(resultDto!));
  } catch {
    // ignora
  }

  return resultDto!;
}

