/**
 * Arquivo: src/components/Admin/OutboxStatusModal.tsx
 * Objetivo: modal administrativo e operacional para visualizar o estado da fila de sincronização
 *           (Outbox), inspecionar eventos recusados pela nuvem e disparar re-tentativas manuais. Fechamentos de
 *           caixa recusados (do PDV e do Local Gateway) mostram o motivo e, para gerente/administrador, permitem
 *           informar a justificativa e reenviar o mesmo evento.
 */
import { useEffect, useState } from "react";
import { AlertTriangle, CheckCircle2, Clock, RefreshCw, X, RotateCcw } from "lucide-react";
import type { OutboxEvent } from "@/shared/types/sync";
import { useOutboxStatus } from "@/hooks/useOutboxStatus";
import { Toast } from "@/hooks/Dialog";
import ImportPendingBackupButton from "@/components/Admin/ImportPendingBackupButton";
import { db } from "@/infrastructure/database/dexie";
import {
  currentTenantId,
  resolveRejectedCashClose,
} from "@/infrastructure/database/repositories/OutboxRepository";
import { gatewayClient, getGatewayConfig, type GatewayFailedEvent } from "@/infrastructure/gateway";
import { getStoredAuthUser } from "@/utils/authStorage";
import { MANAGER_ROLES } from "@/utils/cashRegisterFormat";
import { releaseFiscalPendingSales } from "@/utils/gatewayFiscalAttempt";

type OutboxStatusModalProps = {
  isOpen: boolean;
  onClose: () => void;
};

