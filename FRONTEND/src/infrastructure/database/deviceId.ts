/**
 * Arquivo: src/infrastructure/database/deviceId.ts
 * Objetivo: gera um UUID na primeira execução do PDV e persiste no IndexedDB.
 *           O mesmo deviceId é retornado em todas as sessões subsequentes.
 */
import { db } from "./dexie";

let cachedDeviceId: string | null = null;

export async function getOrCreateDeviceId(): Promise<string> {
  if (cachedDeviceId) return cachedDeviceId;

  // Buscar primeiro registro na tabela devices
  const existing = await db.devices.toCollection().first();
  if (existing) {
    cachedDeviceId = existing.id;
    return existing.id;
  }

  // Primeira execução: gerar UUID e persistir
  const newId = crypto.randomUUID();
  await db.devices.put({ id: newId, createdAt: new Date().toISOString() });
  cachedDeviceId = newId;
  return newId;
}

/**
 * Retorna o deviceId já inicializado (null se ainda não carregou).
 */
export function getCachedDeviceId(): string | null {
  return cachedDeviceId;
}
