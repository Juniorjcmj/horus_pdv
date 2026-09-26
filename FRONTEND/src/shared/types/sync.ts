/**
 * Arquivo: src/shared/types/sync.ts
 * Objetivo: tipos compartilhados para sincronização offline-first.
 */

export type ConnectionStatus = "ONLINE" | "OFFLINE" | "API_UNAVAILABLE" | "SYNCING";

export type OutboxStatus = "PENDING" | "PROCESSING" | "PROCESSED" | "FAILED";

export type OutboxEvent = {
  id: string;
  deviceId: string;
  tenantId: string;
  storeId: string;
  eventType: string;
  aggregateType: string;
  aggregateId: string;
  payload: string;
  sequence: number;
  occurredAt: string;
  createdAt: string;
  status: OutboxStatus;
  retryCount: number;
  lastAttemptAt: string | null;
  lastError: string | null;
};

export type SyncCheckpoint = {
  deviceId: string;
  lastUploadedSequence: number;
  lastDownloadedSequence: number;
  lastSuccessfulSyncAt: string | null;
};

export type SyncLog = {
  id: string;
  timestamp: string;
  direction: "push" | "pull";
  status: "success" | "error";
  eventsCount: number;
  durationMs: number;
  error: string | null;
};
