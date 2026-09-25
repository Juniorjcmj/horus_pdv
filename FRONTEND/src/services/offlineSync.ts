/**
 * Arquivo: src/services/offlineSync.ts
 * Objetivo: sincroniza vendas offline pendentes com a API quando a conexão é restabelecida.
 */
import { salesHistoryService } from "./api/salesHistoryService";
import { getPendingSales, removePendingSale } from "./offlineStore";

const SYNC_INTERVAL_MS = 30_000;

let intervalId: ReturnType<typeof setInterval> | null = null;
let syncing = false;

async function syncPendingSales(): Promise<void> {
  if (syncing) return;
  const pending = getPendingSales();
  if (pending.length === 0) return;

  syncing = true;
  try {
    for (const sale of pending) {
      try {
        await salesHistoryService.register(sale.payload);
        removePendingSale(sale.id);
        window.dispatchEvent(
          new CustomEvent("offline-sync-success", { detail: { localSaleNumber: sale.localSaleNumber } }),
        );
      } catch {
        // API ainda indisponível — mantém na fila e tenta na próxima rodada
        break;
      }
    }
  } finally {
    syncing = false;
  }
}

export function startOfflineSync(): () => void {
  // Tenta sincronizar imediatamente ao iniciar
  void syncPendingSales();

  // Polling periódico
  intervalId = setInterval(() => void syncPendingSales(), SYNC_INTERVAL_MS);

  // Sincroniza imediatamente ao reconectar
  const onOnline = () => void syncPendingSales();
  window.addEventListener("online", onOnline);

  // Retorna cleanup function
  return () => {
    if (intervalId) {
      clearInterval(intervalId);
      intervalId = null;
    }
    window.removeEventListener("online", onOnline);
  };
}
