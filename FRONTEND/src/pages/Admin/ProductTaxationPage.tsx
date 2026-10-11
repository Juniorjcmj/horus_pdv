import { useEffect, useMemo, useState } from "react";
import { RefreshCw } from "lucide-react";
import PageHeader from "@/components/Admin/PageHeader";
import ListState from "@/components/Admin/ListState";
import TableScrollArea from "@/components/Admin/TableScrollArea";
import useRemoteList from "@/hooks/useRemoteList";
import { productService, type ProductDto } from "@/services/api/productService";
import { fiscalAiService, type FiscalAiConfig, type FiscalAiReport } from "@/services/api/fiscalAiService";
import FiscalAiSettings from "@/components/Admin/FiscalAiSettings";
import FiscalAiReviewPanel from "@/components/Admin/FiscalAiReviewPanel";
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
const analysisColumnWidth = 176;

export default function ProductTaxationPage() {
  const { items, isLoading, error, reload } = useRemoteList(productService.list, "Não foi possível carregar a tributação dos produtos.");
  const [search, setSearch] = useState("");
  const [ncm, setNcm] = useState("");
  const [page, setPage] = useState(1);
  const role = getStoredAuthUser()?.role.toLowerCase();
  const canAnalyze = role === "administrador" || role === "gerente";
  const [config, setConfig] = useState<FiscalAiConfig | null>(null);
  const [configError, setConfigError] = useState("");
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [aiProduct, setAiProduct] = useState<ProductDto | null>(null);
  const [aiReport, setAiReport] = useState<FiscalAiReport | null>(null);
  const [aiLoading, setAiLoading] = useState(false);
  const [aiProgress, setAiProgress] = useState("");
  const [aiError, setAiError] = useState("");
  useEffect(() => {
    if (!canAnalyze) return;
    let active = true;
    fiscalAiService.config().then(result => {
      if (active) setConfig(result ?? null);
    }).catch(() => { if (active) setConfigError("Não foi possível consultar a configuração de IA. A tabela continua disponível."); });
    return () => { active = false; };
  }, [canAnalyze]);
  const analyze = async (product: ProductDto) => {
    setAiProduct(product); setAiLoading(true); setAiError(""); setAiReport(null); setAiProgress("Iniciando análise…");
    try { setAiReport(await fiscalAiService.analyze(product.id, setAiProgress)); }
    catch (reason) { setAiError(reason instanceof Error ? reason.message : "Não foi possível analisar o produto."); }
    finally { setAiLoading(false); }
  };
  const filtered = useMemo(() => {
    const term = search.trim().toLocaleLowerCase("pt-BR");
    return items.filter(product =>
      (!term || `${product.productCode ?? ""} ${product.productName ?? ""}`.toLocaleLowerCase("pt-BR").includes(term)) &&
      (!ncm || digits(product.ncm ?? "").startsWith(ncm)));
  }, [items, search, ncm]);
  const pages = Math.max(1, Math.ceil(filtered.length / pageSize));
  const currentPage = Math.min(page, pages);
  const start = (currentPage - 1) * pageSize;
  const rows = filtered.slice(start, start + pageSize);
  const hasFilters = Boolean(search || ncm);
  const clear = () => { setSearch(""); setNcm(""); setPage(1); };

  if (aiProduct) return <div className="p-4 sm:p-6 lg:p-8"><FiscalAiReviewPanel key={`${aiProduct.id}:${aiReport?.id ?? "loading"}`} name={`${aiProduct.productCode} · ${aiProduct.productName}`} report={aiReport} loading={aiLoading} progress={aiProgress} error={aiError}
    onBack={() => { setAiProduct(null); setAiReport(null); }} onRetry={() => void analyze(aiProduct)} onApplied={reload} /></div>;

  return <section className="flex min-w-0 flex-col gap-6 p-4 pb-16 sm:p-6 sm:pb-16 lg:p-8 lg:pb-16" aria-label="Tributação dos produtos">
    <div className="flex flex-col gap-3">
    <PageHeader className="mb-0!" title="Tributação dos produtos" description="Compare os dados fiscais cadastrados dos produtos em uma única tabela." action={
      <div className="flex flex-wrap gap-2"><button type="button" className="btn-outline-secondary inline-flex min-h-11 items-center gap-2" disabled={isLoading} onClick={() => void reload()}><RefreshCw size={16} />Atualizar</button>
        {role === "administrador" && <button type="button" className="btn-outline-secondary min-h-11" onClick={() => setSettingsOpen(value => !value)}>Configurar IA</button>}</div>
    } />
    {canAnalyze && <p className="max-w-3xl text-sm leading-relaxed text-text-secondary">{configError || (config?.configurada ? `IA configurada: Gemini 2.5 Flash-Lite${config.usarJev ? " com conferência Jev" : ""}. Use Analisar com IA na linha do produto.` : config ? "Cadastre a chave em Configurar IA para analisar os produtos. Somente o administrador pode configurar a chave." : "Consultando configuração de IA…")}</p>}
    </div>
    {settingsOpen && <FiscalAiSettings config={config} onSaved={value => { setConfig(value); setConfigError(""); setSettingsOpen(false); }} onClose={() => setSettingsOpen(false)} />}
    <div className="flex flex-col gap-3">
    <div className="grid grid-cols-1 items-end gap-4 sm:grid-cols-2 xl:grid-cols-[minmax(0,1fr)_minmax(16rem,24rem)_auto]">
      <label className="flex min-w-0 flex-col gap-2 text-sm text-text-secondary">Código ou descrição
        <input type="search" className="input-field h-11 w-full" value={search} onChange={event => { setSearch(event.target.value); setPage(1); }} placeholder="Busque um produto" />
      </label>
      <label className="flex min-w-0 flex-col gap-2 text-sm text-text-secondary">Filtrar por NCM
        <input type="text" inputMode="numeric" className="input-field h-11 w-full" value={ncm} onChange={event => { setNcm(digits(event.target.value).slice(0, 8)); setPage(1); }} placeholder="Código completo ou primeiros dígitos" aria-describedby="ncm-filter-help" />
      </label>
      <button type="button" className="btn-outline-secondary min-h-11 whitespace-nowrap sm:col-span-2 sm:justify-self-start xl:col-span-1" onClick={clear} disabled={!hasFilters}>Limpar filtros</button>
    </div>
    <p id="ncm-filter-help" className="max-w-5xl text-sm leading-relaxed text-text-secondary">O filtro aceita NCM com ou sem pontos. “—” indica campo não informado; zero é exibido como zero. Esta tela consulta o cadastro e não valida o enquadramento fiscal.</p>
    </div>
    <div className="flex flex-col gap-3">
    <p role="status" className="text-sm text-text-secondary">{isLoading ? "Carregando produtos…" : error ? "Consulta indisponível" : `${filtered.length} de ${items.length} produtos encontrados`}</p>
    <TableScrollArea label="Tabela de tributação dos produtos" className="overflow-x-auto rounded-xl border border-border-primary">
      <table className="w-full table-fixed text-left text-sm" style={{ minWidth: codeColumnWidth + descriptionColumnWidth + columns.length * taxColumnWidth + (canAnalyze ? analysisColumnWidth : 0) }}>
        <caption className="sr-only">Código, descrição e campos fiscais dos produtos cadastrados</caption>
        <colgroup>
          <col style={{ width: codeColumnWidth }} /><col style={{ width: descriptionColumnWidth }} />
          {columns.map(([key]) => <col key={key} style={{ width: taxColumnWidth }} />)}
          {canAnalyze && <col style={{ width: analysisColumnWidth }} />}
        </colgroup>
        <thead className="bg-bg-secondary text-text-secondary"><tr>
          <th scope="col" className="sticky left-0 z-20 bg-bg-secondary px-4 py-3">Código</th>
          <th scope="col" className="bg-bg-secondary px-4 py-3 sm:sticky sm:left-40 sm:z-20">Descrição</th>
          {columns.map(([key, title]) => <th scope="col" key={key} className="whitespace-nowrap px-4 py-3">{title}</th>)}
          {canAnalyze && <th scope="col" className="whitespace-nowrap px-4 py-3">Análise fiscal</th>}
        </tr></thead>
        <tbody className="divide-y divide-border-primary text-text-primary">
          {isLoading || error || !rows.length ? <tr><td colSpan={columns.length + 2 + (canAnalyze ? 1 : 0)}>
            <ListState loading={isLoading} error={Boolean(error)} title={isLoading ? "Carregando produtos…" : error ?? (hasFilters ? "Nenhum produto encontrado" : "Nenhum produto cadastrado")}
              description={error ? "Verifique a conexão e tente novamente." : hasFilters ? "Tente outro NCM ou outra descrição." : undefined}
              actionLabel={error ? "Tentar novamente" : hasFilters ? "Limpar filtros" : undefined}
              onAction={isLoading ? undefined : error ? () => void reload() : hasFilters ? clear : undefined} />
          </td></tr> : rows.map(product => <tr key={product.id}>
            <td className="sticky left-0 z-10 whitespace-nowrap bg-bg-surface px-4 py-2 tabular-nums">{display(product.productCode)}</td>
            <th scope="row" className="bg-bg-surface px-4 py-2 font-medium sm:sticky sm:left-40 sm:z-10"><span className="block break-words">{display(product.productName)}</span></th>
            {columns.map(([key]) => <td key={key} className="whitespace-nowrap bg-bg-surface px-4 py-2 tabular-nums">{display(product[key])}</td>)}
            {canAnalyze && <td className="bg-bg-surface px-4 py-2"><button type="button" className="btn-outline-secondary min-h-11 whitespace-nowrap text-sm" disabled={!config?.configurada || aiLoading} aria-label={`Analisar com IA ${product.productName}`} onClick={() => void analyze(product)}>Analisar com IA</button></td>}
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
