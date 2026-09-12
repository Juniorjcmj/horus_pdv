/**
 * Arquivo: src/pages/Admin/CashRegisterPage.tsx
 * Objetivo: gerencia abertura, fechamento com conferência, sangria/reforço, histórico e
 *           bloqueios operacionais do caixa.
 * Entradas esperadas: não recebe props; carrega status via API e executa ações do operador autenticado.
 *
 * Conferência: o "dinheiro esperado" e a "diferença" são sempre calculados pelo servidor (a
 * partir das vendas em dinheiro do turno + reforços - sangrias) — a tela só espelha esses
 * valores e, quando há qualquer diferença, exige o motivo antes de deixar fechar.
 */
import {
  ArrowDownCircle,
  ArrowUpCircle,
  Banknote,
  Clock3,
  Eye,
  LockKeyhole,
  RefreshCw,
  ShieldCheck,
  UnlockKeyhole,
  Wallet,
} from "lucide-react";
import { type ClipboardEvent, type FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import CashClosingSummaryModal from "@/components/Admin/CashClosingSummaryModal";
import CashMovementModal from "@/components/Admin/CashMovementModal";
import PageHeader from "@/components/Admin/PageHeader";
import LoadingBar from "@/components/Loading/LoadingBar";
import LoadingButton from "@/components/Loading/LoadingButton";
import TablePagination from "@/components/Pagination/TablePagination";
import { Toast, useStatusDialog } from "@/hooks/Dialog";
import useInputMasks from "@/hooks/InputMasks/useInputMasks";
import PageLayout from "@/layout/PageLayout";
import {
  cashRegisterService,
  type CashMovementType,
  type CashRegisterSessionDto,
  type CashRegisterStatusDto,
} from "@/services/api/cashRegisterService";
import { companyService } from "@/services/api/companyService";
import { getStoredAuthUser } from "@/utils/authStorage";

const MANAGER_ROLES = ["administrador", "gerente"];

const PAYMENT_LABELS: Record<string, string> = {
  dinheiro: "Dinheiro",
  pix: "PIX",
  debito: "Cartão Débito",
  credito: "Cartão Crédito",
};

function paymentLabel(paymentType: string) {
  return PAYMENT_LABELS[paymentType.toLowerCase()] ?? paymentType;
}

function formatDateTime(value?: string | null) {
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
  });
}

function formatElapsed(minutes: number) {
  if (minutes < 1) return "menos de 1 min";
  const hours = Math.floor(minutes / 60);
  const remainingMinutes = minutes % 60;
  if (hours === 0) return `${remainingMinutes} min`;
  return `${hours}h ${String(remainingMinutes).padStart(2, "0")}min`;
}

function preventInvalidMoneyBeforeInput(event: FormEvent<HTMLInputElement>) {
  const data = (event.nativeEvent as InputEvent).data ?? "";
  if (data && /[^0-9,.]/.test(data)) {
    event.preventDefault();
  }
}

function hasNonZeroDifference(value?: string | null) {
  if (!value) return false;
  return value !== "0,00" && value !== "-0,00";
}

