/**
 * Arquivo: src/infrastructure/testHarness.ts
 * Objetivo: expõe módulos e repositórios para suíte de testes de arquitetura automatizados no navegador.
 *           Habilitado apenas em ambiente de desenvolvimento (import.meta.env.DEV).
 */
import { db } from "./database/dexie";
import * as OutboxRepository from "./database/repositories/OutboxRepository";
import * as CashSessionRepository from "./database/repositories/CashSessionRepository";
import * as SaleOutboxAdapter from "@/application/sales/SaleOutboxAdapter";
import * as ProductRepository from "./database/repositories/ProductRepository";
import * as ProductSyncAdapter from "@/application/products/ProductSyncAdapter";
import * as UserRepository from "./database/repositories/UserRepository";
import * as cryptoHash from "@/utils/cryptoHash";
import { syncEngine } from "./synchronization/SyncEngine";
import { syncCoordinator } from "./synchronization/SyncCoordinator";
import { cashRegisterService } from "@/services/api/cashRegisterService";
import { salesHistoryService } from "@/services/api/salesHistoryService";

export function setupTestHarness(): void {
  if (typeof window === "undefined") return;

  (window as unknown as Record<string, unknown>).__horus_test__ = {
    db,
    OutboxRepository,
    CashSessionRepository,
    SaleOutboxAdapter,
    ProductRepository,
    ProductSyncAdapter,
    UserRepository,
    cryptoHash,
    syncEngine,
    syncCoordinator,
    cashRegisterService,
    salesHistoryService,
    resetDatabase: async () => {
      await db.transaction(
        "rw",
        [
          db.devices,
          db.users,
          db.products,
          db.customers,
          db.cashSessions,
          db.sales,
          db.saleItems,
          db.payments,
          db.stockMovements,
          db.outbox,
          db.processedEvents,
          db.syncCheckpoint,
          db.syncLogs,
        ],
        async () => {
          await db.sales.clear();
          await db.saleItems.clear();
          await db.payments.clear();
          await db.stockMovements.clear();
          await db.products.clear();
          await db.cashSessions.clear();
          await db.outbox.clear();
          await db.processedEvents.clear();
          await db.users.clear();
        },
      );
      try {
        window.localStorage.clear();
        window.sessionStorage.clear();
      } catch {
        // ignora
      }
    },
  };
}
