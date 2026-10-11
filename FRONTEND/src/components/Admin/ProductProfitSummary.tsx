import { ArrowLeft } from "lucide-react";
import PageHeader from "./PageHeader";
import { formatProductMarkup, formatProductMoney, productProfitability, type ProductPrices } from "@/utils/productProfitability";

type Props = { product: ProductPrices; onBack: () => void };

export default function ProductProfitSummary({ product, onBack }: Props) {
  const { cost, sale, profit, margin, markup } = productProfitability(product);
  const unit = product.unidadeComercial?.trim().toUpperCase() || "UN";
  const basis = unit === "KG" ? "1 kg" : unit === "UN" ? "1 unidade" : `1 ${unit}`;
  const metrics = [
    { label: "Preço de compra", value: formatProductMoney(cost) },
    { label: "Preço de venda", value: formatProductMoney(sale) },
    { label: "Lucro bruto", value: formatProductMoney(profit) },
    { label: "Lucro bruto (%)", value: formatProductMarkup(margin) },
    { label: "Markup", value: formatProductMarkup(markup) },
  ];
  return (
    <section className="space-y-5" aria-label="Lucro e markup do produto">
      <PageHeader title="Lucro e markup do produto" description="Consulta dos preços atuais do cadastro, sem período."
        action={<button type="button" onClick={onBack} className="inline-flex min-h-11 items-center gap-2 rounded-xl border border-border-primary bg-bg-light px-4 py-2 font-semibold text-text-primary hover:bg-hover-light"><ArrowLeft size={16} aria-hidden="true" />Voltar</button>} />
      <div className="rounded-xl border border-border-primary bg-bg-light p-5 sm:p-6">
        <p className="text-sm text-text-secondary">Código {product.productCode}</p>
        <h2 className="mt-1 break-words text-xl font-semibold text-text-primary">{product.productName}</h2>
        <p className="mt-2 text-sm text-text-secondary">Valores para {basis}, conforme a unidade de venda cadastrada.</p>
        <dl className="mt-6 grid grid-cols-1 gap-5 border-t border-border-primary pt-5 sm:grid-cols-2 xl:grid-cols-5">
          {metrics.map(({ label, value }) => <div key={label}>
            <dt className="text-sm text-text-secondary">{label}</dt>
            <dd className="mt-1 text-2xl font-semibold tabular-nums text-text-primary">{value}</dd>
          </div>)}
        </dl>
      </div>
      <div className="space-y-2 text-sm text-text-secondary">
        <p>Lucro bruto = preço de venda − preço de compra.</p>
        <p>Lucro bruto (%) = lucro bruto ÷ preço de venda × 100 (margem bruta sobre a venda).</p>
        <p>Markup = lucro bruto ÷ preço de compra × 100.</p>
        <p>O lucro bruto não desconta impostos, taxas ou outras despesas. Os valores não consideram quantidade vendida ou estoque.</p>
        {profit !== null && profit < 0 && <p role="status" className="font-semibold text-text-primary">Venda abaixo do custo: prejuízo de {formatProductMoney(-profit)} por {basis}.</p>}
        {(cost === null || sale === null) && <p role="status" className="font-semibold text-text-primary">Complete os preços de compra e venda no cadastro para calcular o lucro e o markup.</p>}
        {cost === 0 && <p role="status" className="font-semibold text-text-primary">O custo está zerado. O markup só pode ser calculado com um custo maior que zero.</p>}
        {sale === 0 && <p role="status" className="font-semibold text-text-primary">O preço de venda está zerado. O lucro em porcentagem só pode ser calculado com um preço de venda maior que zero.</p>}
      </div>
    </section>
  );
}
