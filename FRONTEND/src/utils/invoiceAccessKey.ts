/** Identifica a nota pela chave, sem consultar a SEFAZ nem presumir seus itens. */
export function readInvoiceAccessKey(value: string) {
  const key = value.replace(/\D/g, "");
  if (key.length !== 44) return null;
  let sum = 0;
  for (let i = 42, weight = 2; i >= 0; i--, weight = weight === 9 ? 2 : weight + 1) {
    sum += Number(key[i]) * weight;
  }
  const remainder = sum % 11;
  if (Number(key[43]) !== (remainder < 2 ? 0 : 11 - remainder)) return null;
  const model = key.slice(20, 22);
  if (model !== "55" && model !== "65") return null;
  return { key, model, cnpj: key.slice(6, 20), serie: String(Number(key.slice(22, 25))), numeroNota: String(Number(key.slice(25, 34))), uf: key.slice(0, 2) };
}
