/**
 * Arquivo: src/components/Admin/DanfePreviewModal.tsx
 * Objetivo: exibir em tela os dados de uma NFC-e autorizada (chave, protocolo, QR Code) e
 *           permitir o acionamento direto da impressão térmica oficial em bobina de 80mm.
 * Entradas esperadas: recebe o detalhe do documento fiscal, nome da empresa, callbacks de fechar e imprimir.
 */
import { QRCodeSVG } from "qrcode.react";
import { ExternalLink, Printer, ReceiptText, X } from "lucide-react";
import {
  FISCAL_STATUS,
  fiscalStatusBadgeClass,
  fiscalStatusLabel,
  type FiscalDocumentDetailDto,
} from "@/services/api/fiscalService";
import { getSefazConsultaUrl } from "@/utils/danfePrint";

function formatChave(chave: string | null) {
  if (!chave) return "—";
  return chave.replace(/(\d{4})(?=\d)/g, "$1 ").trim();
}

function formatDate(value: string | null) {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("pt-BR", {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

export default function DanfePreviewModal({
  detail,
  companyName,
  onClose,
  onPrintDanfe,
  isPrinting = false,
}: {
  detail: FiscalDocumentDetailDto;
  companyName: string;
  onClose: () => void;
  onPrintDanfe?: (detail: FiscalDocumentDetailDto) => void;
  isPrinting?: boolean;
}) {
  const autorizado =
    detail.status === FISCAL_STATUS.Autorizado ||
    detail.status === FISCAL_STATUS.ContingenciaPendente;

  const effectiveQrCodeUrl =
    detail.qrCodeUrl?.trim() ||
    (detail.chaveAcesso ? `${getSefazConsultaUrl()}?p=${detail.chaveAcesso}` : null);

  return (
    <div className="fixed inset-0 z-layer-dialog flex items-end bg-black/55 px-3 backdrop-blur-sm md:items-center md:justify-center">
      <div className="w-full max-w-lg overflow-hidden rounded-t-2xl border border-border-primary bg-bg-light shadow-2xl md:rounded-2xl">
        <div className="flex items-center justify-between border-b border-border-primary px-4 py-3">
          <div className="flex items-center gap-2">
            <span className="inline-flex h-9 w-9 items-center justify-center rounded-xl bg-accent/10 text-accent">
              <ReceiptText size={18} />
            </span>
            <div>
              <h2 className="text-base font-semibold text-text-primary">NFC-e / DANFE</h2>
              <p className="text-xs text-text-secondary">
                Venda {detail.saleNumber} · Série {detail.serie} · Número {detail.numeroNf}
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="inline-flex h-9 w-9 items-center justify-center rounded-lg border border-border-primary text-text-secondary hover:bg-hover-light"
            aria-label="Fechar DANFE"
          >
            <X size={16} />
          </button>
        </div>

        <div className="max-h-[74vh] space-y-4 overflow-y-auto p-5">
          <span
            className={`inline-flex rounded-full px-2.5 py-1 text-xs font-semibold ${fiscalStatusBadgeClass(detail.status)}`}
          >
            {fiscalStatusLabel(detail.status)}
          </span>

          {!autorizado ? (
            <p className="text-sm text-text-secondary">
              {detail.motivoStatus ||
                "Este documento ainda não foi autorizado pela SEFAZ — o QR Code fica disponível assim que a emissão for concluída."}
            </p>
          ) : null}

          {autorizado && effectiveQrCodeUrl ? (
            <div className="flex flex-col items-center gap-3 rounded-xl border border-border-secondary bg-white p-4">
              <QRCodeSVG value={effectiveQrCodeUrl} size={176} includeMargin={true} />
              <a
                href={effectiveQrCodeUrl}
                target="_blank"
                rel="noreferrer"
                className="inline-flex items-center gap-1 text-xs font-medium text-accent hover:underline"
              >
                Abrir link de consulta <ExternalLink size={12} />
              </a>
            </div>
          ) : null}

          <dl className="space-y-3 text-sm">
            <div>
              <dt className="text-xs uppercase text-text-tertiary">Emitente</dt>
              <dd className="font-semibold text-text-primary">{companyName}</dd>
            </div>
            <div>
              <dt className="text-xs uppercase text-text-tertiary">Chave de acesso</dt>
              <dd className="break-all font-mono text-xs font-semibold text-text-primary">
                {formatChave(detail.chaveAcesso)}
              </dd>
            </div>
            <div>
              <dt className="text-xs uppercase text-text-tertiary">Protocolo de autorização</dt>
              <dd className="font-semibold text-text-primary">{detail.protocolo || "—"}</dd>
            </div>
            <div>
              <dt className="text-xs uppercase text-text-tertiary">Data/hora de autorização</dt>
              <dd className="font-semibold text-text-primary">{formatDate(detail.dhAutorizacao)}</dd>
            </div>
          </dl>
        </div>

        <div className="flex flex-col-reverse gap-2 border-t border-border-primary px-4 py-3 sm:flex-row sm:justify-end">
          <button type="button" onClick={onClose} className="btn-secondary">
            Fechar
          </button>
          {autorizado && onPrintDanfe ? (
            <button
              type="button"
              onClick={() => onPrintDanfe(detail)}
              disabled={isPrinting}
              className="btn-primary inline-flex items-center justify-center gap-2"
            >
              <Printer size={16} />
              {isPrinting ? "Preparando..." : "Imprimir DANFE 80mm"}
            </button>
          ) : null}
        </div>
      </div>
    </div>
  );
}
