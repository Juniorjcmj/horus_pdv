/**
 * Arquivo: src/components/SettingsPage/GatewayTokensCard.tsx
 * Objetivo: token por loja do Local Gateway (Gateway → Cloud). Admin/gerente gera, vê e revoga.
 *           O token em texto aparece UMA única vez (na geração) para ser colado no instalador do Gateway.
 */
import { Copy, KeyRound, ShieldOff } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { Toast, useStatusDialog } from "@/hooks/Dialog";
import { gatewayTokenService, type GatewayTokenInfo } from "@/services/api/gatewayTokenService";

function formatDate(value?: string | null) {
  if (!value) return "nunca";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("pt-BR");
}

export default function GatewayTokensCard() {
  const statusDialog = useStatusDialog();
  const [tokens, setTokens] = useState<GatewayTokenInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [nome, setNome] = useState("");
  const [generating, setGenerating] = useState(false);
  const [newToken, setNewToken] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setTokens(await gatewayTokenService.listar());
    } catch {
      /* offline / sem permissão: lista vazia */
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load();
  }, [load]);

  const handleGenerate = async () => {
    const name = nome.trim();
    if (!name) {
      Toast.error('Dê um nome ao token, ex.: "Loja Centro".');
      return;
    }
    setGenerating(true);
    try {
      const result = await gatewayTokenService.gerar(name);
      setNewToken(result.token);
      setNome("");
      await load();
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Não foi possível gerar o token.");
    } finally {
      setGenerating(false);
    }
  };

  const handleCopy = async () => {
    if (!newToken) return;
    try {
      await navigator.clipboard.writeText(newToken);
      Toast.success("Token copiado.");
    } catch {
      Toast.error("Não foi possível copiar: selecione o texto e copie manualmente.");
    }
  };

  const handleRevoke = async (token: GatewayTokenInfo) => {
    const confirmed = await statusDialog.confirm(
      `Revogar o token "${token.nome}"? O Gateway que usa esse token para de sincronizar com a nuvem até receber um token novo.`,
      { confirmLabel: "Revogar", cancelLabel: "Cancelar" },
    );
    if (!confirmed) return;
    try {
      await gatewayTokenService.revogar(token.id);
      Toast.success("Token revogado.");
      await load();
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Não foi possível revogar o token.");
    }
  };

  return (
    <div className="rounded-xl border border-border-primary bg-bg-primary p-4">
      <div className="flex gap-3">
        <span className="mt-0.5 inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-accent/10 text-accent">
          <KeyRound size={18} />
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-base font-semibold text-text-primary">Token do Gateway</p>
          <p className="mt-1 text-sm text-text-secondary">
            Chave que o Local Gateway da loja usa para enviar vendas e caixa à nuvem. Gere um token por loja e cole-o
            na instalação do Gateway. Ele aparece uma única vez.
          </p>

          <div className="mt-3 flex flex-col gap-2 sm:flex-row">
            <input
              value={nome}
              onChange={(event) => setNome(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter") void handleGenerate();
              }}
              maxLength={120}
              placeholder='Nome da loja, ex.: "Loja Centro"'
              className="input-field min-w-0 flex-1 text-sm"
            />
            <button
              type="button"
              onClick={() => void handleGenerate()}
              disabled={generating}
              className="btn-primary inline-flex shrink-0 items-center justify-center gap-2 whitespace-nowrap disabled:opacity-60"
            >
              <KeyRound size={15} />
              {generating ? "Gerando..." : "Gerar token"}
            </button>
          </div>

          {newToken ? (
            <div className="mt-3 rounded-xl border border-success/30 bg-success/10 p-3">
              <p className="text-sm font-semibold text-success">Token gerado. Copie agora: ele não será mostrado de novo.</p>
              <div className="mt-2 flex items-center gap-2">
                <code className="min-w-0 flex-1 select-all break-all rounded-lg border border-border-primary bg-bg-light px-2 py-1.5 font-mono text-xs text-text-primary">
                  {newToken}
                </code>
                <button
                  type="button"
                  onClick={() => void handleCopy()}
                  className="inline-flex shrink-0 items-center gap-1.5 rounded-lg border border-border-secondary px-3 py-1.5 text-xs font-semibold text-text-secondary hover:bg-hover-light"
                >
                  <Copy size={13} />
                  Copiar
                </button>
              </div>
              <button
                type="button"
                onClick={() => setNewToken(null)}
                className="mt-2 text-xs font-semibold text-text-secondary hover:underline"
              >
                Já copiei, esconder
              </button>
            </div>
          ) : null}

          <div className="mt-3 space-y-2">
            {loading ? (
              <p className="text-sm text-text-tertiary">Carregando tokens...</p>
            ) : tokens.length === 0 ? (
              <p className="text-sm text-text-tertiary">Nenhum token gerado ainda.</p>
            ) : (
              tokens.map((token) => (
                <div
                  key={token.id}
                  className={`flex flex-col gap-2 rounded-lg border border-border-primary p-2.5 text-sm sm:flex-row sm:items-center sm:justify-between ${
                    token.revokedAt ? "opacity-60" : ""
                  }`}
                >
                  <div className="min-w-0">
                    <p className="font-semibold text-text-primary">
                      {token.nome}{" "}
                      <span className="font-mono text-xs font-normal text-text-secondary">{token.tokenPrefix}…</span>
                    </p>
                    <p className="text-xs text-text-secondary">
                      Criado em {formatDate(token.createdAt)}
                      {token.createdBy ? ` por ${token.createdBy}` : ""} • Último uso: {formatDate(token.lastUsedAt)}
                    </p>
                  </div>
                  {token.revokedAt ? (
                    <span className="shrink-0 text-xs font-semibold text-primary">Revogado em {formatDate(token.revokedAt)}</span>
                  ) : (
                    <button
                      type="button"
                      onClick={() => void handleRevoke(token)}
                      className="inline-flex shrink-0 items-center gap-1.5 rounded-lg border border-primary/40 px-3 py-1.5 text-xs font-semibold text-primary hover:bg-primary/10"
                    >
                      <ShieldOff size={13} />
                      Revogar
                    </button>
                  )}
                </div>
              ))
            )}
          </div>
        </div>
      </div>
      {statusDialog.Dialog}
    </div>
  );
}
