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
  reload: () => Promise<void>;
};

export function useProducts(): UseProductsReturn {
  const [products, setProducts] = useState<Product[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const isOnline = connectivityService.status === "ONLINE";

      if (isOnline) {
        // Online: sincroniza da API → IndexedDB → retorna
        const records = await syncProductsFromApi();
        setProducts(records.map(toProduct));
      } else {
        // Offline: carrega do IndexedDB (com migração do localStorage se necessário)
        const records = await loadProductsLocal();
        if (records.length > 0) {
          setProducts(records.map(toProduct));
        } else {
          setError("Sem conexão e sem cache de produtos disponível.");
        }
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
      } catch {
        setError("Erro ao carregar produtos do cache local.");
      }
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  return { products, loading, error, reload: load };
}
