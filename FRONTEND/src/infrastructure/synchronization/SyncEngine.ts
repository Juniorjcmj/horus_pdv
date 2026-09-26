/**
 * Arquivo: src/infrastructure/synchronization/SyncEngine.ts
 * Objetivo: processa eventos do outbox (IndexedDB) e sincroniza com a API.
 *           Substitui o offlineSync.ts (localStorage) por uma abordagem baseada em outbox.
 *           Também migra vendas pendentes do localStorage na primeira execução.
 */
import { salesHistoryService, type RegisterSalePayload } from "@/services/api/salesHistoryService";
import {
  getPendingEvents,
  markProcessing,
  markProcessed,
  markFailed,
  getPendingCount,
  purgeProcessed,
} from "@/infrastructure/database/repositories/OutboxRepository";
import { connectivityService } from "./ConnectivityService";
import { db } from "@/infrastructure/database/dexie";
import type { OutboxStatus } from "@/shared/types/sync";
import { getCachedDeviceId } from "@/infrastructure/database/deviceId";

const SYNC_INTERVAL_MS = 30_000;
const MAX_RETRIES = 10;
const LS_OFFLINE_SALES_KEY = "horus-pdv-offline-sales";

type PendingSaleLegacy = {
  id: string;
  queuedAt: string;
  payload: RegisterSalePayload;
  localSaleNumber: string;
};

type SyncListener = (pendingCount: number) => void;

class SyncEngine {
  private intervalId: ReturnType<typeof setInterval> | null = null;
  private syncing = false;
  private listeners: Set<SyncListener> = new Set();

  /** Inicia o engine — migra legado, agenda polling, escuta reconexão. */
  start(): () => void {
    // Migra vendas legadas do localStorage para outbox (IndexedDB)
    void this.migrateLegacySales();

    // Tenta sincronizar imediatamente
    void this.processOutbox();

    // Polling periódico
    this.intervalId = setInterval(() => void this.processOutbox(), SYNC_INTERVAL_MS);

    // Sincroniza ao reconectar
    const onOnline = () => void this.processOutbox();
    window.addEventListener("online", onOnline);

    // Limpeza periódica de eventos antigos processados (1x por sessão)
    void purgeProcessed(7);

    return () => {
      if (this.intervalId) {
        clearInterval(this.intervalId);
        this.intervalId = null;
      }
      window.removeEventListener("online", onOnline);
    };
  }

  /** Inscreve listener para atualizações de contagem pendente. */
  subscribe(listener: SyncListener): () => void {
    this.listeners.add(listener);
    // Notifica com o valor atual
    void getPendingCount().then((c) => listener(c));
    return () => {
      this.listeners.delete(listener);
    };
  }

  private async notifyListeners(): Promise<void> {
    const count = await getPendingCount();
    for (const listener of this.listeners) {
      listener(count);
    }
  }

  /** Processa todos os eventos pendentes no outbox. */
  private async processOutbox(): Promise<void> {
    if (this.syncing) return;
    if (connectivityService.status !== "ONLINE") return;

    this.syncing = true;
    try {
      const pending = await getPendingEvents();
      if (pending.length === 0) return;

      for (const event of pending) {
        if (event.retryCount >= MAX_RETRIES) continue;

        await markProcessing(event.id);

        try {
          await this.dispatchEvent(event.eventType, event.payload);
          await markProcessed(event.id);

          // Dispara evento customizado para a UI (compatibilidade com SalesStartPage)
          window.dispatchEvent(
            new CustomEvent("offline-sync-success", {
              detail: { localSaleNumber: event.aggregateId },
            }),
          );
        } catch (err) {
          const msg = err instanceof Error ? err.message : "Erro desconhecido";
          await markFailed(event.id, msg);
          // Se falhou, para de processar — a API provavelmente está fora
          break;
        }
      }
    } finally {
      this.syncing = false;
      await this.notifyListeners();
    }
  }

  /** Despacha um evento para a API correta baseado no eventType. */
  private async dispatchEvent(eventType: string, payloadJson: string): Promise<void> {
    switch (eventType) {
      case "SALE_CREATED": {
        const payload = JSON.parse(payloadJson) as RegisterSalePayload;
        await salesHistoryService.register(payload);
        break;
      }
      default:
        throw new Error(`Tipo de evento desconhecido: ${eventType}`);
    }
  }

  /** Migra vendas pendentes do localStorage (offlineStore.ts) para o outbox (IndexedDB). */
  private async migrateLegacySales(): Promise<void> {
    try {
      const raw = window.localStorage.getItem(LS_OFFLINE_SALES_KEY);
      if (!raw) return;

      const sales = JSON.parse(raw) as PendingSaleLegacy[];
      if (!sales || sales.length === 0) return;

      const deviceId = getCachedDeviceId() || "unknown";
      let seq = 0;
      const last = await db.outbox.orderBy("sequence").last();
      seq = last ? last.sequence : 0;

      for (const sale of sales) {
        seq += 1;
        await db.outbox.put({
          id: sale.id,
          deviceId,
          tenantId: "",
          storeId: "",
          eventType: "SALE_CREATED",
          aggregateType: "Sale",
          aggregateId: sale.localSaleNumber,
          payload: JSON.stringify(sale.payload),
          sequence: seq,
          occurredAt: sale.queuedAt,
          createdAt: sale.queuedAt,
          status: "PENDING" as OutboxStatus,
          retryCount: 0,
          lastAttemptAt: null,
          lastError: null,
        });
      }

      // Remove do localStorage após migração
      window.localStorage.removeItem(LS_OFFLINE_SALES_KEY);
      await this.notifyListeners();
    } catch {
      // Falha silenciosa — mantém no localStorage para próxima tentativa
    }
  }
}

export const syncEngine = new SyncEngine();
