/**
 * Arquivo: src/components/Admin/ReceiptPreviewModal.tsx
 * Objetivo: exibir e imprimir o comprovante de venda no PDV, suportando tanto o
 *           DANFE NFC-e oficial com QR Code (quando autorizada) quanto o cupom gerencial.
 * Entradas esperadas: recebe dados da venda, itens, empresa, dados fiscais (opcionais) e callbacks.
 */
import { useCallback, useEffect, useMemo, useState } from "react";
import { Loader2, Printer, ReceiptText, RefreshCw, X } from "lucide-react";
import { Toast } from "@/hooks/Dialog";
import { printReviewedReceipt } from "@/utils/pendingDanfePrint";
import { fiscalService, type FiscalDocumentDetailDto } from "@/services/api/fiscalService";
import { buildDanfePrintHtml, formatChaveAcesso, generateQrCodeSvg } from "@/utils/danfePrint";

export type PaymentType = "dinheiro" | "pix" | "debito" | "credito" | string;

export type ReceiptCompany = {
  fantasyName?: string;
  corporateName?: string;
  cnpj?: string;
  stateRegistration?: string;
  address?: string;
  number?: string;
  neighborhood?: string;
  city?: string;
  uf?: string;
  phone?: string;
  sacPhone?: string;
  ambienteFiscal?: number;
} | null;

export type SaleReceiptItem = {
  id: string;
  code: string;
  name: string;
  quantity: number;
  unitPrice: number;
  total: number;
  discount?: number;
  promocaoNome?: string | null;
  unit?: string;
};

export type ReceiptPaymentItem = {
  paymentType: string;
  paymentLabel: string;
  amount: number;
  cashGiven?: number;
  changeAmount?: number;
};

export type SaleReceipt = {
  saleNumber: string;
  clientSaleId?: string;
  eventId?: string;
  issuedAt: string;
  printedAt?: string;
  company: ReceiptCompany;
  customerCpf: string;
  paymentType: PaymentType;
  paymentLabel: string;
  operatorName: string;
  subtotal: number;
  cashGiven: number;
  change: number;
  items: SaleReceiptItem[];
  payments?: ReceiptPaymentItem[];
  fiscalDetail?: FiscalDocumentDetailDto | null;
  saldoDevedorAtual?: number;
  isFiado?: boolean;
  needsFiscalPrint?: boolean;
};

