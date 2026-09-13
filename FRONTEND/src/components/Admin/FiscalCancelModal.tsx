/**
 * Arquivo: src/components/Admin/FiscalCancelModal.tsx
 * Objetivo: modal dedicado para cancelamento formal de NFC-e perante a SEFAZ, com
 *           validação de justificativa (mínimo 15 caracteres), motivos rápidos e alertas legais.
 * Entradas esperadas: documento fiscal a ser cancelado, callbacks de fechamento e confirmação.
 */

import { useState } from "react";
import { AlertOctagon, AlertTriangle, CheckCircle2, Clock, FileText, Loader2, Sparkles, X } from "lucide-react";
import type { FiscalDocumentDto } from "@/services/api/fiscalService";

type FiscalCancelModalProps = {
  document: FiscalDocumentDto;
  onClose: () => void;
  onConfirm: (document: FiscalDocumentDto, justificativa: string) => Promise<void>;
  isCanceling?: boolean;
};

const MOTIVOS_SUGERIDOS = [
  "Erro nos valores ou quantidades informadas nos itens da venda.",
  "Desistência da compra ou devolução de mercadoria pelo cliente.",
  "Forma de pagamento registrada incorretamente no fechamento.",
  "Emissão duplicada em contingência por instabilidade de rede.",
];

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
  });
}

