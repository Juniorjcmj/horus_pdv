import { decimalNumber, itemNet, money, moneyCents, sumMoney } from "./saleMoney";
import type { SaleHistoryDto } from "@/services/api/salesHistoryService";
export function registeredItemTotal(row: SaleHistoryDto): number {
  if (row.itemTotal != null && row.itemTotal.trim() !== "") {
    try { return money(row.itemTotal); } catch { /* explicit missing/invalid fallback */ }
  }
  if (row.desconto == null) throw new Error("Líquido e desconto não informados no registro histórico.");
  return itemNet(row.quantity, decimalNumber(row.unitPrice), row.desconto);
}
export function receiptFacts(rows: SaleHistoryDto[], payments?: Array<{ paymentType: string; amount: number; cashGiven?: number | null; changeAmount?: number | null }>) {
  const items = rows.map((row, index) => ({ id: `${row.saleNumber}-${row.productCode}-${index}`, code: row.productCode, name: row.productName, quantity: row.quantity, unitPrice: decimalNumber(row.unitPrice), total: registeredItemTotal(row), discount: row.desconto }));
  const subtotal = money(rows[0].totalAmount);
  const cash = payments?.filter(p => p.paymentType.trim().toLowerCase() === "dinheiro");
  return { items, subtotal, cashGiven: cash?.length && cash.every(p => p.cashGiven != null) ? sumMoney(cash.map(p => p.cashGiven!)) : undefined, change: cash?.length && cash.every(p => p.changeAmount != null) ? sumMoney(cash.map(p => p.changeAmount!)) : undefined, inconsistency: moneyCents(sumMoney(items.map(i => i.total))) !== moneyCents(subtotal) ? "Divergência histórica: soma dos itens difere do total registrado." : undefined, payments: payments?.map(p => ({ ...p, paymentLabel: p.paymentType, cashGiven: p.cashGiven ?? undefined, changeAmount: p.changeAmount ?? undefined })) };
}
