/**
 * Arquivo: src/infrastructure/database/repositories/CustomerRepository.ts
 * Objetivo: acesso à tabela `customers` do IndexedDB via Dexie.
 */
import { db, type LocalCustomerRecord } from "../dexie";

export const customerRepository = {
  async getAll(): Promise<LocalCustomerRecord[]> {
    return db.customers.toArray();
  },

  async getById(id: string): Promise<LocalCustomerRecord | undefined> {
    return db.customers.get(id);
  },

  /** Busca por CPF/CNPJ exato (somente dígitos). */
  async findByDocument(digits: string): Promise<LocalCustomerRecord | undefined> {
    const all = await db.customers.toArray();
    return all.find((c) => c.cpfCnpj.replace(/\D/g, "") === digits);
  },

  /** Busca textual por nome ou documento (client-side). */
  async search(term: string): Promise<LocalCustomerRecord[]> {
    const normalized = term.trim().toLowerCase();
    if (!normalized) return this.getAll();
    const all = await db.customers.toArray();
    return all.filter(
      (c) =>
        c.name.toLowerCase().includes(normalized) ||
        c.cpfCnpj.includes(normalized),
    );
  },

  async upsert(record: LocalCustomerRecord): Promise<void> {
    await db.customers.put(record);
  },

  async bulkUpsert(records: LocalCustomerRecord[]): Promise<void> {
    await db.customers.bulkPut(records);
  },

  async count(): Promise<number> {
    return db.customers.count();
  },

  async clear(): Promise<void> {
    await db.customers.clear();
  },
};
