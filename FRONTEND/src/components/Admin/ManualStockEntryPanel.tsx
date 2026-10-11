import { useMemo, useRef, useState, type FormEvent } from "react";
import { ArrowLeft, Check, Plus, Search, Trash2 } from "lucide-react";
import PageHeader from "./PageHeader";
import ListState from "./ListState";
import TableScrollArea from "./TableScrollArea";
import useRemoteList from "@/hooks/useRemoteList";
import { productService, type ProductDto } from "@/services/api/productService";
import { supplierService } from "@/services/api/supplierService";
import { ApiError } from "@/services/api/apiClient";
import { manualConversion, manualNumber, manualEntryService, readManualAttachment, type ManualEntryItem, type ManualEntryPayload } from "@/services/api/manualEntryService";
import type { NfeImportResult } from "@/services/api/nfeImportService";

const money = (n: number) => Number.isFinite(n) ? n.toLocaleString("pt-BR", { style: "currency", currency: "BRL" }) : "—";
const quantity = (n: number) => Number.isFinite(n) ? n.toLocaleString("pt-BR", { maximumFractionDigits: 4 }) : "—";
const button = "inline-flex min-h-11 items-center justify-center gap-2 rounded-xl border border-border-primary bg-bg-light px-4 py-2 font-semibold text-text-primary hover:bg-hover-light disabled:cursor-not-allowed disabled:opacity-60";
const blank = (): ManualEntryItem => ({ numeroItem: 0, produtoExistenteId: null, productCode: "", productName: "", gtin: "SEM GTIN", ncm: "00000000", cest: null,
  unidadeComercial: "UN", quantidade: "", precoCusto: "", precoVenda: "", quantidadeCompra: "1", unidadeCompra: "UN", fatorConversao: "1", custoCompra: "", alterarPrecoVenda: false, dataValidade: "", numeroLote: "" });
