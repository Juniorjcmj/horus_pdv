/**
 * Arquivo: src/application/sales/SaleOutboxAdapter.ts
 * Objetivo: persiste vendas offline atomicamente em todas as tabelas locais do IndexedDB
 *           (sales, saleItems, payments, stockMovements, products e outbox) e fornece
 *           consulta do histórico de vendas local para operação offline.
 */
import { enqueueEvent } from "@/infrastructure/database/repositories/OutboxRepository";
import type { RegisterSalePayload, SaleHistoryDto } from "@/services/api/salesHistoryService";
import {
  db,
  type SaleRecord,
  type SaleItemRecord,
  type PaymentRecord,
  type StockMovementRecord,
} from "@/infrastructure/database/dexie";
import { getCachedDeviceId } from "@/infrastructure/database/deviceId";

/**
 * Enfileira uma venda no outbox e persiste atomicamente todas as tabelas locais do PDV.
 * Retorna a referência local da venda (usada como saleNumber offline provisório).
 */
export async function queueSaleToOutbox(payload: RegisterSalePayload): Promise<string> {
  const clientSaleId =
    payload.clientSaleId ||
    (typeof crypto !== "undefined" && crypto.randomUUID ? crypto.randomUUID() : `cs-${Date.now()}`);
  const eventId =
    payload.eventId ||
    (typeof crypto !== "undefined" && crypto.randomUUID ? crypto.randomUUID() : `ev-${Date.now()}`);
  const localRef = payload.offlineReference || `OFF-${Date.now().toString().slice(-6)}`;
  const occurredAt = payload.occurredAt || new Date().toISOString();

  payload.clientSaleId = clientSaleId;
  payload.eventId = eventId;
  payload.eventType = "SALE_CREATED";
  payload.offlineReference = localRef;
  payload.occurredAt = occurredAt;

  const deviceId = getCachedDeviceId() || "unknown-device";
  const totalAmountNum =
    typeof payload.totalAmount === "number"
      ? payload.totalAmount
      : parseFloat(String(payload.totalAmount || "0").replace(/\./g, "").replace(",", ".")) || 0;

  await db.transaction(
    "rw",
    [db.sales, db.saleItems, db.payments, db.stockMovements, db.outbox, db.products, db.cashSessions],
    async () => {
      // 1. Obter sessão atual de caixa (se houver)
      const cachedCash = await db.cashSessions.get("cash-status-cache");
      const sessionId = cachedCash?.id || "cx-offline";

      // 2. Gravar cabeçalho da venda
      const saleRecord: SaleRecord = {
        id: clientSaleId,
        deviceId,
        tenantId: "",
        sessionId,
        saleNumber: localRef,
        customerId: payload.customerCpf || null,
        customerName: payload.customerName || null,
        totalAmount: totalAmountNum,
        status: "COMPLETED",
        createdAt: occurredAt,
      };
      await db.sales.put(saleRecord);

      // 3. Gravar itens da venda e baixar estoque local
      const itemRecords: SaleItemRecord[] = [];
      const movementRecords: StockMovementRecord[] = [];

      for (let i = 0; i < payload.items.length; i++) {
        const item = payload.items[i];
        const itemId = `${clientSaleId}-item-${i + 1}`;
        const unitPrice = item.unitPrice ?? 0;
        const discount = item.desconto ?? 0;
        const itemTotal = item.itemTotal ?? Math.max(0, item.quantity * unitPrice - discount);

        itemRecords.push({
          id: itemId,
          saleId: clientSaleId,
          productId: item.productCode,
          productCode: item.productCode,
          productName: item.productName,
          quantity: item.quantity,
          unitPrice,
          discount,
          total: itemTotal,
        });

        movementRecords.push({
          id:
            typeof crypto !== "undefined" && crypto.randomUUID
              ? crypto.randomUUID()
              : `sm-${Date.now()}-${i}`,
          tenantId: "",
          productId: item.productCode,
          productCode: item.productCode,
          type: "SALE",
          quantity: -item.quantity,
          referenceId: clientSaleId,
          createdAt: occurredAt,
        });

        // Atualiza estoque local no db.products
        const prod =
          (await db.products.where("productCode").equals(item.productCode).first()) ||
          (await db.products.where("barcode").equals(item.productCode).first());
        if (prod) {
          await db.products.update(prod.id, {
            stock: (prod.stock ?? 0) - item.quantity,
            updatedAt: new Date().toISOString(),
          });
        }
      }

      await db.saleItems.bulkPut(itemRecords);
      await db.stockMovements.bulkAdd(movementRecords);

      // 4. Gravar pagamentos da venda
      if (Array.isArray(payload.payments) && payload.payments.length > 0) {
        const paymentRecords: PaymentRecord[] = payload.payments.map((p, idx) => ({
          id: `${clientSaleId}-pay-${idx + 1}`,
          saleId: clientSaleId,
          paymentType: p.paymentType,
          amount: p.amount,
          cashGiven: p.cashGiven ?? null,
          changeAmount: p.changeAmount ?? null,
        }));
        await db.payments.bulkPut(paymentRecords);
      } else {
        await db.payments.put({
          id: `${clientSaleId}-pay-1`,
          saleId: clientSaleId,
          paymentType: payload.paymentType || "dinheiro",
          amount: totalAmountNum,
          cashGiven: null,
          changeAmount: null,
        });
      }

      // 5. Enfileirar no Outbox para sincronização posterior
      await enqueueEvent({
        id: eventId,
        clientSaleId,
        payloadHash: payload.payloadHash,
        occurredAt,
        eventType: "SALE_CREATED",
        aggregateType: "Sale",
        aggregateId: clientSaleId,
        payload,
      });
    },
  );

  return localRef;
}

