/**
 * Arquivo: src/infrastructure/database/dexie.ts
 * Objetivo: schema do banco IndexedDB local via Dexie.js — todas as tabelas do PDV offline-first.
 * Versão 1: fundação (devices, users, products, customers, cashSessions, sales, saleItems,
 *           payments, stockMovements, outbox, processedEvents, syncCheckpoint, syncLogs).
 */
import Dexie, { type EntityTable } from "dexie";
import type { OutboxEvent, SyncCheckpoint, SyncLog } from "@/shared/types/sync";

// ---------------------------------------------------------------------------
// Tipos das tabelas
// ---------------------------------------------------------------------------

export type DeviceRecord = {
  id: string;
  createdAt: string;
};

export type LocalUserRecord = {
  id: string;
  tenantId: string;
  email: string;
  name: string;
  role: string;
  passwordHash: string;
  lastOnlineLoginAt: string;
  maxOfflineDays: number;
};

export type LocalProductRecord = {
  id: string;
  tenantId: string;
  barcode: string;
  productCode: string;
  productName: string;
  salePrice: number;
  unitPrice: number;
  stock: number;
  unit: string;
  categoryId: string | null;
  imageUrl: string;
  marca: string | null;
  ncm: string;
  cfop: string;
  active: boolean;
  controlaValidade: boolean;
  dataValidade: string | null;
  diasAlertaValidade: number;
  updatedAt: string;
  version: number;
};

export type LocalCustomerRecord = {
  id: string;
  tenantId: string;
  name: string;
  cpfCnpj: string;
  phone: string;
  email: string;
  limiteCredito: number;
  saldoDevedor: number;
  updatedAt: string;
  version: number;
};

export type CashSessionRecord = {
  id: string;
  deviceId: string;
  tenantId: string;
  userId: string;
  status: "OPEN" | "CLOSED";
  openingAmount: number;
  closingAmount: number | null;
  openedAt: string;
  closedAt: string | null;
};

export type SaleRecord = {
  id: string;
  deviceId: string;
  tenantId: string;
  sessionId: string;
  saleNumber: string;
  customerId: string | null;
  customerName: string | null;
  totalAmount: number;
  status: "COMPLETED" | "CANCELLED";
  createdAt: string;
};

export type SaleItemRecord = {
  id: string;
  saleId: string;
  productId: string;
  productCode: string;
  productName: string;
  quantity: number;
  unitPrice: number;
  discount: number;
  total: number;
};

export type PaymentRecord = {
  id: string;
  saleId: string;
  paymentType: string;
  amount: number;
  cashGiven: number | null;
  changeAmount: number | null;
};

export type StockMovementRecord = {
  id: string;
  tenantId: string;
  productId: string;
  productCode: string;
  type: "SALE" | "RETURN" | "PURCHASE" | "ADJUSTMENT" | "TRANSFER";
  quantity: number;
  referenceId: string;
  createdAt: string;
};

export type ProcessedEventRecord = {
  eventId: string;
  processedAt: string;
};

// ---------------------------------------------------------------------------
// Banco
// ---------------------------------------------------------------------------

export class LocalDatabase extends Dexie {
  devices!: EntityTable<DeviceRecord, "id">;
  users!: EntityTable<LocalUserRecord, "id">;
  products!: EntityTable<LocalProductRecord, "id">;
  customers!: EntityTable<LocalCustomerRecord, "id">;
  cashSessions!: EntityTable<CashSessionRecord, "id">;
  sales!: EntityTable<SaleRecord, "id">;
  saleItems!: EntityTable<SaleItemRecord, "id">;
  payments!: EntityTable<PaymentRecord, "id">;
  stockMovements!: EntityTable<StockMovementRecord, "id">;
  outbox!: EntityTable<OutboxEvent, "id">;
  processedEvents!: EntityTable<ProcessedEventRecord, "eventId">;
  syncCheckpoint!: EntityTable<SyncCheckpoint, "deviceId">;
  syncLogs!: EntityTable<SyncLog, "id">;

  constructor() {
    super("HorusPdvLocal");

    this.version(1).stores({
      devices: "id",
      users: "id, tenantId, email",
      products: "id, tenantId, barcode, productCode, categoryId, updatedAt",
      customers: "id, tenantId, cpfCnpj, updatedAt",
      cashSessions: "id, deviceId, tenantId, status, openedAt",
      sales: "id, deviceId, tenantId, sessionId, status, createdAt",
      saleItems: "id, saleId, productId",
      payments: "id, saleId, paymentType",
      stockMovements: "id, tenantId, productId, type, createdAt",
      outbox: "id, deviceId, sequence, status, eventType, createdAt",
      processedEvents: "eventId, processedAt",
      syncCheckpoint: "deviceId",
      syncLogs: "id, timestamp, direction, status",
    });
  }
}

/** Singleton — uma única instância por aba do navegador. */
export const db = new LocalDatabase();