function SessionRow({
  session,
  onViewDetails,
}: {
  session: CashRegisterSessionDto;
  onViewDetails: (session: CashRegisterSessionDto) => void;
}) {
  const isOpen = session.status.toLowerCase() === "aberto";
  const diferenca = session.differenceAmount;
  const temDiferenca = hasNonZeroDifference(diferenca);
  return (
    <tr className="border-b border-border-primary">
      <td className="px-3 py-3">
        <span
          className={`inline-flex rounded-full border px-2.5 py-1 text-xs font-semibold ${
            isOpen
              ? "border-success/30 bg-success/15 text-success"
              : "border-secondary/30 bg-secondary/10 text-secondary"
          }`}
        >
          {session.status}
        </span>
      </td>
      <td className="px-3 py-3 text-text-primary">{formatDateTime(session.openedAt)}</td>
      <td className="px-3 py-3 text-text-secondary">{formatDateTime(session.closedAt)}</td>
      <td className="px-3 py-3 text-text-secondary">{session.operatorName || "-"}</td>
      <td className="px-3 py-3 text-right font-semibold text-text-primary">
        R$ {session.openingAmount || "0,00"}
      </td>
      <td className="px-3 py-3 text-right text-text-secondary">
        R$ {session.closingAmount || "0,00"}
      </td>
      <td className="px-3 py-3 text-right">
        {session.differenceAmount ? (
          <span className={temDiferenca ? "font-semibold text-danger" : "text-text-tertiary"}>
            R$ {session.differenceAmount}
          </span>
        ) : (
          <span className="text-text-tertiary">-</span>
        )}
      </td>
      <td className="px-3 py-3 text-right">
        <button
          type="button"
          onClick={() => onViewDetails(session)}
          className="inline-flex items-center gap-1 rounded-lg border border-border-secondary px-2.5 py-1 text-xs font-semibold text-text-secondary hover:bg-hover-light"
        >
          <Eye size={14} />
          Detalhes
        </button>
      </td>
    </tr>
  );
}

