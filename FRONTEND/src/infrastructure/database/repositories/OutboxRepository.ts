/**
 * Arquivo: src/infrastructure/database/repositories/OutboxRepository.ts
 * Objetivo: acesso à tabela `outbox` do IndexedDB — enfileira eventos para sincronização,
 *           marca como processados e fornece contadores para a UI.
 */
import { db } from "../dexie";
import type { OutboxEvent, OutboxStatus } from "@/shared/types/sync";
import { getCachedDeviceId } from "../deviceId";

let sequenceCounter = 0;

/** Inicializa o contador de sequência baseado no maior valor existente. */
async function ensureSequence(): Promise<void> {
  if (sequenceCounter > 0) return;
  const last = await db.outbox.orderBy("sequence").last();
  sequenceCounter = last ? last.sequence : 0;
}

/** Cria um novo evento no outbox. Retorna o ID gerado. */
export async function enqueueEvent(params: {
  eventType: string;
  aggregateType: string;
  aggregateId: string;
  payload: unknown;
  tenantId?: string;
  storeId?: string;
}): Promise<string> {
  await ensureSequence();
  sequenceCounter += 1;

  const id = `outbox-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const now = new Date().toISOString();

  const event: OutboxEvent = {
    id,
    deviceId: getCachedDeviceId() || "unknown",
    tenantId: params.tenantId || "",
    storeId: params.storeId || "",
    eventType: params.eventType,
    aggregateType: params.aggregateType,
    aggregateId: params.aggregateId,
    payload: JSON.stringify(params.payload),
    sequence: sequenceCounter,
    occurredAt: now,
    createdAt: now,
    status: "PENDING" as OutboxStatus,
    retryCount: 0,
    lastAttemptAt: null,
    lastError: null,
  };

  await db.outbox.put(event);
  return id;
}

/** Retorna todos os eventos pendentes, ordenados por sequência. */
export async function getPendingEvents(): Promise<OutboxEvent[]> {
  return db.outbox.where("status").equals("PENDING").sortBy("sequence");
}

/** Marca um evento como sendo processado (em trânsito). */
export async function markProcessing(id: string): Promise<void> {
  await db.outbox.update(id, {
    status: "PROCESSING" as OutboxStatus,
    lastAttemptAt: new Date().toISOString(),
  });
}

/** Marca um evento como processado com sucesso. */
export async function markProcessed(id: string): Promise<void> {
  await db.outbox.update(id, { status: "PROCESSED" as OutboxStatus });
}

/** Marca um evento como falho, incrementa retryCount. */
export async function markFailed(id: string, error: string): Promise<void> {
  const event = await db.outbox.get(id);
  if (!event) return;
  await db.outbox.update(id, {
    status: "PENDING" as OutboxStatus, // volta para PENDING para retry
    retryCount: event.retryCount + 1,
    lastError: error,
    lastAttemptAt: new Date().toISOString(),
  });
}

/** Conta eventos pendentes (para indicadores na UI). */
export async function getPendingCount(): Promise<number> {
  return db.outbox.where("status").anyOf(["PENDING", "PROCESSING"]).count();
}

/** Remove eventos processados com mais de N dias (limpeza). */
export async function purgeProcessed(olderThanDays: number = 7): Promise<number> {
  const cutoff = new Date();
  cutoff.setDate(cutoff.getDate() - olderThanDays);
  const cutoffStr = cutoff.toISOString();

  const old = await db.outbox
    .where("status")
    .equals("PROCESSED")
    .filter((e) => e.createdAt < cutoffStr)
    .toArray();

  const ids = old.map((e) => e.id);
  await db.outbox.bulkDelete(ids);
  return ids.length;
}
