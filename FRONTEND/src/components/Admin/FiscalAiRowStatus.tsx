import { Check, Eye, Loader2 } from "lucide-react";
import type { FiscalAiProductStatus } from "@/services/api/fiscalAiService";

const labels = { ok: "OK", corrigir: "Ajustes", revisao: "R — Revisão contábil", desatualizada: "Reanalisar", aguardando: "Na fila", analisando: "Analisando", falha: "Falha na análise" };
type Props = {
  name: string; summary?: FiscalAiProductStatus; activity?: { loading: boolean; progress: string; error: string };
  known: boolean; unavailable: boolean; configured: boolean; onAnalyze: () => void; onOpen: () => void;
};
export default function FiscalAiRowStatus({ name, summary, activity, known, unavailable, configured, onAnalyze, onOpen }: Props) {
  const busy = !!activity?.loading;
  const failed = !!activity?.error;
  const label = busy ? "Analisando…" : failed ? "Falha na consulta" : summary ? labels[summary.situacao] : known ? "Não analisado" : unavailable ? "Situação indisponível" : "Consultando…";
  const tone = busy || failed || !summary ? "text-text-secondary"
    : summary.situacao === "ok" ? "text-success" : summary.situacao === "revisao" ? "text-primary" : "text-text-secondary";
  return <div className="flex flex-col gap-1.5">
    <div className="flex min-h-11 items-center justify-between gap-2">
      <span className={`inline-flex items-center gap-1.5 text-xs font-semibold ${tone}`} title={summary?.mensagem}>
        {busy ? <Loader2 aria-hidden="true" size={16} className="animate-spin motion-reduce:animate-none" /> : summary?.situacao === "ok" && !failed ? <Check aria-hidden="true" size={16} /> : null}{label}
      </span>
      {summary?.analiseId && <button type="button" className="btn-outline-secondary inline-flex h-11 w-11 shrink-0 items-center justify-center p-0!" aria-label={`Ver análise de ${name}`} title="Ver relatório da análise" onClick={onOpen}><Eye aria-hidden="true" size={18} /></button>}
    </div>
    {busy ? <p role="status" className="text-xs leading-relaxed text-text-secondary">{activity?.progress}</p>
      : <button type="button" className="btn-outline-secondary inline-flex min-h-11 items-center justify-center whitespace-nowrap text-sm" disabled={!configured} aria-label={`${failed ? "Tentar análise novamente" : "Analisar com IA"} ${name}`} onClick={onAnalyze}>{failed || summary?.situacao === "falha" ? "Tentar novamente" : summary?.analiseId ? "Analisar novamente" : "Analisar com IA"}</button>}
    {summary?.analisadoEm && <p className="text-xs text-text-secondary">Última análise: {new Date(summary.analisadoEm).toLocaleString("pt-BR", { dateStyle: "short", timeStyle: "short" })}</p>}
    {activity?.error && <p role="alert" className="text-xs leading-relaxed text-danger">{activity.error}</p>}
    {!failed && !busy && summary?.situacao === "falha" && <p className="text-xs leading-relaxed text-danger">{summary.mensagem}</p>}
  </div>;
}
