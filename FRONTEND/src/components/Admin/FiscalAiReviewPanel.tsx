import { useState } from "react";
import { ArrowLeft, Loader2 } from "lucide-react";
import PageHeader from "./PageHeader";
import TableScrollArea from "./TableScrollArea";
import { fiscalAiService, type FiscalAiReport } from "@/services/api/fiscalAiService";

const labels: Record<string, string> = { ncm: "NCM", cest: "CEST", cfop: "CFOP", origemMercadoria: "Origem", csosnIcms: "CSOSN ICMS", cstIcms: "CST ICMS", aliquotaIcms: "ICMS (%)", cstPis: "CST PIS", cstCofins: "CST COFINS", cstIbsCbs: "CST IBS/CBS", cClassTrib: "cClassTrib" };
const display = (value: string | null) => value || "Não informado";
type Props = { name: string; report: FiscalAiReport | null; loading: boolean; error: string; onBack: () => void; onRetry: () => void; onApplied: () => Promise<void> };
export default function FiscalAiReviewPanel({ name, report, loading, error, onBack, onRetry, onApplied }: Props) {
  const [selected, setSelected] = useState<string[]>([]);
  const [reviewed, setReviewed] = useState(false);
  const [saving, setSaving] = useState(false);
  const [applyError, setApplyError] = useState("");
  const [applied, setApplied] = useState(false);
  const apply = async () => {
    if (!report || !reviewed || !selected.length) return;
    setSaving(true); setApplyError("");
    try {
      await fiscalAiService.apply(report.produtoId, report.id, selected);
      setApplied(true); setSelected([]); await onApplied();
    } catch (reason) { setApplyError(reason instanceof Error ? reason.message : "Não foi possível aplicar as correções."); }
    finally { setSaving(false); }
  };
  const cited = new Set(report?.sugestoes.flatMap(s => s.fontes) ?? []);
  return <section className="space-y-5 pb-16" aria-label="Análise fiscal com IA">
    <button type="button" className="btn-outline-secondary inline-flex min-h-11 items-center gap-2" disabled={loading || saving} onClick={onBack}><ArrowLeft size={16} />Voltar à tabela</button>
    <PageHeader title="Análise fiscal com IA" description={name} />
    {loading && <p role="status" className="flex items-center gap-2 text-text-secondary"><Loader2 size={20} className="animate-spin" />Analisando cadastro e referências fiscais…</p>}
    {error && <div role="alert" className="space-y-3"><p className="text-danger">{error}</p><button type="button" className="btn-outline-secondary min-h-11" onClick={onRetry}>Tentar análise novamente</button></div>}
    {report && <>
      <div className="space-y-2"><h2 className="text-lg font-semibold text-text-primary">Resultado da análise</h2><p className="max-w-prose text-text-primary">{report.resumo}</p>
        <p className="text-sm text-text-secondary">{report.modelo} · {new Date(report.analisadoEm).toLocaleString("pt-BR")} · UF {report.uf} · CRT {report.crt}</p>
        <p className="max-w-prose text-sm text-text-secondary">Jev: {report.situacaoJev}</p>
        <p className="text-sm text-text-secondary">{report.custoUsd === null ? "Custo total não informado pelo provedor." : `Custo informado: US$ ${report.custoUsd.toFixed(6)}`}</p>
      </div>
      {report.pendencias.length > 0 && <div><h2 className="font-semibold text-text-primary">Informações que precisam de conferência</h2><ul className="mt-2 list-disc space-y-2 pl-5 text-text-secondary">{report.pendencias.map((item, i) => <li key={i} className="max-w-prose">{item}</li>)}</ul></div>}
      <p className="max-w-prose text-sm text-text-secondary">Sugestões da IA precisam de revisão. A conferência Jev mede a sustentação nas referências fornecidas; não comprova que a tributação está correta. A consulta não altera notas já emitidas.</p>
      {report.sugestoes.length ? <TableScrollArea label="Sugestões de correção fiscal" className="overflow-x-auto rounded-xl border border-border-primary">
        <table className="w-full min-w-[900px] text-left text-sm"><thead className="bg-bg-secondary text-text-secondary"><tr>{["Selecionar", "Campo", "Atual", "Sugerido", "Justificativa e conferência"].map(title => <th key={title} scope="col" className="px-4 py-3">{title}</th>)}</tr></thead>
          <tbody className="divide-y divide-border-primary text-text-primary">{report.sugestoes.map(s => <tr key={s.campo}>
            <td className="px-4 py-3"><input type="checkbox" aria-label={`Selecionar correção de ${labels[s.campo] ?? s.campo}`} checked={selected.includes(s.campo)} disabled={!s.podeAplicar || saving || applied} onChange={event => setSelected(values => event.target.checked ? [...values, s.campo] : values.filter(v => v !== s.campo))} /></td>
            <th scope="row" className="whitespace-nowrap px-4 py-3 font-medium">{labels[s.campo] ?? s.campo}</th>
            <td className="px-4 py-3 tabular-nums">{display(s.atual)}</td><td className="px-4 py-3 tabular-nums">{display(s.sugerido)}</td>
            <td className="space-y-2 px-4 py-3"><p className="max-w-prose">{s.justificativa}</p>{s.probabilidadeJev !== null && <p className="text-xs text-text-secondary">Sustentação das evidências segundo Jev: {(s.probabilidadeJev * 100).toFixed(0)}%</p>}{s.bloqueio && <p className="text-sm text-danger">{s.bloqueio}</p>}</td>
          </tr>)}</tbody>
        </table>
      </TableScrollArea> : <p className="text-text-secondary">Nenhuma correção foi sugerida com os dados e referências disponíveis.</p>}
      {report.sugestoes.some(s => s.podeAplicar) && !applied && <div className="space-y-3">
        <label className="flex min-h-11 items-start gap-3 text-sm text-text-primary"><input className="mt-1" type="checkbox" checked={reviewed} disabled={saving} onChange={event => setReviewed(event.target.checked)} /><span className="max-w-prose">Revisei as sugestões selecionadas e confirmei o enquadramento aplicável ao produto e à empresa.</span></label>
        <button type="button" className="btn-primary min-h-11" disabled={saving || !reviewed || !selected.length} onClick={() => void apply()}>{saving ? "Aplicando…" : `Aplicar ${selected.length} ${selected.length === 1 ? "correção selecionada" : "correções selecionadas"}`}</button>
      </div>}
      {applied && <p role="status" className="text-text-primary">Correções aplicadas. A tabela foi atualizada e o antes/depois ficou registrado no histórico.</p>}
      {applyError && <p role="alert" className="text-danger">{applyError}</p>}
      {cited.size > 0 && <div><h2 className="font-semibold text-text-primary">Referências usadas nas sugestões</h2><ul className="mt-2 space-y-3 text-sm text-text-secondary">{report.fontes.filter(f => cited.has(f.id)).map(f => <li key={f.id}><a className="underline underline-offset-4" href={f.url} target="_blank" rel="noreferrer">{f.titulo}</a><p className="mt-1 max-w-prose">{f.conteudo}</p></li>)}</ul></div>}
    </>}
  </section>;
}
