/**
 * Arquivo: src/components/SettingsPage/DesktopGatewayCard.tsx
 * Objetivo: "Gateway deste computador" — só no programa desktop (1.1.0+), que traz o Quack Gateway embutido.
 *           Mostra se ele está rodando e conectado à nuvem; admin/gerente ativa (gera o token sozinho) ou desativa.
 */
import { ExternalLink, Power, PowerOff, RefreshCw, Server } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { Toast, useStatusDialog } from "@/hooks/Dialog";
import type { DesktopGatewayStatus } from "@/infrastructure/desktop/bridge";
import {
  activateEmbeddedGateway,
  deactivateEmbeddedGateway,
  getDesktopGateway,
  getEmbeddedCloudState,
  type EmbeddedCloudState,
} from "@/infrastructure/desktop/desktopGateway";

type Props = { canManage: boolean };

function describe(status: DesktopGatewayStatus | null, cloud: EmbeddedCloudState | null) {
  if (!status) return { tone: "text-text-secondary", text: "Consultando o programa..." };
  if (!status.bundled) return { tone: "text-highlight-strong", text: "Esta instalação do programa não traz o Gateway. Instale a versão mais nova." };
  if (!status.configured) return { tone: "text-text-secondary", text: "Desativado." };
  if (!status.running) return { tone: "text-primary", text: status.lastError || "Ativado, mas não está respondendo." };
  if (status.external) return { tone: "text-highlight-strong", text: `Usando outro Gateway já instalado neste computador (${status.url}).` };
  if (!cloud) return { tone: "text-highlight-strong", text: "Rodando. Verificando a conexão com a nuvem..." };
  if (cloud.companyMismatch) return { tone: "text-primary", text: "O token pertence a outra empresa. Desative e ative de novo." };
  if (!cloud.connected) {
    return { tone: "text-highlight-strong", text: `Rodando, sem conexão com a nuvem agora${cloud.lastError ? `: ${cloud.lastError}` : "."} As vendas ficam guardadas.` };
  }
  return { tone: "text-success", text: "Rodando e conectado à nuvem." };
}

export default function DesktopGatewayCard({ canManage }: Props) {
  const bridge = getDesktopGateway();
  const statusDialog = useStatusDialog();
  const [status, setStatus] = useState<DesktopGatewayStatus | null>(null);
  const [cloud, setCloud] = useState<EmbeddedCloudState | null>(null);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    if (!bridge) return;
    try {
      const next = await bridge.status();
      setStatus(next);
      setCloud(next.running ? await getEmbeddedCloudState(next.url) : null);
    } catch {
      setStatus(null);
    }
  }, [bridge]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void refresh();
    const timer = setInterval(() => void refresh(), 15_000);
    return () => clearInterval(timer);
  }, [refresh]);

  if (!bridge) return null;

  const handleActivate = async () => {
    setBusy(true);
    try {
      await activateEmbeddedGateway();
      Toast.success("Gateway ativado neste computador.");
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Não foi possível ativar o Gateway.");
    } finally {
      setBusy(false);
      void refresh();
    }
  };

  const handleDeactivate = async () => {
    const confirmed = await statusDialog.confirm(
      "Desativar o Gateway deste computador? Sem internet, as vendas continuam guardadas no PDV e são enviadas quando a internet voltar.",
      { confirmLabel: "Desativar", cancelLabel: "Cancelar" },
    );
    if (!confirmed) return;
    setBusy(true);
    try {
      await deactivateEmbeddedGateway();
      Toast.success("Gateway desativado.");
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Não foi possível desativar o Gateway.");
    } finally {
      setBusy(false);
      void refresh();
    }
  };

  const info = describe(status, cloud);
  const active = Boolean(status?.configured);

  return (
    <div className="rounded-xl border border-border-primary bg-bg-primary p-4">
      <div className="flex gap-3">
        <span className="mt-0.5 inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-accent/10 text-accent">
          <Server size={18} />
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-base font-semibold text-text-primary">Gateway deste computador</p>
          <p className="mt-1 text-sm text-text-secondary">
            O programa Quack PDV traz o Gateway da loja. Ativado, ele roda junto com o programa e guarda em disco as
            vendas e o caixa feitos sem internet, enviando à nuvem assim que ela voltar. Ideal para loja de um caixa.
          </p>

          <p className={`mt-3 text-sm font-semibold ${info.tone}`}>{info.text}</p>
          {status?.running ? (
            <p className="mt-0.5 font-mono text-xs text-text-tertiary">
              {status.url} • loja {status.storeId || "-"}
            </p>
          ) : null}

          <div className="mt-3 flex flex-wrap gap-2">
            {canManage && status?.bundled ? (
              <button
                type="button"
                onClick={() => void handleActivate()}
                disabled={busy}
                className="btn-primary inline-flex items-center justify-center gap-2 whitespace-nowrap disabled:opacity-60"
              >
                <Power size={15} />
                {busy ? "Aguarde..." : active ? "Gerar token novo e reiniciar" : "Ativar neste computador"}
              </button>
            ) : null}
            {canManage && active ? (
              <button
                type="button"
                onClick={() => void handleDeactivate()}
                disabled={busy}
                className="inline-flex items-center gap-1.5 rounded-lg border border-primary/40 px-3 py-1.5 text-xs font-semibold text-primary hover:bg-primary/10 disabled:opacity-60"
              >
                <PowerOff size={13} />
                Desativar
              </button>
            ) : null}
            {status?.running ? (
              <a
                href={`${status.url}/`}
                target="_blank"
                rel="noreferrer"
                className="inline-flex items-center gap-1.5 rounded-lg border border-border-secondary px-3 py-1.5 text-xs font-semibold text-text-secondary hover:bg-hover-light"
              >
                <ExternalLink size={13} />
                Painel do Gateway
              </a>
            ) : null}
            <button
              type="button"
              onClick={() => void refresh()}
              className="inline-flex items-center gap-1.5 rounded-lg border border-border-secondary px-3 py-1.5 text-xs font-semibold text-text-secondary hover:bg-hover-light"
            >
              <RefreshCw size={13} />
              Atualizar
            </button>
          </div>
          {!canManage && !active ? (
            <p className="mt-2 text-xs text-text-tertiary">Peça a um gerente para ativar.</p>
          ) : null}
        </div>
      </div>
      {statusDialog.Dialog}
    </div>
  );
}
