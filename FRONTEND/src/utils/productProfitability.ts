export type ProductPrices = {
  productName: string;
  productCode: string;
  productUnitPrice: string;
  productSalePrice: string;
  unidadeComercial: string;
  versaoPreco?: string;
};

// Os preços do cadastro usam vírgula decimal; preserva ausência e valores inválidos.
function priceInCents(value: string): number | null {
  const text = String(value ?? "").trim().replace(/^R\$\s*/, "");
  if (!/^(?:\d+|\d{1,3}(?:\.\d{3})+)(?:,\d{1,2})?$/.test(text)) return null;
  const [whole, fraction = ""] = text.replace(/\./g, "").split(",");
  const cents = Number(whole) * 100 + Number(fraction.padEnd(2, "0"));
  return Number.isSafeInteger(cents) ? cents : null;
}

export function productProfitability(product: ProductPrices) {
  const reference = product.versaoPreco?.split("|");
  const exactCost = reference?.length === 2 && /^\d+(?:\.\d{1,4})?$/.test(reference[0]) ? Number(reference[0]) * 100 : null;
  const cost = exactCost ?? priceInCents(product.productUnitPrice);
  const sale = priceInCents(product.productSalePrice);
  const profit = cost !== null && sale !== null ? sale - cost : null;
  return {
    cost: cost === null ? null : cost / 100,
    sale: sale === null ? null : sale / 100,
    profit: profit === null ? null : profit / 100,
    margin: profit !== null && sale !== null && sale > 0 ? profit / sale * 100 : null,
    markup: profit !== null && cost !== null && cost > 0 ? profit / cost * 100 : null,
  };
}

export function formatProductMoney(value: number | null) {
  return value === null ? "—" : value.toLocaleString("pt-BR", { style: "currency", currency: "BRL" });
}

export type ProductPriceMetric = "sale" | "profit" | "margin" | "markup";

export function proposedProductPrice(cost: number | null, metric: ProductPriceMetric, value: string): { sale: number | null; error: string | null } {
  if (cost === null) return { sale: null, error: "Complete o preço de compra no cadastro antes de ajustar a venda." };
  const text = value.trim();
  if (!/^-?\d+(?:[.,]\d{1,4})?$/.test(text)) return { sale: null, error: "Informe um número válido, sem separador de milhar." };
  const number = Number(text.replace(",", "."));
  if ((metric === "markup" || metric === "margin") && cost <= 0) return { sale: null, error: "O custo deve ser maior que zero para ajustar por porcentagem." };
  if (metric === "margin" && number >= 100) return { sale: null, error: "O lucro bruto em porcentagem deve ser menor que 100%." };
  const price = metric === "sale" ? number : metric === "profit" ? cost + number : metric === "markup" ? cost * (1 + number / 100) : cost / (1 - number / 100);
  const sale = Math.round((price + Number.EPSILON) * 100) / 100;
  if (!Number.isFinite(sale) || sale <= 0 || sale > 1_000_000_000) return { sale: null, error: "O ajuste deve resultar em um preço de venda maior que zero e até R$ 1 bilhão." };
  if (cost > 0 && (sale - cost) / cost * 100 > 99_999.9999) return { sale: null, error: "O markup calculado excede o limite de 99.999,9999%. Confira os valores." };
  return { sale, error: null };
}

export function formatProductMarkup(value: number | null) {
  return value === null ? "—" : `${value.toLocaleString("pt-BR", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}%`;
}
