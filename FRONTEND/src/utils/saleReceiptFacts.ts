import { itemNet, money, moneyCents, sumMoney } from "./saleMoney";
import type { SaleHistoryDto } from "@/services/api/salesHistoryService";
export function registeredItemTotal(row: SaleHistoryDto): number {
  if (row.itemTotal != null && row.itemTotal.trim() !== "") {
    try { return money(row.itemTotal); } catch { /* explicit missing/invalid fallback */ }
  }
  return itemNet(row.quantity, money(row.unitPrice), row.desconto ?? 0);
}
export function receiptFacts(rows: SaleHistoryDto[], payments?: Array<{ paymentType: string; amount: number; cashGiven?: number | null; changeAmount?: number | null }>) {
  const items = rows.map((row, index) => ({ id: `${row.saleNumber}-${row.productCode}-${index}`, code: row.productCode, name: row.productName, quantity: row.quantity, unitPrice: money(row.unitPrice), total: registeredItemTotal(row), discount: row.desconto }));
  const subtotal = money(rows[0].totalAmount);
  const cash = payments?.filter(p => p.paymentType.trim().toLowerCase() === "dinheiro");
  return { items, subtotal, cashGiven: cash?.length && cash.every(p => p.cashGiven != null) ? sumMoney(cash.map(p => p.cashGiven!)) : undefined, change: cash?.length && cash.every(p => p.changeAmount != null) ? sumMoney(cash.map(p => p.changeAmount!)) : undefined, inconsistency: moneyCents(sumMoney(items.map(i => i.total))) !== moneyCents(subtotal) ? "Divergência histórica: soma dos itens difere do total registrado." : undefined, payments: payments?.map(p => ({ ...p, paymentLabel: p.paymentType, cashGiven: p.cashGiven ?? undefined, changeAmount: p.changeAmount ?? undefined })) };
}
