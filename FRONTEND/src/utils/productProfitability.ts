export type ProductPrices = {
  productName: string;
  productCode: string;
  productUnitPrice: string;
  productSalePrice: string;
  unidadeComercial: string;
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
  const cost = priceInCents(product.productUnitPrice);
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

export function formatProductMarkup(value: number | null) {
  return value === null ? "—" : `${value.toLocaleString("pt-BR", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}%`;
}
