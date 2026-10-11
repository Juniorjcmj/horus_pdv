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
    setAiProduct(product); setAiLoading(true); setAiError(""); setAiReport(null);
    try { setAiReport(await fiscalAiService.analyze(product.id)); }
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

  if (aiProduct) return <FiscalAiReviewPanel key={`${aiProduct.id}:${aiReport?.id ?? "loading"}`} name={`${aiProduct.productCode} · ${aiProduct.productName}`} report={aiReport} loading={aiLoading} error={aiError}
    onBack={() => { setAiProduct(null); setAiReport(null); }} onRetry={() => void analyze(aiProduct)} onApplied={reload} />;

  return <section className="space-y-5 pb-16" aria-label="Tributação dos produtos">
    <PageHeader title="Tributação dos produtos" description="Compare os dados fiscais cadastrados dos produtos em uma única tabela." action={
      <div className="flex flex-wrap gap-2"><button type="button" className="btn-outline-secondary inline-flex min-h-11 items-center gap-2" disabled={isLoading} onClick={() => void reload()}><RefreshCw size={16} />Atualizar</button>
        {role === "administrador" && <button type="button" className="btn-outline-secondary min-h-11" onClick={() => setSettingsOpen(value => !value)}>Configurar IA</button>}</div>
    } />
    {settingsOpen && <FiscalAiSettings config={config} onSaved={value => { setConfig(value); setConfigError(""); setSettingsOpen(false); }} onClose={() => setSettingsOpen(false)} />}
    {canAnalyze && <p className="max-w-prose text-sm text-text-secondary">{configError || (config?.configurada ? `IA configurada: Gemini 2.5 Flash-Lite${config.usarJev ? " com conferência Jev" : ""}. Use Analisar com IA na linha do produto.` : config ? "Cadastre a chave em Configurar IA para analisar os produtos. Somente o administrador pode configurar a chave." : "Consultando configuração de IA…")}</p>}
    <div className="flex flex-wrap items-end gap-4">
      <label className="min-w-0 flex-1 basis-72 text-sm text-text-secondary">Código ou descrição
        <input type="search" className="input-field mt-1 w-full" value={search} onChange={event => { setSearch(event.target.value); setPage(1); }} placeholder="Busque um produto" />
      </label>
      <label className="min-w-0 flex-1 basis-56 text-sm text-text-secondary">Filtrar por NCM
        <input type="text" inputMode="numeric" className="input-field mt-1 w-full" value={ncm} onChange={event => { setNcm(digits(event.target.value).slice(0, 8)); setPage(1); }} placeholder="Código completo ou primeiros dígitos" aria-describedby="ncm-filter-help" />
      </label>
      <button type="button" className="btn-outline-secondary min-h-11" onClick={clear} disabled={!hasFilters}>Limpar filtros</button>
    </div>
    <p id="ncm-filter-help" className="text-sm text-text-secondary">O filtro aceita NCM com ou sem pontos. “—” indica campo não informado; zero é exibido como zero. Esta tela consulta o cadastro e não valida o enquadramento fiscal.</p>
    <p role="status" className="text-sm text-text-secondary">{isLoading ? "Carregando produtos…" : error ? "Consulta indisponível" : `${filtered.length} de ${items.length} produtos encontrados`}</p>
    <TableScrollArea label="Tabela de tributação dos produtos" className="overflow-x-auto rounded-xl border border-border-primary">
      <table className="w-full min-w-[1600px] table-fixed text-left text-sm">
        <caption className="sr-only">Código, descrição e campos fiscais dos produtos cadastrados</caption>
        <thead className="bg-bg-secondary text-text-secondary"><tr>
          <th scope="col" className="sticky left-0 z-20 w-40 min-w-40 bg-bg-secondary px-4 py-3">Código</th>
          <th scope="col" className="w-64 min-w-64 bg-bg-secondary px-4 py-3 sm:sticky sm:left-40 sm:z-20">Descrição</th>
          {columns.map(([key, title]) => <th scope="col" key={key} className="whitespace-nowrap px-4 py-3">{title}</th>)}
          {canAnalyze && <th scope="col" className="w-44 px-4 py-3">Análise fiscal</th>}
        </tr></thead>
        <tbody className="divide-y divide-border-primary text-text-primary">
          {isLoading || error || !rows.length ? <tr><td colSpan={columns.length + 2 + (canAnalyze ? 1 : 0)}>
            <ListState loading={isLoading} error={Boolean(error)} title={isLoading ? "Carregando produtos…" : error ?? (hasFilters ? "Nenhum produto encontrado" : "Nenhum produto cadastrado")}
              description={error ? "Verifique a conexão e tente novamente." : hasFilters ? "Tente outro NCM ou outra descrição." : undefined}
              actionLabel={error ? "Tentar novamente" : hasFilters ? "Limpar filtros" : undefined}
              onAction={isLoading ? undefined : error ? () => void reload() : hasFilters ? clear : undefined} />
          </td></tr> : rows.map(product => <tr key={product.id}>
            <td className="sticky left-0 z-10 bg-bg-surface px-4 py-3 tabular-nums">{display(product.productCode)}</td>
            <th scope="row" className="bg-bg-surface px-4 py-3 font-medium sm:sticky sm:left-40 sm:z-10"><span className="block max-w-64 break-words">{display(product.productName)}</span></th>
            {columns.map(([key]) => <td key={key} className="whitespace-nowrap bg-bg-surface px-4 py-3 tabular-nums">{display(product[key])}</td>)}
            {canAnalyze && <td className="bg-bg-surface px-4 py-3"><button type="button" className="btn-outline-secondary min-h-11 text-sm" disabled={!config?.configurada || aiLoading} aria-label={`Analisar com IA ${product.productName}`} onClick={() => void analyze(product)}>Analisar com IA</button></td>}
          </tr>)}
        </tbody>
      </table>
    </TableScrollArea>
    {!isLoading && !error && filtered.length > 0 && <nav aria-label="Paginação de produtos" className="flex flex-wrap items-center justify-between gap-3" data-tour="table-pagination">
      <p className="text-sm text-text-secondary">Exibindo {start + 1}–{Math.min(start + pageSize, filtered.length)} de {filtered.length} · Página {currentPage} de {pages}</p>
      <div className="flex gap-2"><button type="button" className="btn-outline-secondary min-h-11" disabled={currentPage === 1} onClick={() => setPage(currentPage - 1)}>Anterior</button><button type="button" className="btn-outline-secondary min-h-11" disabled={currentPage === pages} onClick={() => setPage(currentPage + 1)}>Próxima</button></div>
    </nav>}
  </section>;
}
