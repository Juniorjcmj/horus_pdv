/**
 * Arquivo: src/hooks/useOutboxStatus.ts
 * Objetivo: hook React que expõe o estado completo da fila do Outbox (IndexedDB),
 *           incluindo contagem de pendências, contagem de falhas críticas (MAX_RETRIES)
 *           e métodos de intervenção e re-tentativa manual para o operador.
 */
import { useEffect, useState, useCallback } from "react";
import { syncEngine } from "@/infrastructure/synchronization/SyncEngine";
import {
  getFailedEvents,
  getOldestUnsyncedAt,
  retryFailedEvent,
  retryAllFailed,
} from "@/infrastructure/database/repositories/OutboxRepository";
import type { OutboxEvent } from "@/shared/types/sync";

export type OutboxStatusState = {
  pendingCount: number;
  failedCount: number;
  /** Data (ISO) da venda/evento não sincronizado mais antigo; null se a fila está limpa. */
  oldestUnsyncedAt: string | null;
  syncNow: () => Promise<void>;
  retryAll: () => Promise<number>;
  retryEvent: (id: string) => Promise<void>;
  listFailed: () => Promise<OutboxEvent[]>;
};

export function useOutboxStatus(): OutboxStatusState {
  const [counts, setCounts] = useState<{ pendingCount: number; failedCount: number }>({
    pendingCount: 0,
    failedCount: 0,
  });
  const [oldestUnsyncedAt, setOldestUnsyncedAt] = useState<string | null>(null);

  useEffect(() => {
    return syncEngine.subscribe((pendingCount, failedCount) => {
      setCounts({ pendingCount, failedCount });
      void getOldestUnsyncedAt()
        .then(setOldestUnsyncedAt)
        .catch(() => setOldestUnsyncedAt(null));
    });
  }, []);

  const syncNow = useCallback(async () => {
    await syncEngine.syncNow();
  }, []);

  const retryAll = useCallback(async () => {
    const count = await retryAllFailed();
    await syncEngine.syncNow();
    return count;
  }, []);

  const retryEvent = useCallback(async (id: string) => {
    await retryFailedEvent(id);
    await syncEngine.syncNow();
  }, []);

  const listFailed = useCallback(async () => {
    return getFailedEvents();
  }, []);

  return {
    pendingCount: counts.pendingCount,
    failedCount: counts.failedCount,
    oldestUnsyncedAt,
    syncNow,
    retryAll,
    retryEvent,
    listFailed,
  };
}
