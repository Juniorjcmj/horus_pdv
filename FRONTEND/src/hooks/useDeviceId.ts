/**
 * Arquivo: src/hooks/useDeviceId.ts
 * Objetivo: hook React que retorna o deviceId persistente do IndexedDB.
 */
import { useEffect, useState } from "react";
import { getOrCreateDeviceId } from "@/infrastructure/database/deviceId";

export function useDeviceId(): string | null {
  const [deviceId, setDeviceId] = useState<string | null>(null);

  useEffect(() => {
    void getOrCreateDeviceId().then(setDeviceId);
  }, []);

  return deviceId;
}
