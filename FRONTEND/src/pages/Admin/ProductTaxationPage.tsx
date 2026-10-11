import { useEffect, useMemo, useRef, useState } from "react";
import { Loader2, RefreshCw } from "lucide-react";
import PageHeader from "@/components/Admin/PageHeader";
import ListState from "@/components/Admin/ListState";
import TableScrollArea from "@/components/Admin/TableScrollArea";
import useRemoteList from "@/hooks/useRemoteList";
import { productService, type ProductDto } from "@/services/api/productService";
import { fiscalAiService, type FiscalAiConfig, type FiscalAiReport } from "@/services/api/fiscalAiService";
import FiscalAiSettings from "@/components/Admin/FiscalAiSettings";
import FiscalAiReviewPanel from "@/components/Admin/FiscalAiReviewPanel";
import FiscalAiRowStatus from "@/components/Admin/FiscalAiRowStatus";
import useFiscalAiOverview from "@/hooks/useFiscalAiOverview";
import { getStoredAuthUser } from "@/utils/authStorage";

const columns = [
  ["ncm", "NCM"], ["cest", "CEST"], ["cfop", "CFOP"],
  ["origemMercadoria", "Origem"], ["csosnIcms", "CSOSN ICMS"],
  ["cstIcms", "CST ICMS"], ["aliquotaIcms", "ICMS (%)"],
  ["cstPis", "CST PIS"], ["cstCofins", "CST COFINS"],
  ["cstIbsCbs", "CST IBS/CBS"], ["cClassTrib", "cClassTrib"],
] as const satisfies ReadonlyArray<readonly [keyof ProductDto, string]>;
const digits = (value: string) => value.replace(/\D/g, "");
const display = (value: unknown) => value === null || value === undefined || String(value).trim() === "" ? "—" : String(value);
const pageSize = 50;
const codeColumnWidth = 160;
const descriptionColumnWidth = 288;
const taxColumnWidth = 128;
const analysisColumnWidth = 256;

