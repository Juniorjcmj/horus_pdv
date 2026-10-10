import { AlertTriangle, ArrowLeft, FileCheck2, Loader2, RefreshCw } from "lucide-react";
import { useEffect, useState } from "react";
import PageHeader from "./PageHeader";
import { productService, type ProductFiscalReport } from "@/services/api/productService";

type Props = { product: { id: string; productName: string; ncm: string; cstIbsCbs: string | null; cClassTrib: string | null }; onBack: () => void; onEdit: () => void };
const focus = "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent";

export default function ProductFiscalPanel({ product, onBack, onEdit }: Props) {
  const [report, setReport] = useState<ProductFiscalReport | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let active = true;
    productService.verifyFiscal(product.id).then(data => {
      if (!data) throw new Error("A conferência não retornou dados. Atualize a API e tente novamente.");
      if (active) setReport(data);
    }).catch((reason: unknown) => {
      if (active) setError(reason instanceof Error ? reason.message : "Não foi possível conferir. Verifique a conexão e tente novamente.");
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [product.id, attempt]);
  const errors = report?.apontamentos.filter(item => item.nivel === "erro") ?? [];
  return (
    <section aria-label="Verificação fiscal do produto" className="space-y-5">
      <button type="button" onClick={onBack} className={`btn-outline-secondary inline-flex min-h-10 items-center gap-2 ${focus}`}><ArrowLeft size={16} />Voltar aos produtos</button>
      <PageHeader title="Verificação fiscal" description={product.productName} action={
        <div className="flex flex-wrap gap-2">
          <button type="button" onClick={() => { setLoading(true); setError(""); setReport(null); setAttempt(value => value + 1); }} disabled={loading} className={`btn-outline-secondary inline-flex min-h-10 items-center gap-2 disabled:opacity-50 ${focus}`}><RefreshCw size={16} />Conferir novamente</button>
          <button type="button" onClick={onEdit} className={`btn-primary min-h-10 ${focus}`}>Editar dados fiscais</button>
        </div>
      } />
      <p className="max-w-prose text-sm text-text-secondary">Conferência dos dados salvos para vendas internas por NFC-e no Rio de Janeiro. Nenhum dado do produto é alterado pela consulta.</p>
      {loading && <div role="status" className="flex items-center gap-2 py-8 text-text-secondary"><Loader2 size={20} className="animate-spin" />Consultando tabelas oficiais de NCM e IBS/CBS…</div>}
      {error && <div role="alert" className="rounded-xl border border-danger/40 bg-danger/10 p-4"><p className="text-text-primary">{error}</p><p className="mt-2 text-sm text-text-secondary">Tente novamente. A ferramenta precisa da API atualizada para funcionar.</p></div>}
      {report && <>
        <div role="status" className="flex items-start gap-3 rounded-xl border border-border-primary bg-bg-surface p-4 sm:p-5">
          {errors.length ? <AlertTriangle size={22} className="mt-0.5 shrink-0 text-danger" /> : <FileCheck2 size={22} className="mt-0.5 shrink-0 text-accent" />}
          <div><h2 className="text-lg font-semibold text-text-primary">{errors.length ? `${errors.length} ${errors.length === 1 ? "inconsistência encontrada" : "inconsistências encontradas"}` : "Sem inconsistências nas verificações automáticas"}</h2>
            <p className="mt-1 max-w-prose text-sm text-text-secondary">O enquadramento tributário ainda precisa de revisão contábil. Esta conferência não representa aprovação da SEFAZ.</p></div>
        </div>
        <div className="flex flex-wrap gap-x-6 gap-y-2 border-b border-border-primary pb-4 text-sm text-text-secondary">
          <span>UF: <strong className="text-text-primary">{report.uf}</strong></span><span>Regime: <strong className="text-text-primary">{({ 1: "Simples Nacional", 2: "Simples — excesso de sublimite", 3: "Normal", 4: "MEI" } as Record<number, string>)[report.crt] ?? "Não informado"}</strong></span>
          <span>Ambiente: <strong className="text-text-primary">{report.ambiente === 1 ? "Produção" : "Homologação"}</strong></span>
        </div>
        <div className="space-y-5">
          <div><h2 className="font-semibold text-text-primary">NCM {product.ncm || "não informado"}</h2><p className="mt-1 max-w-prose text-sm text-text-secondary">{report.descricaoNcm ?? "Descrição oficial não disponível para o código cadastrado."}</p></div>
          <div><h2 className="font-semibold text-text-primary">IBS/CBS · CST {product.cstIbsCbs || "não informado"} · cClassTrib {product.cClassTrib || "não informado"}</h2><p className="mt-1 max-w-prose text-sm text-text-secondary">{report.descricaoClassificacao ?? "Classificação oficial não disponível para o código cadastrado."}</p></div>
        </div>
        <div className="overflow-hidden rounded-xl border border-border-primary">
          <h2 className="bg-bg-secondary px-4 py-3 font-semibold text-text-primary">Resultado da conferência</h2>
          <ul className="divide-y divide-border-primary">{report.apontamentos.map((item, index) => <li key={`${item.campo}-${index}`} className="grid gap-1 px-4 py-3 sm:grid-cols-[11rem_1fr] sm:gap-4">
            <div><span className="block text-sm font-semibold text-text-primary">{item.campo}</span><span className={`text-xs font-medium ${item.nivel === "erro" ? "text-danger" : "text-text-secondary"}`}>{item.nivel === "erro" ? "Corrigir" : item.nivel === "aviso" ? "Conferir" : "Verificado"}</span></div>
            <p className="max-w-prose text-sm text-text-secondary">{item.mensagem}</p>
          </li>)}</ul>
        </div>
        <footer className="space-y-2 text-sm text-text-secondary">
          <p>{report.fontesOnline ? "Tabelas oficiais consultadas, com cache de até 24 horas." : "Consulta limitada: usando referência local ou última consulta disponível."} Base NCM: {report.dataBase}. Conferência: {new Date(report.verificadoEm).toLocaleString("pt-BR")}.</p>
          <p>Regras do emissor revisadas para 2026, conforme NT 2025.002 v1.52. Mudanças futuras de lei podem exigir atualização do sistema.</p>
          <div className="flex flex-wrap gap-x-5 gap-y-2"><a className={`underline underline-offset-4 ${focus}`} href="https://portalunico.siscomex.gov.br/classif/" target="_blank" rel="noreferrer">NCM oficial · Siscomex</a><a className={`underline underline-offset-4 ${focus}`} href="https://dfe-portal.svrs.rs.gov.br/CFF/ClassificacaoTributaria" target="_blank" rel="noreferrer">Classificações IBS/CBS · SVRS</a><a className={`underline underline-offset-4 ${focus}`} href="https://dfe-portal.svrs.rs.gov.br/Nfe/Documentos" target="_blank" rel="noreferrer">Notas técnicas oficiais</a></div>
        </footer>
      </>}
    </section>
  );
}
