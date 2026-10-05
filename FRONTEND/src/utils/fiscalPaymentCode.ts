/**
 * Arquivo: src/utils/fiscalPaymentCode.ts
 * Objetivo: converte a forma de pagamento do PDV no código tPag da NFC-e.
 *           Mesma regra do backend (DocumentoFiscalAB.MapearFormaPagamento) para a emissão
 *           em contingência pelo Gateway sair com os mesmos códigos da emissão pela API.
 */

/** tPag: 01 dinheiro, 03 crédito, 04 débito, 05 crédito loja / fiado, 17 PIX dinâmico. */
export function toFiscalPaymentCode(paymentType: string): string {
  const normalized = paymentType.trim().toLowerCase();
  if (normalized.includes("fiado") || normalized.includes("crediario") || normalized.includes("crediário")) return "05";
  if (normalized.includes("pix")) return "17";
  if (normalized.includes("debit") || normalized.includes("débit")) return "04";
  if (normalized.includes("credit") || normalized.includes("crédit")) return "03";
  return "01"; // dinheiro / fallback
}
