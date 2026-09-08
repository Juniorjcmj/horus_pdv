/**
 * Arquivo: src/components/Admin/FiscalErrorModal.tsx
 * Objetivo: exibir em modal os detalhes e o motivo exato de rejeição/erro retornado pela SEFAZ
 *           ou pelo sistema ao tentar emitir uma NFC-e.
 * Entradas esperadas: recebe o documento fiscal com falha e funções de fechamento e reemissão.
 */
import { useState } from "react";
import { AlertTriangle, Check, Copy, RefreshCw, X } from "lucide-react";
import {
  FISCAL_STATUS,
  fiscalStatusBadgeClass,
  fiscalStatusLabel,
  type FiscalDocumentDto,
} from "@/services/api/fiscalService";

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

type FiscalErrorModalProps = {
  document: FiscalDocumentDto;
  onClose: () => void;
  onReemitir?: (doc: FiscalDocumentDto) => Promise<void> | void;
  isReemitindo?: boolean;
};

export default function FiscalErrorModal({
  document,
  onClose,
  onReemitir,
  isReemitindo = false,
}: FiscalErrorModalProps) {
  const [copied, setCopied] = useState(false);

  const errorText =
    document.motivoStatus ||
    "Nenhum detalhe adicional retornado pela SEFAZ ou pelo sistema.";

  const handleCopy = () => {
    navigator.clipboard.writeText(errorText);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="fixed inset-0 z-layer-dialog flex items-end bg-black/55 px-3 backdrop-blur-sm md:items-center md:justify-center">
      <div className="w-full max-w-lg overflow-hidden rounded-t-2xl border border-border-primary bg-bg-light shadow-2xl md:rounded-2xl">
        <div className="flex items-center justify-between border-b border-border-primary px-4 py-3">
          <div className="flex items-center gap-2">
            <span className="inline-flex h-9 w-9 items-center justify-center rounded-xl bg-primary/10 text-primary">
              <AlertTriangle size={18} />
            </span>
            <div>
              <h2 className="text-base font-semibold text-text-primary">
                Falha na Emissão da NFC-e
              </h2>
              <p className="text-xs text-text-secondary">
                Venda {document.saleNumber} · Série {document.serie} · Número {document.numeroNf}
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg p-1 text-text-tertiary hover:bg-bg-primary hover:text-text-primary"
            aria-label="Fechar"
          >
            <X size={18} />
          </button>
        </div>

        <div className="space-y-4 p-4 text-sm">
          <div className="grid grid-cols-2 gap-3 rounded-xl bg-bg-primary p-3">
            <div>
              <span className="block text-xs text-text-secondary">Status</span>
              <span
                className={`mt-1 inline-flex rounded-full px-2.5 py-0.5 text-xs font-semibold ${fiscalStatusBadgeClass(document.status)}`}
              >
                {fiscalStatusLabel(document.status)}
              </span>
            </div>
            <div>
              <span className="block text-xs text-text-secondary">Tentativas</span>
              <span className="mt-1 block font-medium text-text-primary">
                {document.tentativas} {document.tentativas === 1 ? "tentativa" : "tentativas"}
              </span>
            </div>
            <div className="col-span-2">
              <span className="block text-xs text-text-secondary">Data do registro</span>
              <span className="mt-0.5 block font-medium text-text-primary">
                {formatDate(document.criadoEm)}
              </span>
            </div>
          </div>

          <div>
            <div className="mb-1.5 flex items-center justify-between">
              <span className="text-xs font-medium text-text-secondary">
                Motivo retornado pela SEFAZ / Sistema
              </span>
              <button
                type="button"
                onClick={handleCopy}
                className="inline-flex items-center gap-1 text-xs text-accent hover:underline"
              >
                {copied ? <Check size={13} /> : <Copy size={13} />}
                {copied ? "Copiado!" : "Copiar motivo"}
              </button>
            </div>
            <div className="rounded-xl border border-primary/20 bg-primary/5 p-3.5 font-mono text-xs leading-relaxed text-text-primary select-all break-words">
              {errorText}
            </div>
          </div>

          <div className="space-y-1 rounded-xl border border-border-secondary bg-bg-primary/50 p-3 text-xs text-text-secondary">
            <p className="font-semibold text-text-primary">O que fazer?</p>
            <p>
              • Se o erro mencionar <strong>CSC</strong> ou <strong>Hash do QR-Code</strong>: confira o <em>CSC id</em> e o <em>CSC</em> em <strong>Minha Empresa &gt; Dados fiscais</strong>.
            </p>
            <p>
              • Se mencionar <strong>Certificado</strong>: verifique se o certificado A1 (.pfx) e senha foram enviados e estão dentro da validade.
            </p>
            <p>
              • Se mencionar <strong>NCM</strong>, <strong>CFOP</strong> ou <strong>CST/CSOSN</strong>: revise o cadastro dos produtos da venda.
            </p>
            <p>
              • Após corrigir, você pode clicar em <strong>Reemitir NFC-e</strong> abaixo para que o sistema tente transmitir novamente.
            </p>
          </div>
        </div>

        <div className="flex items-center justify-end gap-2 border-t border-border-primary bg-bg-primary/30 px-4 py-3">
          <button type="button" onClick={onClose} className="btn-outline-secondary text-xs">
            Fechar
          </button>
          {onReemitir && document.status === FISCAL_STATUS.Rejeitado ? (
            <button
              type="button"
              disabled={isReemitindo}
              onClick={() => onReemitir(document)}
              className="btn-primary inline-flex items-center gap-1.5 text-xs"
            >
              {isReemitindo ? (
                <>
                  <RefreshCw size={13} className="animate-spin" />
                  Reenfileirando...
                </>
              ) : (
                <>
                  <RefreshCw size={13} />
                  Reemitir NFC-e
                </>
              )}
            </button>
          ) : null}
        </div>
      </div>
    </div>
  );
}
