/**
 * Arquivo: src/components/Admin/PriceOverrideModal.tsx
 * Objetivo: alterar o preço de um produto no caixa com confirmação da senha de um gerente/administrador.
 *           O servidor valida a senha e devolve uma autorização de uso único, que acompanha o item na venda.
 * Entradas esperadas: produto (nome/código), preço atual, callbacks de fechar e de aprovação.
 */
import { KeyRound, Loader2, ShieldCheck, Tag, UserCheck, X } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { Toast } from "@/hooks/Dialog";
import useInputMasks from "@/hooks/InputMasks/useInputMasks";
import { salesHistoryService } from "@/services/api/salesHistoryService";
import { userService, type SupervisorDto } from "@/services/api/userService";

export type PriceOverrideApproval = {
  novoPreco: number;
  autorizacaoId: string;
  supervisorNome: string;
};

type PriceOverrideModalProps = {
  isOpen: boolean;
  productCode: string;
  productName: string;
  /** Preço cadastrado do produto (o que vale sem autorização). */
  currentPrice: number;
  onClose: () => void;
  onApproved: (approval: PriceOverrideApproval) => void;
};

const MOTIVOS_FREQUENTES = [
  "Preço da prateleira diferente do sistema.",
  "Produto avariado ou próximo do vencimento.",
  "Negociação com o cliente.",
];