function formatReceiptDate(value: string) {
  if (!value) return "-";
  const trimmed = value.trim();
  if (/^\d{2}\/\d{2}\/\d{4}/.test(trimmed)) return trimmed;

  const date = new Date(trimmed);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("pt-BR", {
    timeZone: "America/Sao_Paulo",
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

function escapeHtml(value: string) {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

export function buildReceiptPrintHtml(
  receipt: SaleReceipt,
  formatMoney: (value: number) => string,
  fiscal?: FiscalDocumentDetailDto | null
) {
  const companyName =
    receipt.company?.fantasyName || receipt.company?.corporateName || "Quack Sistemas";
  const companyAddress = [
    receipt.company?.address,
    receipt.company?.number,
    receipt.company?.neighborhood,
  ]
    .filter(Boolean)
    .join(", ");
  const companyCity = [receipt.company?.city, receipt.company?.uf].filter(Boolean).join(" - ");
  const rows = receipt.items
    .map(
      (item, index) => `
        <div class="item">
          <div class="line grid">
            <span>${String(index + 1).padStart(2, "0")}</span>
            <span>${escapeHtml(item.name)}</span>
            <span class="right">${item.quantity}</span>
            <span class="right">${formatMoney(item.total)}</span>
          </div>
          <div class="item-meta">${escapeHtml(item.code)} - ${escapeHtml(item.unit || "")} ${formatMoney(item.unitPrice)}</div>
        </div>
      `,
    )
    .join("");

  const effectiveQrCodeUrl = fiscal?.qrCodeUrl?.trim() || "";
  const offlineQrData = !effectiveQrCodeUrl
    ? [
        `Venda: ${receipt.saleNumber}`,
        `Data: ${formatReceiptDate(receipt.issuedAt)}`,
        receipt.company?.cnpj ? `CNPJ: ${receipt.company.cnpj}` : "",
        `Total: R$ ${formatMoney(receipt.subtotal)}`,
        `Pgto: ${receipt.paymentLabel}`,
        `Op: ${receipt.operatorName}`,
      ].filter(Boolean).join("\n")
    : "";
  const qrSvg = generateQrCodeSvg(effectiveQrCodeUrl || offlineQrData, 130);

  return `<!doctype html>
<html lang="pt-BR">
  <head>
    <meta charset="utf-8" />
    <title>Cupom ${escapeHtml(receipt.saleNumber)}</title>
    <style>
      @page { size: 80mm auto; margin: 4mm; }
      * { box-sizing: border-box; }
      body { margin: 0; color: #020617; font: 12px/1.25 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; -webkit-print-color-adjust: exact; print-color-adjust: exact; }
      .receipt { width: 72mm; margin: 0 auto; }
      .center { text-align: center; }
      .brand { font-size: 14px; font-weight: 800; text-transform: uppercase; }
      .divider { border-top: 1px dashed #475569; margin: 10px 0; }
      .line { display: flex; justify-content: space-between; gap: 8px; }
      .grid { display: grid; grid-template-columns: 24px 1fr 34px 54px; gap: 4px; }
      .right { text-align: right; }
      .bold { font-weight: 800; }
      .item { margin-top: 7px; }
      .item-meta { padding-left: 28px; font-size: 11px; }
      .qrcode-wrapper { text-align: center; margin: 8px auto 4px auto; width: 100%; page-break-inside: avoid; }
      .qrcode-box { display: inline-block; padding: 6px; background: #ffffff !important; -webkit-print-color-adjust: exact; print-color-adjust: exact; color-adjust: exact; }
      .qrcode-wrapper svg { display: inline-block; margin: 0 auto; width: 130px !important; height: 130px !important; shape-rendering: crispEdges; -webkit-print-color-adjust: exact; print-color-adjust: exact; color-adjust: exact; }
      .qrcode-wrapper svg rect { fill: #ffffff !important; } .qrcode-wrapper svg path { fill: #000000 !important; }
      .qrcode-caption { font-size: 9px; margin-top: 4px; text-align: center; color: #000; }
    </style>
  </head>
  <body>
    <main class="receipt">
      <section class="center">
        <div class="brand">${escapeHtml(companyName)}</div>
        <div>${escapeHtml(receipt.company?.corporateName || companyName)}</div>
        <div>CNPJ: ${escapeHtml(receipt.company?.cnpj || "-")}</div>
        ${companyAddress ? `<div>${escapeHtml(companyAddress)}</div>` : ""}
        ${companyCity ? `<div>${escapeHtml(companyCity)}</div>` : ""}
        <div>Telefone: ${escapeHtml(receipt.company?.phone || receipt.company?.sacPhone || "-")}</div>
      </section>
      <div class="divider"></div>
      <section>
        <div>CUPOM NAO FISCAL</div>
        <div>Venda: ${escapeHtml(receipt.saleNumber)}</div>
        <div>Emissao: ${escapeHtml(formatReceiptDate(receipt.issuedAt))}</div>
        <div>Operador: ${escapeHtml(receipt.operatorName || "-")}</div>
        <div>CPF/CNPJ consumidor: ${escapeHtml(receipt.customerCpf || "-")}</div>
      </section>
      <div class="divider"></div>
      <section>
        <div class="grid bold"><span>#</span><span>ITEM</span><span class="right">QTD</span><span class="right">TOTAL</span></div>
        ${rows}
      </section>
      <div class="divider"></div>
      <section>
        <div class="line bold"><span>TOTAL</span><span>R$ ${formatMoney(receipt.subtotal)}</span></div>
        ${
          receipt.payments && receipt.payments.length > 1
            ? receipt.payments
                .map(
                  (p) => `<div class="line"><span>${escapeHtml(p.paymentLabel)}</span><span>R$ ${formatMoney(p.amount)}</span></div>`
                )
                .join("") +
              (receipt.change > 0
                ? `<div class="line bold"><span>Troco</span><span>R$ ${formatMoney(receipt.change)}</span></div>`
                : "")
            : `<div class="line"><span>Pagamento</span><span>${escapeHtml(receipt.paymentLabel || "-")}</span></div>
               ${
                 receipt.paymentType === "dinheiro"
                   ? `<div class="line"><span>Valor recebido</span><span>R$ ${formatMoney(receipt.cashGiven)}</span></div>
                      <div class="line"><span>Troco</span><span>R$ ${formatMoney(receipt.change)}</span></div>`
                   : ""
               }`
        }
      </section>

      ${
        receipt.isFiado || receipt.paymentType === "fiado" || receipt.payments?.some((p) => p.paymentType === "fiado")
          ? `
            <div class="divider"></div>
            <section class="center" style="font-size: 11px;">
              <div class="bold" style="font-size: 12px; letter-spacing: 1px;">*** COMPRA FIADO / A PRAZO ***</div>
              ${
                receipt.saldoDevedorAtual !== undefined
                  ? `<div class="line bold" style="margin-top: 4px;"><span>Novo Saldo Devedor:</span><span>R$ ${formatMoney(receipt.saldoDevedorAtual)}</span></div>`
                  : ""
              }
              <div style="margin-top: 24px; border-top: 1px solid #000; padding-top: 4px; font-size: 10px;">Assinatura do Cliente</div>
            </section>
          `
          : ""
      }

      ${
        fiscal?.chaveAcesso
          ? `
            <div class="divider"></div>
            <section class="center" style="font-size: 9.5px;">
              <div>CHAVE DE ACESSO:</div>
              <div class="bold" style="word-break: break-all; margin-top: 2px;">${escapeHtml(formatChaveAcesso(fiscal.chaveAcesso))}</div>
            </section>
          `
          : ""
      }

      ${
        qrSvg
          ? `
            <div class="divider"></div>
            <section class="qrcode-wrapper">
              <div class="qrcode-box">
                ${qrSvg}
              </div>
              <div class="qrcode-caption">${effectiveQrCodeUrl ? "Consulta via leitor de QR Code" : "Dados da venda offline"}</div>
            </section>
          `
          : ""
      }

      <div class="divider"></div>
      <p class="center">Obrigado pela preferencia.</p>
    </main>
    <script>
      function triggerPrint() {
        setTimeout(function () {
          window.focus();
          window.print();
        }, 200);
      }
      if (document.readyState === "complete") {
        triggerPrint();
      } else {
        window.addEventListener("load", triggerPrint);
      }
    </script>
  </body>
</html>`;
}

export default function ReceiptPreviewModal({ receipt, formatMoney, onClose, fiscalDetail }: {
  receipt: SaleReceipt; formatMoney: (value: number) => string; onClose: () => void; fiscalDetail?: FiscalDocumentDetailDto | null;
}) {
  const [currentFiscal, setCurrentFiscal] = useState(fiscalDetail ?? receipt.fiscalDetail ?? null);
  const [isPrinting, setIsPrinting] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const requiresFiscal = !!receipt.needsFiscalPrint || !!currentFiscal;
  const loadDocument = useCallback(async () => {
    setIsLoading(true);
    try { const detail = await fiscalService.getBySaleNumber(receipt.saleNumber); if (detail) setCurrentFiscal(detail); setNotice(null); }
    catch { setNotice("Não foi possível atualizar o comprovante. Tente novamente."); }
    finally { setIsLoading(false); }
  }, [receipt.saleNumber]);
  useEffect(() => {
    if (!requiresFiscal || currentFiscal?.xmlDocumento) return;
    let attempts = 0;
    const timer = setInterval(() => { if (++attempts >= 20) clearInterval(timer); void loadDocument(); }, 1500);
    return () => clearInterval(timer);
  }, [requiresFiscal, currentFiscal?.xmlDocumento, loadDocument]);
  const preview = useMemo(() => {
    try {
      const html = currentFiscal ? buildDanfePrintHtml(receipt, currentFiscal, formatMoney)
        : requiresFiscal ? "" : buildReceiptPrintHtml(receipt, formatMoney);
      return { html: html.replace(/<script>[\s\S]*?<\/script>/g, ""), error: null };
    } catch (error) { return { html: "", error: error instanceof Error ? error.message : "O comprovante está sendo preparado." }; }
  }, [receipt, currentFiscal, formatMoney, requiresFiscal]);
  const printReceipt = async () => {
    if (isPrinting || !preview.html) return;
    setIsPrinting(true);
    try {
      const html = currentFiscal ? buildDanfePrintHtml(receipt, currentFiscal, formatMoney) : buildReceiptPrintHtml(receipt, formatMoney);
      await printReviewedReceipt(html, receipt.saleNumber, !!currentFiscal);
    } catch (error) { Toast.error(error instanceof Error ? error.message : "Confira a impressora e tente novamente."); }
    finally { setIsPrinting(false); }
  };
  return <div className="fixed inset-0 z-layer-dialog flex items-end bg-black/55 px-3 md:items-center md:justify-center">
    <section role="dialog" aria-modal="true" aria-labelledby="receipt-preview-title" className="w-full max-w-lg overflow-hidden rounded-t-2xl border border-border-primary bg-bg-light shadow-xl md:rounded-2xl">
      <header className="flex items-center justify-between border-b border-border-primary px-4 py-3">
        <div className="flex items-center gap-2"><ReceiptText size={20} /><h2 id="receipt-preview-title" className="font-semibold text-text-primary">Comprovante da venda {receipt.saleNumber}</h2></div>
        <button type="button" onClick={onClose} aria-label="Fechar comprovante" className="rounded-lg p-2 text-text-secondary hover:bg-hover-light"><X size={18} /></button>
      </header>
      <div className="max-h-[70vh] overflow-y-auto bg-white p-3">
        {preview.html ? <iframe title="Visualização do comprovante" sandbox="allow-same-origin" srcDoc={preview.html} className="h-[65vh] w-full border-0" />
          : <div role="status" className="p-6 text-center text-sm text-slate-700"><p>{notice || preview.error || "O comprovante está sendo preparado. Ele será impresso automaticamente quando estiver pronto."}</p><button type="button" disabled={isLoading} onClick={() => void loadDocument()} className="btn-secondary mt-4 inline-flex items-center gap-2">{isLoading ? <Loader2 size={16} className="animate-spin" /> : <RefreshCw size={16} />}Atualizar comprovante</button></div>}
      </div>
      <footer className="flex justify-end gap-2 border-t border-border-primary p-3"><button type="button" onClick={onClose} className="btn-secondary">Fechar</button><button type="button" onClick={() => void printReceipt()} disabled={isPrinting || !preview.html} className="btn-primary inline-flex items-center gap-2"><Printer size={16} />{isPrinting ? "Imprimindo..." : "Imprimir"}</button></footer>
    </section>
  </div>;
}