/**
 * Retorna o histórico de vendas armazenado localmente no IndexedDB
 * formatado como SaleHistoryDto para exibição offline.
 */
export async function getLocalSalesHistory(): Promise<SaleHistoryDto[]> {
  const sales = await db.sales.reverse().sortBy("createdAt");
  const result: SaleHistoryDto[] = [];

  for (const sale of sales) {
    const items = await db.saleItems.where("saleId").equals(sale.id).toArray();
    const payments = await db.payments.where("saleId").equals(sale.id).toArray();
    const paymentLabel = payments.length > 0 ? payments.map((p) => p.paymentType).join(" + ") : "dinheiro";

    const formattedDate = new Date(sale.createdAt).toLocaleString("pt-BR", {
      day: "2-digit",
      month: "2-digit",
      year: "numeric",
      hour: "2-digit",
      minute: "2-digit",
    });

    if (items.length === 0) {
      result.push({
        saleNumber: sale.saleNumber,
        customerName: sale.customerName || "-",
        customerCpf: sale.customerId || "-",
        paymentType: paymentLabel,
        totalAmount: sale.totalAmount.toFixed(2).replace(".", ","),
        operatorName: "Operador Local",
        productCode: "-",
        productName: "Venda sem itens",
        quantity: 1,
        unitPrice: sale.totalAmount.toFixed(2).replace(".", ","),
        itemTotal: sale.totalAmount.toFixed(2).replace(".", ","),
        saleDate: formattedDate,
        clientSaleId: sale.id,
        offlineReference: sale.saleNumber,
      });
    } else {
      for (const item of items) {
        result.push({
          saleNumber: sale.saleNumber,
          customerName: sale.customerName || "-",
          customerCpf: sale.customerId || "-",
          paymentType: paymentLabel,
          totalAmount: sale.totalAmount.toFixed(2).replace(".", ","),
          operatorName: "Operador Local",
          productCode: item.productCode,
          productName: item.productName,
          quantity: item.quantity,
          unitPrice: item.unitPrice.toFixed(2).replace(".", ","),
          itemTotal: item.total.toFixed(2).replace(".", ","),
          saleDate: formattedDate,
          clientSaleId: sale.id,
          offlineReference: sale.saleNumber,
        });
      }
    }
  }

  return result;
}
