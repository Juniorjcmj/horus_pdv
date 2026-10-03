/**
 * Arquivo: src/utils/salePayments.ts
 * Objetivo: regras compartilhadas das telas de vendas para agrupar formas de pagamento (dinheiro, cartão, PIX,
 *           fiado), ler o detalhamento de pagamentos de uma venda e reconhecer venda cancelada/estornada.
 * Entradas esperadas: tipo de pagamento e detalhamento "dinheiro=10.00;pix=5.00" vindos da API de vendas.
 */

export type PaymentGroup = "dinheiro" | "cartao" | "pix" | "fiado" | "outros";

export const PAYMENT_GROUP_LABEL: Record<PaymentGroup, string> = {
  dinheiro: "Dinheiro",
  cartao: "Cartão",
  pix: "PIX",
  fiado: "Fiado",
  outros: "Outros",
};

export type SalePayment = { type: string; group: PaymentGroup; amount: number };

/** Agrupa as formas de pagamento do sistema (dinheiro, pix, debito, credito, fiado...) em grupos de filtro. */
export function paymentGroupOf(type: string): PaymentGroup {
  const t = type.toLowerCase();
  if (t.includes("dinheiro")) return "dinheiro";
  if (t.includes("pix")) return "pix";
  if (t.includes("fiado") || t.includes("prazo")) return "fiado";
  if (t.includes("deb") || t.includes("déb") || t.includes("cred") || t.includes("cré") || t.includes("cart")) {
    return "cartao";
  }
  return "outros";
}

/**
 * Pagamentos da venda com o valor de cada forma. Usa o detalhamento enviado pelo servidor
 * ("dinheiro=10.00;pix=5.00"); sem ele (vendas antigas), considera a forma única da venda pelo total.
 */
export function buildPayments(
  breakdown: string | null | undefined,
  paymentType: string,
  total: number,
): SalePayment[] {
  const parsed: SalePayment[] = [];
  for (const part of (breakdown ?? "").split(";")) {
    const [rawType, rawAmount] = part.split("=");
    const type = rawType?.trim();
    const amount = parseFloat(rawAmount ?? "");
    if (type && Number.isFinite(amount)) parsed.push({ type, group: paymentGroupOf(type), amount });
  }
  if (parsed.length > 0) return parsed;
  return [{ type: paymentType, group: paymentGroupOf(paymentType), amount: total }];
}

export function isCancelledStatus(status?: string | null): boolean {
  const s = (status ?? "").toLowerCase();
  return s.startsWith("cancel") || s.startsWith("estorn");
}
