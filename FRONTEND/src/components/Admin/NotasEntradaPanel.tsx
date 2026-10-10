import { ArrowLeft, ChevronLeft, ChevronRight, Download, FileText, RefreshCw, Search } from "lucide-react";
import { useEffect, useState } from "react";
import { nfeImportService, type NotaEntradaDetalhe, type NotaEntradaPagina, type NotaEntradaResumo } from "@/services/api/nfeImportService";
import { Toast } from "@/hooks/Dialog";
import TableScrollArea from "@/components/Admin/TableScrollArea";

const money = (value: number) => value.toLocaleString("pt-BR", { style: "currency", currency: "BRL" });
const date = (value: string) => new Date(value).toLocaleString("pt-BR");
const title = (nota: NotaEntradaResumo) => nota.modelo ? `${nota.modelo === 65 ? "NFC-e" : "NF-e"} ${nota.numeroNota} · Série ${nota.serie}` : "Entrada sem documento enviado";

export default function NotasEntradaPanel() {
  const [input, setInput] = useState("");
  const [busca, setBusca] = useState("");
  const [pagina, setPagina] = useState(1);
  const [refresh, setRefresh] = useState(0);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [resultado, setResultado] = useState<NotaEntradaPagina | null>(null);
  const [detalhe, setDetalhe] = useState<NotaEntradaDetalhe | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [downloading, setDownloading] = useState(false);

  useEffect(() => {
    let active = true;
    // O indicador acompanha cada consulta externa, inclusive paginação e detalhes.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true); setError(null); setDetalhe(null);
    const request = selectedId ? nfeImportService.obterNota(selectedId) : nfeImportService.listarNotas(busca, pagina);
    request.then(data => {
      if (!active) return;
      if ("nota" in data) setDetalhe(data); else setResultado(data);
    }).catch(err => {
      if (active) setError(err instanceof Error ? err.message : "Não foi possível carregar as notas. Tente novamente.");
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [busca, pagina, refresh, selectedId]);

  const download = async (nota: NotaEntradaResumo) => {
    setDownloading(true);
    try { await nfeImportService.baixarXml(nota); }
    catch (err) { Toast.error(err instanceof Error ? err.message : "Não foi possível baixar o XML. Tente novamente."); }
    finally { setDownloading(false); }
  };

  return <section className="space-y-5" aria-labelledby="notas-entrada-heading">
    <div className="flex flex-wrap items-start justify-between gap-3">
      <div>
        <h2 id="notas-entrada-heading" className="text-xl font-semibold text-text-primary">Notas de entrada</h2>
        <p className="mt-1 max-w-2xl text-sm text-text-secondary">Consulte as notas usadas nas entradas de mercadorias e os itens recebidos.</p>
      </div>
      {selectedId ? <button className="btn-outline-secondary inline-flex min-h-10 items-center justify-center gap-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent disabled:cursor-not-allowed disabled:opacity-50" type="button" onClick={() => setSelectedId(null)}><ArrowLeft size={16} /> Voltar às notas</button>
        : <button className="btn-outline-secondary inline-flex min-h-10 items-center justify-center gap-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent disabled:cursor-not-allowed disabled:opacity-50" type="button" disabled={loading} onClick={() => setRefresh(value => value + 1)}><RefreshCw size={16} /> Atualizar</button>}
    </div>

    {!selectedId && <form className="flex flex-wrap items-end gap-2" onSubmit={event => { event.preventDefault(); setPagina(1); setBusca(input.trim()); }}>
      <div className="min-w-0 flex-1">
        <label htmlFor="busca-notas-entrada" className="mb-1 block text-sm text-text-secondary">Fornecedor, número ou chave da nota</label>
        <input id="busca-notas-entrada" className="input-field w-full" value={input} onChange={event => setInput(event.target.value)} maxLength={100} placeholder="Digite para consultar" />
      </div>
      <button className="btn-primary inline-flex min-h-10 items-center justify-center gap-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent" type="submit"><Search size={16} /> Buscar</button>
    </form>}

    {loading && <p role="status" className="py-8 text-sm text-text-secondary">Carregando {selectedId ? "os detalhes da nota" : "as notas de entrada"}…</p>}
    {error && <div role="alert" className="space-y-3 py-5"><p className="text-sm text-red-700 dark:text-red-300">{error}</p><button type="button" className="btn-outline-secondary inline-flex min-h-10 items-center justify-center gap-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent disabled:cursor-not-allowed disabled:opacity-50" onClick={() => setRefresh(value => value + 1)}>Tentar novamente</button></div>}

    {!loading && !error && !selectedId && resultado && <>
      {resultado.notas.length === 0 ? <div className="py-10 text-center"><FileText className="mx-auto mb-3 text-text-secondary" size={28} /><p className="font-medium text-text-primary">{busca ? "Nenhuma nota encontrada" : "Ainda não há notas de entrada armazenadas"}</p><p className="mt-2 text-sm text-text-secondary">{busca ? "Confira o número, a chave ou o nome do fornecedor." : "As próximas entradas por nota serão registradas automaticamente. Notas de entradas antigas precisam ser recuperadas separadamente."}</p></div>
        : <TableScrollArea label="Notas de entrada"><table className="min-w-[800px] w-full text-left text-sm">
          <thead className="border-b border-border-primary text-text-secondary"><tr>{["Entrada", "Nota", "Fornecedor", "Valor da nota", "Documento", ""].map(label => <th key={label} className="px-3 py-3 font-medium">{label}</th>)}</tr></thead>
          <tbody className="divide-y divide-border-primary">{resultado.notas.map(nota => <tr key={nota.id} className="text-text-primary hover:bg-hover-light">
            <td className="whitespace-nowrap px-3 py-4 tabular-nums">{date(nota.criadaEm)}</td>
            <td className="px-3 py-4">{title(nota)}<span className="mt-1 block text-xs text-text-secondary">{nota.quantidadeItens} {nota.quantidadeItens === 1 ? "item" : "itens"}</span></td>
            <td className="px-3 py-4"><span className="block max-w-64 break-words font-medium">{nota.fornecedorNome}</span><span className="mt-1 block text-xs text-text-secondary">{nota.fornecedorCnpj}</span></td>
            <td className="whitespace-nowrap px-3 py-4 tabular-nums">{nota.valorNota === null ? "Não informado" : money(nota.valorNota)}</td>
            <td className="px-3 py-4 text-text-secondary">{nota.temXml ? "XML armazenado" : nota.origem === "digitada" ? "Cupom digitado · sem XML" : "Documento não enviado"}</td>
            <td className="px-3 py-4"><button type="button" className="btn-outline-secondary inline-flex min-h-10 items-center justify-center gap-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent disabled:cursor-not-allowed disabled:opacity-50 whitespace-nowrap" onClick={() => setSelectedId(nota.id)} aria-label={`Ver ${title(nota)}`}>Ver entrada</button></td>
          </tr>)}</tbody>
        </table></TableScrollArea>}
      <div className="flex flex-wrap items-center justify-between gap-3 border-t border-border-primary pt-4 text-sm text-text-secondary">
        <p>{resultado.total} {resultado.total === 1 ? "nota" : "notas"} · Página {pagina} de {Math.max(1, Math.ceil(resultado.total / resultado.tamanhoPagina))}</p>
        <div className="flex gap-2"><button type="button" className="btn-outline-secondary inline-flex min-h-10 items-center justify-center gap-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent disabled:cursor-not-allowed disabled:opacity-50" aria-label="Página anterior" disabled={pagina === 1} onClick={() => setPagina(value => value - 1)}><ChevronLeft size={16} /></button><button type="button" className="btn-outline-secondary inline-flex min-h-10 items-center justify-center gap-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent disabled:cursor-not-allowed disabled:opacity-50" aria-label="Próxima página" disabled={pagina * resultado.tamanhoPagina >= resultado.total} onClick={() => setPagina(value => value + 1)}><ChevronRight size={16} /></button></div>
      </div>
    </>}

    {!loading && !error && detalhe && <div className="space-y-5">
      <div className="flex flex-wrap items-start justify-between gap-4 border-y border-border-primary py-5">
        <div><h3 className="font-semibold text-text-primary">{title(detalhe.nota)}</h3><p className="mt-1 text-sm text-text-secondary">{detalhe.nota.fornecedorNome} · CNPJ {detalhe.nota.fornecedorCnpj}</p></div>
        {detalhe.nota.temXml && <button type="button" className="btn-primary inline-flex min-h-10 items-center justify-center gap-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent" disabled={downloading} onClick={() => void download(detalhe.nota)}><Download size={16} />{downloading ? "Baixando…" : "Baixar XML original"}</button>}
      </div>
      <dl className="grid gap-x-8 gap-y-4 text-sm sm:grid-cols-2 lg:grid-cols-3">
        <div><dt className="text-text-secondary">Entrada no sistema</dt><dd className="mt-1 text-text-primary">{date(detalhe.nota.criadaEm)}{detalhe.nota.usuarioNome && ` · ${detalhe.nota.usuarioNome}`}</dd></div>
        <div><dt className="text-text-secondary">Emissão da nota</dt><dd className="mt-1 text-text-primary">{detalhe.nota.dataEmissao ? date(detalhe.nota.dataEmissao) : "Não informada"}</dd></div>
        <div><dt className="text-text-secondary">Valor total da nota</dt><dd className="mt-1 text-text-primary tabular-nums">{detalhe.nota.valorNota === null ? "Não informado" : money(detalhe.nota.valorNota)}</dd></div>
        <div><dt className="text-text-secondary">Custo dos itens recebidos</dt><dd className="mt-1 text-text-primary tabular-nums">{money(detalhe.nota.valorEntrada)}</dd></div>
        {detalhe.nota.chaveAcesso && <div className="sm:col-span-2"><dt className="text-text-secondary">Chave de acesso</dt><dd className="mt-1 break-all text-text-primary tabular-nums">{detalhe.nota.chaveAcesso}</dd></div>}
      </dl>
      {!detalhe.nota.temXml && <p className="text-sm text-text-secondary">{detalhe.nota.origem === "digitada" ? "Esta entrada foi digitada a partir do cupom. A chave e os itens foram armazenados; o XML não foi fornecido." : "Esta versão do aplicativo não enviou o documento fiscal. O registro conserva os dados da entrada, sem XML."}</p>}
      <div><h3 className="mb-2 font-semibold text-text-primary">Itens recebidos</h3><p className="mb-3 text-sm text-text-secondary">Quantidades e custos confirmados na entrada, após os ajustes de embalagem e vínculo dos produtos.</p>
        <TableScrollArea label="Itens recebidos"><table className="min-w-[650px] w-full text-left text-sm"><thead className="border-b border-border-primary text-text-secondary"><tr>{["Código", "Produto", "Quantidade", "Custo unitário", "Lote / validade"].map(label => <th key={label} className="px-3 py-3 font-medium">{label}</th>)}</tr></thead><tbody className="divide-y divide-border-primary text-text-primary">{detalhe.entrada.itens.map((item, index) => <tr key={`${item.numeroItem}-${index}`}><td className="px-3 py-3">{item.productCode}</td><td className="px-3 py-3">{item.productName}</td><td className="px-3 py-3 tabular-nums">{item.quantidade} {item.unidadeComercial}</td><td className="px-3 py-3 tabular-nums">R$ {item.precoCusto}</td><td className="px-3 py-3">{item.numeroLote || "—"}{item.dataValidade && ` · ${item.dataValidade.split("-").reverse().join("/")}`}</td></tr>)}</tbody></table></TableScrollArea>
      </div>
    </div>}
  </section>;
}
