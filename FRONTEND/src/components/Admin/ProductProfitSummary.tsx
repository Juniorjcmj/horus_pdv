import { useRef, useState } from "react";
import { ArrowLeft, RefreshCw, Save } from "lucide-react";
import PageHeader from "./PageHeader";
import { ApiError } from "@/services/api/apiClient";
import { productService, type ProductDto } from "@/services/api/productService";
import { getStoredAuthUser } from "@/utils/authStorage";
import { formatProductMarkup, formatProductMoney, productProfitability, proposedProductPrice, type ProductPrices, type ProductPriceMetric } from "@/utils/productProfitability";

type Props = { product: ProductPrices & { id: string }; onBack: () => void; onSaved: (product: ProductDto) => void };
const button = "inline-flex min-h-11 items-center justify-center gap-2 rounded-xl border border-border-primary bg-bg-light px-4 py-2 font-semibold text-text-primary hover:bg-hover-light disabled:opacity-60 disabled:cursor-not-allowed";
const numericText = (value: number | null) => value === null ? "" : value.toFixed(2).replace(".", ",");
const fields: { key: ProductPriceMetric; label: string }[] = [
  { key: "sale", label: "Preço de venda (R$)" },
  { key: "profit", label: "Lucro bruto (R$)" },
  { key: "margin", label: "Lucro bruto (%)" },
  { key: "markup", label: "Markup (%)" },
];

