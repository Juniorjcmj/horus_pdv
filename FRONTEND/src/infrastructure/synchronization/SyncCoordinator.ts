/**
 * Arquivo: src/infrastructure/synchronization/SyncCoordinator.ts
 * Objetivo: orquestra sincronização bidirecional (pull + push) em sequência definida.
 *           Pull: produtos → clientes → status de caixa.
 *           Push: outbox (vendas etc.) — delegado ao SyncEngine.
 *           Registra checkpoints e logs de cada ciclo no IndexedDB.
 */
import { db } from "@/infrastructure/database/dexie";
import type { SyncCheckpoint, SyncLog } from "@/shared/types/sync";
import { getCachedDeviceId } from "@/infrastructure/database/deviceId";
import { syncProductsFromApi } from "@/application/products/ProductSyncAdapter";
import { syncCustomersFromApi } from "@/application/customers/CustomerSyncAdapter";
import { getContiguousProcessedSequence } from "../database/repositories/OutboxRepository";
import { connectivityService } from "./ConnectivityService";

/** Intervalo mínimo entre pull syncs completos (2 minutos). */
const MIN_PULL_INTERVAL_MS = 2 * 60 * 1000;

class SyncCoordinator {
  private lastPullAt = 0;
  private pulling = false;

  /**
   * Executa um ciclo de pull sync (API → IndexedDB) para todas as entidades.
   * Retorna true se executou, false se pulou (intervalo mínimo não atingido).
   */
  async pullAll(): Promise<boolean> {
    if (this.pulling) return false;
    if (!connectivityService.isOnline()) return false;

    const now = Date.now();
    if (now - this.lastPullAt < MIN_PULL_INTERVAL_MS) return false;

    this.pulling = true;
    const start = performance.now();
    let totalEvents = 0;
    let hasError = false;
    let errorMsg: string | null = null;

    try {
      connectivityService.setSyncing(true);

      // 1. Produtos
      try {
        const products = await syncProductsFromApi();
        totalEvents += products.length;
      } catch (err) {
        hasError = true;
        errorMsg = err instanceof Error ? err.message : "Erro ao sincronizar produtos";
      }

      // 2. Clientes
      try {
        const customers = await syncCustomersFromApi();
        totalEvents += customers.length;
      } catch (err) {
        if (!hasError) {
          hasError = true;
          errorMsg = err instanceof Error ? err.message : "Erro ao sincronizar clientes";
        }
      }

      // 3. Checkpoint
      await this.updateCheckpoint();

      this.lastPullAt = Date.now();
      return true;
    } finally {
      this.pulling = false;
      connectivityService.setSyncing(false);

      // Registra log do ciclo
      await this.writeLog("pull", hasError ? "error" : "success", totalEvents, performance.now() - start, errorMsg);
    }
  }

  /** Registra um log de push sync (chamado pelo SyncEngine após processar outbox). */
  async logPush(eventsCount: number, durationMs: number, error: string | null): Promise<void> {
    await this.writeLog("push", error ? "error" : "success", eventsCount, durationMs, error);
  }

  /** Retorna os últimos N logs de sincronização. */
  async getRecentLogs(limit: number = 20): Promise<SyncLog[]> {
    return db.syncLogs.orderBy("timestamp").reverse().limit(limit).toArray();
  }

  /** Retorna o checkpoint do dispositivo atual. */
  async getCheckpoint(): Promise<SyncCheckpoint | undefined> {
    const deviceId = getCachedDeviceId() || "unknown";
    return db.syncCheckpoint.get(deviceId);
  }

  /**
   * Atualiza o checkpoint do dispositivo com a sequência contígua processada.
   * Garante que lacunas/eventos com falha não permitam avançar indevidamente o checkpoint.
   */
  async updateCheckpoint(): Promise<void> {
    const deviceId = getCachedDeviceId() || "unknown";
    const contiguousSeq = await getContiguousProcessedSequence();

    await db.syncCheckpoint.put({
      deviceId,
      lastUploadedSequence: contiguousSeq,
      lastDownloadedSequence: 0,
      lastSuccessfulSyncAt: new Date().toISOString(),
    });
  }

  private async writeLog(
    direction: "push" | "pull",
    status: "success" | "error",
    eventsCount: number,
    durationMs: number,
    error: string | null,
  ): Promise<void> {
    const log: SyncLog = {
      id: `log-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
      timestamp: new Date().toISOString(),
      direction,
      status,
      eventsCount,
      durationMs: Math.round(durationMs),
      error,
    };
    await db.syncLogs.put(log);

    // Mantém no máximo 100 logs
    const count = await db.syncLogs.count();
    if (count > 100) {
      const oldest = await db.syncLogs.orderBy("timestamp").limit(count - 100).toArray();
      await db.syncLogs.bulkDelete(oldest.map((l) => l.id));
    }
  }
}

export const syncCoordinator = new SyncCoordinator();
