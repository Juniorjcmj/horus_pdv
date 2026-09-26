/**
 * Arquivo: src/infrastructure/synchronization/SyncEngine.ts
 * Objetivo: processa eventos do outbox (IndexedDB) e sincroniza com a API.
 *           Integra com SyncCoordinator para pull sync orquestrado.
 *           Usa backoff exponencial para retries e dedup via processedEvents.
 */
import { salesHistoryService, type RegisterSalePayload } from "@/services/api/salesHistoryService";
import { cashRegisterService } from "@/services/api/cashRegisterService";
import {
  getPendingEvents,
  markProcessing,
  markProcessed,
  markFailed,
  getPendingCount,
  getFailedCount,
  purgeProcessed,
  recoverStaleProcessing,
} from "@/infrastructure/database/repositories/OutboxRepository";
import { connectivityService } from "./ConnectivityService";
import { syncCoordinator } from "./SyncCoordinator";
import { db } from "@/infrastructure/database/dexie";
import type { OutboxStatus } from "@/shared/types/sync";
import { getCachedDeviceId } from "@/infrastructure/database/deviceId";

const SYNC_INTERVAL_MS = 30_000;
const MAX_RETRIES = 10;
const BASE_BACKOFF_MS = 5_000;
const LS_OFFLINE_SALES_KEY = "horus-pdv-offline-sales";

type PendingSaleLegacy = {
  id: string;
  queuedAt: string;
  payload: RegisterSalePayload;
  localSaleNumber: string;
};

type SyncListener = (pendingCount: number, failedCount: number) => void;

/** Calcula delay de backoff exponencial com jitter. */
function backoffDelay(retryCount: number): number {
  const delay = BASE_BACKOFF_MS * Math.pow(2, Math.min(retryCount, 6)); // cap at ~5min
  const jitter = delay * 0.3 * Math.random();
  return delay + jitter;
}

class SyncEngine {
  private intervalId: ReturnType<typeof setInterval> | null = null;
  private syncing = false;
  private listeners: Set<SyncListener> = new Set();

