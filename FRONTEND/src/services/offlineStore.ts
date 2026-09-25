/**
 * Arquivo: src/services/offlineStore.ts
 * Objetivo: persistência offline usando localStorage para cache de produtos e fila de vendas pendentes.
 */
import type { RegisterSalePayload } from "./api/salesHistoryService";

const PRODUCTS_CACHE_KEY = "horus-pdv-products-cache";
const OFFLINE_SALES_KEY = "horus-pdv-offline-sales";

// Tipo mínimo do produto no PDV (espelho do tipo Product em SalesStartPage)
export type CachedProduct = {
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

export type PendingSale = {
  id: string;
  queuedAt: string;
  payload: RegisterSalePayload;
  localSaleNumber: string;
};

// ── Cache de produtos ──────────────────────────────────────────────────

export function saveProductsCache(products: CachedProduct[]): void {
  try {
    window.localStorage.setItem(PRODUCTS_CACHE_KEY, JSON.stringify(products));
  } catch {
    // localStorage cheio — ignora silenciosamente
  }
}

export function loadProductsCache(): CachedProduct[] | null {
  try {
    const raw = window.localStorage.getItem(PRODUCTS_CACHE_KEY);
    if (!raw) return null;
    return JSON.parse(raw) as CachedProduct[];
  } catch {
    return null;
  }
}

// ── Fila de vendas offline ─────────────────────────────────────────────

function readQueue(): PendingSale[] {
  try {
    const raw = window.localStorage.getItem(OFFLINE_SALES_KEY);
    if (!raw) return [];
    return JSON.parse(raw) as PendingSale[];
  } catch {
    return [];
  }
}

function writeQueue(queue: PendingSale[]): void {
  try {
    window.localStorage.setItem(OFFLINE_SALES_KEY, JSON.stringify(queue));
  } catch {
    // localStorage cheio — não enfileira
  }
}

export function queueSale(payload: RegisterSalePayload): string {
  const id = `OFFLINE-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
  const entry: PendingSale = {
    id,
    queuedAt: new Date().toISOString(),
    payload,
    localSaleNumber: id,
  };
  const queue = readQueue();
  queue.push(entry);
  writeQueue(queue);
  return id;
}

export function getPendingSales(): PendingSale[] {
  return readQueue();
}

export function removePendingSale(id: string): void {
  const queue = readQueue().filter((s) => s.id !== id);
  writeQueue(queue);
}

export function getPendingSalesCount(): number {
  return readQueue().length;
}
