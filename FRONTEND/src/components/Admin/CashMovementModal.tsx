/**
 * Arquivo: src/components/Admin/CashMovementModal.tsx
 * Objetivo: formulário de sangria (retirada) ou reforço (suprimento) de dinheiro do caixa aberto.
 * Entradas esperadas: recebe o tipo de movimento e callbacks de confirmação/fechamento.
 */
import { ArrowDownCircle, ArrowUpCircle, X } from "lucide-react";
import { type ClipboardEvent, type FormEvent, useState } from "react";
import LoadingButton from "@/components/Loading/LoadingButton";
import useInputMasks from "@/hooks/InputMasks/useInputMasks";
import type { CashMovementType } from "@/services/api/cashRegisterService";

export default function CashMovementModal({
  tipo,
  onClose,
  onConfirm,
}: {
  tipo: CashMovementType;
  onClose: () => void;
  onConfirm: (valor: string, motivo: string) => Promise<void>;
}) {
  const { maskMoneyBr } = useInputMasks();
  const [valor, setValor] = useState("0,00");
  const [motivo, setMotivo] = useState("");
  const [saving, setSaving] = useState(false);

  const isSangria = tipo === "Sangria";
  const title = isSangria ? "Sangria de caixa" : "Reforço de caixa";
  const description = isSangria
    ? "Retirada de dinheiro da gaveta durante o turno (ex.: envio ao cofre)."
    : "Entrada de dinheiro na gaveta durante o turno (ex.: troco adicional).";

  const pasteValor = (event: ClipboardEvent<HTMLInputElement>) => {
    event.preventDefault();
    setValor(maskMoneyBr(event.clipboardData.getData("text")));
  };

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (motivo.trim().length < 3) return;
    setSaving(true);
    try {
      await onConfirm(valor, motivo.trim());
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-layer-dialog flex items-end bg-black/55 px-3 backdrop-blur-sm md:items-center md:justify-center">
      <form
        onSubmit={handleSubmit}
        className="w-full max-w-md overflow-hidden rounded-t-2xl border border-border-primary bg-bg-light shadow-2xl md:rounded-2xl"
      >
        <div className="flex items-center justify-between border-b border-border-primary px-4 py-3">
          <div className="flex items-center gap-2">
            <span
              className={`inline-flex h-9 w-9 items-center justify-center rounded-xl ${
                isSangria ? "bg-danger/10 text-danger" : "bg-success/10 text-success"
              }`}
            >
              {isSangria ? <ArrowDownCircle size={18} /> : <ArrowUpCircle size={18} />}
            </span>
            <div>
              <h2 className="text-base font-semibold text-text-primary">{title}</h2>
              <p className="text-xs text-text-secondary">{description}</p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="inline-flex h-9 w-9 items-center justify-center rounded-lg border border-border-primary text-text-secondary hover:bg-hover-light"
            aria-label="Fechar"
          >
            <X size={16} />
          </button>
        </div>

        <div className="space-y-4 p-5">
          <label className="block">
            <span className="mb-1.5 block text-sm text-text-secondary">Valor</span>
            <input
              value={valor}
              inputMode="numeric"
              pattern="[0-9,.]*"
              autoFocus
              onPaste={pasteValor}
              onChange={(event) => setValor(maskMoneyBr(event.target.value))}
              className="input-field w-full"
              placeholder="0,00"
            />
          </label>
          <label className="block">
            <span className="mb-1.5 block text-sm text-text-secondary">Motivo *</span>
            <textarea
              value={motivo}
              onChange={(event) => setMotivo(event.target.value)}
              className="input-field min-h-20 w-full resize-y"
              placeholder={isSangria ? "Ex.: envio ao cofre às 15h" : "Ex.: troco adicional recebido do gerente"}
            />
            {motivo.trim().length > 0 && motivo.trim().length < 3 ? (
              <span className="mt-1 block text-xs text-danger">Mínimo de 3 caracteres.</span>
            ) : null}
          </label>
        </div>

        <div className="flex justify-end gap-2 border-t border-border-primary px-4 py-3">
          <button type="button" onClick={onClose} className="btn-secondary">
            Cancelar
          </button>
          <LoadingButton
            type="submit"
            isLoading={saving}
            loadingLabel="Registrando..."
            disabled={motivo.trim().length < 3}
            className={`inline-flex items-center gap-2 rounded-xl px-4 py-2 text-sm font-semibold text-white disabled:cursor-not-allowed disabled:opacity-60 ${
              isSangria ? "bg-danger hover:bg-danger/90" : "bg-success hover:bg-success/90"
            }`}
          >
            Confirmar {isSangria ? "sangria" : "reforço"}
          </LoadingButton>
        </div>
      </form>
    </div>
  );
}
