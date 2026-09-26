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
  reload: () => Promise<void>;
};

export function useCustomers(): UseCustomersReturn {
  const [customers, setCustomers] = useState<CustomerDto[]>([]);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const isOnline = connectivityService.status === "ONLINE";
      if (isOnline) {
        const records = await syncCustomersFromApi();
        setCustomers(records.map(localToDto));
      } else {
        const records = await loadCustomersLocal();
        setCustomers(records.map(localToDto));
      }
    } catch {
      // Falha na API — tenta carregar do IndexedDB
      try {
        const records = await loadCustomersLocal();
        setCustomers(records.map(localToDto));
      } catch {
        // mantém lista vazia
      }
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  return { customers, loading, reload: load };
}
