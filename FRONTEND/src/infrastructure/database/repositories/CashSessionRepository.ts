/**
 * Arquivo: src/infrastructure/database/repositories/CashSessionRepository.ts
 * Objetivo: persiste o status de caixa no IndexedDB para acesso offline.
 *           Substitui o localStorage (horus-pdv-cash-status) usado anteriormente.
 */
import { db, type CashSessionRecord } from "../dexie";
import type { CashRegisterStatusDto } from "@/services/api/cashRegisterService";
import { getCachedDeviceId } from "../deviceId";
import { enqueueEvent } from "./OutboxRepository";

const CACHE_KEY = "cash-status-cache";

/** Salva o status do caixa no IndexedDB. */
export async function saveCashStatus(status: CashRegisterStatusDto): Promise<void> {
  const deviceId = getCachedDeviceId() || "unknown";
  const session = status.currentSession;

  await db.cashSessions.put({
    id: CACHE_KEY,
    deviceId,
    tenantId: "",
    userId: session?.operatorId || "",
    status: status.canSell ? "OPEN" : "CLOSED",
    openingAmount: session ? parseFloat(session.openingAmount.replace(",", ".") || "0") : 0,
    closingAmount: session?.closingAmount
      ? parseFloat(session.closingAmount.replace(",", ".") || "0")
      : null,
    openedAt: session?.openedAt || "",
    closedAt: session?.closedAt ?? null,
  });

  // Também salva o DTO completo como JSON para compatibilidade
  try {
    window.localStorage.setItem("horus-pdv-cash-status", JSON.stringify(status));
  } catch {
    // localStorage cheio — ignora, temos o IndexedDB
  }
}

/** Carrega o status cacheado do caixa. Tenta IndexedDB primeiro, depois localStorage. */
export async function loadCachedCashStatus(): Promise<CashRegisterStatusDto | null> {
  // Tenta localStorage (tem o DTO completo com todas as propriedades)
  try {
    const raw = window.localStorage.getItem("horus-pdv-cash-status");
    if (raw) {
      return JSON.parse(raw) as CashRegisterStatusDto;
    }
  } catch {
    // cache corrompido — tenta IndexedDB
  }

  // Fallback: constrói um CashRegisterStatusDto mínimo a partir do IndexedDB
  const record = await db.cashSessions.get(CACHE_KEY);
  if (!record) return null;

  return {
    state: record.status === "OPEN" ? "aberto" : "fechado",
    canSell: record.status === "OPEN",
    blockReason: record.status === "OPEN" ? "" : "Caixa fechado",
    serverNow: new Date().toISOString(),
    currentSession: record.status === "OPEN"
      ? {
          id: record.id,
          status: "aberto",
          openedAt: record.openedAt,
          closedAt: null,
          openingAmount: String(record.openingAmount).replace(".", ","),
          closingAmount: "0",
          operatorId: record.userId,
          operatorName: "",
          closedById: "",
          closedByName: "",
          note: "",
          elapsedMinutes: 0,
          movimentos: [],
        }
      : null,
    history: [],
  };
}

/** Remove o cache legado do localStorage (migração). */
export function removeLegacyCashCache(): void {
  try {
    window.localStorage.removeItem("horus-pdv-cash-status");
  } catch {
    // ignora
  }
}