export default function ProductTaxationPage() {
  const { items, isLoading, error, reload } = useRemoteList(productService.list, "Não foi possível carregar a tributação dos produtos.");
  const [search, setSearch] = useState("");
  const [ncm, setNcm] = useState("");
  const [situation, setSituation] = useState("");
  const [page, setPage] = useState(1);
  const role = getStoredAuthUser()?.role.toLowerCase();
  const canAnalyze = role === "administrador" || role === "gerente";
  const [config, setConfig] = useState<FiscalAiConfig | null>(null);
  const [configError, setConfigError] = useState("");
  const [settingsOpen, setSettingsOpen] = useState(false);
  const overview = useFiscalAiOverview(canAnalyze);
  const [batch, setBatch] = useState<{ running: boolean; ncm: string; total: number; done: number; message: string } | null>(null);
  const batchStop = useRef(false);
  const batchRunning = useRef(false);
  const batchEpoch = useRef(0);
  useEffect(() => () => { batchStop.current = true; batchEpoch.current++; }, []);
  const ncmProducts = useMemo(() => ncm.length === 8 ? items.filter(p => digits(p.ncm ?? "") === ncm) : [], [items, ncm]);
  const analyzeNcm = async () => {
    if (batchRunning.current || !config?.configurada || !ncmProducts.length) return;
    const selected = [...ncmProducts], selectedNcm = ncm, version = ++batchEpoch.current;
    batchRunning.current = true; batchStop.current = false;
    let done = 0, message = "Análise em andamento. Mantenha esta tela aberta para concluir o grupo.";
    setBatch({ running: true, ncm: selectedNcm, total: selected.length, done, message });
    try {
      for (const product of selected) {
        if (batchStop.current || version !== batchEpoch.current) break;
        const success = await overview.analyze(product.id);
        if (version !== batchEpoch.current) return;
        if (!success) { message = "Grupo interrompido após uma falha. Os resultados concluídos foram salvos; confira o produto com falha antes de retomar."; break; }
        done++;
        setBatch({ running: true, ncm: selectedNcm, total: selected.length, done, message });
      }
      if (done === selected.length) message = "Análise do grupo concluída. Confira OK, ajustes e R nas linhas dos produtos.";
      else if (batchStop.current) message = "Grupo interrompido. Os resultados concluídos foram salvos.";
    } finally {
      if (version === batchEpoch.current) {
        batchRunning.current = false;
        setBatch({ running: false, ncm: selectedNcm, total: selected.length, done, message });
      }
    }
  };
  const [review, setReview] = useState<{ product: ProductDto; report: FiscalAiReport | null; applied: boolean; loading: boolean; error: string } | null>(null);
  const reportRequest = useRef(0);
  useEffect(() => () => { reportRequest.current++; }, []);
  useEffect(() => {
    if (!canAnalyze) return;
    let active = true;
    fiscalAiService.config().then(result => {
      if (active) setConfig(result ?? null);
    }).catch(() => { if (active) setConfigError("Não foi possível consultar a configuração de IA. A tabela continua disponível."); });
    return () => { active = false; };
  }, [canAnalyze]);
  const openReport = async (product: ProductDto) => {
    const id = overview.summaries[product.id]?.analiseId;
    if (!id) return;
    const version = ++reportRequest.current;
    setReview({ product, report: null, applied: false, loading: true, error: "" });
    try {
      const saved = await fiscalAiService.report(id);
      if (saved.relatorio.produtoId !== product.id) throw new Error("O relatório não corresponde a este produto.");
      if (version === reportRequest.current) setReview({ product, report: saved.relatorio, applied: saved.aplicada, loading: false, error: "" });
    } catch (reason) {
      if (version === reportRequest.current) setReview({ product, report: null, applied: false, loading: false, error: reason instanceof Error ? reason.message : "Não foi possível abrir a análise salva." });
    }
  };
  const filtered = useMemo(() => {
    const term = search.trim().toLocaleLowerCase("pt-BR");
    return items.filter(product => {
      const summary = overview.summaries[product.id];
      const state = overview.activities[product.id]?.loading ? "andamento" : overview.activities[product.id]?.error ? "falha" : summary?.situacao;
      const matches = !situation || (situation === "nao_analisado" ? overview.known && !summary && !state
        : situation === "analisados" ? !!summary?.analiseId
        : situation === "andamento" ? state === "andamento" || state === "aguardando" || state === "analisando"
        : state === situation);
      return matches &&
      (!term || `${product.productCode ?? ""} ${product.productName ?? ""}`.toLocaleLowerCase("pt-BR").includes(term)) &&
      (!ncm || digits(product.ncm ?? "").startsWith(ncm));
    });
  }, [items, search, ncm, situation, overview.summaries, overview.activities, overview.known]);
  const pages = Math.max(1, Math.ceil(filtered.length / pageSize));
  const currentPage = Math.min(page, pages);
  const start = (currentPage - 1) * pageSize;
  const rows = filtered.slice(start, start + pageSize);
  const hasFilters = Boolean(search || ncm || situation);
  const clear = () => { setSearch(""); setNcm(""); setSituation(""); setPage(1); };
  const refresh = async () => { await Promise.all([reload(), overview.reload()]); };

  return <section className="flex min-w-0 flex-col gap-6 p-4 pb-16 sm:p-6 sm:pb-16 lg:p-8 lg:pb-16" aria-label="Tributação dos produtos">
    <div className="flex flex-col gap-3">
    <PageHeader className="mb-0!" title="Tributação dos produtos" description="Compare os dados fiscais cadastrados dos produtos em uma única tabela." action={
      <div className="flex flex-wrap gap-2"><button type="button" className="btn-outline-secondary inline-flex min-h-11 items-center gap-2" disabled={isLoading || overview.loading} onClick={() => void refresh()}><RefreshCw size={16} />Atualizar</button>
        {role === "administrador" && <button type="button" className="btn-outline-secondary min-h-11" onClick={() => setSettingsOpen(value => !value)}>Configurar IA</button>}</div>
    } />
    {canAnalyze && <p className="max-w-3xl text-sm leading-relaxed text-text-secondary">{configError || (config?.configurada ? `IA configurada: Gemini 2.5 Flash-Lite${config.usarJev ? " com conferência Jev" : ""}. Use Analisar com IA na linha do produto.` : config ? "Cadastre a chave em Configurar IA para analisar os produtos. Somente o administrador pode configurar a chave." : "Consultando configuração de IA…")}</p>}
    </div>
    {settingsOpen && <FiscalAiSettings config={config} onSaved={value => { setConfig(value); setConfigError(""); setSettingsOpen(false); }} onClose={() => setSettingsOpen(false)} />}
    <div className="flex flex-col gap-3">
    <div className={`grid grid-cols-1 items-end gap-4 sm:grid-cols-2 ${canAnalyze ? "2xl:grid-cols-[minmax(0,1fr)_minmax(12rem,20rem)_minmax(12rem,16rem)_auto]" : "xl:grid-cols-[minmax(0,1fr)_minmax(16rem,24rem)_auto]"}`}>
      <label className="flex min-w-0 flex-col gap-2 text-sm text-text-secondary">Código ou descrição
        <input type="search" className="input-field h-11 w-full" value={search} onChange={event => { setSearch(event.target.value); setPage(1); }} placeholder="Busque um produto" />
      </label>
      <label className="flex min-w-0 flex-col gap-2 text-sm text-text-secondary">Filtrar por NCM
        <input type="text" inputMode="numeric" className="input-field h-11 w-full" value={ncm} onChange={event => { setNcm(digits(event.target.value).slice(0, 8)); setPage(1); }} placeholder="Código completo ou primeiros dígitos" aria-describedby="ncm-filter-help" />
      </label>
      {canAnalyze && <label className="flex min-w-0 flex-col gap-2 text-sm text-text-secondary">Situação da análise
        <select className="input-field h-11 w-full" value={situation} onChange={event => { setSituation(event.target.value); setPage(1); }}>
          <option value="">Todas as situações</option><option value="nao_analisado">Não analisados</option><option value="analisados">Com análise concluída</option>
          <option value="ok">OK — Sem apontamentos</option><option value="revisao">R — Revisão contábil</option><option value="corrigir">Ajustes sugeridos</option>
          <option value="desatualizada">Precisam de nova análise</option><option value="andamento">Em andamento</option><option value="falha">Falha na análise</option>
        </select>
      </label>}
      <button type="button" className="btn-outline-secondary min-h-11 whitespace-nowrap justify-self-start" onClick={clear} disabled={!hasFilters}>Limpar filtros</button>
    </div>
    <p id="ncm-filter-help" className="max-w-5xl text-sm leading-relaxed text-text-secondary">O filtro aceita NCM com ou sem pontos. “—” indica campo não informado; zero é exibido como zero. Esta tela consulta o cadastro e não valida o enquadramento fiscal.</p>
    {canAnalyze && <p className="text-sm leading-relaxed text-text-secondary">OK: análise concluída sem apontamentos. R: revisão contábil necessária. Alterações posteriores no cadastro exigem nova análise.</p>}
    {canAnalyze && !!ncm && <div className="flex flex-wrap items-center gap-3">
      <button type="button" className="btn-outline-secondary min-h-11" disabled={ncm.length !== 8 || !ncmProducts.length || !config?.configurada || batch?.running || isLoading || !!error} onClick={() => void analyzeNcm()}>
        {ncm.length === 8 ? `Analisar com IA ${ncmProducts.length} produtos do NCM ${ncm}` : "Analisar NCM com IA"}
      </button>
      <p className="text-sm text-text-secondary">{ncm.length !== 8 ? "Informe os 8 dígitos do NCM para analisar o grupo." : "Inclui todos os produtos desse NCM, em todas as páginas, um por vez."}</p>
    </div>}
    {batch && <div className="flex flex-wrap items-center gap-3 rounded-xl border border-border-primary bg-bg-light p-4">
      <div role="status" className="min-w-0 flex-1 space-y-1 text-sm"><p className="flex items-center gap-2 font-medium text-text-primary">{batch.running && <Loader2 aria-hidden="true" size={18} className="animate-spin motion-reduce:animate-none" />}NCM {batch.ncm} · {batch.done} de {batch.total} análises concluídas</p><p className="text-text-secondary">{batch.message}</p></div>
      {batch.running && <button type="button" className="btn-outline-secondary min-h-11" onClick={() => { batchStop.current = true; setBatch(v => v && { ...v, message: "Parando após a análise atual. Os resultados serão preservados." }); }}>Parar após este produto</button>}
    </div>}
    {overview.error && <div role="alert" className="flex flex-wrap items-center gap-3 text-sm text-danger"><p>Não foi possível atualizar as situações salvas. {overview.error}</p><button type="button" className="btn-outline-secondary min-h-11" disabled={overview.loading} onClick={() => void overview.reload()}>Consultar situações novamente</button></div>}
    </div>
    {review && <FiscalAiReviewPanel key={`${review.product.id}:${review.report?.id ?? "loading"}`} embedded alreadyApplied={review.applied} name={`${review.product.productCode} · ${review.product.productName}`} report={review.report} loading={review.loading} error={review.error}
      onBack={() => { reportRequest.current++; setReview(null); }} onRetry={() => void openReport(review.product)} onApplied={refresh} />}
    <div className="flex flex-col gap-3">
    <p role="status" className="text-sm text-text-secondary">{isLoading ? "Carregando produtos…" : error ? "Consulta indisponível" : `${filtered.length} de ${items.length} produtos encontrados`}</p>
    <TableScrollArea label="Tabela de tributação dos produtos" className="overflow-x-auto rounded-xl border border-border-primary">
      <table className="w-full table-fixed text-left text-sm" style={{ minWidth: codeColumnWidth + descriptionColumnWidth + columns.length * taxColumnWidth + (canAnalyze ? analysisColumnWidth : 0) }}>
        <caption className="sr-only">Código, descrição e campos fiscais dos produtos cadastrados</caption>
        <colgroup>
          <col style={{ width: codeColumnWidth }} /><col style={{ width: descriptionColumnWidth }} />
          {canAnalyze && <col style={{ width: analysisColumnWidth }} />}
          {columns.map(([key]) => <col key={key} style={{ width: taxColumnWidth }} />)}
        </colgroup>
        <thead className="bg-bg-gray-theme text-text-secondary"><tr>
          <th scope="col" className="bg-bg-gray-theme px-4 py-3 sm:sticky sm:left-0 sm:z-20">Código</th>
          <th scope="col" className="bg-bg-gray-theme px-4 py-3 sm:sticky sm:left-40 sm:z-20">Descrição</th>
          {canAnalyze && <th scope="col" className="whitespace-nowrap px-4 py-3">Análise fiscal</th>}
          {columns.map(([key, title]) => <th scope="col" key={key} className="whitespace-nowrap px-4 py-3">{title}</th>)}
        </tr></thead>
        <tbody className="divide-y divide-border-primary text-text-primary">
          {isLoading || error || !rows.length ? <tr><td colSpan={columns.length + 2 + (canAnalyze ? 1 : 0)}>
            <ListState loading={isLoading} error={Boolean(error)} title={isLoading ? "Carregando produtos…" : error ?? (hasFilters ? "Nenhum produto encontrado" : "Nenhum produto cadastrado")}
              description={error ? "Verifique a conexão e tente novamente." : hasFilters ? "Tente outro NCM ou outra descrição." : undefined}
              actionLabel={error ? "Tentar novamente" : hasFilters ? "Limpar filtros" : undefined}
              onAction={isLoading ? undefined : error ? () => void reload() : hasFilters ? clear : undefined} />
          </td></tr> : rows.map(product => <tr key={product.id}>
            <td className="whitespace-nowrap bg-bg-light px-4 py-2 tabular-nums sm:sticky sm:left-0 sm:z-10">{display(product.productCode)}</td>
            <th scope="row" className="bg-bg-light px-4 py-2 font-medium sm:sticky sm:left-40 sm:z-10"><span className="block break-words">{display(product.productName)}</span></th>
            {canAnalyze && <td className="bg-bg-light px-4 py-2"><FiscalAiRowStatus name={product.productName} summary={overview.summaries[product.id]} activity={overview.activities[product.id]} known={overview.known} unavailable={!!overview.error} configured={!!config?.configurada && !batch?.running} onAnalyze={() => void overview.analyze(product.id)} onOpen={() => void openReport(product)} /></td>}
            {columns.map(([key]) => <td key={key} className="whitespace-nowrap bg-bg-light px-4 py-2 tabular-nums">{display(product[key])}</td>)}
          </tr>)}
        </tbody>
      </table>
    </TableScrollArea>
    </div>
    {!isLoading && !error && filtered.length > 0 && <nav aria-label="Paginação de produtos" className="flex flex-wrap items-center justify-between gap-3" data-tour="table-pagination">
      <p className="text-sm text-text-secondary">Exibindo {start + 1}–{Math.min(start + pageSize, filtered.length)} de {filtered.length} · Página {currentPage} de {pages}</p>
      <div className="flex gap-2"><button type="button" className="btn-outline-secondary min-h-11" disabled={currentPage === 1} onClick={() => setPage(currentPage - 1)}>Anterior</button><button type="button" className="btn-outline-secondary min-h-11" disabled={currentPage === pages} onClick={() => setPage(currentPage + 1)}>Próxima</button></div>
    </nav>}
  </section>;
}
