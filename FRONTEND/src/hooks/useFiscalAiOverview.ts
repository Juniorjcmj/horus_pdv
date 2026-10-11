import { useCallback, useEffect, useRef, useState } from "react";
import { fiscalAiService, type FiscalAiProductStatus, type FiscalAiReport } from "@/services/api/fiscalAiService";

type RowActivity = { loading: boolean; progress: string; error: string };
function completed(report: FiscalAiReport): FiscalAiProductStatus {
  const situation = report.errosCadastro == null ? "desatualizada"
    : report.pendencias.length || report.errosCadastro.length || report.sugestoes.some(s => !s.podeAplicar) ? "revisao"
    : report.sugestoes.length ? "corrigir" : "ok";
  return { produtoId: report.produtoId, situacao: situation, analiseId: report.id, analisadoEm: report.analisadoEm,
    solicitacaoId: null, mensagem: situation === "ok" ? "Sem apontamentos nesta análise." : "Abra o relatório para conferir os apontamentos.", aplicada: false };
}

export default function useFiscalAiOverview(enabled: boolean) {
  const [summaries, setSummaries] = useState<Record<string, FiscalAiProductStatus>>({});
  const [activities, setActivities] = useState<Record<string, RowActivity>>({});
  const [known, setKnown] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const epoch = useRef(0);
  const request = useRef(0);
  const revision = useRef(0);
  const modified = useRef(new Map<string, number>());
  const running = useRef(new Set<string>());
  const results = useRef(new Map<string, boolean>());
  const resumed = useRef(new Set<string>());
  const invalidate = useCallback(() => {
    epoch.current++; request.current++; running.current.clear(); resumed.current.clear();
  }, []);
  useEffect(() => {
    epoch.current++;
    return invalidate;
  }, [invalidate]);
  const reload = useCallback(async () => {
    if (!enabled) return;
    const generation = epoch.current, version = ++request.current;
    const baseline = revision.current;
    setLoading(true); setError("");
    try {
      const result = await fiscalAiService.overview();
      if (generation !== epoch.current || version !== request.current) return;
      setSummaries(previous => {
        const next = Object.fromEntries(result.map(s => [s.produtoId, s]));
        for (const [id, summary] of Object.entries(previous)) {
          if ((modified.current.get(id) ?? 0) > baseline) next[id] = summary;
        }
        return next;
      }); setKnown(true);
    } catch (reason) {
      if (generation === epoch.current && version === request.current)
        setError(reason instanceof Error ? reason.message : "Não foi possível consultar as análises salvas.");
    } finally { if (generation === epoch.current && version === request.current) setLoading(false); }
  }, [enabled]);
  useEffect(() => { void reload(); }, [reload]);
  const run = useCallback(async (id: string, jobId?: string) => {
    if (!enabled) return false;
    const generation = epoch.current;
    const active = () => generation === epoch.current;
    if (running.current.has(id)) {
      while (active() && running.current.has(id)) await new Promise(resolve => window.setTimeout(resolve, 250));
      return active() && results.current.get(id) === true;
    }
    running.current.add(id);
    results.current.delete(id);
    setActivities(v => ({ ...v, [id]: { loading: true, error: "", progress: jobId ? "Consultando análise em andamento…" : "Iniciando análise…" } }));
    const progress = (message: string) => { if (active()) setActivities(v => ({ ...v, [id]: { loading: true, error: "", progress: message } })); };
    try {
      const report = await (jobId ? fiscalAiService.watch(id, jobId, progress, active) : fiscalAiService.analyze(id, progress, active));
      if (!active()) return false;
      modified.current.set(id, ++revision.current);
      setSummaries(v => ({ ...v, [id]: completed(report) }));
      try {
        const summary = await fiscalAiService.status(id);
        if (!active()) return false;
        modified.current.set(id, ++revision.current);
        setSummaries(v => ({ ...v, [id]: summary }));
      } catch {
        // O relatório já está salvo; uma falha no resumo não é falha da análise.
        if (active()) setError("A análise foi salva, mas não foi possível conferir a situação atual do cadastro. Use Atualizar para consultar novamente.");
      }
      if (!active()) return false;
      results.current.set(id, true);
      return true;
    } catch (reason) {
      if (active()) results.current.set(id, false);
      if (active()) setActivities(v => ({ ...v, [id]: { loading: false, progress: "", error: reason instanceof Error ? reason.message : "Não foi possível analisar o produto." } }));
      return false;
    } finally {
      if (active()) {
        running.current.delete(id);
        setActivities(v => ({ ...v, [id]: { ...v[id], loading: false, progress: "" } }));
      }
    }
  }, [enabled]);
  useEffect(() => {
    for (const summary of Object.values(summaries)) {
      if (summary.solicitacaoId && ["aguardando", "analisando"].includes(summary.situacao) && !resumed.current.has(summary.solicitacaoId)) {
        resumed.current.add(summary.solicitacaoId);
        void run(summary.produtoId, summary.solicitacaoId);
      }
    }
  }, [summaries, run]);
  return { summaries, activities, known, loading, error, reload, analyze: (id: string) => run(id) };
}
