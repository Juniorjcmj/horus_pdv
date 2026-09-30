/**
 * Arquivo: src/components/SettingsPage/GatewayMonitorCard.tsx
 * Objetivo: exibe o monitoramento local do Gateway, indicadores de saúde em tempo real,
 *           link direto para o Dashboard embutido e verificação de atualização controlada (CHANGE GATEWAY 08).
 */
import { useEffect, useState } from "react";
import { Server, ShieldCheck, AlertTriangle, ExternalLink, RefreshCw } from "lucide-react";
import { getGatewayConfig } from "@/infrastructure/gateway/gatewayConfig";
import { gatewayClient, type GatewayDashboardSummary, type SafeUpdateCheckResult } from "@/infrastructure/gateway/gatewayClient";

export default function GatewayMonitorCard() {
  const config = getGatewayConfig();
  const [data, setData] = useState<GatewayDashboardSummary | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [updateCheck, setUpdateCheck] = useState<SafeUpdateCheckResult | null>(null);

  const fetchStatus = async () => {
    if (!config.url) {
      setData(null);
      setError("Gateway local não configurado neste terminal.");
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const summary = await gatewayClient.getDashboard();
      setData(summary);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Falha ao comunicar com o Gateway.");
      setData(null);
    } finally {
      setLoading(false);
    }
  };

  const handleCheckSafeUpdate = async () => {
    try {
      const result = await gatewayClient.checkSafeUpdate();
      setUpdateCheck(result);
    } catch (err) {
      alert("Erro ao verificar atualização: " + (err instanceof Error ? err.message : String(err)));
    }
  };

  useEffect(() => {
    if (config.url) {
      fetchStatus();
    }
  }, [config.url]);

  return (
    <div className="rounded-xl border border-border-primary bg-bg-primary p-4 space-y-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between sm:gap-4">
        <div className="flex gap-3">
          <span className="mt-0.5 inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-primary/10 text-primary">
            <Server size={18} />
          </span>
          <div>
            <div className="flex items-center gap-2">
              <p className="text-base font-semibold text-text-primary">Local Gateway (LAN)</p>
              {data && (
                <span className="inline-flex items-center gap-1 rounded-full bg-emerald-500/10 px-2 py-0.5 text-xs font-medium text-emerald-500">
                  <span className="h-1.5 w-1.5 rounded-full bg-emerald-500 animate-pulse" />
                  Operacional
                </span>
              )}
              {error && (
                <span className="inline-flex items-center gap-1 rounded-full bg-rose-500/10 px-2 py-0.5 text-xs font-medium text-rose-500">
                  Offline
                </span>
              )}
            </div>
            <p className="mt-1 text-sm text-text-secondary">
              Coordenador de rede local para contingência e troca de pedidos entre terminais sem internet.
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          {config.url && (
            <a
              href={`${config.url.replace(/\/$/, "")}/dashboard`}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-1.5 rounded-lg border border-border-primary bg-bg-secondary px-3 py-1.5 text-xs font-medium text-text-primary hover:bg-bg-light transition"
            >
              <ExternalLink size={14} />
              Abrir Dashboard
            </a>
          )}
          <button
            type="button"
            onClick={fetchStatus}
            disabled={loading}
            className="inline-flex items-center gap-1.5 rounded-lg border border-border-primary bg-bg-secondary px-3 py-1.5 text-xs font-medium text-text-primary hover:bg-bg-light transition disabled:opacity-50"
          >
            <RefreshCw size={14} className={loading ? "animate-spin" : ""} />
            Atualizar
          </button>
        </div>
      </div>

      {config.url ? (
        <div className="rounded-lg bg-bg-secondary p-3 text-xs text-text-secondary space-y-2">
          <div className="flex justify-between items-center">
            <span>URL Configurada: <strong className="text-text-primary">{config.url}</strong></span>
            <span>Terminal: <strong className="text-text-primary">{config.terminalId || "Não definido"}</strong></span>
          </div>

          {data && (
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 pt-2 border-t border-border-primary">
              <div className="p-2 rounded bg-bg-primary/50">
                <span className="text-text-secondary block">Cloud Sync</span>
                <strong className={`capitalize ${data.health.cloud === "online" ? "text-emerald-500" : "text-amber-500"}`}>
                  {data.health.cloud}
                </strong>
              </div>
              <div className="p-2 rounded bg-bg-primary/50">
                <span className="text-text-secondary block">Pendentes (Cloud)</span>
                <strong className={data.events.pendingCloud > 0 ? "text-rose-500" : "text-text-primary"}>
                  {data.events.pendingCloud} evento(s)
                </strong>
              </div>
              <div className="p-2 rounded bg-bg-primary/50">
                <span className="text-text-secondary block">Terminais LAN</span>
                <strong className="text-text-primary">
                  {data.terminals.online} / {data.terminals.total} online
                </strong>
              </div>
              <div className="p-2 rounded bg-bg-primary/50">
                <span className="text-text-secondary block">Pedidos Ativos</span>
                <strong className="text-text-primary">{data.activeOrdersCount}</strong>
              </div>
            </div>
          )}

          {data && (
            <div className="flex items-center justify-between pt-2 border-t border-border-primary">
              <div className="flex items-center gap-1.5">
                {data.updateReadiness.isSafe ? (
                  <ShieldCheck size={16} className="text-emerald-500" />
                ) : (
                  <AlertTriangle size={16} className="text-rose-500" />
                )}
                <span className={data.updateReadiness.isSafe ? "text-emerald-600 dark:text-emerald-400" : "text-rose-600 dark:text-rose-400"}>
                  {data.updateReadiness.message}
                </span>
              </div>
              <button
                type="button"
                onClick={handleCheckSafeUpdate}
                className="text-xs text-primary underline hover:text-primary/80 font-medium"
              >
                Verificar Atualização Controlada
              </button>
            </div>
          )}

          {updateCheck && (
            <div className={`mt-2 p-2.5 rounded border text-xs ${updateCheck.safe ? "border-emerald-500/40 bg-emerald-500/10 text-emerald-500" : "border-rose-500/40 bg-rose-500/10 text-rose-500"}`}>
              <strong>Resultado da Verificação:</strong> {updateCheck.message}
            </div>
          )}

          {error && (
            <div className="text-rose-500 pt-1">
              {error}
            </div>
          )}
        </div>
      ) : (
        <div className="rounded-lg bg-bg-secondary p-3 text-xs text-text-secondary">
          Nenhum Gateway configurado na rede local. Quando configurado em <code className="text-text-primary">localStorage</code> ou via descoberta automática, o monitoramento e contingência serão ativados.
        </div>
      )}
    </div>
  );
}
