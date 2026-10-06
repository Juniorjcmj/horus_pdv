/**
 * Arquivo: src/hooks/useProducts.ts
 * Objetivo: hook que carrega produtos do IndexedDB (offline-first) e sincroniza com a API quando online.
 *           Substitui o padrão anterior de carregar direto da API com fallback para localStorage.
 */
import { useCallback, useEffect, useState } from "react";
import { syncProductsFromApi, loadProductsLocal } from "@/application/products/ProductSyncAdapter";
import type { LocalProductRecord } from "@/infrastructure/database/dexie";
import { connectivityService } from "@/infrastructure/synchronization/ConnectivityService";

type Product = {
  id: string;
  name: string;
  code: string;
  stock: number;
  salePrice: number;
  imageUrl?: string;
  unit: string;
  marca?: string | null;
  categoriaId?: string | null;
  dataValidade?: string | null;
  controlaValidade?: boolean;
  diasAlertaValidade?: number;
  diasRestantes?: number | null;
};

/** Converte LocalProductRecord para o formato Product usado pelo PDV. */
function toProduct(r: LocalProductRecord): Product {
  // Calcula dias restantes de validade, se controlado
  let diasRestantes: number | null = null;
  if (r.controlaValidade && r.dataValidade) {
    const validade = new Date(r.dataValidade);
    const hoje = new Date();
    hoje.setHours(0, 0, 0, 0);
    diasRestantes = Math.ceil((validade.getTime() - hoje.getTime()) / (1000 * 60 * 60 * 24));
  }

  return {
    id: r.id,
    name: r.productName,
    code: r.productCode,
    stock: r.stock,
    salePrice: r.salePrice,
    imageUrl: r.imageUrl || undefined,
    unit: r.unit,
    marca: r.marca,
    categoriaId: r.categoryId,
    dataValidade: r.dataValidade,
    controlaValidade: r.controlaValidade,
    diasAlertaValidade: r.diasAlertaValidade,
    diasRestantes,
  };
}

type UseProductsReturn = {
  products: Product[];
  loading: boolean;
  error: string | null;
  reload: (forceOnline?: boolean) => Promise<number>;
  /** Relê só o IndexedDB (sem download do servidor). */
  refreshLocal: () => Promise<number>;
};

export function useProducts(): UseProductsReturn {
  const [products, setProducts] = useState<Product[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (forceOnline = false): Promise<number> => {
    setLoading(true);
    setError(null);
    try {
      const isOnline = forceOnline || connectivityService.status === "ONLINE" || navigator.onLine;

      if (isOnline) {
        // Online: sincroniza da API → IndexedDB → retorna
        const records = await syncProductsFromApi();
        setProducts(records.map(toProduct));
        return records.length;
      } else {
        // Offline: carrega do IndexedDB (com migração do localStorage se necessário)
        const records = await loadProductsLocal();
        if (records.length > 0) {
          setProducts(records.map(toProduct));
        } else {
          setError("Sem conexão e sem cache de produtos disponível.");
        }
        return records.length;
      }
    } catch {
      // Falha na API — tenta carregar do IndexedDB
      try {
        const records = await loadProductsLocal();
        if (records.length > 0) {
          setProducts(records.map(toProduct));
        } else {
          setError("Sem conexão e sem cache de produtos disponível.");
        }
        return records.length;
      } catch {
        setError("Erro ao carregar produtos do cache local.");
        return 0;
      }
    } finally {
      setLoading(false);
    }
  }, []);

  /**
   * Relê só o IndexedDB, sem baixar o catálogo do servidor. Usado depois da venda (o estoque já foi
   * baixado localmente) e depois do F10 (que acabou de sincronizar) — evita download completo repetido.
   */
  const refreshLocal = useCallback(async (): Promise<number> => {
    try {
      const records = await loadProductsLocal();
      setProducts(records.map(toProduct));
      return records.length;
    } catch {
      return 0;
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  return { products, loading, error, reload: load, refreshLocal };
}
