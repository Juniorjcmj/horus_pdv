/**
 * Arquivo: src/components/SettingsPage/GatewaySettingsCard.tsx
 * Objetivo: cadastro do endereço LAN do Local Gateway (Quack Gateway) da empresa. A Cloud é a
 *           autoridade: o admin define aqui e os terminais aprendem automaticamente enquanto online,
 *           usando o endereço no fallback offline (zero config no terminal). Camada aditiva.
 */
import { useEffect, useState } from "react";
import { Download, FileText, Router } from "lucide-react";
import { YesNoSegmentedControl } from "@/components/Form";
import { Toast } from "@/hooks/Dialog";
import { gatewayConfigService } from "@/services/api/gatewayConfigService";

export default function GatewaySettingsCard() {
  const [gatewayUrl, setGatewayUrl] = useState("");
  const [enabled, setEnabled] = useState(true);
  const [updatedAt, setUpdatedAt] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    gatewayConfigService
      .get()
      .then((config) => {
        if (config) {
          setGatewayUrl(config.gatewayUrl);
          setEnabled(config.enabled);
          setUpdatedAt(config.updatedAt ?? null);
        }
      })
      .catch(() => {
        /* sem endereço ainda / offline — deixa em branco */
      })
      .finally(() => setLoading(false));
  }, []);

  const handleSave = async () => {
    const url = gatewayUrl.trim();
    if (!url) {
      Toast.error("Informe o endereço do Gateway.");
      return;
    }
    try {
      // Validação leve no cliente (o servidor revalida): precisa ser http(s)://host:porta
      const parsed = new URL(url);
      if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
        throw new Error("protocolo");
      }
    } catch {
      Toast.error("Endereço inválido. Use http(s)://host-ou-ip:porta (ex.: https://quack-gateway.local:5443).");
      return;
    }

    setSaving(true);
    try {
      const saved = await gatewayConfigService.save({ gatewayUrl: url, enabled });
      if (saved) {
        setUpdatedAt(saved.updatedAt ?? null);
      }
      Toast.success("Endereço do Gateway salvo. Os terminais aprenderão na próxima janela online.");
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao salvar o endereço do Gateway.");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="rounded-xl border border-border-primary bg-bg-primary p-4">
      <div className="flex gap-3">
        <span className="mt-0.5 inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-accent/10 text-accent">
          <Router size={18} />
        </span>
        <div className="flex-1">
          <p className="text-base font-semibold text-text-primary">Local Gateway (offline por loja)</p>
          <p className="mt-1 text-sm text-text-secondary">
            Endereço do Gateway na rede da loja. Os terminais aprendem este endereço automaticamente
            enquanto há internet e o utilizam quando a conexão cai — sem configurar cada terminal.
          </p>

          <div className="mt-4 flex flex-col gap-4 sm:flex-row sm:items-end">
            <label className="flex-1">
              <span className="mb-1 block text-sm text-text-secondary">Endereço do Gateway</span>
              <input
                type="text"
                className="input-field"
                placeholder="https://quack-gateway.local:5443"
                value={gatewayUrl}
                onChange={(e) => setGatewayUrl(e.target.value)}
                disabled={loading || saving}
                autoComplete="off"
                spellCheck={false}
              />
            </label>
            <div>
              <span className="mb-1 block text-sm text-text-secondary">Ativo</span>
              <YesNoSegmentedControl
                value={enabled}
                onChange={setEnabled}
                ariaLabel="Ativar uso do Gateway"
              />
            </div>
          </div>

          <div className="mt-4 flex items-center justify-between gap-3">
            <p className="text-xs text-text-tertiary">
              {updatedAt
                ? `Última atualização: ${new Date(updatedAt).toLocaleString("pt-BR")}`
                : "Nenhum endereço cadastrado ainda."}
            </p>
            <button
              type="button"
              className="btn-success"
              onClick={handleSave}
              disabled={loading || saving}
            >
              {saving ? "Salvando..." : "Salvar"}
            </button>
          </div>

          <p className="mt-3 text-xs text-text-tertiary">
            Dica: prefira um <strong>host</strong> com HTTPS (ex.: <code>https://quack-gateway.local:5443</code>)
            para evitar bloqueio de conteúdo misto no navegador. IP com HTTP funciona em redes que permitem.
          </p>

          <div className="mt-4 border-t border-border-secondary/50 pt-4">
            <p className="text-sm font-medium text-text-primary">Instalar o Gateway na loja</p>
            <p className="mt-1 text-xs text-text-tertiary">
              Baixe o instalador completo (já inclui o programa) e siga o checklist. Na máquina da loja:
              extrair e rodar <code>install-service.ps1 -SkipPublish</code> — sem instalar nada.
            </p>
            <div className="mt-3 flex flex-wrap gap-2">
              <a
                href="/gateway/quack-gateway-completo.zip"
                download
                className="inline-flex items-center gap-2 rounded-lg bg-accent px-3 py-2 text-sm font-semibold text-white shadow-sm transition hover:bg-hover-accent"
              >
                <Download size={16} /> Baixar instalador completo (.zip)
              </a>
              <a
                href="/gateway/checklist.html"
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center gap-2 rounded-lg border border-border-secondary px-3 py-2 text-sm font-semibold text-text-secondary transition hover:border-accent/40 hover:text-text-primary"
              >
                <FileText size={16} /> Ver / imprimir checklist
              </a>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
