import { useState } from "react";
import { fiscalAiService, type FiscalAiConfig } from "@/services/api/fiscalAiService";

type Props = { config: FiscalAiConfig | null; onSaved: (config: FiscalAiConfig) => void; onClose: () => void };
export default function FiscalAiSettings({ config, onSaved, onClose }: Props) {
  const [key, setKey] = useState("");
  const [jev, setJev] = useState(config?.usarJev ?? true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const save = async (remove = false) => {
    setBusy(true); setError("");
    try {
      const result = await fiscalAiService.saveConfig(key, jev, remove);
      if (!result) throw new Error("Configuração não retornada. Tente novamente.");
      setKey(""); onSaved(result);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "Não foi possível salvar."); }
    finally { setBusy(false); }
  };
  return <section aria-label="Configurar IA fiscal" className="space-y-4 rounded-xl border border-border-primary bg-bg-surface p-5">
    <h2 className="text-lg font-semibold text-text-primary">Configurar IA fiscal</h2>
    <p className="max-w-prose text-sm text-text-secondary">Modelo econômico: Gemini 2.5 Flash-Lite. Cada análise envia ao OpenRouter a descrição, os códigos fiscais e o regime da empresa. A chave é protegida no servidor e não é devolvida para esta tela.</p>
    <label className="block max-w-xl text-sm text-text-secondary">Chave OpenRouter
      <input type="password" autoComplete="new-password" spellCheck={false} className="input-field mt-1 w-full" value={key} onChange={event => setKey(event.target.value)} placeholder={config?.configurada ? "Chave cadastrada — deixe vazio para manter" : "Cole sua chave sk-or-…"} disabled={busy} />
    </label>
    <label className="flex min-h-11 items-center gap-3 text-sm text-text-primary"><input type="checkbox" checked={jev} disabled={busy} onChange={event => setJev(event.target.checked)} />Usar Jev para conferir as evidências das sugestões</label>
    <p className="max-w-prose text-sm text-text-secondary">A conferência Jev faz uma segunda chamada cobrada pelo OpenRouter. Ela não substitui a revisão contábil. Se estiver indisponível, a análise poderá ser lida, mas as sugestões não poderão ser aplicadas.</p>
    {error && <p role="alert" className="text-danger">{error}</p>}
    <div className="flex flex-wrap gap-3">
      <button type="button" className="btn-primary min-h-11" disabled={busy || (!key.trim() && !config?.configurada)} onClick={() => void save()}>{busy ? "Salvando…" : "Salvar configuração"}</button>
      <button type="button" className="btn-outline-secondary min-h-11" disabled={busy} onClick={onClose}>Fechar configuração</button>
      {config?.configurada && <button type="button" className="btn-outline-secondary min-h-11" disabled={busy} onClick={() => void save(true)}>Remover chave</button>}
    </div>
  </section>;
}
