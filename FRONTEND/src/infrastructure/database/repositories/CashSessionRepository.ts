/**
 * Arquivo: src/infrastructure/database/repositories/CashSessionRepository.ts
 * Objetivo: persiste o status de caixa no IndexedDB para acesso offline.
 *           Substitui o localStorage (horus-pdv-cash-status) usado anteriormente.
 */
import { db, type CashSessionRecord } from "../dexie";
import type { CashRegisterStatusDto } from "@/services/api/cashRegisterService";
import { getCachedDeviceId } from "../deviceId";

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
