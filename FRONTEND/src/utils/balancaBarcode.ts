/**
 * Arquivo: src/utils/balancaBarcode.ts
 * Objetivo: decodifica o código de barras EAN-13 de "peso variável" impresso por etiquetas de
 *           balança (Toledo, Filizola, Urano etc.) — prefixo "2" + código do produto + peso.
 * Entradas esperadas: recebe a string lida pelo leitor de código de barras (ou digitada) no PDV.
 *
 * Formato assumido (o mais comum em balanças de mercado configuradas de fábrica no Brasil):
 *   posição 1        -> "2" (prefixo padrão de peso variável)
 *   posições 2-6     -> código do produto (PLU), 5 dígitos
 *   posições 7-11    -> peso em gramas, 5 dígitos (ex.: 00452 = 452 g = 0,452 kg)
 *   posição 12       -> dígito de verificação interno da balança (não validado aqui — varia
 *                        por fabricante/configuração; não afeta a leitura do peso)
 *   posição 13       -> dígito verificador EAN-13 padrão sobre as posições 1-12
 *
 * Se a balança da loja usar outro layout (ex.: código de 6 dígitos, ou peso codificado como
 * preço em vez de peso), ajuste PRODUCT_CODE_LENGTH/WEIGHT_LENGTH abaixo e confirme testando
 * com uma etiqueta real — não há como validar isso sem o equipamento físico.
 */

const PREFIX = "2";
const PRODUCT_CODE_START = 1;
const PRODUCT_CODE_LENGTH = 5;
const WEIGHT_START = PRODUCT_CODE_START + PRODUCT_CODE_LENGTH; // 6
const WEIGHT_LENGTH = 5;
const TOTAL_LENGTH = 13;

export type BalancaBarcode = {
  /** Código do produto (PLU) tal como veio no código de barras, com zeros à esquerda. */
  productCode: string;
  /** Peso em quilogramas, já convertido (ex.: 0.452). */
  weightKg: number;
};

/** Dígito verificador padrão EAN-13 sobre os 12 primeiros dígitos. */
function isValidEan13(digits: string): boolean {
  let sum = 0;
  for (let i = 0; i < 12; i += 1) {
    const digit = Number(digits[i]);
    sum += i % 2 === 0 ? digit : digit * 3;
  }
  const checkDigit = (10 - (sum % 10)) % 10;
  return checkDigit === Number(digits[12]);
}

/**
 * Tenta interpretar `value` como um código de balança de peso variável.
 * Retorna `null` quando não bate com o formato (trata como busca de texto normal).
 */
export function parseBalancaBarcode(value: string): BalancaBarcode | null {
  const digits = value.trim();
  if (digits.length !== TOTAL_LENGTH || !/^\d+$/.test(digits)) return null;
  if (digits[0] !== PREFIX) return null;
  if (!isValidEan13(digits)) return null;

  const productCode = digits.slice(PRODUCT_CODE_START, PRODUCT_CODE_START + PRODUCT_CODE_LENGTH);
  const weightGrams = Number(digits.slice(WEIGHT_START, WEIGHT_START + WEIGHT_LENGTH));
  if (!Number.isFinite(weightGrams) || weightGrams <= 0) return null;

  return { productCode, weightKg: weightGrams / 1000 };
}