const today = () => new Intl.DateTimeFormat("en-CA", { timeZone: "America/Sao_Paulo", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());

type Props = { onBack: () => void; onSaved: () => void; onHistory: (id: string) => void };

export default function ManualStockEntryPanel({ onBack, onSaved, onHistory }: Props) {
  const products = useRemoteList(productService.list, "Não foi possível carregar os produtos.");
  const suppliers = useRemoteList(supplierService.list, "Não foi possível carregar os fornecedores. Você pode continuar sem fornecedor.");
  const [date, setDate] = useState(today);
  const [supplier, setSupplier] = useState("");
  const [documentType, setDocumentType] = useState("cupom");
  const [documentNumber, setDocumentNumber] = useState("");
  const [key, setKey] = useState("");
  const [note, setNote] = useState("");
  const [attachment, setAttachment] = useState<ManualEntryPayload["comprovante"]>(null);
  const [reading, setReading] = useState(false);
  const [search, setSearch] = useState("");
  const [draft, setDraft] = useState<ManualEntryItem>(blank);
  const [newProduct, setNewProduct] = useState(false);
  const [editing, setEditing] = useState<number | null>(null);
  const [items, setItems] = useState<ManualEntryItem[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [result, setResult] = useState<NfeImportResult | null>(null);
  const [discard, setDiscard] = useState(false);
  const [operation, setOperation] = useState(() => crypto.randomUUID());
  const pending = useRef<ManualEntryPayload | null>(null);
  const submitting = useRef(false);
  const lineId = useRef(0);
  const searchRef = useRef<HTMLInputElement>(null);
  const attachmentRef = useRef<HTMLInputElement>(null);
  const frozen = busy || uncertain || reading;
  const matches = useMemo(() => {
    const text = search.trim().toLocaleLowerCase("pt-BR");
    return text ? products.items.filter(p => `${p.productCode} ${p.productName} ${p.gtin || ""}`.toLocaleLowerCase("pt-BR").includes(text)).slice(0, 8) : [];
  }, [products.items, search]);
  const patch = (value: Partial<ManualEntryItem>) => setDraft(current => ({ ...current, ...value }));
  const select = (p: ProductDto) => {
    setDraft({ ...blank(), produtoExistenteId: p.id, productCode: p.productCode, productName: p.productName, ncm: p.ncm,
      unidadeComercial: p.unidadeComercial || "UN", unidadeCompra: p.unidadeComercial || "UN", precoVenda: p.productSalePrice, custoCompra: p.productUnitPrice });
    setSearch(""); setNewProduct(false); setError(null); setEditing(null);
  };
  const conversion = manualConversion(draft);
  const current = products.items.find(p => p.id === draft.produtoExistenteId);
  const previousQty = current ? manualNumber(current.productQnt) : 0;
  const queuedQty = items.filter(i => i.produtoExistenteId === draft.produtoExistenteId && i.numeroItem !== editing).reduce((sum, i) => sum + manualConversion(i).quantity, 0);
  const total = items.reduce((sum, item) => sum + manualConversion(item).total, 0);
  const add = (event: FormEvent) => {
    event.preventDefault(); setError(null);
    if (!draft.produtoExistenteId && !newProduct) return setError("Selecione um produto ou cadastre um novo produto.");
    if (!draft.productName.trim()) return setError("Descrição do produto é obrigatória.");
    if (![conversion.quantity, conversion.cost, conversion.total].every(n => Number.isFinite(n) && n > 0)) return setError("Informe quantidade, conversão e custo maiores que zero.");
    if ((newProduct || draft.alterarPrecoVenda) && !(manualNumber(draft.precoVenda) > 0)) return setError("Informe um preço de venda maior que zero.");
    if (newProduct && draft.productCode && items.some(i => i.numeroItem !== editing && !i.produtoExistenteId && i.productCode === draft.productCode)) return setError("Este código já está na entrada.");
    const item = { ...draft, numeroItem: editing ?? ++lineId.current };
    setItems(list => editing === null ? [...list, item] : list.map(i => i.numeroItem === editing ? item : i));
    setDraft(blank()); setNewProduct(false); setEditing(null); setSearch(""); searchRef.current?.focus();
  };
  const confirm = async () => {
    if (submitting.current) return;
    if (!pending.current && (!items.length || editing !== null || draft.produtoExistenteId || newProduct)) return setError("Adicione ou cancele o produto em edição antes de confirmar a entrada.");
    if (!date) return setError("Informe a data da compra.");
    submitting.current = true; setBusy(true); setError(null);
    pending.current ??= { operacaoId: operation, dataCompra: date, fornecedorId: supplier || null, tipoDocumento: documentType, numeroDocumento: documentNumber,
      chaveAcesso: key.trim() || null, observacao: note, comprovante: attachment, itens: items };
    try {
      const saved = await manualEntryService.confirm(pending.current);
      setResult(saved); setUncertain(false); pending.current = null; onSaved();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Não foi possível confirmar a entrada.");
      const ambiguous = !(e instanceof ApiError) || e.status === 0 || e.status >= 500;
      setUncertain(ambiguous);
      if (!ambiguous) pending.current = null;
    } finally { submitting.current = false; setBusy(false); }
  };
  const reset = () => { setResult(null); setItems([]); setDraft(blank()); setNewProduct(false); setEditing(null); setSearch(""); setAttachment(null); setDocumentNumber(""); setKey(""); setNote(""); setSupplier(""); setDate(today()); setError(null); setOperation(crypto.randomUUID()); void products.reload(); };
  const leave = () => items.length || draft.produtoExistenteId || newProduct ? setDiscard(true) : onBack();

  if (result) return <section className="space-y-5">
    <PageHeader title="Entrada registrada" description="Os produtos, custos e comprovante foram salvos no histórico de entradas." />
    <div role="status" className="space-y-2 text-text-primary"><Check size={24} aria-hidden="true" /><p className="text-lg font-semibold">Estoque atualizado com sucesso.</p><p>{result.produtosCriados} produto(s) criado(s) · {result.produtosAtualizados} item(ns) recebido(s) em produtos existentes.</p><p>Total da entrada: <strong>{money(total)}</strong></p></div>
    {result.produtosCriados > 0 && <p className="text-sm text-text-secondary">Os produtos novos estão disponíveis no cadastro. Confira seus dados fiscais antes da venda com emissão de nota.</p>}
    <div className="flex flex-wrap gap-3"><button className="btn-primary min-h-11" onClick={() => onHistory(result.notaEntradaId!)}>Ver entrada no histórico</button><button className={button} onClick={reset}>Nova entrada</button><button className={button} onClick={onBack}>Voltar aos produtos</button></div>
  </section>;

  return <section className="space-y-6" aria-label="Entrada manual de produtos">
    <PageHeader title="Entrada manual" description="Receba produtos de uma compra com cupom ou outro comprovante. XML e fornecedor são opcionais."
      action={<button className={button} disabled={frozen} onClick={leave}><ArrowLeft size={16} aria-hidden="true" />Voltar aos produtos</button>} />
    {discard && <div role="alert" className="flex flex-wrap items-center gap-3 rounded-xl border border-border-primary p-4 text-text-primary"><p>A entrada ainda não foi salva. Deseja descartar os dados?</p><button className={button} onClick={onBack}>Descartar entrada</button><button className={button} onClick={() => setDiscard(false)}>Continuar preenchendo</button></div>}
    {error && <p role="alert" className="rounded-xl border border-border-primary p-4 text-text-primary">{error}</p>}
    {uncertain && <p role="status" className="text-sm text-text-secondary">A confirmação pode ter sido recebida pelo servidor. Os dados foram preservados; tente confirmar novamente para recuperar o resultado sem repetir a entrada no estoque.</p>}
    <fieldset disabled={frozen} className="min-w-0 space-y-5 disabled:opacity-70">
      <legend className="mb-3 text-lg font-semibold text-text-primary">Dados da compra</legend>
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <label className="text-sm text-text-secondary">Data da compra<input type="date" className="input-field mt-1 min-h-11 w-full" value={date} onChange={e => setDate(e.target.value)} required /></label>
        <label className="text-sm text-text-secondary">Fornecedor (opcional)<select className="select-field mt-1 min-h-11 w-full" value={supplier} onChange={e => setSupplier(e.target.value)}><option value="">Sem fornecedor</option>{suppliers.items.map(s => <option key={s.id} value={s.id}>{s.fantasyName || s.companyName}</option>)}</select></label>
        <label className="text-sm text-text-secondary">Tipo de comprovante<select className="select-field mt-1 min-h-11 w-full" value={documentType} onChange={e => setDocumentType(e.target.value)}><option value="cupom">Cupom / NFC-e</option><option value="recibo">Recibo</option><option value="outro">Outro comprovante</option></select></label>
        <label className="text-sm text-text-secondary">Número do documento (opcional)<input className="input-field mt-1 min-h-11 w-full" maxLength={30} value={documentNumber} onChange={e => setDocumentNumber(e.target.value)} /></label>
      </div>
      {suppliers.error && <div className="flex flex-wrap items-center gap-3 text-sm text-text-secondary"><p>{suppliers.error}</p><button className={button} onClick={() => void suppliers.reload()}>Recarregar fornecedores</button></div>}
      <details className="text-sm text-text-secondary"><summary className="cursor-pointer py-2 font-semibold text-text-primary">Chave, comprovante e observação (opcionais)</summary><div className="mt-3 grid gap-4 sm:grid-cols-2">
        <label>Chave de acesso<input className="input-field mt-1 min-h-11 w-full" inputMode="numeric" maxLength={44} value={key} onChange={e => setKey(e.target.value.replace(/\D/g, ""))} /></label>
        <div><label>Foto, PDF ou XML · até 10 MB<input ref={attachmentRef} type="file" className="mt-2 block min-h-11 w-full" accept=".jpg,.jpeg,.png,.pdf,.xml" onChange={async e => { const file = e.target.files?.[0]; if (!file) return; setReading(true); setError(null); try { setAttachment(await readManualAttachment(file)); } catch (err) { setAttachment(null); if (attachmentRef.current) attachmentRef.current.value = ""; setError(err instanceof Error ? err.message : "Comprovante inválido."); } finally { setReading(false); } }} /></label>{attachment && <p className="mt-1 break-all">{attachment.nome} <button type="button" onClick={() => { setAttachment(null); if (attachmentRef.current) attachmentRef.current.value = ""; }} className="min-h-11 px-2 underline">Remover comprovante</button></p>}</div>
        <label className="sm:col-span-2">Observação<textarea className="input-field mt-1 w-full" rows={2} maxLength={1000} value={note} onChange={e => setNote(e.target.value)} /></label>
      </div></details>
    </fieldset>
    <fieldset disabled={frozen} className="min-w-0 space-y-4 border-t border-border-primary pt-5 disabled:opacity-70">
      <legend className="text-lg font-semibold text-text-primary">Produtos recebidos</legend>
      {products.isLoading ? <ListState loading title="Carregando produtos…" /> : products.error ? <ListState error title={products.error} actionLabel="Tentar novamente" onAction={() => void products.reload()} /> : <>
        <div className="flex flex-wrap items-end gap-3"><label className="min-w-0 flex-1 text-sm text-text-secondary">Buscar por código, descrição ou leitor<input ref={searchRef} type="search" className="input-field mt-1 min-h-11 w-full" value={search} onChange={e => setSearch(e.target.value)} onKeyDown={e => { if (e.key === "Enter") { e.preventDefault(); const p = products.items.find(p => p.productCode === search.trim() || p.gtin === search.trim()); if (p) select(p); else setError("Código não encontrado. Selecione um produto da lista ou cadastre um novo."); } }} /></label>
          <button type="button" className={button} onClick={() => { setDraft({ ...blank(), productName: /^\d+$/.test(search) ? "" : search, productCode: /^\d+$/.test(search) ? search : "" }); setNewProduct(true); setSearch(""); setEditing(null); setError(null); }}><Plus size={16} aria-hidden="true" />Cadastrar novo produto</button></div>
        {search.trim() && <ul aria-label="Produtos encontrados" className="divide-y divide-border-primary rounded-xl border border-border-primary bg-bg-light">{matches.length ? matches.map(p => <li key={p.id}><button type="button" className="flex min-h-11 w-full items-center gap-3 px-4 py-3 text-left text-text-primary hover:bg-hover-light" onClick={() => select(p)}><Search size={16} aria-hidden="true" /><span>{p.productCode} · {p.productName}</span></button></li>) : <li className="p-4 text-sm text-text-secondary">Nenhum produto encontrado. Use “Cadastrar novo produto”.</li>}</ul>}
        {(draft.produtoExistenteId || newProduct) && <form onSubmit={add} className="space-y-4 rounded-xl border border-border-primary bg-bg-light p-4 sm:p-5">
          <h3 className="font-semibold text-text-primary">{newProduct ? "Novo produto" : `${draft.productCode} · ${draft.productName}`}</h3>
          {newProduct && <div className="grid gap-4 sm:grid-cols-2"><label className="text-sm text-text-secondary">Descrição do produto<input required maxLength={250} className="input-field mt-1 min-h-11 w-full" value={draft.productName} onChange={e => patch({ productName: e.target.value })} /></label><label className="text-sm text-text-secondary">Código ou barras (opcional)<input maxLength={50} className="input-field mt-1 min-h-11 w-full" placeholder="Código interno gerado se não informado" value={draft.productCode} onChange={e => patch({ productCode: e.target.value })} /></label></div>}
          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
            <label className="text-sm text-text-secondary">Quantidade comprada<input required inputMode="decimal" className="input-field mt-1 min-h-11 w-full" value={draft.quantidadeCompra} onChange={e => patch({ quantidadeCompra: e.target.value })} /></label>
            <label className="text-sm text-text-secondary">Unidade da compra<select className="select-field mt-1 min-h-11 w-full" value={draft.unidadeCompra} onChange={e => patch({ unidadeCompra: e.target.value })}>{Array.from(new Set([draft.unidadeComercial, "UN", "KG", "CX", "PCT", "FD", "LT"])).map(u => <option key={u} value={u}>{u}</option>)}</select></label>
            <label className="text-sm text-text-secondary">{draft.unidadeComercial} por unidade comprada<input required inputMode="decimal" className="input-field mt-1 min-h-11 w-full" value={draft.fatorConversao} onChange={e => patch({ fatorConversao: e.target.value })} /></label>
            <label className="text-sm text-text-secondary">Custo de 1 {draft.unidadeCompra} (R$)<input required inputMode="decimal" className="input-field mt-1 min-h-11 w-full" value={draft.custoCompra} onChange={e => patch({ custoCompra: e.target.value })} /></label>
          </div>
          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
            {newProduct && <label className="text-sm text-text-secondary">Unidade de venda<select className="select-field mt-1 min-h-11 w-full" value={draft.unidadeComercial} onChange={e => patch({ unidadeComercial: e.target.value })}>{["UN", "KG", "LT", "PCT", "CX"].map(u => <option key={u} value={u}>{u}</option>)}</select></label>}
            <label className="text-sm text-text-secondary">Preço de venda por {draft.unidadeComercial} (R$)<input required={newProduct || draft.alterarPrecoVenda} disabled={!newProduct && !draft.alterarPrecoVenda} inputMode="decimal" className="input-field mt-1 min-h-11 w-full disabled:opacity-60" value={draft.precoVenda} onChange={e => patch({ precoVenda: e.target.value })} /></label>
            <label className="text-sm text-text-secondary">Lote (opcional)<input maxLength={60} className="input-field mt-1 min-h-11 w-full" value={draft.numeroLote || ""} onChange={e => patch({ numeroLote: e.target.value })} /></label>
            <label className="text-sm text-text-secondary">Validade (opcional)<input type="date" className="input-field mt-1 min-h-11 w-full" value={draft.dataValidade || ""} onChange={e => patch({ dataValidade: e.target.value })} /></label>
          </div>
          {!newProduct && <label className="flex min-h-11 items-center gap-2 text-sm text-text-primary"><input type="checkbox" checked={draft.alterarPrecoVenda} onChange={e => patch({ alterarPrecoVenda: e.target.checked })} />Alterar o preço de venda deste produto</label>}
          <div role="status" className="space-y-1 border-y border-border-primary py-3 text-sm text-text-primary"><p>Entrada de <strong>{quantity(conversion.quantity)} {draft.unidadeComercial}</strong> · Custo por {draft.unidadeComercial}: <strong>{money(conversion.cost)}</strong> · Total: <strong>{money(conversion.total)}</strong></p><p>Estoque atual: {quantity(previousQty)} {draft.unidadeComercial} → Após esta entrada: {quantity(previousQty + (draft.produtoExistenteId ? queuedQty : 0) + conversion.quantity)} {draft.unidadeComercial}</p></div>
          <div className="flex flex-wrap gap-3"><button className="btn-primary min-h-11" type="submit">{editing === null ? "Adicionar à entrada" : "Salvar alteração do item"}</button><button type="button" className={button} onClick={() => { setDraft(blank()); setNewProduct(false); setEditing(null); }}>Cancelar item</button></div>
        </form>}
      </>}
      {!items.length ? <p className="py-4 text-sm text-text-secondary">Adicione os produtos da compra. O estoque será atualizado somente na confirmação.</p> : <TableScrollArea label="Itens da entrada manual" className="rounded-xl border border-border-primary bg-bg-light"><table className="min-w-[950px] w-full text-left text-sm"><thead className="text-text-secondary"><tr>{["Produto", "Compra", "Entrada no estoque", "Custo por unidade", "Preço de venda", "Total", "Ações"].map(label => <th key={label} className="px-3 py-3 font-semibold">{label}</th>)}</tr></thead><tbody className="divide-y divide-border-primary text-text-primary">{items.map(item => { const c = manualConversion(item); return <tr key={item.numeroItem}><td className="px-3 py-3"><span className="block font-semibold">{item.productName}</span><span className="text-xs text-text-secondary">{item.productCode || "Código a gerar"}{!item.produtoExistenteId && " · Novo produto"}</span></td><td className="px-3 py-3 whitespace-nowrap">{item.quantidadeCompra} {item.unidadeCompra}</td><td className="px-3 py-3 whitespace-nowrap">{quantity(c.quantity)} {item.unidadeComercial}</td><td className="px-3 py-3 whitespace-nowrap tabular-nums">{money(c.cost)}</td><td className="px-3 py-3 whitespace-nowrap tabular-nums">{money(manualNumber(item.precoVenda))}</td><td className="px-3 py-3 whitespace-nowrap tabular-nums">{money(c.total)}</td><td className="px-3 py-3"><div className="flex gap-2"><button type="button" className={button} aria-label={`Editar ${item.productName}`} onClick={() => { setDraft({ ...item }); setNewProduct(!item.produtoExistenteId); setEditing(item.numeroItem); setSearch(""); }}>Editar</button><button type="button" className={button} aria-label={`Remover ${item.productName}`} onClick={() => { setItems(list => list.filter(i => i.numeroItem !== item.numeroItem)); if (editing === item.numeroItem) { setDraft(blank()); setEditing(null); setNewProduct(false); } }}><Trash2 size={16} aria-hidden="true" /></button></div></td></tr>; })}</tbody></table></TableScrollArea>}
    </fieldset>
    <div className="flex flex-wrap items-center justify-between gap-4 border-t border-border-primary pt-5"><p className="text-lg text-text-primary">Total da entrada: <strong className="tabular-nums">{money(total)}</strong><span className="mt-1 block text-sm text-text-secondary">{items.length} item(ns) · O custo recebido atualizará o cadastro do produto.</span></p><button type="button" className="btn-primary min-h-11" disabled={busy || reading || !items.length || (Boolean(products.error) && !uncertain)} onClick={() => void confirm()}>{busy ? "Confirmando entrada…" : uncertain ? "Tentar confirmar novamente" : "Confirmar entrada no estoque"}</button></div>
  </section>;
}