/** Abre o caixa localmente quando offline e enfileira evento no outbox (transação atômica). */
export async function openCashLocal(
  openingAmount: string,
  operatorId?: string,
  operatorName?: string,
): Promise<CashRegisterStatusDto> {
  const deviceId = getCachedDeviceId() || "unknown";
  const now = new Date().toISOString();
  const sessionId = `cx-offline-${Date.now()}`;
  const openingNum = parseFloat(openingAmount.replace(/\./g, "").replace(",", ".")) || 0;

  const sessionRecord: CashSessionRecord = {
    id: sessionId,
    deviceId,
    tenantId: "",
    userId: operatorId || "",
    status: "OPEN",
    openingAmount: openingNum,
    closingAmount: null,
    openedAt: now,
    closedAt: null,
  };

  // Transação atômica: sessão + cache + outbox
  await db.transaction("rw", [db.cashSessions, db.outbox], async () => {
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
  });

  const statusDto: CashRegisterStatusDto = {
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

  try {
    window.localStorage.setItem("horus-pdv-cash-status", JSON.stringify(statusDto));
  } catch {
    // ignora
  }

  return statusDto;
}

/** Fecha o caixa localmente quando offline e enfileira evento no outbox (transação atômica). */
export async function closeCashLocal(
  closingAmount: string,
  note = "",
  differenceReason?: string,
  operatorId?: string,
  operatorName?: string,
): Promise<CashRegisterStatusDto> {
  const now = new Date().toISOString();
  const cached = await db.cashSessions.get(CACHE_KEY);
  const sessionId = cached?.id && cached.id !== CACHE_KEY ? cached.id : `cx-offline-${Date.now()}`;
  const closingNum = parseFloat(closingAmount.replace(/\./g, "").replace(",", ".")) || 0;
  const deviceId = getCachedDeviceId() || "unknown";

  const closedRecord: CashSessionRecord = {
    id: sessionId,
    deviceId,
    tenantId: "",
    userId: operatorId || cached?.userId || "",
    status: "CLOSED",
    openingAmount: cached?.openingAmount || 0,
    closingAmount: closingNum,
    openedAt: cached?.openedAt || now,
    closedAt: now,
  };

  // Transação atômica: sessão + cache + outbox
  await db.transaction("rw", [db.cashSessions, db.outbox], async () => {
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
  });

  const statusDto: CashRegisterStatusDto = {
    state: "fechado",
    canSell: false,
    blockReason: "Caixa fechado",
    serverNow: now,
    currentSession: null,
    lastSession: {
      id: sessionId,
      status: "fechado",
      openedAt: closedRecord.openedAt,
      closedAt: now,
      openingAmount: String(closedRecord.openingAmount).replace(".", ","),
      closingAmount,
      operatorId: closedRecord.userId,
      operatorName: operatorName || "Operador",
      closedById: operatorId || "",
      closedByName: operatorName || "",
      note,
      differenceReason: differenceReason || null,
      elapsedMinutes: 0,
      movimentos: [],
    },
    history: [],
  };

  try {
    window.localStorage.setItem("horus-pdv-cash-status", JSON.stringify(statusDto));
  } catch {
    // ignora
  }

  return statusDto;
}

/** Registra movimento de caixa (sangria/reforço) offline e enfileira no outbox (transação atômica). */
export async function registerMovementLocal(
  tipo: "Reforco" | "Sangria",
  valor: string,
  motivo: string,
  operatorId?: string,
  operatorName?: string,
): Promise<CashRegisterStatusDto> {
  const now = new Date().toISOString();
  const cached = await loadCachedCashStatus();

  // Transação atômica: movimento local + outbox
  await db.transaction("rw", [db.cashSessions, db.outbox], async () => {
    await enqueueEvent({
      id: `ev-cxmov-${Date.now()}`,
      eventType: "CASH_MOVEMENT",
      aggregateType: "CashSession",
      aggregateId: cached?.currentSession?.id || "cx-offline",
      occurredAt: now,
      payload: {
        tipo,
        valor,
        motivo,
        operatorId,
        operatorName,
        createdAt: now,
      },
    });

    if (cached?.currentSession) {
      cached.currentSession.movimentos = cached.currentSession.movimentos || [];
      cached.currentSession.movimentos.push({
        id: `mov-local-${Date.now()}`,
        tipo,
        valor,
        motivo,
        createdAt: now,
        operatorName: operatorName || "Operador",
      });
      await saveCashStatus(cached);
    }
  });

  return cached || (await loadCachedCashStatus())!;
}