  /** Inicia o engine — migra legado, agenda polling, escuta reconexão. */
  start(): () => void {
    // Migra vendas legadas do localStorage para outbox (IndexedDB)
    void this.migrateLegacySales();

    // Recupera eventos órfãos em PROCESSING (crash anterior)
    void recoverStaleProcessing();

    // Tenta sincronizar imediatamente
    void this.runCycle();

    // Polling periódico
    this.intervalId = setInterval(() => void this.runCycle(), SYNC_INTERVAL_MS);

    // Sincroniza ao reconectar
    const onOnline = () => void this.runCycle();
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

  /** Inscreve listener para atualizações de contagem pendente e falhas. */
  subscribe(listener: SyncListener): () => void {
    this.listeners.add(listener);
    // Notifica imediatamente com os valores atuais
    void Promise.all([getPendingCount(), getFailedCount()]).then(([p, f]) => listener(p, f));
    return () => {
      this.listeners.delete(listener);
    };
  }

  private async notifyListeners(): Promise<void> {
    const [pendingCount, failedCount] = await Promise.all([
      getPendingCount(),
      getFailedCount(),
    ]);
    for (const listener of this.listeners) {
      listener(pendingCount, failedCount);
    }
  }

  /** Força a execução imediata de um ciclo de sincronização (push + pull). */
  async syncNow(): Promise<void> {
    await this.runCycle();
  }

  /** Ciclo completo: push (outbox) → pull (coordinator). */
  private async runCycle(): Promise<void> {
    await this.processOutbox();

    // Pull sync (produtos, clientes) — SyncCoordinator gerencia intervalo mínimo
    if (connectivityService.isOnline()) {
      void syncCoordinator.pullAll();
    }
  }

  /** Processa todos os eventos pendentes no outbox com lock cross-tab e backoff exponencial. */
  private async processOutbox(): Promise<void> {
    if (this.syncing) return;
    if (!connectivityService.isOnline()) return;

    // Garante que apenas uma aba processe o outbox por vez (lock cross-tab)
    if (typeof navigator !== "undefined" && navigator.locks) {
      try {
        await navigator.locks.request("horus-pdv-sync-engine", { ifAvailable: true }, async (lock) => {
          if (!lock) return; // Outra aba já está processando
          await this.doProcessOutbox();
        });
        return;
      } catch {
        // Fallback caso navigator.locks falhe no navegador
      }
    }

    await this.doProcessOutbox();
  }

  private async doProcessOutbox(): Promise<void> {
    this.syncing = true;
    const start = performance.now();
    let processedCount = 0;
    let pushError: string | null = null;

    try {
      // Recupera eventos órfãos em PROCESSING antes de buscar pendentes
      await recoverStaleProcessing();

      const pending = await getPendingEvents();
      if (pending.length === 0) return;

      const now = Date.now();

      for (const event of pending) {
        // Pula eventos que excederam max retries
        if (event.retryCount >= MAX_RETRIES) continue;

        // Backoff exponencial: pula se ainda não é hora de tentar novamente
        if (event.retryCount > 0 && event.lastAttemptAt) {
          const lastAttempt = new Date(event.lastAttemptAt).getTime();
          const nextRetryAt = lastAttempt + backoffDelay(event.retryCount);
          if (now < nextRetryAt) continue;
        }

        // Dedup: verifica se já foi processado (idempotência)
        const alreadyProcessed = await db.processedEvents.get(event.id);
        if (alreadyProcessed) {
          await markProcessed(event.id);
          continue;
        }

        await markProcessing(event.id);

        try {
          const result = await this.dispatchEvent(event.eventType, event.payload);

          // Registra na tabela de dedup
          await db.processedEvents.put({
            eventId: event.id,
            processedAt: new Date().toISOString(),
          });

          await markProcessed(event.id);
          processedCount++;

          // Dispara evento customizado para a UI
          window.dispatchEvent(
            new CustomEvent("offline-sync-success", {
              detail: {
                localSaleNumber: event.aggregateId,
                serverSaleNumber: result?.saleNumber,
                isReplay: result?.isReplay ?? false,
              },
            }),
          );
        } catch (err) {
          const msg = err instanceof Error ? err.message : "Erro desconhecido";
          pushError = msg;
          await markFailed(event.id, msg);
          // Para de processar — a API provavelmente está fora
          break;
        }
      }
    } finally {
      this.syncing = false;
      const durationMs = performance.now() - start;

      // Registra log de push se houve atividade
      if (processedCount > 0 || pushError) {
        void syncCoordinator.logPush(processedCount, durationMs, pushError);
      }

      // Atualiza o checkpoint contíguo seguro no IndexedDB
      await syncCoordinator.updateCheckpoint();
      await this.notifyListeners();
    }
  }

  /** Despacha um evento para a API correta baseado no eventType. */
  private async dispatchEvent(
    eventType: string,
    payloadJson: string,
  ): Promise<{ saleNumber?: string; isReplay?: boolean } | void> {
    switch (eventType) {
      case "SALE_CREATED": {
        const payload = JSON.parse(payloadJson) as RegisterSalePayload;
        const res = await salesHistoryService.register(payload);
        return res;
      }
      case "CASH_OPEN": {
        const payload = JSON.parse(payloadJson) as { openingAmount: string };
        try {
          await cashRegisterService.open(payload.openingAmount);
        } catch (err) {
          const msg = err instanceof Error ? err.message : String(err);
          // Se já existe caixa aberto no servidor, considera resolvido de forma idempotente
          if (msg.includes("Já existe um caixa aberto")) {
            return;
          }
          throw err;
        }
        return;
      }
      case "CASH_CLOSE": {
        const payload = JSON.parse(payloadJson) as {
          closingAmount: string;
          note?: string;
          differenceReason?: string;
        };
        try {
          await cashRegisterService.close(
            payload.closingAmount,
            payload.note,
            payload.differenceReason,
          );
        } catch (err) {
          const msg = err instanceof Error ? err.message : String(err);
          // Se já não existe caixa aberto no servidor, considera resolvido de forma idempotente
          if (msg.includes("Não existe caixa aberto")) {
            return;
          }
          throw err;
        }
        return;
      }
      case "CASH_MOVEMENT": {
        const payload = JSON.parse(payloadJson) as {
          tipo: "Reforco" | "Sangria";
          valor: string;
          motivo: string;
        };
        await cashRegisterService.registrarMovimento(payload.tipo, payload.valor, payload.motivo);
        return;
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
