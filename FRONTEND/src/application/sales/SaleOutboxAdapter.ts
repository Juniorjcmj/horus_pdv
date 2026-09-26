/**
 * Arquivo: src/application/sales/SaleOutboxAdapter.ts
 * Objetivo: enfileira vendas offline no outbox (IndexedDB) em vez do localStorage.
 */
import { enqueueEvent } from "@/infrastructure/database/repositories/OutboxRepository";
import type { RegisterSalePayload } from "@/services/api/salesHistoryService";

/**
 * Enfileira uma venda no outbox para sincronização posterior.
 * Retorna o ID local da venda (usado como saleNumber offline).
 */
export async function queueSaleToOutbox(payload: RegisterSalePayload): Promise<string> {
  const localId = `OFFLINE-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;

  await enqueueEvent({
    eventType: "SALE_CREATED",
    aggregateType: "Sale",
    aggregateId: localId,
    payload,
  });

  return localId;
}