export default function ProductProfitSummary({ product, onBack, onSaved }: Props) {
  const [base, setBase] = useState(product);
  const [edited, setEdited] = useState<{ field: ProductPriceMetric; value: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const [savedMessage, setSavedMessage] = useState<string | null>(null);
  const [discard, setDiscard] = useState(false);
  const submitting = useRef(false);
  const original = productProfitability(base);
  const proposal = edited ? proposedProductPrice(original.cost, edited.field, edited.value) : { sale: original.sale, error: null };
  const values = productProfitability({ ...base, productSalePrice: numericText(proposal.sale) });
  const changed = Boolean(edited) && (proposal.error !== null || proposal.sale !== original.sale);
  const user = getStoredAuthUser();
  const canEdit = [user?.role, ...(user?.perfisAdicionais ?? [])].some(role => ["administrador", "gerente", "atendente"].includes(role?.toLowerCase() ?? ""));
  const unit = base.unidadeComercial?.trim().toUpperCase() || "UN";
  const basis = unit === "KG" ? "1 kg" : unit === "UN" ? "1 unidade" : `1 ${unit}`;
  const reset = () => { setEdited(null); setError(null); setSavedMessage(null); setDiscard(false); };
  const save = async () => {
    if (submitting.current || !changed || proposal.error || proposal.sale === null || !base.versaoPreco || conflict) return;
    submitting.current = true; setBusy(true); setError(null); setSavedMessage(null);
    try {
      const saved = await productService.updateSalePrice(base.id, { precoVenda: numericText(proposal.sale), versaoConsultada: base.versaoPreco });
      setBase(saved); setEdited(null); setDiscard(false); onSaved(saved);
      setSavedMessage(`Preço de venda salvo: ${formatProductMoney(productProfitability(saved).sale)}.`);
    } catch (err) { setError(err instanceof Error ? err.message : "Não foi possível salvar o preço. Tente novamente."); setConflict(err instanceof ApiError && err.status === 409); }
    finally { submitting.current = false; setBusy(false); }
  };
  const refresh = async () => {
    if (submitting.current) return;
    submitting.current = true; setBusy(true); setError(null);
    try {
      const updated = (await productService.list()).find(p => p.id === base.id);
      if (!updated) throw new Error("Produto não encontrado. Volte à lista para conferir.");
      setBase(updated); onSaved(updated); reset(); setConflict(false);
    } catch (err) { setError(err instanceof Error ? err.message : "Não foi possível atualizar os preços. Tente novamente."); }
    finally { submitting.current = false; setBusy(false); }
  };

  return <section className="space-y-5" aria-label="Lucro e markup do produto">
    <PageHeader title="Lucro e markup do produto" description="Analise os preços atuais e ajuste a venda pelo lucro ou markup, sem período."
      action={<button type="button" disabled={busy} onClick={() => changed ? setDiscard(true) : onBack()} className={button}><ArrowLeft size={16} aria-hidden="true" />Voltar</button>} />
    {discard && <div role="alert" className="flex flex-wrap items-center gap-3 rounded-xl border border-border-primary p-4 text-text-primary"><p>O ajuste de preço ainda não foi salvo.</p><button type="button" className={button} onClick={onBack}>Descartar ajuste e voltar</button><button type="button" className={button} onClick={() => setDiscard(false)}>Continuar ajustando</button></div>}
    <div className="rounded-xl border border-border-primary bg-bg-light p-5 sm:p-6">
      <p className="text-sm text-text-secondary">Código {base.productCode}</p>
      <h2 className="mt-1 break-words text-xl font-semibold text-text-primary">{base.productName}</h2>
      <p className="mt-2 text-sm text-text-secondary">Valores para {basis}, conforme a unidade de venda cadastrada.</p>
      <dl className="mt-5 flex flex-wrap gap-x-10 gap-y-4 border-y border-border-primary py-4 text-text-primary">
        <div><dt className="text-sm text-text-secondary">Preço de compra</dt><dd className="mt-1 text-xl font-semibold tabular-nums">{formatProductMoney(original.cost)}</dd></div>
        <div><dt className="text-sm text-text-secondary">Preço de venda cadastrado</dt><dd className="mt-1 text-xl font-semibold tabular-nums">{formatProductMoney(original.sale)}</dd></div>
      </dl>
      <form className="mt-5 space-y-5" onSubmit={e => { e.preventDefault(); void save(); }}>
        <fieldset disabled={busy || !canEdit || original.cost === null} className="min-w-0 grid gap-5 sm:grid-cols-2 xl:grid-cols-4">
          {fields.map(({ key, label }) => <label key={key} className="min-w-0 text-sm text-text-secondary">
            {label}
            <input inputMode="decimal" autoComplete="off" className="input-field mt-2 min-h-11 w-full tabular-nums disabled:opacity-60"
              disabled={(key === "markup" || key === "margin") && !(original.cost !== null && original.cost > 0)}
              value={edited?.field === key ? edited.value : numericText(values[key])} placeholder="—" aria-invalid={edited?.field === key && Boolean(proposal.error)}
              aria-describedby="price-adjust-help"
              onChange={e => { setEdited({ field: key, value: e.target.value }); setError(null); setSavedMessage(null); }}
              onBlur={() => { if (edited?.field === key && proposal.sale !== null && !proposal.error) setEdited({ field: "sale", value: numericText(proposal.sale) }); }} />
          </label>)}
        </fieldset>
        <p id="price-adjust-help" className="max-w-3xl text-sm text-text-secondary">Altere um dos valores para recalcular os demais. O preço é arredondado para centavos; os percentuais mostram o resultado desse preço. Use vírgula ou ponto decimal, sem separador de milhar.</p>
        <dl aria-label="Resultado do ajuste" className="grid gap-x-8 gap-y-3 border-t border-border-primary pt-4 text-text-primary sm:grid-cols-3">
          {[{ label: "Venda calculada", value: formatProductMoney(values.sale) }, { label: "Lucro calculado", value: formatProductMoney(values.profit) }, { label: "Markup calculado", value: formatProductMarkup(values.markup) }].map(metric => <div key={metric.label}><dt className="text-sm text-text-secondary">{metric.label}</dt><dd className="mt-1 font-semibold tabular-nums">{metric.value}</dd></div>)}
        </dl>
        {proposal.error && <p role="alert" className="text-sm text-text-primary">{proposal.error}</p>}
        {error && <p role="alert" className="text-sm text-text-primary">{error}</p>}
        {savedMessage && <p role="status" className="font-semibold text-text-primary">{savedMessage}</p>}
        {canEdit ? <div className="flex flex-wrap items-center gap-3">
          <button type="submit" disabled={busy || !changed || Boolean(proposal.error) || !base.versaoPreco || conflict} className="btn-primary inline-flex min-h-11 items-center justify-center gap-2 disabled:opacity-60 disabled:cursor-not-allowed"><Save size={16} aria-hidden="true" />{busy ? "Salvando…" : "Salvar preço de venda"}</button>
          {changed && !conflict && <button type="button" disabled={busy} className={button} onClick={reset}>Desfazer ajuste</button>}
          {(conflict || !base.versaoPreco) && <button type="button" disabled={busy} className={button} onClick={() => void refresh()}><RefreshCw size={16} aria-hidden="true" />Atualizar preços e refazer ajuste</button>}
          <p className="text-sm text-text-secondary">{changed ? "Ajuste ainda não salvo no cadastro." : "Os valores atuais estão salvos no cadastro."}</p>
        </div> : <p className="text-sm text-text-secondary">Seu perfil permite consultar os valores. A alteração exige permissão de edição de produtos.</p>}
      </form>
    </div>
    <div className="space-y-2 text-sm text-text-secondary">
      <p>Lucro bruto = preço de venda − preço de compra.</p>
      <p>Lucro bruto (%) = lucro bruto ÷ preço de venda × 100 (margem bruta sobre a venda).</p>
      <p>Markup = lucro bruto ÷ preço de compra × 100.</p>
      <p>O lucro bruto não desconta impostos, taxas ou outras despesas. Os valores não consideram quantidade vendida ou estoque.</p>
      {values.profit !== null && values.profit < 0 && <p role="status" className="font-semibold text-text-primary">Venda abaixo do custo: prejuízo de {formatProductMoney(-values.profit)} por {basis}.</p>}
      {(original.cost === null || original.sale === null) && <p role="status" className="font-semibold text-text-primary">Complete os preços de compra e venda no cadastro para calcular o lucro e o markup.</p>}
      {original.cost === 0 && <p role="status" className="font-semibold text-text-primary">O custo está zerado. O markup só pode ser calculado com um custo maior que zero.</p>}
      {values.sale === 0 && <p role="status" className="font-semibold text-text-primary">O preço de venda está zerado. O lucro em porcentagem só pode ser calculado com um preço de venda maior que zero.</p>}
    </div>
  </section>;
}
