/**
 * Arquivo: src/hooks/useSyncStatus.ts
 * Objetivo: hook que expõe informações de sincronização para a UI
 *           (último sync, logs recentes, checkpoint).
 */
import { useCallback, useEffect, useState } from "react";
import { syncCoordinator } from "@/infrastructure/synchronization/SyncCoordinator";
import type { SyncCheckpoint, SyncLog } from "@/shared/types/sync";

type SyncStatusInfo = {
  checkpoint: SyncCheckpoint | null;
  recentLogs: SyncLog[];
  loading: boolean;
  refresh: () => Promise<void>;
};

export function useSyncStatus(): SyncStatusInfo {
  const [checkpoint, setCheckpoint] = useState<SyncCheckpoint | null>(null);
  const [recentLogs, setRecentLogs] = useState<SyncLog[]>([]);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const [cp, logs] = await Promise.all([
        syncCoordinator.getCheckpoint(),
        syncCoordinator.getRecentLogs(10),
      ]);
      setCheckpoint(cp ?? null);
      setRecentLogs(logs);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  return { checkpoint, recentLogs, loading, refresh };
}
