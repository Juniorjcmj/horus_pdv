import { useMemo, useState } from "react";
import { ArrowLeft, RefreshCw } from "lucide-react";
import PageHeader from "../PageHeader";
import ListState from "../ListState";
import TableScrollArea from "../TableScrollArea";
import ProductProfitSummary from "../ProductProfitSummary";
import TablePagination from "@/components/Pagination/TablePagination";
import useRemoteList from "@/hooks/useRemoteList";
import { productService } from "@/services/api/productService";
import { formatProductMarkup, formatProductMoney, productProfitability } from "@/utils/productProfitability";

export default function ProductProfitabilityView({ onBack }: { onBack: () => void }) {
  const { items: products, setItems: setProducts, isLoading, error, reload } = useRemoteList(productService.list, "Não foi possível carregar os preços dos produtos.");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const selected = products.find(product => product.id === selectedId);
  const filtered = useMemo(() => {
    const term = search.trim().toLocaleLowerCase("pt-BR");
    return products.filter(product => `${product.productCode} ${product.productName}`.toLocaleLowerCase("pt-BR").includes(term));
  }, [products, search]);
  const safePage = Math.min(page, Math.max(1, Math.ceil(filtered.length / pageSize)));
  const visible = filtered.slice((safePage - 1) * pageSize, safePage * pageSize);

  if (selected) return <ProductProfitSummary product={selected} onBack={() => setSelectedId(null)} onSaved={saved => setProducts(list => list.map(p => p.id === saved.id ? saved : p))} />;

  return <div className="space-y-5">
    <PageHeader title="Lucro e markup sem período" description="Consulte cada produto pelos preços atuais de compra e venda, mesmo que ele ainda não tenha sido vendido."
      action={<>
        <button type="button" className="inline-flex min-h-11 items-center gap-2 rounded-xl border border-border-primary bg-bg-light px-4 py-2 font-semibold text-text-primary hover:bg-hover-light" onClick={onBack}><ArrowLeft size={16} aria-hidden="true" />Voltar aos relatórios</button>
        <button type="button" disabled={isLoading} onClick={() => void reload()} className="inline-flex min-h-11 items-center gap-2 rounded-xl border border-border-primary bg-bg-light px-4 py-2 font-semibold text-text-primary hover:bg-hover-light disabled:opacity-60"><RefreshCw size={16} aria-hidden="true" />Atualizar preços</button>
      </>} />
    <div className="max-w-2xl">
      <label htmlFor="current-profit-search" className="mb-2 block text-sm text-text-secondary">Código ou descrição do produto</label>
      <input id="current-profit-search" type="search" value={search} onChange={event => { setSearch(event.target.value); setPage(1); }}
        placeholder="Busque e clique no produto para ver os detalhes" className="input-field min-h-11 w-full" />
    </div>
    <p className="text-sm text-text-secondary">Lucro bruto e markup por unidade de venda cadastrada. Clique no nome do produto para conferir o cálculo.</p>
    {isLoading ? <ListState loading title="Carregando preços dos produtos…" />
      : error ? <ListState error title={error} actionLabel="Tentar novamente" onAction={() => void reload()} />
      : filtered.length === 0 ? <ListState title="Nenhum produto encontrado" description={search ? "Confira o código ou a descrição informada." : "Cadastre um produto para consultar seus preços."} />
      : <>
        <TableScrollArea label="Preços, lucro e markup dos produtos" className="rounded-xl border border-border-primary bg-bg-light">
          <table className="w-full min-w-[1000px] text-left text-sm">
            <thead className="bg-bg-gray-theme text-text-secondary"><tr>
              {["Código", "Descrição do produto", "Unidade", "Preço de compra", "Preço de venda", "Lucro bruto (R$)", "Lucro bruto (%)", "Markup (%)"].map((label, index) =>
                <th key={label} scope="col" className={`px-4 py-3 whitespace-nowrap font-semibold ${index >= 3 ? "text-right" : ""}`}>{label}</th>)}
            </tr></thead>
            <tbody>{visible.map(product => {
              const values = productProfitability(product);
              return <tr key={product.id} className="border-t border-border-primary text-text-primary">
                <td className="px-4 py-2 whitespace-nowrap">{product.productCode}</td>
                <td className="px-4 py-2"><button type="button" onClick={() => setSelectedId(product.id)}
                  className="min-h-11 text-left font-semibold text-text-primary underline decoration-border-primary underline-offset-4 hover:decoration-current focus-visible:outline-2 focus-visible:outline-offset-2">{product.productName}</button></td>
                <td className="px-4 py-2">{product.unidadeComercial || "UN"}</td>
                {[formatProductMoney(values.cost), formatProductMoney(values.sale), formatProductMoney(values.profit), formatProductMarkup(values.margin), formatProductMarkup(values.markup)].map((value, index) =>
                  <td key={index} className="px-4 py-2 text-right whitespace-nowrap tabular-nums">{value}</td>)}
              </tr>;
            })}</tbody>
          </table>
        </TableScrollArea>
        <TablePagination totalItems={filtered.length} currentPage={safePage} itemsPerPage={pageSize} onPageChange={setPage}
          onItemsPerPageChange={value => { setPageSize(value); setPage(1); }} />
      </>}
    <p className="text-sm text-text-secondary">Lucro bruto = venda − compra. Lucro bruto (%) = lucro bruto ÷ venda × 100. Markup = lucro bruto ÷ compra × 100. “—” indica preço não informado ou cálculo sem base maior que zero. Impostos, taxas e outras despesas não são descontados.</p>
  </div>;
}
