/**
 * Arquivo: src/infrastructure/database/repositories/CashSessionRepository.ts
 * Objetivo: persiste o status de caixa no IndexedDB para acesso offline.
 *           Garante atomicidade total entre a mutação de estado local e o enfileiramento no Outbox.
 */
import { db, type CashSessionRecord, type CashSessionMovementItem, type CashSessionPaymentItem } from "../dexie";
import type { CashRegisterStatusDto } from "@/services/api/cashRegisterService";
import { getCachedDeviceId } from "../deviceId";
import { enqueueEvent } from "./OutboxRepository";
import { computeCashMovementPayloadHash } from "@/utils/cryptoHash";

const CACHE_KEY = "cash-status-cache";
const FORMA_DINHEIRO = "dinheiro";

function parseMoney(value: string | number | null | undefined): number {
  if (value === null || value === undefined) return 0;
  if (typeof value === "number") return value;
  const parsed = parseFloat(value.replace(/\./g, "").replace(",", "."));
  return Number.isFinite(parsed) ? parsed : 0;
}

function formatMoney(value: number): string {
  return value.toLocaleString("pt-BR", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/** Minutos entre a abertura e `until` (agora, por padrão) — o servidor manda isso pronto; offline calculamos. */
function elapsedMinutesSince(openedAt: string | null | undefined, until?: string | null): number {
  const start = openedAt ? Date.parse(openedAt) : Number.NaN;
  const end = until ? Date.parse(until) : Date.now();
  if (Number.isNaN(start) || Number.isNaN(end)) return 0;
  return Math.max(0, Math.floor((end - start) / 60_000));
}

function round2(value: number): number {
  return Math.round((value + Number.EPSILON) * 100) / 100;
}

/**
 * Soma, por forma de pagamento, as vendas feitas neste aparelho que ainda NÃO foram sincronizadas
 * (evento SALE_CREATED no outbox fora do status PROCESSED) desde a abertura do turno. As já
 * sincronizadas estão no total do servidor e não podem ser somadas de novo.
 */
async function sumPendingLocalSales(openedAt: string): Promise<Record<string, number>> {
  const events = await db.outbox
    .where("eventType")
    .equals("SALE_CREATED")
    .filter((event) => event.status !== "PROCESSED")
    .toArray();
  const saleIds = events.map((event) => event.aggregateId);
  if (saleIds.length === 0) return {};

  const openedMs = Date.parse(openedAt);
  const sales = await db.sales
    .where("id")
    .anyOf(saleIds)
    .filter((sale) => {
      if (sale.status !== "COMPLETED") return false;
      const createdMs = Date.parse(sale.createdAt);
      return Number.isNaN(openedMs) || Number.isNaN(createdMs) || createdMs >= openedMs;
    })
    .toArray();
  if (sales.length === 0) return {};

  const payments = await db.payments
    .where("saleId")
    .anyOf(sales.map((sale) => sale.id))
    .toArray();

  const totals: Record<string, number> = {};
  for (const payment of payments) {
    const key = payment.paymentType.trim().toLowerCase() || "outros";
    totals[key] = (totals[key] ?? 0) + payment.amount;
  }
  return totals;
}

export type OfflineCashSummary = {
  paymentBreakdown: CashSessionPaymentItem[];
  /** Dinheiro esperado na gaveta: fundo de troco + vendas em dinheiro + reforços - sangrias (pt-BR). */
  expectedCashAmount: string;
};

/**
 * Totais do turno quando o servidor não está disponível: último total conhecido do servidor
 * + vendas locais pendentes de sincronização + movimentos (sangria/reforço) já guardados.
 */
export async function computeOfflineCashSummary(
  record: Pick<CashSessionRecord, "openedAt" | "openingAmount" | "movimentos" | "paymentBreakdown">,
): Promise<OfflineCashSummary> {
  const totals: Record<string, number> = {};
  for (const item of record.paymentBreakdown ?? []) {
    const key = item.paymentType.trim().toLowerCase() || "outros";
    totals[key] = (totals[key] ?? 0) + parseMoney(item.total);
  }

  const pending = await sumPendingLocalSales(record.openedAt);
  for (const [key, value] of Object.entries(pending)) {
    totals[key] = (totals[key] ?? 0) + value;
  }

  const movimentos = record.movimentos ?? [];
  const reforcos = movimentos
    .filter((item) => item.tipo.toLowerCase() === "reforco")
    .reduce((sum, item) => sum + parseMoney(item.valor), 0);
  const sangrias = movimentos
    .filter((item) => item.tipo.toLowerCase() === "sangria")
    .reduce((sum, item) => sum + parseMoney(item.valor), 0);

  const expected = round2(record.openingAmount + (totals[FORMA_DINHEIRO] ?? 0) + reforcos - sangrias);

  return {
    paymentBreakdown: Object.entries(totals).map(([paymentType, total]) => ({
      paymentType,
      total: formatMoney(round2(total)),
    })),
    expectedCashAmount: formatMoney(expected),
  };
}

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
      // Guarda o total por forma de pagamento do servidor: base para o fechamento offline.
      paymentBreakdown: session.paymentBreakdown ?? [],
      expectedCashAmount: session.expectedCashAmount ?? null,
      differenceAmount: session.differenceAmount ?? null,
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
    // Turno aberto: totais ao vivo (servidor + vendas locais pendentes). Fechado: valores congelados no fechamento.
    const liveSummary = isOpened ? await computeOfflineCashSummary(record) : null;
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
            openingAmount: formatMoney(record.openingAmount),
            closingAmount: "0,00",
            operatorId: record.userId,
            operatorName: record.operatorName || "Operador",
            closedById: "",
            closedByName: "",
            note: record.note || "",
            elapsedMinutes: elapsedMinutesSince(record.openedAt),
            movimentos: record.movimentos || [],
            expectedCashAmount: liveSummary?.expectedCashAmount,
            paymentBreakdown: liveSummary?.paymentBreakdown,
          }
        : null,
      lastSession: !isOpened
        ? {
            id: sessionId,
            status: "fechado",
            openedAt: record.openedAt,
            closedAt: record.closedAt || record.openedAt,
            openingAmount: formatMoney(record.openingAmount),
            closingAmount: record.closingAmount ? formatMoney(record.closingAmount) : "0,00",
            operatorId: record.userId,
            operatorName: record.operatorName || "Operador",
            closedById: record.userId,
            closedByName: record.operatorName || "Operador",
            note: record.note || "",
            differenceReason: record.differenceReason || null,
            elapsedMinutes: elapsedMinutesSince(record.openedAt, record.closedAt),
            movimentos: record.movimentos || [],
            expectedCashAmount: record.expectedCashAmount ?? null,
            differenceAmount: record.differenceAmount ?? null,
            paymentBreakdown: record.paymentBreakdown ?? [],
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
/**
 * `eventId`: o mesmo já enviado na tentativa online. Se o servidor gravou mas a resposta não chegou
 * (timeout), o reenvio da fila com o mesmo EventId vira replay idempotente em vez de duplicar.
 */
export async function openCashLocal(
  openingAmount: string,
  operatorId?: string,
  operatorName?: string,
  eventId?: string,
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
      id: eventId || `ev-cxopen-${Date.now()}`,
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
        // Sem vendas nem movimentos ainda: a gaveta deve ter só o fundo de troco.
        expectedCashAmount: openingAmount,
        paymentBreakdown: [],
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
  /** Mesmo EventId da tentativa online (ver openCashLocal). */
  eventId?: string,
): Promise<CashRegisterStatusDto> {
  const now = new Date().toISOString();
  const closingNum = parseFloat(closingAmount.replace(/\./g, "").replace(",", ".")) || 0;
  const deviceId = getCachedDeviceId() || "unknown";

  let resultDto: CashRegisterStatusDto;

  // Totais do turno calculados ANTES da transação (ela só cobre cashSessions e outbox; as vendas
  // e pagamentos locais ficam em outras tabelas): servidor + vendas locais pendentes + movimentos.
  const cachedBefore = await db.cashSessions.get(CACHE_KEY);
  const summary = await computeOfflineCashSummary({
    openedAt: cachedBefore?.openedAt || now,
    openingAmount: cachedBefore?.openingAmount || 0,
    movimentos: cachedBefore?.movimentos || [],
    paymentBreakdown: cachedBefore?.paymentBreakdown,
  });
  const differenceNum = round2(closingNum - parseMoney(summary.expectedCashAmount));

  // Transação atômica única: leitura, gravação e outbox
  await db.transaction("rw", [db.cashSessions, db.outbox], async () => {
    const cached = await db.cashSessions.get(CACHE_KEY);
    const sessionId = cached?.actualSessionId || (cached?.id && cached.id !== CACHE_KEY ? cached.id : `cx-offline-${Date.now()}`);
    const openedAt = cached?.openedAt || now;
    const openingNum = cached?.openingAmount || 0;
    const existingMovimentos = cached?.movimentos || [];
    const openedMs = Date.parse(openedAt);
    const elapsedMinutes = Number.isNaN(openedMs) ? 0 : Math.max(0, Math.floor((Date.parse(now) - openedMs) / 60000));

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
      paymentBreakdown: summary.paymentBreakdown,
      expectedCashAmount: summary.expectedCashAmount,
      differenceAmount: formatMoney(differenceNum),
    };

    await db.cashSessions.put(closedRecord);
    await db.cashSessions.put({ ...closedRecord, id: CACHE_KEY });

    await enqueueEvent({
      id: eventId || `ev-cxclose-${Date.now()}`,
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
        elapsedMinutes,
        movimentos: existingMovimentos,
        expectedCashAmount: summary.expectedCashAmount,
        differenceAmount: formatMoney(differenceNum),
        paymentBreakdown: summary.paymentBreakdown,
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
  /** Mesmo EventId da tentativa online (ver openCashLocal). */
  eventId?: string,
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
      id: eventId || `ev-cxmov-${Date.now()}`,
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
        openingAmount: formatMoney(cached.openingAmount),
        closingAmount: "0,00",
        operatorId: cached.userId,
        operatorName: cached.operatorName || "Operador",
        closedById: "",
        closedByName: "",
        note: cached.note || "",
        elapsedMinutes: elapsedMinutesSince(cached.openedAt),
        movimentos: updatedMovimentos,
      },
      history: [],
    };
  });

  // Esperado em gaveta e totais atualizados com o novo movimento (leitura fora da transação:
  // vendas e pagamentos locais ficam em outras tabelas).
  const cachedAfter = await db.cashSessions.get(CACHE_KEY);
  if (cachedAfter && resultDto!.currentSession) {
    const summary = await computeOfflineCashSummary(cachedAfter);
    resultDto!.currentSession.expectedCashAmount = summary.expectedCashAmount;
    resultDto!.currentSession.paymentBreakdown = summary.paymentBreakdown;
  }

  // Espelho não bloqueante no localStorage após commit da transação
  try {
    window.localStorage.setItem("horus-pdv-cash-status", JSON.stringify(resultDto!));
  } catch {
    // ignora
  }

  return resultDto!;
}

