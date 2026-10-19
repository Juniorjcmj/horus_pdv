/**
 * Arquivo: src/components/Admin/SaleCorrectionModal.tsx
 * Objetivo: modal de correção de venda pelo financeiro — seleciona itens para remover,
 *           justifica e gera nova venda com os itens restantes.
 */

import { useEffect, useMemo, useState } from "react";
import {
  CheckCircle2,
  Edit3,
  Loader2,
  Printer,
  Sparkles,
  Trash2,
  X,
} from "lucide-react";
import { Toast } from "@/hooks/Dialog";
import {
  salesHistoryService,
  type SaleDetailFullDto,
  type SaleHistoryDto,
  type CorrectSaleResult,
} from "@/services/api/salesHistoryService";
import useInputMasks from "@/hooks/InputMasks/useInputMasks";

export type SaleCorrectionModalProps = {
  isOpen: boolean;
  saleNumber: string;
  onClose: () => void;
  onSuccess?: () => void;
};

const MOTIVOS_CORRECAO = [
  "Item lançado por engano.",
  "Cliente devolveu produto parcialmente.",
  "Erro de quantidade no lançamento.",
  "Produto com defeito / avariado.",
  "Troca parcial de mercadoria.",
];

type Step = "select" | "justify" | "done";

export default function SaleCorrectionModal({
  isOpen,
  saleNumber,
  onClose,
  onSuccess,
}: SaleCorrectionModalProps) {
  const { formatMoneyBr, parseMoneyBr } = useInputMasks();
  const [step, setStep] = useState<Step>("select");
  const [loading, setLoading] = useState(false);
  const [detail, setDetail] = useState<SaleDetailFullDto | null>(null);
  const [selectedCodes, setSelectedCodes] = useState<Set<string>>(() => new Set());
  const [justificativa, setJustificativa] = useState(MOTIVOS_CORRECAO[0]);
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<CorrectSaleResult | null>(null);

  useEffect(() => {
    if (!isOpen) return;
    setStep("select");
    setSelectedCodes(new Set());
    setJustificativa(MOTIVOS_CORRECAO[0]);
    setResult(null);
    setDetail(null);

    setLoading(true);
    salesHistoryService
      .getDetails(saleNumber)
      .then((data) => setDetail(data))
      .catch(() => Toast.error("Não foi possível carregar os detalhes da venda."))
      .finally(() => setLoading(false));
  }, [isOpen, saleNumber]);

  // Agrupa itens por productCode (o histórico pode trazer linhas duplicadas por item)
  const uniqueItems = useMemo(() => {
    if (!detail?.items) return [];
    const seen = new Map<string, SaleHistoryDto>();
    for (const item of detail.items) {
      if (!seen.has(item.productCode)) seen.set(item.productCode, item);
    }
    return [...seen.values()];
  }, [detail]);

  const totalOriginal = detail ? parseMoneyBr(detail.totalAmount || "0,00") : 0;

  const totalRemovido = useMemo(() => {
    let sum = 0;
    for (const item of uniqueItems) {
      if (selectedCodes.has(item.productCode)) {
        sum += parseMoneyBr(item.itemTotal || "0,00");
      }
    }
    return sum;
  }, [uniqueItems, selectedCodes, parseMoneyBr]);

  const novoTotal = totalOriginal - totalRemovido;
  const allSelected = uniqueItems.length > 0 && selectedCodes.size === uniqueItems.length;
  const noneSelected = selectedCodes.size === 0;

  const toggleCode = (code: string) => {
    setSelectedCodes((prev) => {
      const next = new Set(prev);
      if (next.has(code)) next.delete(code);
      else next.add(code);
      return next;
    });
  };

  const handleSubmit = async () => {
    if (noneSelected) {
      Toast.error("Selecione ao menos um produto para remover.");
      return;
    }
    if (allSelected) {
      Toast.error("Não é possível remover todos os itens. Use o cancelamento da venda.");
      return;
    }
    if (!justificativa.trim() || justificativa.trim().length < 5) {
      Toast.error("Informe uma justificativa de no mínimo 5 caracteres.");
      return;
    }

    setSubmitting(true);
    try {
      const response = await salesHistoryService.correct(saleNumber, {
        produtosParaRemover: [...selectedCodes],
        justificativa: justificativa.trim(),
      });

      if (!response.success) {
        Toast.error(response.message || "Falha ao corrigir venda.");
        return;
      }

      setResult(response.data ?? null);
      setStep("done");
      Toast.success(`Venda #${saleNumber} corrigida. Nova venda: #${response.data?.novaVendaNumber}.`);
      onSuccess?.();
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao corrigir venda.");
    } finally {
      setSubmitting(false);
    }
  };

  const handlePrintNewReceipt = async () => {
    if (!result) return;
    try {
      const printData = await salesHistoryService.print(result.novaVendaNumber);
      if (printData) {
        Toast.success("Cupom da nova venda enviado para impressão.");
      }
    } catch {
      Toast.error("Falha ao preparar impressão da nova venda.");
    }
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-layer-dialog flex items-end bg-black/60 px-3 backdrop-blur-sm md:items-center md:justify-center">
      <div className="flex max-h-[92vh] w-full max-w-lg flex-col overflow-hidden rounded-t-2xl border border-border-primary bg-bg-light shadow-2xl md:rounded-2xl">
        {/* HEADER */}
        <div className="flex items-center justify-between border-b border-border-primary px-4 py-3 bg-bg-secondary/40">
          <div className="flex items-center gap-2">
            <span className="inline-flex h-9 w-9 items-center justify-center rounded-xl bg-amber-500/10 text-amber-500">
              <Edit3 size={18} />
            </span>
            <div>
              <h2 className="text-base font-semibold text-text-primary">
                Correção de Venda #{saleNumber}
              </h2>
              <p className="text-xs text-text-secondary">
                {step === "select"
                  ? "Selecione os itens para remover"
                  : step === "justify"
                    ? "Informe a justificativa"
                    : "Correção concluída"}
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="inline-flex h-9 w-9 items-center justify-center rounded-lg border border-border-primary text-text-secondary hover:bg-hover-light"
            aria-label="Fechar modal"
          >
            <X size={16} />
          </button>
        </div>

        {/* CONTENT */}
        <div className="flex-1 overflow-y-auto p-5 space-y-4">
          {loading ? (
            <div className="flex items-center justify-center py-12">
              <Loader2 size={24} className="animate-spin text-text-tertiary" />
            </div>
          ) : step === "done" && result ? (
            /* STEP 3: RESULTADO */
            <div className="py-4 text-center space-y-4">
              <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-emerald-500/10 text-emerald-500">
                <CheckCircle2 size={32} />
              </div>
              <div>
                <h3 className="text-lg font-bold text-text-primary">Venda Corrigida</h3>
                <p className="text-xs text-text-secondary mt-1">
                  Venda original #{result.vendaOriginalNumber} → nova venda{" "}
                  <strong>#{result.novaVendaNumber}</strong>
                </p>
              </div>

              <div className="rounded-xl border border-emerald-500/30 bg-emerald-500/10 p-3.5 text-xs text-left space-y-1.5">
                <div className="flex justify-between">
                  <span className="text-text-secondary">Itens removidos:</span>
                  <span className="font-bold text-text-primary">{result.itensRemovidos}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-text-secondary">Novo total:</span>
                  <span className="font-bold text-text-primary">R$ {result.novoTotal}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-text-secondary">Financeiro:</span>
                  <span className="font-medium text-text-primary">{result.financeiroNome}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-text-secondary">Data/hora:</span>
                  <span className="font-medium text-text-primary">{result.corrigidaEm}</span>
                </div>
                {result.fiscalQueued && (
                  <div className="flex justify-between">
                    <span className="text-text-secondary">NFC-e:</span>
                    <span className="font-medium text-emerald-600">Enfileirada para emissão</span>
                  </div>
                )}
              </div>

              <div className="pt-2 flex flex-col sm:flex-row gap-2 justify-center">
                <button
                  type="button"
                  onClick={handlePrintNewReceipt}
                  className="btn-primary inline-flex items-center justify-center gap-2 text-xs py-2 px-4 font-semibold"
                >
                  <Printer size={15} />
                  Imprimir Cupom da Nova Venda
                </button>
                <button type="button" onClick={onClose} className="btn-secondary text-xs py-2 px-4">
                  Fechar
                </button>
              </div>
            </div>
          ) : step === "justify" ? (
            /* STEP 2: JUSTIFICATIVA */
            <div className="space-y-4">
              <div className="rounded-xl border border-border-primary bg-bg-secondary p-3.5 text-xs space-y-1.5">
                <div className="flex justify-between">
                  <span className="text-text-secondary">Itens a remover:</span>
                  <span className="font-bold text-rose-500">{selectedCodes.size}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-text-secondary">Valor removido:</span>
                  <span className="font-bold text-rose-500">- R$ {formatMoneyBr(totalRemovido)}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-text-secondary">Novo total da venda:</span>
                  <span className="font-bold text-emerald-600">R$ {formatMoneyBr(novoTotal)}</span>
                </div>
              </div>

              <div className="space-y-1.5">
                <label className="flex items-center gap-1 text-[11px] font-semibold text-text-secondary">
                  <Sparkles size={12} className="text-accent" />
                  Motivos frequentes:
                </label>
                <div className="flex flex-wrap gap-1.5">
                  {MOTIVOS_CORRECAO.map((motivo, idx) => (
                    <button
                      key={idx}
                      type="button"
                      onClick={() => setJustificativa(motivo)}
                      className={`rounded-lg border px-2.5 py-1 text-[11px] font-medium transition ${
                        justificativa === motivo
                          ? "border-accent bg-accent/10 text-accent"
                          : "border-border-secondary bg-bg-secondary text-text-secondary hover:border-accent hover:text-accent"
                      }`}
                    >
                      {motivo}
                    </button>
                  ))}
                </div>
              </div>

              <div className="space-y-1">
                <label
                  htmlFor="correction-justificativa"
                  className="block text-xs font-semibold text-text-primary"
                >
                  Justificativa da correção <span className="text-danger">*</span>
                </label>
                <textarea
                  id="correction-justificativa"
                  rows={3}
                  value={justificativa}
                  onChange={(e) => setJustificativa(e.target.value)}
                  placeholder="Descreva o motivo da correção..."
                  className="input-field w-full text-xs"
                  disabled={submitting}
                  required
                />
              </div>

              <div className="flex justify-end gap-2 pt-2 border-t border-border-primary">
                <button
                  type="button"
                  onClick={() => setStep("select")}
                  disabled={submitting}
                  className="btn-secondary text-xs"
                >
                  Voltar
                </button>
                <button
                  type="button"
                  onClick={handleSubmit}
                  disabled={submitting || !justificativa.trim() || justificativa.trim().length < 5}
                  className="btn-primary inline-flex items-center justify-center gap-2 text-xs font-semibold py-2 px-4"
                >
                  {submitting ? (
                    <>
                      <Loader2 size={14} className="animate-spin" />
                      Corrigindo venda...
                    </>
                  ) : (
                    <>
                      <Edit3 size={14} />
                      Confirmar Correção
                    </>
                  )}
                </button>
              </div>
            </div>
          ) : (
            /* STEP 1: SELECIONAR ITENS */
            <div className="space-y-4">
              {/* Resumo da venda */}
              <div className="rounded-xl border border-border-primary bg-bg-secondary p-3.5 text-xs space-y-2">
                <div className="flex items-center justify-between border-b border-border-primary/60 pb-2">
                  <span className="font-bold text-text-primary text-sm">Venda #{saleNumber}</span>
                  <span className="font-bold text-text-primary text-sm">
                    R$ {formatMoneyBr(totalOriginal)}
                  </span>
                </div>
                <p className="text-[11px] text-text-secondary">
                  {detail?.operatorName || "Operador"} · {detail?.saleDate || "-"} ·{" "}
                  {detail?.customerName || "Consumidor"}
                </p>
              </div>

              {/* Lista de itens */}
              <div className="space-y-1.5">
                <span className="block text-xs font-semibold text-text-primary">
                  Marque os itens para <span className="text-rose-500">remover</span>:
                </span>
                {uniqueItems.length === 0 ? (
                  <p className="text-xs text-text-tertiary py-4 text-center">Nenhum item encontrado.</p>
                ) : (
                  <ul className="divide-y divide-border-primary rounded-xl border border-border-primary overflow-hidden">
                    {uniqueItems.map((item) => {
                      const checked = selectedCodes.has(item.productCode);
                      return (
                        <li key={item.productCode}>
                          <label
                            className={`flex items-center gap-3 px-3 py-2.5 cursor-pointer transition ${
                              checked
                                ? "bg-rose-500/5 dark:bg-rose-500/10"
                                : "hover:bg-hover-light"
                            }`}
                          >
                            <input
                              type="checkbox"
                              checked={checked}
                              onChange={() => toggleCode(item.productCode)}
                              className="h-4 w-4 accent-rose-500 shrink-0"
                            />
                            <div className="flex-1 min-w-0">
                              <span
                                className={`block text-xs font-medium leading-snug ${
                                  checked ? "line-through text-text-tertiary" : "text-text-primary"
                                }`}
                              >
                                {item.productName}
                              </span>
                              <span className="block text-[11px] text-text-secondary">
                                Cód. {item.productCode} · {item.quantity}x · R${" "}
                                {formatMoneyBr(parseMoneyBr(item.unitPrice || "0,00"))}
                              </span>
                            </div>
                            <span
                              className={`text-xs font-bold tabular-nums shrink-0 ${
                                checked ? "text-rose-500 line-through" : "text-text-primary"
                              }`}
                            >
                              R$ {formatMoneyBr(parseMoneyBr(item.itemTotal || "0,00"))}
                            </span>
                          </label>
                        </li>
                      );
                    })}
                  </ul>
                )}
              </div>

              {/* Preview do novo total */}
              {!noneSelected && (
                <div
                  className={`rounded-xl border p-3 text-xs space-y-1 ${
                    allSelected
                      ? "border-rose-500/30 bg-rose-500/10"
                      : "border-emerald-500/30 bg-emerald-500/10"
                  }`}
                >
                  {allSelected ? (
                    <p className="font-semibold text-rose-600 dark:text-rose-400">
                      Todos os itens selecionados — use o cancelamento da venda.
                    </p>
                  ) : (
                    <>
                      <div className="flex justify-between">
                        <span className="text-text-secondary">Removendo:</span>
                        <span className="font-bold text-rose-500">
                          - R$ {formatMoneyBr(totalRemovido)}
                        </span>
                      </div>
                      <div className="flex justify-between">
                        <span className="text-text-secondary">Novo total:</span>
                        <span className="font-bold text-emerald-600 dark:text-emerald-400">
                          R$ {formatMoneyBr(novoTotal)}
                        </span>
                      </div>
                    </>
                  )}
                </div>
              )}

              <div className="flex justify-end gap-2 pt-2 border-t border-border-primary">
                <button type="button" onClick={onClose} className="btn-secondary text-xs">
                  Cancelar
                </button>
                <button
                  type="button"
                  onClick={() => setStep("justify")}
                  disabled={noneSelected || allSelected}
                  className="btn-primary inline-flex items-center justify-center gap-2 text-xs font-semibold py-2 px-4"
                >
                  <Trash2 size={14} />
                  Continuar ({selectedCodes.size} {selectedCodes.size === 1 ? "item" : "itens"})
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
