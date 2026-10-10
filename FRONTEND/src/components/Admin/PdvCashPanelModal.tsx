/**
 * Arquivo: src/components/Admin/PdvCashPanelModal.tsx
 * Objetivo: painel de caixa dentro da frente de venda (F6) — abertura, sangria, reforço e fechamento
 *           sem sair da tela de vendas. Usa as mesmas ações da tela de Caixa (useCashRegisterActions),
 *           inclusive o modo offline. O histórico de turnos continua na tela de Caixa.
 */
import { ArrowDownCircle, ArrowUpCircle, LockKeyhole, UnlockKeyhole, Wallet, X } from "lucide-react";
import { type ClipboardEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import CashClosingSummaryModal, { printCashClosingReceipt } from "@/components/Admin/CashClosingSummaryModal";
import CashMovementModal from "@/components/Admin/CashMovementModal";
import LoadingButton from "@/components/Loading/LoadingButton";
import { Toast, useStatusDialog } from "@/hooks/Dialog";
import useInputMasks from "@/hooks/InputMasks/useInputMasks";
import { useCashRegisterActions } from "@/hooks/useCashRegisterActions";
import type { CompanyDto } from "@/services/api/companyService";
import type {
  CashMovementType,
  CashRegisterSessionDto,
  CashRegisterStatusDto,
} from "@/services/api/cashRegisterService";
import { getStoredAuthUser } from "@/utils/authStorage";
import {
  MANAGER_ROLES,
  cashPaymentLabel,
  formatElapsed,
  preventInvalidMoneyBeforeInput,
} from "@/utils/cashRegisterFormat";

type PdvCashPanelModalProps = {
  cashStatus: CashRegisterStatusDto | null;
  onRefreshStatus: () => Promise<CashRegisterStatusDto | null>;
  company: CompanyDto | null;
  onStatusChange: (status: CashRegisterStatusDto | null) => void;
  onClose: () => void;
  /** Depois de fechar o caixa, oferece "Sair do sistema" (troca de operador / fim de turno). */
  onLogout?: () => void;
};

export default function PdvCashPanelModal({
  cashStatus,
  onRefreshStatus,
  company,
  onStatusChange,
  onClose,
  onLogout,
}: PdvCashPanelModalProps) {
  const { maskMoneyBr, parseMoneyBr, formatMoneyBr } = useInputMasks();
  const statusDialog = useStatusDialog();
  const { saving, openCash, closeCash, registerMovement } = useCashRegisterActions(onStatusChange);

  const currentSession = cashStatus?.currentSession ?? null;
  const hasOpenSession = currentSession !== null;
  const canSell = cashStatus?.canSell === true;
  const expectedCash = currentSession?.expectedCashAmount || "0,00";
  const companyName = company?.fantasyName || "Quack PDV";

  const loggedUser = useMemo(() => getStoredAuthUser(), []);
  const isManager = loggedUser ? MANAGER_ROLES.includes(loggedUser.role.toLowerCase()) : false;
  const isResponsavelPeloCaixa =
    isManager || !currentSession || !loggedUser || currentSession.operatorId === loggedUser.id;

  const [openingAmount, setOpeningAmount] = useState("0,00");
  const [closingAmount, setClosingAmount] = useState(expectedCash);
  const [closingNote, setClosingNote] = useState("");
  const [differenceReason, setDifferenceReason] = useState("");
  const [closingFormOpen, setClosingFormOpen] = useState(false);
  const [movementType, setMovementType] = useState<CashMovementType | null>(null);
  const [closingSummary, setClosingSummary] = useState<CashRegisterSessionDto | null>(null);
  const [refreshing, setRefreshing] = useState(true);
  const [refreshFailed, setRefreshFailed] = useState(false);

  const refreshStatus = useCallback(async () => {
    setRefreshing(true);
    setRefreshFailed(false);
    try {
      const status = await onRefreshStatus();
      if (!status) throw new Error("Status do caixa indisponível.");
      return status;
    } catch {
      setRefreshFailed(true);
      Toast.error("Não foi possível atualizar os totais do caixa. Tente novamente antes de fechar.");
      return null;
    } finally {
      setRefreshing(false);
    }
  }, [onRefreshStatus]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void refreshStatus();
  }, [refreshStatus]);

  const openingInputRef = useRef<HTMLInputElement>(null);
  const closingInputRef = useRef<HTMLInputElement>(null);

  const difference = useMemo(
    () => Math.round((parseMoneyBr(closingAmount) - parseMoneyBr(expectedCash)) * 100) / 100,
    [closingAmount, expectedCash, parseMoneyBr],
  );
  const hasDifference = hasOpenSession && difference !== 0;

  // Foco no campo de valor: abertura quando o caixa está fechado, contagem quando o fechamento é aberto.
  useEffect(() => {
    const target = hasOpenSession ? (closingFormOpen ? closingInputRef.current : null) : openingInputRef.current;
    if (!target) return;
    const timer = window.setTimeout(() => {
      target.focus();
      target.select();
    }, 60);
    return () => window.clearTimeout(timer);
  }, [hasOpenSession, closingFormOpen, refreshing]);

  // Esc: com a confirmação aberta responde "Não"; senão fecha o painel (sangria/reforço e resumo tratam o próprio Esc).
  const confirmOpen = statusDialog.Dialog !== null;
  const closeConfirm = statusDialog.close;
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape" || movementType || closingSummary || saving) return;
      event.preventDefault();
      event.stopPropagation();
      if (confirmOpen) closeConfirm();
      else onClose();
    };
    window.addEventListener("keydown", onKeyDown, true);
    return () => window.removeEventListener("keydown", onKeyDown, true);
  }, [movementType, closingSummary, saving, confirmOpen, closeConfirm, onClose]);

  const pasteMoney = (setter: (value: string) => void) => (event: ClipboardEvent<HTMLInputElement>) => {
    event.preventDefault();
    setter(maskMoneyBr(event.clipboardData.getData("text")));
  };

  const handleOpen = async () => {
    if (saving) return;
    if (await openCash(openingAmount)) onClose();
  };

  const startClosing = async () => {
    const latest = await refreshStatus();
    if (!latest?.currentSession) return;
    setClosingAmount(latest.currentSession.expectedCashAmount || "0,00");
    setDifferenceReason("");
    setClosingFormOpen(true);
  };

  const handleClose = async () => {
    if (saving || refreshing) return;
    const latest = await refreshStatus();
    if (!latest?.currentSession) return;
    const latestDifference = Math.round((parseMoneyBr(closingAmount) - parseMoneyBr(latest.currentSession.expectedCashAmount || "0,00")) * 100) / 100;
    if (latestDifference !== 0 && differenceReason.trim().length < 3) {
      Toast.error("Confira os totais atualizados e informe o motivo da diferença antes de fechar.");
      return;
    }
    const confirmed = await statusDialog.confirm("Fechar o caixa atual? As vendas ficam bloqueadas até nova abertura.");
    if (!confirmed) return;

    const closed = await closeCash(closingAmount, closingNote, latestDifference !== 0 ? differenceReason.trim() : undefined);
    if (closed === null) return;
    setClosingFormOpen(false);
    setClosingNote("");
    setDifferenceReason("");
    setClosingSummary(closed);
    printCashClosingReceipt(closed, company, companyName);
  };

  const handleMovement = async (valor: string, motivo: string) => {
    if (!movementType) return;
    if (await registerMovement(movementType, valor, motivo)) setMovementType(null);
  };

  return (
    <div className="fixed inset-0 z-layer-modal flex items-end bg-black/45 md:items-center md:justify-center">
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="pdv-cash-panel-title"
        className="max-h-[90vh] w-full overflow-y-auto rounded-t-2xl border border-border-primary bg-bg-light p-4 md:max-w-2xl md:rounded-2xl"
      >
        <div className="mb-3 flex items-start justify-between gap-3">
          <div>
            <h2 id="pdv-cash-panel-title" className="text-lg font-semibold text-text-primary">
              Caixa
            </h2>
            <p className={`text-sm font-semibold ${canSell ? "text-success" : "text-primary"}`}>
              {canSell
                ? `Aberto há ${formatElapsed(currentSession?.elapsedMinutes ?? 0)} • ${currentSession?.operatorName || "-"}`
                : cashStatus?.blockReason || "Caixa fechado. Abra o caixa para vender."}
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Fechar painel de caixa"
            className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border border-border-primary text-text-secondary hover:bg-hover-light"
          >
            <X size={14} />
          </button>
        </div>

        {refreshing ? (
          <p role="status" className="py-4 text-sm text-text-secondary">Atualizando totais do caixa...</p>
        ) : refreshFailed ? (
          <button type="button" onClick={() => void refreshStatus()} className="btn-primary">Tentar atualizar novamente</button>
        ) : !hasOpenSession ? (
          <form
            className="space-y-3"
            onSubmit={(event) => {
              event.preventDefault();
              void handleOpen();
            }}
          >
            <label className="block">
              <span className="mb-1.5 block text-sm text-text-secondary">Fundo de troco (valor inicial na gaveta)</span>
              <input
                ref={openingInputRef}
                value={openingAmount}
                inputMode="numeric"
                pattern="[0-9,.]*"
                onBeforeInput={preventInvalidMoneyBeforeInput}
                onPaste={pasteMoney(setOpeningAmount)}
                onChange={(event) => setOpeningAmount(maskMoneyBr(event.target.value))}
                className="input-field w-full text-lg font-semibold"
                placeholder="0,00"
              />
            </label>
            <LoadingButton
              type="submit"
              isLoading={saving}
              loadingLabel="Abrindo..."
              className="btn-success inline-flex h-11 w-full items-center justify-center gap-2"
            >
              <UnlockKeyhole size={16} />
              Abrir caixa (Enter)
            </LoadingButton>
          </form>
        ) : (
          <div className="space-y-3">
            <div className="grid gap-3 md:grid-cols-2">
              <div className="rounded-xl border border-border-primary bg-bg-primary p-3">
                <div className="mb-2 flex items-center gap-2 text-text-primary">
                  <Wallet size={15} />
                  <h3 className="text-sm font-semibold">Vendas do turno</h3>
                </div>
                {currentSession.paymentBreakdown && currentSession.paymentBreakdown.length > 0 ? (
                  <div className="space-y-1 text-sm">
                    {currentSession.paymentBreakdown.map((item) => (
                      <div key={item.paymentType} className="flex justify-between">
                        <span className="text-text-secondary">{cashPaymentLabel(item.paymentType)}</span>
                        <span className="font-semibold text-text-primary">R$ {item.total}</span>
                      </div>
                    ))}
                  </div>
                ) : (
                  <p className="text-sm text-text-tertiary">Nenhuma venda neste turno ainda.</p>
                )}
                <div className="mt-2 flex justify-between border-t border-border-primary pt-2 text-sm">
                  <span className="text-text-secondary">Fundo de troco</span>
                  <span className="font-semibold text-text-primary">R$ {currentSession.openingAmount}</span>
                </div>
                <div className="mt-1 flex justify-between text-sm font-bold text-text-primary">
                  <span>Dinheiro esperado na gaveta</span>
                  <span>R$ {expectedCash}</span>
                </div>
              </div>

              <div className="rounded-xl border border-border-primary bg-bg-primary p-3">
                <h3 className="mb-2 text-sm font-semibold text-text-primary">Sangrias e reforços</h3>
                {currentSession.movimentos.length > 0 ? (
                  <div className="max-h-32 space-y-1.5 overflow-y-auto pr-1">
                    {currentSession.movimentos.map((item) => (
                      <div key={item.id} className="flex items-start justify-between gap-2 text-sm">
                        <div className="min-w-0">
                          <span
                            className={item.tipo === "Sangria" ? "font-semibold text-primary" : "font-semibold text-success"}
                          >
                            {item.tipo === "Sangria" ? "Sangria" : "Reforço"}
                          </span>
                          <p className="truncate text-xs text-text-secondary">{item.motivo}</p>
                        </div>
                        <span className="shrink-0 font-semibold text-text-primary">R$ {item.valor}</span>
                      </div>
                    ))}
                  </div>
                ) : (
                  <p className="text-sm text-text-tertiary">Nenhuma movimentação neste turno.</p>
                )}
              </div>
            </div>

            {isResponsavelPeloCaixa ? (
              !closingFormOpen ? (
                <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
                  <button
                    type="button"
                    onClick={() => setMovementType("Reforco")}
                    className="inline-flex h-11 items-center justify-center gap-2 rounded-xl border border-success/30 bg-success/10 text-sm font-semibold text-success hover:bg-success/20"
                  >
                    <ArrowUpCircle size={16} />
                    Reforço
                  </button>
                  <button
                    type="button"
                    onClick={() => setMovementType("Sangria")}
                    className="inline-flex h-11 items-center justify-center gap-2 rounded-xl border border-primary/30 bg-primary/10 text-sm font-semibold text-primary hover:bg-primary/20"
                  >
                    <ArrowDownCircle size={16} />
                    Sangria
                  </button>
                  <button
                    type="button"
                    onClick={startClosing}
                    className="btn-primary inline-flex h-11 items-center justify-center gap-2 rounded-xl"
                  >
                    <LockKeyhole size={16} />
                    Fechar caixa
                  </button>
                </div>
              ) : (
                <form
                  className="space-y-3 rounded-xl border border-border-primary p-3"
                  onSubmit={(event) => {
                    event.preventDefault();
                    void handleClose();
                  }}
                >
                  <label className="block">
                    <span className="mb-1.5 block text-sm text-text-secondary">Valor contado na gaveta (dinheiro)</span>
                    <input
                      ref={closingInputRef}
                      value={closingAmount}
                      inputMode="numeric"
                      pattern="[0-9,.]*"
                      onBeforeInput={preventInvalidMoneyBeforeInput}
                      onPaste={pasteMoney(setClosingAmount)}
                      onChange={(event) => setClosingAmount(maskMoneyBr(event.target.value))}
                      className="input-field w-full text-lg font-semibold"
                      placeholder="0,00"
                    />
                  </label>

                  <div
                    className={`rounded-xl border p-3 text-sm ${
                      difference === 0
                        ? "border-success/30 bg-success/10 text-success"
                        : "border-primary/30 bg-primary/10 text-primary"
                    }`}
                  >
                    <div className="flex justify-between font-bold">
                      <span>Diferença</span>
                      <span>R$ {formatMoneyBr(difference)}</span>
                    </div>
                    <p className="mt-0.5 text-xs">
                      {difference === 0
                        ? "Confere com o esperado."
                        : difference > 0
                          ? "Sobra em relação ao esperado."
                          : "Falta em relação ao esperado."}
                    </p>
                  </div>

                  {hasDifference ? (
                    <label className="block">
                      <span className="mb-1.5 block text-sm text-text-secondary">Motivo da diferença *</span>
                      <textarea
                        value={differenceReason}
                        onChange={(event) => setDifferenceReason(event.target.value)}
                        className="input-field min-h-16 w-full resize-y"
                        placeholder="Explique a sobra ou falta antes de fechar o caixa"
                      />
                    </label>
                  ) : null}

                  <label className="block">
                    <span className="mb-1.5 block text-sm text-text-secondary">Observação</span>
                    <textarea
                      value={closingNote}
                      onChange={(event) => setClosingNote(event.target.value)}
                      className="input-field min-h-16 w-full resize-y"
                      placeholder="Observação geral do fechamento (opcional)"
                    />
                  </label>

                  <div className="grid grid-cols-2 gap-2">
                    <button
                      type="button"
                      onClick={() => setClosingFormOpen(false)}
                      className="h-11 rounded-xl border border-border-secondary text-sm font-semibold text-text-secondary hover:bg-hover-light"
                    >
                      Voltar
                    </button>
                    <LoadingButton
                      type="submit"
                      isLoading={saving}
                      loadingLabel="Fechando..."
                      disabled={hasDifference && differenceReason.trim().length < 3}
                      className="btn-primary inline-flex h-11 items-center justify-center gap-2 disabled:cursor-not-allowed disabled:opacity-60"
                    >
                      <LockKeyhole size={16} />
                      Confirmar fechamento
                    </LoadingButton>
                  </div>
                </form>
              )
            ) : (
              <p className="rounded-xl border border-accent/30 bg-accent/10 p-3 text-sm text-accent">
                Só {currentSession.operatorName || "quem abriu este caixa"} ou um gerente/administrador podem fechar o
                caixa ou lançar sangria/reforço. Você pode continuar vendendo normalmente.
              </p>
            )}
          </div>
        )}
      </div>

      {movementType ? (
        <CashMovementModal tipo={movementType} onClose={() => setMovementType(null)} onConfirm={handleMovement} />
      ) : null}

      {closingSummary ? (
        <CashClosingSummaryModal
          session={closingSummary}
          company={company}
          companyName={companyName}
          onClose={() => {
            setClosingSummary(null);
            onClose();
          }}
          onLogout={onLogout}
        />
      ) : null}

      {statusDialog.Dialog}
    </div>
  );
}
