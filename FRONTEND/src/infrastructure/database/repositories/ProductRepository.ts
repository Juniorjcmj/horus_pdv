/**
 * Arquivo: src/infrastructure/database/repositories/ProductRepository.ts
 * Objetivo: acesso à tabela `products` do IndexedDB via Dexie.
 *           Fornece operações de leitura, upsert em lote e busca por código de barras/código.
 */
import { db, type LocalProductRecord } from "../dexie";

export const productRepository = {
  /** Retorna todos os produtos ativos do IndexedDB. */
  async getAll(): Promise<LocalProductRecord[]> {
    return db.products.toArray();
  },

  /** Busca um produto pelo ID. */
  async getById(id: string): Promise<LocalProductRecord | undefined> {
    return db.products.get(id);
  },

  /** Busca produto por código de barras (barcode) ou código interno (productCode). */
  async findByCode(code: string): Promise<LocalProductRecord | undefined> {
    const byBarcode = await db.products.where("barcode").equals(code).first();
    if (byBarcode) return byBarcode;
    return db.products.where("productCode").equals(code).first();
  },

  /** Busca textual por nome, código ou marca (client-side filter). */
  async search(term: string): Promise<LocalProductRecord[]> {
    const normalized = term.trim().toLowerCase();
    if (!normalized) return this.getAll();

    const all = await db.products.toArray();
    return all.filter(
      (p) =>
        p.productName.toLowerCase().includes(normalized) ||
        p.productCode.toLowerCase().includes(normalized) ||
        (p.barcode && p.barcode.toLowerCase().includes(normalized)) ||
        (p.marca && p.marca.toLowerCase().includes(normalized)),
    );
  },

  /** Upsert em lote — substitui registros existentes, insere novos. */
  async bulkUpsert(records: LocalProductRecord[]): Promise<void> {
    await db.products.bulkPut(records);
  },

  /** Total de produtos no IndexedDB. */
  async count(): Promise<number> {
    return db.products.count();
  },

  /** Remove todos os produtos (usado antes de full-sync). */
  async clear(): Promise<void> {
    await db.products.clear();
  },
};