export default function FiscalCancelModal({
  document,
  onClose,
  onConfirm,
  isCanceling = false,
}: FiscalCancelModalProps) {
  const [justificativa, setJustificativa] = useState("");
  const trimmed = justificativa.trim();
  const charCount = trimmed.length;
  const isValidLength = charCount >= 15;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!isValidLength || isCanceling) return;
    await onConfirm(document, trimmed);
  };

  const selectSuggestedMotivo = (motivo: string) => {
    setJustificativa(motivo);
  };

  return (
    <div className="fixed inset-0 z-layer-dialog flex items-end bg-black/60 px-3 backdrop-blur-sm md:items-center md:justify-center">
      <div className="w-full max-w-xl overflow-hidden rounded-t-2xl border border-border-primary bg-bg-light shadow-2xl md:rounded-2xl">
        {/* Cabeçalho do modal */}
        <div className="flex items-center justify-between border-b border-border-primary bg-primary/5 px-5 py-4">
          <div className="flex items-center gap-3">
            <span className="inline-flex h-10 w-10 items-center justify-center rounded-xl bg-primary/15 text-primary">
              <AlertOctagon size={22} />
            </span>
            <div>
              <h2 className="text-base font-bold text-text-primary">Cancelar Nota Fiscal (NFC-e)</h2>
              <p className="text-xs text-text-secondary">
                Venda {document.saleNumber} · Série {document.serie} · Número {document.numeroNf}
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={isCanceling}
            className="rounded-lg p-1 text-text-secondary hover:bg-hover-light hover:text-text-primary"
            aria-label="Fechar modal"
          >
            <X size={18} />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4 p-5">
          {/* Card Resumo da Nota */}
          <div className="rounded-xl border border-border-secondary bg-bg-primary p-3.5 text-xs space-y-2">
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
              <div>
                <span className="block text-[11px] text-text-secondary font-medium">Valor Total</span>
                <span className="block font-bold text-text-primary text-sm">
                  {document.totalAmount ? `R$ ${document.totalAmount}` : "—"}
                </span>
              </div>
              <div>
                <span className="block text-[11px] text-text-secondary font-medium">Emitido em</span>
                <span className="block font-semibold text-text-primary">
                  {formatDate(document.dhAutorizacao || document.criadoEm)}
                </span>
              </div>
              <div>
                <span className="block text-[11px] text-text-secondary font-medium">Protocolo SEFAZ</span>
                <span className="block font-mono font-semibold text-text-primary truncate" title={document.protocolo || ""}>
                  {document.protocolo || "—"}
                </span>
              </div>
              <div>
                <span className="block text-[11px] text-text-secondary font-medium">Cliente</span>
                <span className="block font-semibold text-text-primary truncate" title={document.customerName || "Consumidor"}>
                  {document.customerName || "Consumidor"}
                </span>
              </div>
            </div>

            {document.chaveAcesso && (
              <div className="border-t border-border-primary/60 pt-2">
                <span className="block text-[11px] text-text-secondary font-medium">Chave de Acesso</span>
                <span className="block font-mono text-[11px] text-text-secondary break-all">
                  {formatChave(document.chaveAcesso)}
                </span>
              </div>
            )}
          </div>

          {/* Alerta de Regras SEFAZ */}
          <div className="flex gap-2.5 rounded-xl border border-amber-500/30 bg-amber-500/10 p-3 text-xs text-amber-600 dark:text-amber-400">
            <Clock size={16} className="shrink-0 mt-0.5" />
            <div className="space-y-1">
              <p className="font-semibold">Regras Legais de Cancelamento (SEFAZ):</p>
              <p className="leading-relaxed opacity-90">
                O cancelamento só pode ser efetuado dentro do prazo regulamentar do seu estado (geralmente até 30 minutos ou 24h após a autorização) e se as mercadorias ainda não tiverem saído do estabelecimento. O cancelamento é **definitivo e irreversível**.
              </p>
            </div>
          </div>

          {/* Seção de Justificativas Pré-definidas */}
          <div className="space-y-1.5">
            <label className="flex items-center gap-1.5 text-xs font-semibold text-text-primary">
              <Sparkles size={13} className="text-accent" />
              Motivos Frequentes (clique para preencher):
            </label>
            <div className="flex flex-wrap gap-1.5">
              {MOTIVOS_SUGERIDOS.map((motivo, index) => (
                <button
                  key={index}
                  type="button"
                  onClick={() => selectSuggestedMotivo(motivo)}
                  className="rounded-lg border border-border-secondary bg-bg-secondary px-2.5 py-1 text-[11px] font-medium text-text-secondary hover:border-accent hover:text-accent transition-colors text-left"
                >
                  {motivo}
                </button>
              ))}
            </div>
          </div>

          {/* Campo de Justificativa Livre */}
          <div className="space-y-1.5">
            <div className="flex items-center justify-between">
              <label htmlFor="cancel-justificativa" className="text-xs font-semibold text-text-primary flex items-center gap-1">
                <FileText size={13} />
                Justificativa para a SEFAZ <span className="text-primary">*</span>
              </label>
              <span
                className={`text-[11px] font-mono font-medium ${
                  isValidLength ? "text-success" : "text-text-secondary"
                }`}
              >
                {charCount} / 15 caracteres mínimos
              </span>
            </div>
            <textarea
              id="cancel-justificativa"
              rows={3}
              value={justificativa}
              onChange={(e) => setJustificativa(e.target.value)}
              placeholder="Descreva detalhadamente o motivo do cancelamento para homologação perante o fisco..."
              className="input-field w-full text-xs font-medium leading-relaxed resize-none"
              disabled={isCanceling}
              autoFocus
            />
            <div className="flex items-center gap-1 text-[11px] text-text-secondary">
              {isValidLength ? (
                <span className="flex items-center gap-1 text-success font-medium">
                  <CheckCircle2 size={12} /> Justificativa com tamanho válido para a SEFAZ.
                </span>
              ) : (
                <span className="flex items-center gap-1 text-text-secondary">
                  <AlertTriangle size={12} className="text-amber-500" /> A SEFAZ exige no mínimo 15 caracteres para autorizar o evento de cancelamento.
                </span>
              )}
            </div>
          </div>

          {/* Rodapé e Ações */}
          <div className="flex flex-col-reverse gap-2 border-t border-border-primary pt-4 sm:flex-row sm:justify-end">
            <button
              type="button"
              onClick={onClose}
              disabled={isCanceling}
              className="btn-secondary text-xs"
            >
              Voltar / Não Cancelar
            </button>
            <button
              type="submit"
              disabled={!isValidLength || isCanceling}
              className="btn-danger inline-flex items-center justify-center gap-2 text-xs font-semibold"
            >
              {isCanceling ? (
                <>
                  <Loader2 size={14} className="animate-spin" />
                  Homologando na SEFAZ...
                </>
              ) : (
                <>
                  <AlertOctagon size={14} />
                  Confirmar Cancelamento
                </>
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
