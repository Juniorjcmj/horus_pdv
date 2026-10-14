// Decimal text -> integer cents. BigInt avoids binary midpoint errors (1.005 -> 101).
function decimal(value: number | string): [bigint, number] {
  if (typeof value === "number" && !Number.isFinite(value)) throw new Error("Valor monetário não finito.");
  let text = String(value).trim();
  if (text.includes(",")) text = text.replace(/\./g, "").replace(",", ".");
  const match = /^([+-]?)(\d+)(?:\.(\d*))?(?:e([+-]?\d+))?$/i.exec(text);
  if (!match) throw new Error("Valor monetário inválido.");
  const exponent = Number(match[4] ?? 0);
  if (Math.abs(exponent) > 100) throw new Error("Valor monetário fora da faixa.");
  return [BigInt(`${match[1] === "-" ? "-" : ""}${match[2]}${match[3] ?? ""}`), (match[3]?.length ?? 0) - exponent];
}
function rounded(coefficient: bigint, scale: number): number {
  const sign = coefficient < 0 ? -1n : 1n;
  const absolute = coefficient * sign;
  const divisor = 10n ** BigInt(Math.max(0, scale - 2));
  const result = scale <= 2 ? absolute * 10n ** BigInt(2 - scale) : (absolute + divisor / 2n) / divisor;
  const cents = Number(result * sign);
  if (!Number.isSafeInteger(cents)) throw new Error("Valor monetário fora da faixa.");
  return cents;
}
export function moneyCents(value: number | string): number { const [n, scale] = decimal(value); return rounded(n, scale); }
export const money = (value: number | string): number => moneyCents(value) / 100;
export function productMoney(quantity: number, price: number): number {
  const [q, qs] = decimal(quantity); const [p, ps] = decimal(price);
  return rounded(q * p, qs + ps) / 100;
}
export const sumMoney = (values: number[]): number => values.reduce((sum, value) => sum + moneyCents(value), 0) / 100;
export const subtractMoney = (a: number, b: number): number => (moneyCents(a) - moneyCents(b)) / 100;
export function itemNet(quantity: number, price: number, discount = 0): number {
  if (!Number.isFinite(quantity) || quantity <= 0 || !Number.isFinite(price) || price < 0 || !Number.isFinite(discount) || discount < 0) throw new Error("Quantidade, preço ou desconto inválido.");
  const gross = moneyCents(productMoney(quantity, price)); const reduction = moneyCents(discount);
  if (reduction > gross) throw new Error("Desconto superior ao bruto do item.");
  return (gross - reduction) / 100;
}
export function validateCredit(limit: number | null | undefined, debt: number | null | undefined, requested: number): void {
  if (limit == null || debt == null || !Number.isFinite(limit) || !Number.isFinite(debt) || limit < 0 || debt < 0) throw new Error("Dados de crédito indisponíveis. Atualize o cliente antes de comprar fiado.");
  const available = Math.max(0, moneyCents(limit) - moneyCents(debt)); const demand = moneyCents(requested);
  if (limit <= 0 || demand > available) throw new Error(`Crédito insuficiente. Disponível: R$ ${(available / 100).toFixed(2)}; solicitado em fiado: R$ ${(demand / 100).toFixed(2)}; insuficiência: R$ ${(Math.max(0, demand - available) / 100).toFixed(2)}. Cliente sem limite não pode comprar fiado.`);
}
type Settlement = { totalAmount: string; paymentType: string; items: Array<{ quantity: number; unitPrice?: number; desconto?: number; itemTotal?: number }>; payments?: Array<{ paymentType: string; amount: number; cashGiven?: number | null; changeAmount?: number | null }> };
export function validateSale<T extends Settlement>(sale: T): T {
  const total = moneyCents(sale.totalAmount);
  if (total <= 0 || !sale.items.length) throw new Error("Venda deve ter itens e total positivo.");
  const items = sale.items.map(item => {
    if (item.unitPrice == null) throw new Error("Preço do item indisponível.");
    const net = itemNet(item.quantity, item.unitPrice, item.desconto ?? 0);
    if (item.itemTotal != null && moneyCents(item.itemTotal) !== moneyCents(net)) throw new Error("Líquido informado difere do cálculo do item.");
    return { ...item, desconto: money(item.desconto ?? 0), itemTotal: net };
  });
  if (items.reduce((sum, item) => sum + moneyCents(item.itemTotal), 0) !== total) throw new Error("Soma dos itens líquidos difere do total da venda.");
  const payments = (sale.payments?.length ? sale.payments : [{ paymentType: sale.paymentType, amount: total / 100 }]).map(payment => {
    const paymentType = payment.paymentType.trim() && payment.paymentType.trim() !== "-" ? payment.paymentType.trim() : "dinheiro";
    const amount = moneyCents(payment.amount); const change = moneyCents(payment.changeAmount ?? 0);
    if (payment.amount <= 0 || amount <= 0 || (payment.cashGiven ?? 0) < 0 || (payment.changeAmount ?? 0) < 0) throw new Error("Pagamento, recebido ou troco inválido.");
    if (paymentType.toLowerCase() !== "dinheiro" && change !== 0) throw new Error("Troco permitido somente em dinheiro.");
    if (paymentType.toLowerCase() === "dinheiro" && (payment.cashGiven != null || payment.changeAmount != null) && moneyCents(payment.cashGiven ?? payment.amount) !== amount + change) throw new Error("Recebido em dinheiro deve ser igual à parcela mais troco.");
    return { ...payment, paymentType, amount: amount / 100, cashGiven: payment.cashGiven == null ? undefined : money(payment.cashGiven), changeAmount: payment.changeAmount == null ? undefined : change / 100 };
  });
  if (payments.reduce((sum, payment) => sum + moneyCents(payment.amount), 0) !== total) throw new Error("Soma dos pagamentos difere do total da venda.");
  return { ...sale, items, payments };
}
export function financialRevision(items: Array<{ code: string; quantity: number; unitPrice: number; discount?: number; itemTotal?: number }>): string {
  return JSON.stringify(items.map(item => [item.code, item.quantity, item.unitPrice, moneyCents(item.discount ?? 0), moneyCents(item.itemTotal ?? itemNet(item.quantity, item.unitPrice, item.discount))]));
}

export function settlementKey(sale: Settlement): string {
  const snapshot = validateSale(sale);
  return JSON.stringify({ total: moneyCents(snapshot.totalAmount), items: snapshot.items.map(i => [i.quantity, i.unitPrice, i.desconto, i.itemTotal, (i as { productCode?: string }).productCode]), payments: snapshot.payments?.map(p => [p.paymentType.toLowerCase(), p.amount, p.cashGiven ?? null, p.changeAmount ?? null]) });
}

/** Preserve unit price precision when reading historic records. */
export function decimalNumber(value: string | number): number {
  decimal(value);
  const text = String(value).trim();
  return Number(text.includes(",") ? text.replace(/\./g, "").replace(",", ".") : text);
}
