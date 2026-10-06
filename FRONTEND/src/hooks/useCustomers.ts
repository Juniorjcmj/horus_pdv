/**
 * Arquivo: src/hooks/useCustomers.ts
 * Objetivo: hook offline-first para lista de clientes.
 *           Online: sync da API → IndexedDB → state.
 *           Offline: IndexedDB → state.
 */
import { useCallback, useEffect, useState } from "react";
import type { CustomerDto } from "@/services/api/customerService";
import {
  syncCustomersFromApi,
  loadCustomersLocal,
  localToDto,
} from "@/application/customers/CustomerSyncAdapter";
import { connectivityService } from "@/infrastructure/synchronization/ConnectivityService";

type UseCustomersReturn = {
  customers: CustomerDto[];
  loading: boolean;
  reload: (forceOnline?: boolean) => Promise<number>;
  /** Relê só o IndexedDB (sem download do servidor). */
  refreshLocal: () => Promise<number>;
};

export function useCustomers(): UseCustomersReturn {
  const [customers, setCustomers] = useState<CustomerDto[]>([]);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async (forceOnline = false): Promise<number> => {
    setLoading(true);
    try {
      const isOnline = forceOnline || connectivityService.status === "ONLINE" || navigator.onLine;
      if (isOnline) {
        const records = await syncCustomersFromApi();
        setCustomers(records.map(localToDto));
        return records.length;
      } else {
        const records = await loadCustomersLocal();
        setCustomers(records.map(localToDto));
        return records.length;
      }
    } catch {
      // Falha na API — tenta carregar do IndexedDB
      try {
        const records = await loadCustomersLocal();
        setCustomers(records.map(localToDto));
        return records.length;
      } catch {
        // mantém lista vazia
        return 0;
      }
    } finally {
      setLoading(false);
    }
  }, []);

  /** Relê só o IndexedDB (sem baixar a lista do servidor). */
  const refreshLocal = useCallback(async (): Promise<number> => {
    try {
      const records = await loadCustomersLocal();
      setCustomers(records.map(localToDto));
      return records.length;
    } catch {
      return 0;
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    // Saldo do fiado mudou no IndexedDB: basta reler o local. Antes chamava load(), que com internet
    // baixava a lista INTEIRA de clientes do servidor a cada venda fiado/recebimento.
    const handleUpdate = () => {
      void refreshLocal();
    };
    window.addEventListener("customer-balance-updated", handleUpdate);
    return () => window.removeEventListener("customer-balance-updated", handleUpdate);
  }, [refreshLocal]);

  return { customers, loading, reload: load, refreshLocal };
}