export default function OutboxStatusModal({ isOpen, onClose }: OutboxStatusModalProps) {
  const { pendingCount, failedCount, syncNow, retryAll, retryEvent, listFailed } = useOutboxStatus();
  const [failedList, setFailedList] = useState<OutboxEvent[]>([]);
  const [loading, setLoading] = useState(false);

  const [fiscalPendingCount, setFiscalPendingCount] = useState(0);
  const [gatewayRejected, setGatewayRejected] = useState<GatewayFailedEvent[]>([]);
  const [reasons, setReasons] = useState<Record<string, string>>({});

  const loggedUser = getStoredAuthUser();
  const isManager = loggedUser ? MANAGER_ROLES.includes(loggedUser.role.toLowerCase()) : false;

  const loadFailedEvents = async () => {
    try {
      const tenant = currentTenantId();
      setFiscalPendingCount(await db.outbox.where("status").equals("FISCAL_PENDING").filter(e => e.tenantId === tenant).count());
    } catch {
      setFiscalPendingCount(0);
    }
    try {
      const items = await listFailed();
      setFailedList(items);
    } catch {
      setFailedList([]);
    }
    // Fechamentos recusados que estão guardados no Local Gateway da loja (se houver um configurado).
    try {
      const config = getGatewayConfig();
      if (config.enabled && config.url) {
        const events = await gatewayClient.getFailedEvents();
        setGatewayRejected(events.filter((evt) => evt.eventType === "CASH_CLOSE"));
      } else {
        setGatewayRejected([]);
      }
    } catch {
      setGatewayRejected([]);
    }
  };

  useEffect(() => {
    if (isOpen) {
      void loadFailedEvents();
    }
  }, [isOpen, failedCount]);

  if (!isOpen) return null;

  const handleSyncNow = async () => {
    setLoading(true);
    try {
      await syncNow();
      Toast.success("Sincronização acionada.");
      await loadFailedEvents();
    } catch {
      Toast.error("Erro ao disparar sincronização.");
    } finally {
      setLoading(false);
    }
  };

  const handleRetryAll = async () => {
    setLoading(true);
    try {
      const count = await retryAll();
      Toast.success(`${count} evento(s) reenfileirado(s) para tentativa de envio.`);
      await loadFailedEvents();
    } catch {
      Toast.error("Erro ao reenfileirar eventos.");
    } finally {
      setLoading(false);
    }
  };

  const handleReleaseFiscalPending = async () => {
    setLoading(true);
    try {
      const count = await releaseFiscalPendingSales();
      Toast.success(`${count} venda(s) liberada(s) para envio. A nuvem decide a emissão da nota.`);
      await syncNow();
      await loadFailedEvents();
    } catch {
      Toast.error("Erro ao liberar vendas aguardando nota fiscal.");
    } finally {
      setLoading(false);
    }
  };

  const reasonOf = (key: string) => (reasons[key] ?? "").trim();

  const handleResolveLocal = async (id: string) => {
    const reason = reasonOf(`local-${id}`);
    if (reason.length < 3) {
      Toast.error("Informe a justificativa da diferença (mínimo 3 letras).");
      return;
    }
    setLoading(true);
    try {
      if (!(await resolveRejectedCashClose(id, reason))) {
        Toast.error("Esse fechamento não está mais recusado.");
      } else {
        Toast.success("Fechamento reenviado com a justificativa.");
        await syncNow();
      }
      await loadFailedEvents();
    } catch {
      Toast.error("Erro ao reenviar o fechamento.");
    } finally {
      setLoading(false);
    }
  };

  const handleResolveGateway = async (seq: number) => {
    const reason = reasonOf(`gw-${seq}`);
    if (reason.length < 3) {
      Toast.error("Informe a justificativa da diferença (mínimo 3 letras).");
      return;
    }
    setLoading(true);
    try {
      await gatewayClient.resolveFailedEvent(seq, reason);
      Toast.success("Fechamento reenviado ao Gateway com a justificativa.");
      await loadFailedEvents();
    } catch {
      Toast.error("Não foi possível reenviar o fechamento pelo Gateway.");
    } finally {
      setLoading(false);
    }
  };

  const handleRetrySingle = async (id: string) => {
    try {
      await retryEvent(id);
      Toast.success("Evento reenfileirado.");
      await loadFailedEvents();
    } catch {
      Toast.error("Erro ao reenfileirar evento.");
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4 backdrop-blur-xs">
      <div className="flex max-h-[90vh] w-full max-w-2xl flex-col rounded-2xl border border-border-primary bg-bg-primary text-text-primary shadow-2xl">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-border-secondary p-4 md:p-5">
          <div className="flex items-center gap-2.5">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-accent/10 text-accent">
              <RefreshCw size={20} className={loading ? "animate-spin" : ""} />
            </div>
            <div>
              <h3 className="text-lg font-bold">Fila de Sincronização (Outbox)</h3>
              <p className="text-xs text-text-secondary">
                Monitoramento de integridade e reenvio de operações offline
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg p-1.5 text-text-secondary transition hover:bg-hover-light hover:text-text-primary"
          >
            <X size={20} />
          </button>
        </div>

        {/* Content */}
        <div className="flex-1 space-y-4 overflow-y-auto p-4 md:p-5">
          {/* Status Cards */}
          <div className="grid grid-cols-2 gap-3">
            <div className="rounded-xl border border-yellow-500/30 bg-yellow-500/10 p-3.5">
              <div className="flex items-center gap-2 text-yellow-600 dark:text-yellow-400">
                <Clock size={16} />
                <span className="text-xs font-semibold uppercase tracking-wider">Pendentes</span>
              </div>
              <p className="mt-1 text-2xl font-bold text-yellow-700 dark:text-yellow-300">
                {pendingCount}
              </p>
              <p className="text-xs text-text-secondary">Aguardando envio ou em trânsito</p>
            </div>

            <div
              className={`rounded-xl border p-3.5 ${
                failedCount > 0
                  ? "border-rose-500/40 bg-rose-500/10 text-rose-700 dark:text-rose-300"
                  : "border-emerald-500/30 bg-emerald-500/10 text-emerald-700 dark:text-emerald-300"
              }`}
            >
              <div className="flex items-center gap-2">
                {failedCount > 0 ? <AlertTriangle size={16} /> : <CheckCircle2 size={16} />}
                <span className="text-xs font-semibold uppercase tracking-wider">Falhas Críticas</span>
              </div>
              <p className="mt-1 text-2xl font-bold">{failedCount}</p>
              <p className="text-xs text-text-secondary">
                {failedCount > 0 ? "Recusados pela nuvem — precisam de ação" : "Nenhum evento recusado"}
              </p>
            </div>
          </div>

          {fiscalPendingCount > 0 && (
            <div className="flex items-center justify-between gap-3 rounded-xl border border-yellow-500/30 bg-yellow-500/10 p-3.5 text-xs">
              <p className="text-text-primary">
                <strong>{fiscalPendingCount}</strong> venda(s) aguardando a nota de contingência do Gateway.
                Liberar envia à nuvem, que aplica as regras de emissão da empresa.
              </p>
              <button
                type="button"
                onClick={handleReleaseFiscalPending}
                disabled={loading}
                className="inline-flex shrink-0 items-center gap-1.5 rounded-lg bg-accent px-2.5 py-1 text-xs font-semibold text-white transition hover:bg-accent/90 disabled:opacity-50"
              >
                <RotateCcw size={13} />
                Liberar envio
              </button>
            </div>
          )}

          {/* Fechamentos de caixa recusados (PDV e Gateway): motivo + justificativa do gerente */}
          {(failedList.some((e) => e.eventType === "CASH_CLOSE") || gatewayRejected.length > 0) && (
            <div className="space-y-2" data-testid="rejected-closings">
              <h4 className="text-sm font-semibold text-rose-600 dark:text-rose-400">Fechamentos recusados</h4>
              {!isManager && (
                <p className="text-xs text-text-secondary">
                  Só gerente ou administrador pode informar a justificativa e reenviar o fechamento.
                </p>
              )}
              {[
                ...failedList
                  .filter((e) => e.eventType === "CASH_CLOSE")
                  .map((e) => ({
                    key: `local-${e.id}`,
                    title: e.aggregateId || e.id,
                    when: e.occurredAt,
                    error: e.lastError,
                    origin: "Este caixa",
                    resolve: () => handleResolveLocal(e.id),
                  })),
                ...gatewayRejected.map((e) => ({
                  key: `gw-${e.seq}`,
                  title: e.eventId,
                  when: e.occurredAt ?? e.createdAt,
                  error: e.lastError,
                  origin: "Gateway da loja",
                  resolve: () => handleResolveGateway(e.seq),
                })),
              ].map((item) => (
                <div key={item.key} className="space-y-2 rounded-xl border border-rose-500/20 bg-bg-gray-theme p-3 text-xs">
                  <div className="flex items-center gap-2">
                    <span className="rounded bg-rose-500/20 px-1.5 py-0.5 font-mono text-[10px] font-bold text-rose-700 dark:text-rose-300">
                      CASH_CLOSE
                    </span>
                    <span className="truncate font-mono text-text-secondary">{item.title}</span>
                    <span className="ml-auto text-[10px] text-text-tertiary">{item.origin}</span>
                  </div>
                  <p className="text-rose-600 dark:text-rose-400">{item.error || "Recusado pela nuvem."}</p>
                  {item.when && (
                    <p className="text-[10px] text-text-tertiary">
                      Fechado em: {new Date(item.when).toLocaleString("pt-BR")}
                    </p>
                  )}
                  {isManager && (
                    <div className="flex items-center gap-2">
                      <input
                        type="text"
                        value={reasons[item.key] ?? ""}
                        onChange={(e) => setReasons((prev) => ({ ...prev, [item.key]: e.target.value }))}
                        placeholder="Justificativa da diferença"
                        aria-label="Justificativa da diferença"
                        maxLength={300}
                        className="input-field min-h-11 flex-1"
                      />
                      <button
                        type="button"
                        onClick={() => void item.resolve()}
                        disabled={loading}
                        className="inline-flex min-h-11 shrink-0 items-center gap-1 rounded-lg bg-accent px-3 py-1 text-xs font-semibold text-white transition hover:bg-accent/90 disabled:opacity-50"
                      >
                        <RotateCcw size={13} />
                        Reenviar
                      </button>
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}

          {/* Failed events list */}
          {failedList.length > 0 ? (
            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <h4 className="text-sm font-semibold text-rose-600 dark:text-rose-400">
                  Eventos com Falha Requerendo Intervenção:
                </h4>
                <button
                  type="button"
                  onClick={handleRetryAll}
                  disabled={loading}
                  className="inline-flex items-center gap-1.5 rounded-lg border border-rose-500/40 bg-rose-500/20 px-2.5 py-1 text-xs font-semibold text-rose-700 transition hover:bg-rose-500/30 dark:text-rose-200"
                >
                  <RotateCcw size={13} />
                  Re-tentar Todos
                </button>
              </div>

              <div className="max-h-60 space-y-2 overflow-y-auto">
                {failedList.map((evt) => (
                  <div
                    key={evt.id}
                    className="flex items-start justify-between gap-3 rounded-xl border border-rose-500/20 bg-bg-gray-theme p-3 text-xs"
                  >
                    <div className="min-w-0 flex-1 space-y-1">
                      <div className="flex items-center gap-2">
                        <span className="rounded bg-rose-500/20 px-1.5 py-0.5 font-mono text-[10px] font-bold text-rose-700 dark:text-rose-300">
                          {evt.eventType}
                        </span>
                        <span className="truncate font-mono text-text-secondary">
                          {evt.aggregateId || evt.id}
                        </span>
                      </div>
                      <p className="text-rose-600 dark:text-rose-400">
                        {evt.lastError || "Recusado pela nuvem."}
                      </p>
                      <p className="text-[10px] text-text-tertiary">
                        Registrado em: {new Date(evt.occurredAt).toLocaleString("pt-BR")} • Tentativas:{" "}
                        {evt.retryCount}
                      </p>
                    </div>
                    <button
                      type="button"
                      onClick={() => handleRetrySingle(evt.id)}
                      className="inline-flex shrink-0 items-center gap-1 rounded-lg border border-border-secondary bg-bg-primary px-2 py-1 text-[11px] font-medium text-text-primary transition hover:bg-hover-light"
                    >
                      <RotateCcw size={12} />
                      Tentar
                    </button>
                  </div>
                ))}
              </div>
            </div>
          ) : (
            <div className="flex flex-col items-center justify-center rounded-xl border border-border-secondary bg-bg-gray-theme/50 p-6 text-center text-xs text-text-secondary">
              <CheckCircle2 size={24} className="mb-2 text-emerald-500" />
              <p className="font-semibold text-text-primary">Fila íntegra</p>
              <p>Não há eventos com falha crítica bloqueados no outbox.</p>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-between border-t border-border-secondary p-4 md:p-5">
          <button
            type="button"
            onClick={onClose}
            className="rounded-xl border border-border-secondary px-4 py-2 text-sm font-semibold text-text-secondary transition hover:bg-hover-light hover:text-text-primary"
          >
            Fechar
          </button>
          <div className="flex items-center gap-2">
            <ImportPendingBackupButton onImported={() => void loadFailedEvents()} />
            <button
              type="button"
              onClick={handleSyncNow}
              disabled={loading}
              className="inline-flex items-center gap-2 rounded-xl bg-accent px-4 py-2 text-sm font-semibold text-white shadow-sm transition hover:bg-accent/90 active:scale-95 disabled:opacity-50"
            >
              <RefreshCw size={15} className={loading ? "animate-spin" : ""} />
              Sincronizar Agora
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
