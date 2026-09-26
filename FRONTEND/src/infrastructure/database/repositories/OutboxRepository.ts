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

/** Cria um novo evento no outbox. Retorna o ID gerado (EventId). */
export async function enqueueEvent(params: {
  id?: string;
  eventType: string;
  aggregateType: string;
  aggregateId: string;
  clientSaleId?: string;
  payloadHash?: string;
  payload: unknown;
  occurredAt?: string;
  tenantId?: string;
  storeId?: string;
}): Promise<string> {
  await ensureSequence();
  sequenceCounter += 1;

  const id = params.id || (typeof crypto !== "undefined" && crypto.randomUUID ? crypto.randomUUID() : `evt-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`);
  const now = new Date().toISOString();
  const eventOccurredAt = params.occurredAt || now;

  const event: OutboxEvent = {
    id,
    deviceId: getCachedDeviceId() || "unknown",
    tenantId: params.tenantId || "",
    storeId: params.storeId || "",
    eventType: params.eventType,
    aggregateType: params.aggregateType,
    aggregateId: params.aggregateId,
    clientSaleId: params.clientSaleId,
    payloadHash: params.payloadHash,
    payload: JSON.stringify(params.payload),
    sequence: sequenceCounter,
    occurredAt: eventOccurredAt,
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

export const MAX_OUTBOX_RETRIES = 10;

/** Marca um evento como falho, incrementa retryCount e transiciona para FAILED ao atingir o limite. */
export async function markFailed(id: string, error: string, maxRetries = MAX_OUTBOX_RETRIES): Promise<void> {
  const event = await db.outbox.get(id);
  if (!event) return;
  const nextRetryCount = event.retryCount + 1;
  const nextStatus: OutboxStatus = nextRetryCount >= maxRetries ? "FAILED" : "PENDING";

  await db.outbox.update(id, {
    status: nextStatus,
    retryCount: nextRetryCount,
    lastError: error,
    lastAttemptAt: new Date().toISOString(),
  });
}

/** Conta eventos pendentes de sincronização (status PENDING ou PROCESSING). */
export async function getPendingCount(): Promise<number> {
  return db.outbox.where("status").anyOf(["PENDING", "PROCESSING"]).count();
}

/** Conta eventos com falha crítica (status FAILED após esgotar retries). */
export async function getFailedCount(): Promise<number> {
  return db.outbox.where("status").equals("FAILED").count();
}

/** Retorna lista de eventos com falha crítica para auditoria e intervenção manual. */
export async function getFailedEvents(): Promise<OutboxEvent[]> {
  return db.outbox.where("status").equals("FAILED").sortBy("sequence");
}

/** Re-enfileira um evento com falha específica para nova tentativa de sincronização. */
export async function retryFailedEvent(id: string): Promise<void> {
  await db.outbox.update(id, {
    status: "PENDING" as OutboxStatus,
    retryCount: 0,
    lastError: null,
    lastAttemptAt: null,
  });
}

/** Re-enfileira todos os eventos com falha para nova tentativa em lote. */
export async function retryAllFailed(): Promise<number> {
  const failed = await db.outbox.where("status").equals("FAILED").toArray();
  for (const event of failed) {
    await db.outbox.update(event.id, {
      status: "PENDING" as OutboxStatus,
      retryCount: 0,
      lastError: null,
      lastAttemptAt: null,
    });
  }
  return failed.length;
}

/**
 * Calcula a sequência segura contígua de upload já confirmada pelo servidor.
 * Corresponde à maior sequência tal que todos os eventos até ela foram PROCESSED sem lacunas.
 */
export async function getContiguousProcessedSequence(): Promise<number> {
  const firstUnprocessed = await db.outbox
    .where("status")
    .anyOf(["PENDING", "PROCESSING", "FAILED"])
    .sortBy("sequence");

  if (firstUnprocessed.length > 0) {
    return Math.max(0, firstUnprocessed[0].sequence - 1);
  }

  const lastProcessed = await db.outbox.orderBy("sequence").last();
  return lastProcessed ? lastProcessed.sequence : 0;
}

/**
 * Recupera eventos órfãos em PROCESSING (ex: crash do browser durante sync).
 * Reseta para PENDING eventos que estão em PROCESSING há mais de `staleMs` milissegundos.
 */
export async function recoverStaleProcessing(staleMs: number = 120_000): Promise<number> {
  const cutoff = new Date(Date.now() - staleMs).toISOString();
  const stale = await db.outbox
    .where("status")
    .equals("PROCESSING" as OutboxStatus)
    .filter((e) => !e.lastAttemptAt || e.lastAttemptAt < cutoff)
    .toArray();

  for (const event of stale) {
    await db.outbox.update(event.id, {
      status: "PENDING" as OutboxStatus,
    });
  }
  return stale.length;
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
