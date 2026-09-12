/**
 * Arquivo: src/components/Admin/ReceiptPreviewModal.tsx
 * Objetivo: exibir e imprimir o comprovante de venda no PDV, suportando tanto o
 *           DANFE NFC-e oficial com QR Code (quando autorizada) quanto o cupom gerencial.
 * Entradas esperadas: recebe dados da venda, itens, empresa, dados fiscais (opcionais) e callbacks.
 */
import { useCallback, useEffect, useState } from "react";
import {
  AlertCircle,
  ExternalLink,
  Loader2,
  Printer,
  QrCode,
  ReceiptText,
  RefreshCw,
  X,
} from "lucide-react";
import { QRCodeSVG } from "qrcode.react";
import {
  fiscalService,
  FISCAL_STATUS,
  fiscalStatusBadgeClass,
  fiscalStatusLabel,
  type FiscalDocumentDetailDto,
} from "@/services/api/fiscalService";
import {
  buildDanfePrintHtml,
  formatChaveAcesso,
  formatNumeroNf,
  generateQrCodeSvg,
  getSefazConsultaUrl,
} from "@/utils/danfePrint";

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

function buildReceiptPrintHtml(
  receipt: SaleReceipt,
  formatMoney: (value: number) => string,
  fiscal?: FiscalDocumentDetailDto | null
) {
  const companyName =
    receipt.company?.fantasyName || receipt.company?.corporateName || "Quack PDV";
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
          <div class="item-meta">${escapeHtml(item.code)} - UN ${formatMoney(item.unitPrice)}</div>
        </div>
      `,
    )
    .join("");

  const effectiveQrCodeUrl =
    fiscal?.qrCodeUrl?.trim() ||
    (fiscal?.chaveAcesso
      ? `${getSefazConsultaUrl(receipt.company?.uf)}?p=${fiscal.chaveAcesso}`
      : "");
  const qrSvg = generateQrCodeSvg(effectiveQrCodeUrl, 130);

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
      .qrcode-box { display: inline-block; padding: 4px; background: #ffffff; }
      .qrcode-wrapper svg { display: inline-block; margin: 0 auto; width: 130px !important; height: 130px !important; shape-rendering: crispEdges; }
      .qrcode-wrapper path { fill: #000000 !important; }
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
              <div class="qrcode-caption">Consulta via leitor de QR Code</div>
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

export default function ReceiptPreviewModal({
  receipt,
  formatMoney,
  onClose,
  fiscalDetail,
}: {
  receipt: SaleReceipt;
  formatMoney: (value: number) => string;
  onClose: () => void;
  fiscalDetail?: FiscalDocumentDetailDto | null;
}) {
  const initialFiscal = fiscalDetail ?? receipt.fiscalDetail ?? null;
  const [currentFiscal, setCurrentFiscal] = useState<FiscalDocumentDetailDto | null>(initialFiscal);
  const [isPolling, setIsPolling] = useState(
    () =>
      !(
        initialFiscal &&
        (initialFiscal.status === FISCAL_STATUS.Autorizado ||
          initialFiscal.status === FISCAL_STATUS.ContingenciaPendente)
      )
  );
  const [sefazNotice, setSefazNotice] = useState<string | null>(null);

  const isDanfe = Boolean(
    currentFiscal &&
      (currentFiscal.status === FISCAL_STATUS.Autorizado ||
        currentFiscal.status === FISCAL_STATUS.ContingenciaPendente)
  );

  const companyName =
    receipt.company?.fantasyName || receipt.company?.corporateName || "Quack PDV";
  const companyAddress = [
    receipt.company?.address,
    receipt.company?.number,
    receipt.company?.neighborhood,
  ]
    .filter(Boolean)
    .join(", ");
  const companyCity = [receipt.company?.city, receipt.company?.uf].filter(Boolean).join(" - ");

  const checkFiscalStatus = useCallback(async () => {
    if (!receipt.saleNumber) return false;
    try {
      const doc = await fiscalService.getBySaleNumber(receipt.saleNumber);
      if (doc) {
        setCurrentFiscal(doc);
        if (
          doc.status === FISCAL_STATUS.Autorizado ||
          doc.status === FISCAL_STATUS.ContingenciaPendente
        ) {
          setSefazNotice(null);
          return true;
        }
        if (doc.status === FISCAL_STATUS.Rejeitado) {
          setSefazNotice(
            doc.motivoStatus
              ? `Rejeição SEFAZ: ${doc.motivoStatus}`
              : "NFC-e rejeitada pela SEFAZ."
          );
          return true;
        }
        if (doc.status === FISCAL_STATUS.Denegado) {
          setSefazNotice(`NFC-e denegada pela SEFAZ: ${doc.motivoStatus ?? ""}`);
          return true;
        }
      }
    } catch {
      // Ignora erro transitório de rede
    }
    return false;
  }, [receipt.saleNumber]);

  useEffect(() => {
    if (isDanfe) return;

    let isMounted = true;
    let attempts = 0;
    const maxAttempts = 10;

    const timer = setInterval(async () => {
      attempts++;
      const finished = await checkFiscalStatus();
      if (!isMounted) return;
      if (finished || attempts >= maxAttempts) {
        setIsPolling(false);
        clearInterval(timer);
      }
    }, 1200);

    return () => {
      isMounted = false;
      clearInterval(timer);
    };
  }, [checkFiscalStatus, isDanfe]);

  const effectiveQrCodeUrl =
    currentFiscal?.qrCodeUrl?.trim() ||
    (currentFiscal?.chaveAcesso
      ? `${getSefazConsultaUrl(receipt.company?.uf)}?p=${currentFiscal.chaveAcesso}`
      : null);

  const printReceipt = () => {
    const popup = window.open("", "_blank", "width=420,height=720");
    if (!popup) return;
    popup.document.open();
    if (isDanfe && currentFiscal) {
      popup.document.write(buildDanfePrintHtml(receipt, currentFiscal, formatMoney));
    } else {
      popup.document.write(buildReceiptPrintHtml(receipt, formatMoney, currentFiscal));
    }
    popup.document.close();
  };

  const tributosEstimados = receipt.subtotal * 0.3145;

  return (
    <div className="fixed inset-0 z-layer-dialog flex items-end bg-black/55 px-3 backdrop-blur-sm md:items-center md:justify-center">
      <div className="w-full max-w-4xl overflow-hidden rounded-t-2xl border border-border-primary bg-bg-light shadow-2xl md:rounded-2xl">
        <div className="flex items-center justify-between border-b border-border-primary px-4 py-3">
          <div className="flex items-center gap-2">
            <span className="inline-flex h-9 w-9 items-center justify-center rounded-xl bg-accent/10 text-accent">
              <ReceiptText size={18} />
            </span>
            <div>
              <h2 className="text-base font-semibold text-text-primary">
                {isDanfe ? "DANFE NFC-e (Bobina 80mm)" : "Prévia de impressão"}
              </h2>
              <p className="text-xs text-text-secondary">
                {isDanfe && currentFiscal
                  ? `NFC-e nº ${formatNumeroNf(currentFiscal.numeroNf)} · Série ${currentFiscal.serie} · Venda ${receipt.saleNumber}`
                  : `Cupom da venda ${receipt.saleNumber}`}
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="inline-flex h-9 w-9 items-center justify-center rounded-lg border border-border-primary text-text-secondary hover:bg-hover-light"
            aria-label="Fechar prévia de impressão"
          >
            <X size={16} />
          </button>
        </div>

        <div className="grid max-h-[74vh] overflow-y-auto bg-bg-primary md:grid-cols-[minmax(0,1fr)_320px]">
          <div className="p-4">
            {isPolling ? (
              <div className="mx-auto mb-3 flex max-w-[360px] items-center justify-center gap-2 rounded-lg border border-accent/40 bg-accent/10 px-3 py-2 text-xs font-medium text-accent animate-pulse">
                <Loader2 size={15} className="animate-spin shrink-0" />
                <span>Autorizando NFC-e junto à SEFAZ...</span>
              </div>
            ) : sefazNotice ? (
              <div className="mx-auto mb-3 flex max-w-[360px] items-start gap-2 rounded-lg border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-xs text-amber-300">
                <AlertCircle size={15} className="mt-0.5 shrink-0" />
                <span>{sefazNotice}</span>
              </div>
            ) : null}

            <div className="mx-auto w-full max-w-[360px] border border-border-secondary bg-white px-5 py-4 font-mono text-[11.5px] leading-tight text-slate-950 shadow-sm">
              {/* CABEÇALHO EMITENTE */}
              <div className="text-center">
                <p className="text-sm font-bold uppercase">{receipt.company?.corporateName || companyName}</p>
                {receipt.company?.fantasyName && receipt.company.fantasyName !== receipt.company.corporateName ? (
                  <p className="uppercase text-xs">{receipt.company.fantasyName}</p>
                ) : null}
                <p>CNPJ: {receipt.company?.cnpj || "-"}</p>
                {receipt.company?.stateRegistration ? (
                  <p>Inscrição Estadual: {receipt.company.stateRegistration}</p>
                ) : null}
                {companyAddress ? <p>{companyAddress}</p> : null}
                {companyCity ? <p>{companyCity}</p> : null}
                <p>Telefone: {receipt.company?.phone || receipt.company?.sacPhone || "-"}</p>
              </div>

              <div className="my-3 border-t border-dashed border-slate-500" />

              {/* TÍTULO / IDENTIFICAÇÃO DO CUPOM */}
              {isDanfe && currentFiscal ? (
                <div className="space-y-1 text-center">
                  <p className="text-xs font-extrabold uppercase">DANFE NFC-e</p>
                  <p className="text-[10px] leading-tight">Documento Auxiliar da Nota Fiscal de Consumidor Eletrônica</p>
                  <p className="text-[10px] font-bold leading-tight">Não permite aproveitamento de crédito de ICMS</p>
                  {receipt.company?.ambienteFiscal === 2 ? (
                    <div className="my-1 border border-black p-1 text-[10px] font-extrabold">
                      EMITIDA EM AMBIENTE DE HOMOLOGAÇÃO<br />SEM VALOR FISCAL
                    </div>
                  ) : null}
                  {currentFiscal.status === FISCAL_STATUS.ContingenciaPendente ? (
                    <div className="my-1 border border-black p-1 text-[10px] font-extrabold">
                      EMITIDA EM CONTINGÊNCIA<br />Pendente de autorização
                    </div>
                  ) : null}
                </div>
              ) : (
                <div className="space-y-1">
                  <p className="font-bold">CUPOM NAO FISCAL</p>
                  <p>Venda: {receipt.saleNumber}</p>
                  <p>Emissao: {formatReceiptDate(receipt.issuedAt)}</p>
                  <p>Operador: {receipt.operatorName}</p>
                  <p>CPF/CNPJ consumidor: {receipt.customerCpf || "-"}</p>
                </div>
              )}

              <div className="my-3 border-t border-dashed border-slate-500" />

              {/* ITENS */}
              <div className="grid grid-cols-[20px_1fr_32px_52px] gap-1 font-bold">
                <span>#</span>
                <span>ITEM</span>
                <span className="text-right">QTD</span>
                <span className="text-right">TOTAL</span>
              </div>
              <div className="mt-1 space-y-1.5">
                {receipt.items.map((item, index) => (
                  <div key={item.id || index}>
                    <div className="grid grid-cols-[20px_1fr_32px_52px] gap-1">
                      <span>{String(index + 1).padStart(2, "0")}</span>
                      <span className="break-words">{item.name}</span>
                      <span className="text-right">{item.quantity}</span>
                      <span className="text-right">{formatMoney(item.total)}</span>
                    </div>
                    <p className="pl-6 text-[10px] text-slate-700">
                      {item.code} - UN {formatMoney(item.unitPrice)}
                    </p>
                  </div>
                ))}
              </div>

              <div className="my-3 border-t border-dashed border-slate-500" />

              {/* TOTAIS E PAGAMENTO */}
              <div className="space-y-1">
                {isDanfe ? (
                  <div className="flex justify-between text-[11px]">
                    <span>Qtd. total de itens</span>
                    <span>{receipt.items.length}</span>
                  </div>
                ) : null}
                <div className="flex justify-between text-sm font-bold">
                  <span>TOTAL R$</span>
                  <span>{formatMoney(receipt.subtotal)}</span>
                </div>

                <div className="pt-1">
                  <div className="font-bold text-[11px]">FORMA DE PAGAMENTO</div>
                  {receipt.payments && receipt.payments.length > 1 ? (
                    <>
                      {receipt.payments.map((p, idx) => (
                        <div key={idx} className="flex justify-between pl-1 text-[11px]">
                          <span>{p.paymentLabel}</span>
                          <span>R$ {formatMoney(p.amount)}</span>
                        </div>
                      ))}
                      {receipt.change > 0 ? (
                        <div className="flex justify-between font-bold text-emerald-700">
                          <span>Troco</span>
                          <span>R$ {formatMoney(receipt.change)}</span>
                        </div>
                      ) : null}
                    </>
                  ) : (
                    <>
                      <div className="flex justify-between text-[11px]">
                        <span>{receipt.paymentLabel}</span>
                        <span>R$ {formatMoney(receipt.subtotal)}</span>
                      </div>
                      {receipt.paymentType === "dinheiro" && receipt.change > 0 ? (
                        <div className="flex justify-between font-bold text-emerald-700">
                          <span>Troco</span>
                          <span>R$ {formatMoney(receipt.change)}</span>
                        </div>
                      ) : null}
                    </>
                  )}
                </div>
              </div>

              {/* TRIBUTOS LEI 12.741/2012 */}
              {isDanfe ? (
                <>
                  <div className="my-3 border-t border-dashed border-slate-500" />
                  <div className="text-center text-[10px]">
                    <p>Tributos Totais Incidentes (Lei Fed. 12.741/2012):</p>
                    <p className="font-bold">R$ {formatMoney(tributosEstimados)}</p>
                  </div>
                </>
              ) : null}

              {/* IDENTIFICAÇÃO FISCAL E QR CODE */}
              {isDanfe && currentFiscal ? (
                <>
                  <div className="my-3 border-t border-dashed border-slate-500" />
                  <div className="space-y-1 text-center text-[10px]">
                    <p className="font-bold">
                      NFC-e nº {formatNumeroNf(currentFiscal.numeroNf)} Série {currentFiscal.serie}
                    </p>
                    <p>Data de Emissão: {formatReceiptDate(receipt.issuedAt)}</p>
                    {currentFiscal.protocolo ? (
                      <p>
                        Protocolo de Autorização: <strong>{currentFiscal.protocolo}</strong>
                      </p>
                    ) : null}
                  </div>

                  <div className="my-3 border-t border-dashed border-slate-500" />
                  <div className="space-y-1 text-center text-[10px]">
                    <p>Consulte pela Chave de Acesso em:</p>
                    <p className="break-all font-bold text-[9px]">{getSefazConsultaUrl(receipt.company?.uf)}</p>
                    <p className="pt-1 text-[9px] uppercase font-bold">Chave de Acesso:</p>
                    <p className="break-all font-mono font-bold text-[9.5px]">
                      {formatChaveAcesso(currentFiscal.chaveAcesso)}
                    </p>
                  </div>

                  <div className="my-3 border-t border-dashed border-slate-500" />
                  <div className="text-center text-[10px]">
                    <p className="font-bold uppercase">
                      CONSUMIDOR:{" "}
                      {receipt.customerCpf && receipt.customerCpf !== "-" && receipt.customerCpf.trim().length > 0
                        ? `CPF ${receipt.customerCpf}`
                        : "NÃO IDENTIFICADO"}
                    </p>
                  </div>

                  {effectiveQrCodeUrl ? (
                    <div className="mt-3 flex flex-col items-center justify-center">
                      <div className="border border-slate-300 p-1.5 bg-white">
                        <QRCodeSVG value={effectiveQrCodeUrl} size={140} includeMargin={true} />
                      </div>
                      <span className="mt-1 text-[9px]">Consulta via leitor de QR Code</span>
                    </div>
                  ) : null}
                </>
              ) : null}

              <div className="my-3 border-t border-dashed border-slate-500" />
              <p className="text-center text-[10.5px]">Obrigado pela preferencia.</p>
            </div>
          </div>

          {/* SIDEBAR LATERAL */}
          <aside className="border-t border-border-primary bg-bg-light p-4 md:border-l md:border-t-0">
            {isDanfe && currentFiscal ? (
              <div className="space-y-2">
                <span
                  className={`inline-flex rounded-full px-2.5 py-1 text-xs font-semibold ${fiscalStatusBadgeClass(currentFiscal.status)}`}
                >
                  {fiscalStatusLabel(currentFiscal.status)}
                </span>
                <p className="text-xs text-text-secondary">
                  Documento fiscal autorizado e pronto para impressão térmica na bobina de 80mm.
                </p>
              </div>
            ) : isPolling ? (
              <div className="rounded-xl border border-accent/30 bg-accent/10 p-3 text-xs text-accent">
                <div className="flex items-center gap-2 font-semibold">
                  <Loader2 size={14} className="animate-spin" />
                  <span>Transmitindo à SEFAZ</span>
                </div>
                <p className="mt-1 text-text-secondary">
                  Aguardando resposta do servidor fiscal para gerar o QR Code oficial.
                </p>
              </div>
            ) : sefazNotice ? (
              <div className="rounded-xl border border-amber-500/30 bg-amber-500/10 p-3 text-xs text-amber-400">
                <p className="font-semibold">Emissão não autorizada</p>
                <p className="mt-1 text-[11px] text-text-secondary">{sefazNotice}</p>
                <button
                  type="button"
                  onClick={() => {
                    setIsPolling(true);
                    checkFiscalStatus().finally(() => setIsPolling(false));
                  }}
                  className="btn-secondary mt-2.5 w-full inline-flex items-center justify-center gap-1.5 text-xs py-1.5"
                >
                  <RefreshCw size={12} />
                  Tentar consultar novamente
                </button>
              </div>
            ) : (
              <div className="space-y-2">
                <div className="rounded-xl border border-border-primary bg-bg-secondary p-3 text-xs">
                  <p className="font-semibold text-text-primary">Cupom Não Fiscal</p>
                  <p className="mt-1 text-text-secondary">
                    NFC-e ainda não autorizada no momento da emissão.
                  </p>
                  <button
                    type="button"
                    onClick={() => {
                      setIsPolling(true);
                      checkFiscalStatus().finally(() => setIsPolling(false));
                    }}
                    disabled={isPolling}
                    className="btn-secondary mt-2.5 w-full inline-flex items-center justify-center gap-1.5 text-xs py-1.5"
                  >
                    <RefreshCw size={12} className={isPolling ? "animate-spin" : ""} />
                    {isPolling ? "Consultando SEFAZ..." : "Consultar SEFAZ agora"}
                  </button>
                </div>
              </div>
            )}

            <dl className="mt-4 space-y-3 text-sm">
              <div>
                <dt className="text-xs uppercase text-text-tertiary">Venda</dt>
                <dd className="font-semibold text-text-primary">{receipt.saleNumber}</dd>
              </div>
              {isDanfe && currentFiscal ? (
                <>
                  <div>
                    <dt className="text-xs uppercase text-text-tertiary">NFC-e</dt>
                    <dd className="font-semibold text-text-primary">
                      {formatNumeroNf(currentFiscal.numeroNf)} (Série {currentFiscal.serie})
                    </dd>
                  </div>
                  {currentFiscal.protocolo ? (
                    <div>
                      <dt className="text-xs uppercase text-text-tertiary">Protocolo SEFAZ</dt>
                      <dd className="font-mono text-xs font-semibold text-text-primary">{currentFiscal.protocolo}</dd>
                    </div>
                  ) : null}
                </>
              ) : null}
              <div>
                <dt className="text-xs uppercase text-text-tertiary">Emissao</dt>
                <dd className="font-semibold text-text-primary">{formatReceiptDate(receipt.issuedAt)}</dd>
              </div>
              <div>
                <dt className="text-xs uppercase text-text-tertiary">Itens</dt>
                <dd className="font-semibold text-text-primary">{receipt.items.length}</dd>
              </div>
              <div>
                <dt className="text-xs uppercase text-text-tertiary">Total</dt>
                <dd className="text-2xl font-bold text-text-primary">R$ {formatMoney(receipt.subtotal)}</dd>
              </div>
            </dl>

            {isDanfe && effectiveQrCodeUrl ? (
              <div className="mt-4 border-t border-border-primary pt-3">
                <a
                  href={effectiveQrCodeUrl}
                  target="_blank"
                  rel="noreferrer"
                  className="inline-flex items-center gap-1.5 text-xs text-accent hover:underline"
                >
                  <QrCode size={13} />
                  Abrir link de consulta SEFAZ <ExternalLink size={11} />
                </a>
              </div>
            ) : null}
          </aside>
        </div>

        <div className="flex flex-col-reverse gap-2 border-t border-border-primary px-4 py-3 sm:flex-row sm:justify-end">
          <button type="button" onClick={onClose} className="btn-secondary">
            Fechar
          </button>
          <button
            type="button"
            onClick={printReceipt}
            className="btn-primary inline-flex items-center justify-center gap-2"
          >
            <Printer size={16} />
            {isDanfe ? "Imprimir DANFE 80mm" : "Imprimir Cupom Não Fiscal"}
          </button>
        </div>
      </div>
    </div>
  );
}