export default function PriceOverrideModal({
  isOpen,
  productCode,
  productName,
  currentPrice,
  onClose,
  onApproved,
}: PriceOverrideModalProps) {
  const { maskMoneyBr, parseMoneyBr, formatMoneyBr } = useInputMasks();

  const [supervisores, setSupervisores] = useState<SupervisorDto[]>([]);
  const [loadingSupervisores, setLoadingSupervisores] = useState(false);
  const [novoPrecoText, setNovoPrecoText] = useState("");
  const [supervisorId, setSupervisorId] = useState("");
  const [senha, setSenha] = useState("");
  const [motivo, setMotivo] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const priceInputRef = useRef<HTMLInputElement>(null);

  const loadSupervisores = useCallback(async () => {
    setLoadingSupervisores(true);
    try {
      const list = await userService.listSupervisores();
      setSupervisores(list);
      if (list.length === 1) setSupervisorId(list[0].id);
    } catch {
      Toast.error("Não foi possível carregar a lista de gerentes. A alteração de preço exige conexão.");
    } finally {
      setLoadingSupervisores(false);
    }
  }, []);

  useEffect(() => {
    if (!isOpen) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setNovoPrecoText("");
    setSenha("");
    setMotivo("");
    void loadSupervisores();
    window.setTimeout(() => priceInputRef.current?.focus(), 50);
  }, [isOpen, loadSupervisores]);

  if (!isOpen) return null;

  const novoPreco = parseMoneyBr(novoPrecoText);
  const podeConfirmar = novoPreco > 0 && Boolean(supervisorId) && senha.trim().length > 0 && !submitting;

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();

    if (novoPreco <= 0) {
      Toast.error("Informe o novo preço.");
      priceInputRef.current?.focus();
      return;
    }
    if (Math.abs(novoPreco - currentPrice) < 0.005) {
      Toast.error("O novo preço é igual ao preço atual do produto.");
      return;
    }
    if (!supervisorId || !senha.trim()) {
      Toast.error("Selecione o gerente e digite a senha.");
      return;
    }
    if (typeof navigator !== "undefined" && navigator.onLine === false) {
      Toast.error("Sem conexão: a alteração de preço precisa validar a senha do gerente no servidor.");
      return;
    }

    setSubmitting(true);
    try {
      const result = await salesHistoryService.autorizarPreco({
        supervisorId,
        supervisorPassword: senha.trim(),
        productCode,
        precoNovo: novoPreco,
        motivo: motivo.trim() || undefined,
      });
      if (!result?.autorizacaoId) {
        Toast.error("O servidor não confirmou a autorização do preço.");
        return;
      }

      setSenha("");
      onApproved({
        novoPreco: result.precoNovo,
        autorizacaoId: result.autorizacaoId,
        supervisorNome: result.supervisorNome,
      });
    } catch (error) {
      setSenha("");
      Toast.error(error instanceof Error ? error.message : "Não foi possível autorizar a alteração de preço.");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-layer-dialog flex items-end bg-black/60 px-3 backdrop-blur-sm md:items-center md:justify-center">
      <div className="flex max-h-[92vh] w-full max-w-md flex-col overflow-hidden rounded-t-2xl border border-border-primary bg-bg-light shadow-2xl md:rounded-2xl">
        <div className="flex items-center justify-between border-b border-border-primary bg-bg-secondary/40 px-4 py-3">
          <div className="flex items-center gap-2">
            <span className="inline-flex h-9 w-9 items-center justify-center rounded-xl bg-accent/10 text-accent">
              <Tag size={18} />
            </span>
            <div>
              <h2 className="text-base font-semibold text-text-primary">Alterar preço do produto</h2>
              <p className="text-xs text-text-secondary">Exige a senha de um gerente</p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Fechar"
            className="inline-flex h-9 w-9 items-center justify-center rounded-lg border border-border-primary text-text-secondary hover:bg-hover-light"
          >
            <X size={16} />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="flex-1 space-y-4 overflow-y-auto p-5">
          <div className="rounded-xl border border-border-primary bg-bg-secondary p-3 text-xs">
            <span className="block text-sm font-bold text-text-primary">{productName}</span>
            <span className="mt-0.5 block text-text-secondary">
              Cód. {productCode} · preço atual <strong>R$ {formatMoneyBr(currentPrice)}</strong>
            </span>
          </div>

          <div className="space-y-1">
            <label htmlFor="po-preco" className="block text-xs font-semibold text-text-primary">
              Novo preço unitário (R$) <span className="text-danger">*</span>
            </label>
            <input
              ref={priceInputRef}
              id="po-preco"
              inputMode="numeric"
              value={novoPrecoText}
              onChange={(event) => setNovoPrecoText(maskMoneyBr(event.target.value))}
              placeholder="0,00"
              className="input-field w-full text-right text-lg font-bold"
              disabled={submitting}
            />
          </div>

          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div className="space-y-1">
              <label htmlFor="po-gerente" className="flex items-center gap-1.5 text-xs font-semibold text-text-primary">
                <UserCheck size={13} className="text-accent" />
                Gerente <span className="text-danger">*</span>
              </label>
              <select
                id="po-gerente"
                value={supervisorId}
                onChange={(event) => setSupervisorId(event.target.value)}
                disabled={loadingSupervisores || submitting}
                className="input-field w-full text-xs font-medium"
              >
                <option value="">Selecione...</option>
                {supervisores.map((supervisor) => (
                  <option key={supervisor.id} value={supervisor.id}>
                    {supervisor.name} ({supervisor.role === "administrador" ? "Administrador" : "Gerente"})
                  </option>
                ))}
              </select>
            </div>
            <div className="space-y-1">
              <label htmlFor="po-senha" className="flex items-center gap-1.5 text-xs font-semibold text-text-primary">
                <KeyRound size={13} className="text-accent" />
                Senha do gerente <span className="text-danger">*</span>
              </label>
              <input
                id="po-senha"
                type="password"
                autoComplete="off"
                value={senha}
                onChange={(event) => setSenha(event.target.value)}
                placeholder="Digite a senha..."
                className="input-field w-full text-xs font-medium"
                disabled={submitting}
              />
            </div>
          </div>

          <div className="space-y-1.5">
            <label htmlFor="po-motivo" className="block text-xs font-semibold text-text-primary">
              Motivo (opcional)
            </label>
            <input
              id="po-motivo"
              value={motivo}
              onChange={(event) => setMotivo(event.target.value)}
              maxLength={200}
              className="input-field w-full text-xs"
              placeholder="Ex.: preço da prateleira diferente"
              disabled={submitting}
            />
            <div className="flex flex-wrap gap-1.5">
              {MOTIVOS_FREQUENTES.map((item) => (
                <button
                  key={item}
                  type="button"
                  onClick={() => setMotivo(item)}
                  className="rounded-lg border border-border-secondary bg-bg-secondary px-2 py-1 text-[11px] font-medium text-text-secondary transition hover:border-accent hover:text-accent"
                >
                  {item}
                </button>
              ))}
            </div>
          </div>

          <div className="flex items-start gap-2 rounded-xl border border-border-primary bg-bg-secondary p-3 text-[11px] text-text-secondary">
            <ShieldCheck size={14} className="mt-0.5 shrink-0 text-accent" />
            <span>
              A autorização fica registrada com o nome do gerente e vale só para este produto e preço, por 2 horas. Itens
              com preço alterado não recebem promoção.
            </span>
          </div>

          <div className="flex justify-end gap-2 border-t border-border-primary pt-3">
            <button type="button" onClick={onClose} disabled={submitting} className="btn-secondary text-xs">
              Cancelar
            </button>
            <button
              type="submit"
              disabled={!podeConfirmar}
              className="btn-primary inline-flex items-center gap-2 text-xs font-semibold disabled:opacity-60"
            >
              {submitting ? (
                <>
                  <Loader2 size={14} className="animate-spin" /> Validando...
                </>
              ) : (
                "Autorizar e aplicar preço"
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
