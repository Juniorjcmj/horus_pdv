/**
 * Arquivo: src/hooks/useConnectivity.ts
 * Objetivo: hook React que expõe o status de conexão do ConnectivityService.
 */
import { useEffect, useState } from "react";
import { connectivityService } from "@/infrastructure/synchronization/ConnectivityService";
import type { ConnectionStatus } from "@/shared/types/sync";

export function useConnectivity(): ConnectionStatus {
  const [status, setStatus] = useState<ConnectionStatus>(connectivityService.status);

  useEffect(() => {
    return connectivityService.subscribe(setStatus);
  }, []);

  return status;
}
