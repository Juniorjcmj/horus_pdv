/**
 * Arquivo: src/infrastructure/database/repositories/OutboxRepository.ts
 * Objetivo: acesso à tabela `outbox` do IndexedDB — enfileira eventos para sincronização,
 *           marca como processados e fornece contadores para a UI.
 *
 * Empresas: cada pendência guarda a empresa (tenantId) do usuário logado ao ser criada, e todas as
 * leituras daqui só enxergam as da empresa logada. Assim, no mesmo computador usado por duas empresas,
 * as vendas offline de uma nunca são enviadas com o login da outra — ficam guardadas até alguém da
 * empresa dona entrar.
 */
import { db } from "../dexie";
import type { OutboxEvent, OutboxStatus } from "@/shared/types/sync";
import { getStoredAuthUser } from "@/utils/authStorage";
import { getCachedDeviceId } from "../deviceId";

/** Empresa do usuário logado ("" sem login). */
export function currentTenantId(): string {
  try {
    return getStoredAuthUser()?.companyId?.trim() ?? "";
  } catch {
    return "";
  }
}

/**
 * A pendência é da empresa logada? As sem empresa (antigas) valem para quem estiver logado até serem
 * adotadas (adoptUntaggedEvents) — mesmo comportamento de antes desta separação.
 */
export function isCurrentTenant(event: Pick<OutboxEvent, "tenantId">, tenant = currentTenantId()): boolean {
  return !event.tenantId || event.tenantId === tenant;
}

/**
 * Pendências sem empresa (gravadas antes desta separação, ou sem login) passam a ser da empresa logada.
 * Retorna quantas foram adotadas.
 */
export async function adoptUntaggedEvents(tenant = currentTenantId()): Promise<number> {
  if (!tenant) return 0;
  return db.outbox.filter((event) => !event.tenantId).modify({ tenantId: tenant });
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
  const id = params.id || (typeof crypto !== "undefined" && crypto.randomUUID ? crypto.randomUUID() : `evt-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`);
  const now = new Date().toISOString();
  const eventOccurredAt = params.occurredAt || now;

  const result = await db.transaction("rw", db.outbox, async () => {
    const last = await db.outbox.orderBy("sequence").last();
    const sequence = (last ? last.sequence : 0) + 1;

    const event: OutboxEvent = {
      id,
      deviceId: getCachedDeviceId() || "unknown",
      tenantId: params.tenantId || currentTenantId(),
      storeId: params.storeId || "",
      eventType: params.eventType,
      aggregateType: params.aggregateType,
      aggregateId: params.aggregateId,
      clientSaleId: params.clientSaleId,
      payloadHash: params.payloadHash,
      payload: JSON.stringify(params.payload),
      sequence,
      occurredAt: eventOccurredAt,
      createdAt: now,
      status: "PENDING" as OutboxStatus,
      retryCount: 0,
      lastAttemptAt: null,
      lastError: null,
    };

    await db.outbox.put(event);
    return id;
  });
  // Avisa quem acompanha a fila (ex.: backup automático das pendências). Disparado após o enqueue;
  // se o chamador ainda está numa transação externa, o backup roda com debounce depois do commit.
  if (typeof window !== "undefined") {
    window.dispatchEvent(new CustomEvent(OUTBOX_CHANGED_EVENT));
  }
  return result;
}

/** Evento de janela disparado sempre que um evento novo entra no outbox. */
export const OUTBOX_CHANGED_EVENT = "horus-outbox-changed";

/**
 * Eventos a enviar à nuvem, por sequência: PENDING e FORWARDED (já no Gateway da loja, mas a nuvem ainda
 * não confirmou — reenviar com o mesmo EventId é replay idempotente).
 */
export async function getPendingEvents(): Promise<OutboxEvent[]> {
  const tenant = currentTenantId();
  return db.outbox
    .where("status")
    .anyOf(["PENDING", "FORWARDED"])
    .filter((event) => isCurrentTenant(event, tenant))
    .sortBy("sequence");
}

/** Eventos que ainda podem ser entregues ao Gateway da loja (só PENDING), por sequência. */
export async function getForwardableEvents(): Promise<OutboxEvent[]> {
  const tenant = currentTenantId();
  return db.outbox
    .where("status")
    .equals("PENDING")
    .filter((event) => isCurrentTenant(event, tenant))
    .sortBy("sequence");
}

/** Marca o evento como entregue ao Gateway da loja (guardado em disco lá; a nuvem ainda não confirmou). */
export async function markForwarded(id: string): Promise<void> {
  await db.outbox.update(id, {
    status: "FORWARDED" as OutboxStatus,
    lastError: null,
    lastAttemptAt: new Date().toISOString(),
  });
}

/** Status que ainda NÃO chegaram à nuvem (fiado/estoque/fechamento offline precisam contá-los). */
export const NOT_IN_CLOUD_STATUSES: OutboxStatus[] = ["PENDING", "PROCESSING", "FORWARDED"];

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
  const tenant = currentTenantId();
  return db.outbox
    .where("status")
    .anyOf(["PENDING", "PROCESSING"])
    .filter((event) => isCurrentTenant(event, tenant))
    .count();
}

/** Data (ISO) do evento ainda não sincronizado mais antigo (PENDING, PROCESSING ou FAILED); null se a fila está limpa. */
export async function getOldestUnsyncedAt(): Promise<string | null> {
  const tenant = currentTenantId();
  const events = await db.outbox
    .where("status")
    .anyOf(["PENDING", "PROCESSING", "FAILED"])
    .filter((event) => isCurrentTenant(event, tenant))
    .toArray();
  let oldest: string | null = null;
  for (const evt of events) {
    const at = evt.occurredAt || evt.createdAt;
    if (at && (!oldest || Date.parse(at) < Date.parse(oldest))) oldest = at;
  }
  return oldest;
}

/** Conta eventos com falha crítica (status FAILED após esgotar retries). */
export async function getFailedCount(): Promise<number> {
  const tenant = currentTenantId();
  return db.outbox
    .where("status")
    .equals("FAILED")
    .filter((event) => isCurrentTenant(event, tenant))
    .count();
}

/** Retorna lista de eventos com falha crítica para auditoria e intervenção manual. */
export async function getFailedEvents(): Promise<OutboxEvent[]> {
  const tenant = currentTenantId();
  return db.outbox
    .where("status")
    .equals("FAILED")
    .filter((event) => isCurrentTenant(event, tenant))
    .sortBy("sequence");
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
  const tenant = currentTenantId();
  const failed = await db.outbox
    .where("status")
    .equals("FAILED")
    .filter((event) => isCurrentTenant(event, tenant))
    .toArray();
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
  const tenant = currentTenantId();
  const firstUnprocessed = await db.outbox
    .where("status")
    .anyOf(["PENDING", "PROCESSING", "FAILED", "FORWARDED"])
    .filter((event) => isCurrentTenant(event, tenant))
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
