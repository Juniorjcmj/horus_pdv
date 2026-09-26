/**
 * Arquivo: src/hooks/usePendingSalesCount.ts
 * Objetivo: hook React que retorna a contagem de vendas offline pendentes no outbox (IndexedDB).
 */
import { useEffect, useState } from "react";
import { syncEngine } from "@/infrastructure/synchronization/SyncEngine";

export function usePendingSalesCount(): number {
  const [count, setCount] = useState(0);

  useEffect(() => {
    return syncEngine.subscribe(setCount);
  }, []);

  return count;
}