export default function CashRegisterPage() {
  const { maskMoneyBr, parseMoneyBr, formatMoneyBr } = useInputMasks();
  const statusDialog = useStatusDialog();
  const [cashStatus, setCashStatus] = useState<CashRegisterStatusDto | null>(null);
  const [companyName, setCompanyName] = useState("Quack PDV");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [openingAmount, setOpeningAmount] = useState("0,00");
  const [closingAmount, setClosingAmount] = useState("0,00");
  const [closingNote, setClosingNote] = useState("");
  const [differenceReason, setDifferenceReason] = useState("");
  const [movementModalType, setMovementModalType] = useState<CashMovementType | null>(null);
  const [closingSummary, setClosingSummary] = useState<CashRegisterSessionDto | null>(null);
  const [viewingSession, setViewingSession] = useState<CashRegisterSessionDto | null>(null);
  const [currentPage, setCurrentPage] = useState(1);
  const [itemsPerPage, setItemsPerPage] = useState(10);

  const currentSession = cashStatus?.currentSession ?? null;
  const canSell = cashStatus?.canSell === true;
  const hasOpenSession = currentSession !== null;

  const loggedUser = useMemo(() => getStoredAuthUser(), []);
  const isManager = loggedUser ? MANAGER_ROLES.includes(loggedUser.role.toLowerCase()) : false;
  const isResponsavelPeloCaixa =
    isManager || !currentSession || !loggedUser || currentSession.operatorId === loggedUser.id;

  const stateTone = useMemo(() => {
    if (canSell) return "border-success/30 bg-success/10 text-success";
    if (cashStatus?.state === "expirado") return "border-primary/30 bg-primary/10 text-primary";
    return "border-accent/30 bg-accent/10 text-accent";
  }, [canSell, cashStatus?.state]);
  const historyRows = useMemo(() => cashStatus?.history ?? [], [cashStatus?.history]);
  const totalPages = Math.max(1, Math.ceil(historyRows.length / itemsPerPage));
  const safeCurrentPage = Math.min(currentPage, totalPages);
  const paginatedHistoryRows = useMemo(() => {
    const start = (safeCurrentPage - 1) * itemsPerPage;
    return historyRows.slice(start, start + itemsPerPage);
  }, [historyRows, itemsPerPage, safeCurrentPage]);

  const expectedCash = currentSession?.expectedCashAmount || "0,00";
  const difference = useMemo(
    () => Math.round((parseMoneyBr(closingAmount) - parseMoneyBr(expectedCash)) * 100) / 100,
    [closingAmount, expectedCash, parseMoneyBr],
  );
  const hasDifference = hasOpenSession && difference !== 0;

  const loadStatus = useCallback(async () => {
    const status = await cashRegisterService.status();
    setCashStatus(status ?? null);
    setClosingAmount(status?.currentSession?.expectedCashAmount || "0,00");
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    loadStatus()
      .catch(() => {
        Toast.error("Não foi possível carregar o status do caixa.");
      })
      .finally(() => setLoading(false));
    companyService
      .get()
      .then((company) => {
        if (company?.fantasyName) setCompanyName(company.fantasyName);
      })
      .catch(() => {
        /* nome da empresa é só decorativo no resumo/impressão — sem empresa, mantém o padrão */
      });
  }, [loadStatus]);

  const updateOpeningAmount = (value: string) => setOpeningAmount(maskMoneyBr(value));
  const updateClosingAmount = (value: string) => setClosingAmount(maskMoneyBr(value));

  const pasteOpeningAmount = (event: ClipboardEvent<HTMLInputElement>) => {
    event.preventDefault();
    updateOpeningAmount(event.clipboardData.getData("text"));
  };

  const pasteClosingAmount = (event: ClipboardEvent<HTMLInputElement>) => {
    event.preventDefault();
    updateClosingAmount(event.clipboardData.getData("text"));
  };

  const openCashRegister = async () => {
    setSaving(true);
    try {
      const status = await cashRegisterService.open(openingAmount);
      setCashStatus(status ?? null);
      Toast.success("Caixa aberto. Frente de caixa liberada para venda.");
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Não foi possível abrir o caixa.");
    } finally {
      setSaving(false);
    }
  };

  const closeCashRegister = async () => {
    if (hasDifference && differenceReason.trim().length < 3) {
      Toast.error("Informe o motivo da diferença antes de fechar o caixa.");
      return;
    }

    const confirmed = await statusDialog.confirm("Fechar o caixa atual?");
    if (!confirmed) return;

    setSaving(true);
    try {
      const status = await cashRegisterService.close(
        closingAmount,
        closingNote,
        hasDifference ? differenceReason.trim() : undefined,
      );
      setCashStatus(status ?? null);
      setClosingNote("");
      setDifferenceReason("");
      if (status?.lastSession) {
        setClosingSummary(status.lastSession);
      }
      Toast.success("Caixa fechado. Vendas bloqueadas até nova abertura.");
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Não foi possível fechar o caixa.");
    } finally {
      setSaving(false);
    }
  };

  const registerMovement = async (tipo: CashMovementType, valor: string, motivo: string) => {
    try {
      const status = await cashRegisterService.registrarMovimento(tipo, valor, motivo);
      setCashStatus(status ?? null);
      setMovementModalType(null);
      Toast.success(tipo === "Sangria" ? "Sangria registrada." : "Reforço registrado.");
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Não foi possível registrar o movimento.");
    }
  };

  const refreshStatus = async () => {
    setLoading(true);
    try {
      await loadStatus();
      Toast.success("Status do caixa atualizado.");
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Não foi possível atualizar o caixa.");
    } finally {
      setLoading(false);
    }
  };

  if (loading && !cashStatus) {
    return (
      <div className="flex min-h-[360px] items-center justify-center text-text-secondary">
        <LoadingBar />
      </div>
    );
  }

  return (
    <PageLayout size="wide" className="space-y-4 py-4 md:space-y-6 md:py-6 lg:py-8">
      <PageHeader
        title="Abertura e Fechamento de Caixa"
        description="Controle operacional do caixa, com conferência por forma de pagamento e trilha de auditoria."
        action={
          <button
            type="button"
            onClick={refreshStatus}
            className="inline-flex items-center gap-2 rounded-xl border border-border-secondary px-4 py-2 text-sm font-semibold text-text-secondary transition hover:bg-hover-light hover:text-text-primary"
            disabled={loading || saving}
          >
            <RefreshCw size={16} className={loading ? "animate-spin" : ""} />
            Atualizar
          </button>
        }
      />

      <section className={`rounded-2xl border p-4 ${stateTone}`}>
        <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
          <div className="flex items-start gap-3">
            <span className="inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-bg-light/80">
              {canSell ? <UnlockKeyhole size={20} /> : <LockKeyhole size={20} />}
            </span>
            <div>
              <p className="text-sm font-semibold uppercase tracking-wide">
                {canSell ? "Caixa aberto para venda" : "Venda bloqueada"}
              </p>
              <h2 className="text-xl font-bold text-text-primary">
                {canSell
                  ? `Aberto por ${formatElapsed(currentSession?.elapsedMinutes ?? 0)}`
                  : cashStatus?.blockReason || "Abra o caixa para liberar o PDV."}
              </h2>
              {currentSession ? (
                <p className="mt-1 text-sm text-text-secondary">
                  Operador: {currentSession.operatorName || "-"} • Abertura:{" "}
                  {formatDateTime(currentSession.openedAt)}
                </p>
              ) : null}
              {currentSession && !isResponsavelPeloCaixa ? (
                <p className="mt-1 text-sm font-semibold text-accent">
                  Esse caixa foi aberto por {currentSession.operatorName} — só ela ou um gerente/administrador
                  podem fechá-lo ou lançar sangria/reforço. Você ainda pode vender normalmente.
                </p>
              ) : null}
            </div>
          </div>
        </div>
      </section>

      {hasOpenSession ? (
        <section className="grid gap-4 md:grid-cols-2">
          <div className="card rounded-2xl p-4">
            <div className="mb-3 flex items-center justify-between">
              <div className="flex items-center gap-2 text-text-primary">
                <Wallet size={16} />
                <h3 className="text-sm font-semibold">Vendas por forma de pagamento</h3>
              </div>
            </div>
            {currentSession.paymentBreakdown && currentSession.paymentBreakdown.length > 0 ? (
              <div className="space-y-1.5 text-sm">
                {currentSession.paymentBreakdown.map((item) => (
                  <div key={item.paymentType} className="flex justify-between">
                    <span className="text-text-secondary">{paymentLabel(item.paymentType)}</span>
                    <span className="font-semibold text-text-primary">R$ {item.total}</span>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-sm text-text-tertiary">Nenhuma venda registrada neste turno ainda.</p>
            )}
            <div className="mt-3 flex justify-between border-t border-border-primary pt-3 text-sm font-bold text-text-primary">
              <span>Dinheiro esperado na gaveta</span>
              <span>R$ {expectedCash}</span>
            </div>
          </div>

          <div className="card rounded-2xl p-4">
            <div className="mb-3 flex items-center justify-between">
              <h3 className="text-sm font-semibold text-text-primary">Sangrias e reforços</h3>
              {isResponsavelPeloCaixa ? (
                <div className="flex gap-2">
                  <button
                    type="button"
                    onClick={() => setMovementModalType("Reforco")}
                    className="inline-flex items-center gap-1 rounded-lg border border-success/30 bg-success/10 px-2.5 py-1 text-xs font-semibold text-success hover:bg-success/20"
                  >
                    <ArrowUpCircle size={14} />
                    Reforço
                  </button>
                  <button
                    type="button"
                    onClick={() => setMovementModalType("Sangria")}
                    className="inline-flex items-center gap-1 rounded-lg border border-danger/30 bg-danger/10 px-2.5 py-1 text-xs font-semibold text-danger hover:bg-danger/20"
                  >
                    <ArrowDownCircle size={14} />
                    Sangria
                  </button>
                </div>
              ) : null}
            </div>
            {currentSession.movimentos.length > 0 ? (
              <div className="max-h-40 space-y-2 overflow-y-auto pr-1">
                {currentSession.movimentos.map((item) => (
                  <div key={item.id} className="flex items-start justify-between text-sm">
                    <div>
                      <span className={item.tipo === "Sangria" ? "font-semibold text-danger" : "font-semibold text-success"}>
                        {item.tipo === "Sangria" ? "Sangria" : "Reforço"}
                      </span>
                      <p className="text-xs text-text-secondary">{item.motivo}</p>
                    </div>
                    <span className="font-semibold text-text-primary">R$ {item.valor}</span>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-sm text-text-tertiary">Nenhuma movimentação neste turno.</p>
            )}
          </div>
        </section>
      ) : null}

      <section className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_380px]">
        <div className="card rounded-2xl p-4">
          <div className="mb-4 flex items-center gap-2 text-text-primary">
            <Banknote size={18} />
            <h2 className="text-lg font-semibold">
              {hasOpenSession ? "Fechar caixa atual" : "Abrir caixa do dia"}
            </h2>
          </div>

          {hasOpenSession ? (
            <div className="space-y-4">
              <div className="grid gap-3 sm:grid-cols-3">
                <div className="rounded-xl border border-border-primary bg-bg-primary p-3">
                  <p className="text-xs text-text-secondary">Valor inicial</p>
                  <p className="mt-1 text-lg font-bold text-text-primary">
                    R$ {currentSession.openingAmount}
                  </p>
                </div>
                <div className="rounded-xl border border-border-primary bg-bg-primary p-3">
                  <p className="text-xs text-text-secondary">Tempo aberto</p>
                  <p className="mt-1 text-lg font-bold text-text-primary">
                    {formatElapsed(currentSession.elapsedMinutes)}
                  </p>
                </div>
                <div className="rounded-xl border border-border-primary bg-bg-primary p-3">
                  <p className="text-xs text-text-secondary">Status</p>
                  <p className="mt-1 text-lg font-bold text-text-primary">
                    {canSell ? "Regular" : "Exige fechamento"}
                  </p>
                </div>
              </div>

              {isResponsavelPeloCaixa ? (
                <>
                  <label className="block">
                    <span className="mb-1.5 block text-sm text-text-secondary">
                      Valor contado na gaveta (dinheiro)
                    </span>
                    <input
                      value={closingAmount}
                      inputMode="numeric"
                      pattern="[0-9,.]*"
                      onBeforeInput={preventInvalidMoneyBeforeInput}
                      onPaste={pasteClosingAmount}
                      onChange={(event) => updateClosingAmount(event.target.value)}
                      className="input-field w-full"
                      placeholder="0,00"
                    />
                    <span className="mt-1 block text-xs text-text-secondary">
                      Esperado: R$ {expectedCash} (fundo de troco + vendas em dinheiro + reforços - sangrias)
                    </span>
                  </label>

                  <div
                    className={`rounded-xl border p-3 text-sm ${
                      difference === 0
                        ? "border-success/30 bg-success/10 text-success"
                        : "border-danger/30 bg-danger/10 text-danger"
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
                      <span className="mb-1.5 block text-sm text-text-secondary">
                        Motivo da diferença *
                      </span>
                      <textarea
                        value={differenceReason}
                        onChange={(event) => setDifferenceReason(event.target.value)}
                        className="input-field min-h-20 w-full resize-y"
                        placeholder="Explique a sobra ou falta antes de fechar o caixa"
                      />
                    </label>
                  ) : null}

                  <label className="block">
                    <span className="mb-1.5 block text-sm text-text-secondary">Observação</span>
                    <textarea
                      value={closingNote}
                      onChange={(event) => setClosingNote(event.target.value)}
                      className="input-field min-h-24 w-full resize-y"
                      placeholder="Observação geral do fechamento (opcional)"
                    />
                  </label>

                  <LoadingButton
                    type="button"
                    onClick={closeCashRegister}
                    isLoading={saving}
                    loadingLabel="Fechando..."
                    disabled={hasDifference && differenceReason.trim().length < 3}
                    className="btn-primary inline-flex w-full items-center justify-center gap-2 disabled:cursor-not-allowed disabled:opacity-60 sm:w-auto"
                  >
                    <LockKeyhole size={16} />
                    Fechar caixa
                  </LoadingButton>
                </>
              ) : (
                <p className="rounded-xl border border-accent/30 bg-accent/10 p-3 text-sm text-accent">
                  Só {currentSession.operatorName || "quem abriu este caixa"} ou um gerente/administrador podem
                  fechá-lo. Você pode continuar vendendo normalmente.
                </p>
              )}
            </div>
          ) : (
            <div className="space-y-4">
              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">Valor inicial</span>
                <input
                  value={openingAmount}
                  inputMode="numeric"
                  pattern="[0-9,.]*"
                  onBeforeInput={preventInvalidMoneyBeforeInput}
                  onPaste={pasteOpeningAmount}
                  onChange={(event) => updateOpeningAmount(event.target.value)}
                  className="input-field w-full"
                  placeholder="0,00"
                />
              </label>

              <LoadingButton
                type="button"
                onClick={openCashRegister}
                isLoading={saving}
                loadingLabel="Abrindo..."
                className="btn-success inline-flex w-full items-center justify-center gap-2 sm:w-auto"
              >
                <UnlockKeyhole size={16} />
                Abrir caixa
              </LoadingButton>
            </div>
          )}
        </div>

        <aside className="card rounded-2xl p-4">
          <div className="mb-3 flex items-center gap-2 text-text-primary">
            <ShieldCheck size={18} />
            <h2 className="text-lg font-semibold">Regras aplicadas</h2>
          </div>
          <div className="space-y-3 text-sm text-text-secondary">
            <p>Venda só é confirmada se existir caixa aberto no dia.</p>
            <p>Caixa aberto por mais de 24 horas bloqueia novas vendas.</p>
            <p>Caixa vencido precisa ser fechado antes de uma nova abertura.</p>
            <p>Qualquer diferença entre o esperado e o contado exige justificativa para fechar.</p>
          </div>
          <div className="mt-4 rounded-xl border border-border-primary bg-bg-primary p-3 text-sm">
            <div className="flex items-center gap-2 text-text-primary">
              <Clock3 size={16} />
              <span className="font-semibold">Última movimentação</span>
            </div>
            <p className="mt-2 text-text-secondary">
              {cashStatus?.lastSession
                ? `${cashStatus.lastSession.status} em ${formatDateTime(
                    cashStatus.lastSession.closedAt || cashStatus.lastSession.openedAt,
                  )}`
                : "Nenhuma movimentação registrada."}
            </p>
          </div>
        </aside>
      </section>

      <section className="card overflow-hidden rounded-2xl">
        <div className="border-b border-border-primary px-4 py-3">
          <h2 className="text-lg font-semibold text-text-primary">Histórico de caixa</h2>
          <p className="text-sm text-text-secondary">Últimas aberturas e fechamentos, com diferença de conferência.</p>
        </div>
        <div className="overflow-x-auto">
          <table className="min-w-[960px] w-full text-sm">
            <thead className="bg-bg-gray-theme text-xs uppercase text-text-secondary">
              <tr>
                <th className="px-3 py-2 text-left">Status</th>
                <th className="px-3 py-2 text-left">Abertura</th>
                <th className="px-3 py-2 text-left">Fechamento</th>
                <th className="px-3 py-2 text-left">Operador</th>
                <th className="px-3 py-2 text-right">Inicial</th>
                <th className="px-3 py-2 text-right">Final</th>
                <th className="px-3 py-2 text-right">Diferença</th>
                <th className="px-3 py-2 text-right">Ações</th>
              </tr>
            </thead>
            <tbody>
              {paginatedHistoryRows.map((session) => (
                <SessionRow key={session.id} session={session} onViewDetails={setViewingSession} />
              ))}
            </tbody>
          </table>
        </div>
        <div className="px-4 py-4">
          <TablePagination
            totalItems={historyRows.length}
            currentPage={safeCurrentPage}
            itemsPerPage={itemsPerPage}
            onPageChange={setCurrentPage}
            onItemsPerPageChange={(value) => {
              setItemsPerPage(value);
              setCurrentPage(1);
            }}
          />
        </div>
      </section>

      {movementModalType ? (
        <CashMovementModal
          tipo={movementModalType}
          onClose={() => setMovementModalType(null)}
          onConfirm={(valor, motivo) => registerMovement(movementModalType, valor, motivo)}
        />
      ) : null}

      {closingSummary ? (
        <CashClosingSummaryModal
          session={closingSummary}
          companyName={companyName}
          onClose={() => setClosingSummary(null)}
        />
      ) : null}

      {viewingSession ? (
        <CashClosingSummaryModal
          session={viewingSession}
          companyName={companyName}
          onClose={() => setViewingSession(null)}
        />
      ) : null}

      {statusDialog.Dialog}
    </PageLayout>
  );
}
