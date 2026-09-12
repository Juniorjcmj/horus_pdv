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

export type BalancaProductLookup = {
  code: string;
  salePrice: number;
  unit?: string;
};

export type BalancaBarcode = {
  /** Código do produto (PLU) tal como veio no código de barras. */
  productCode: string;
  /** Peso em quilogramas (ex.: 0.762). */
  weightKg: number;
  /** Valor total em R$, caso a balança esteja configurada no modo Preço Total (ex.: 6.85). */
  totalPrice?: number;
  /** Modo identificado: "price" (preço total) ou "weight" (peso em gramas). */
  mode: "price" | "weight";
};

/** Dígito verificador padrão EAN-13 sobre os 12 primeiros dígitos. */
export function isValidEan13(digits: string): boolean {
  let sum = 0;
  for (let i = 0; i < 12; i += 1) {
    const digit = Number(digits[i]);
    sum += i % 2 === 0 ? digit : digit * 3;
  }
  const checkDigit = (10 - (sum % 10)) % 10;
  return checkDigit === Number(digits[12]);
}

/**
 * Interpreta código de barras de balança (EAN-13 iniciando com '2').
 * Suporta:
 * 1. Modo Preço Total (ex: Triunfo Quantum - '2' + 4 dígitos código + 7 dígitos centavos + DV)
 * 2. Modo Preço Total (5 ou 6 dígitos código)
 * 3. Modo Peso em gramas (5 dígitos código + 5 dígitos gramas + DV)
 * 4. Modo Peso em gramas (4 dígitos código + 6 dígitos gramas + DV)
 */
export function parseBalancaBarcode(
  value: string,
  knownProducts?: BalancaProductLookup[],
): BalancaBarcode | null {
  const digits = value.trim();
  if (digits.length !== 13 || !/^\d+$/.test(digits)) return null;
  if (digits[0] !== "2") return null;
  if (!isValidEan13(digits)) return null;

  // Se recebemos a lista de produtos conhecidos, tentamos casar com os códigos cadastrados
  if (knownProducts && knownProducts.length > 0) {
    // 1. Tenta código de 4 dígitos (ex.: Triunfo Quantum com PLU '2691')
    const code4 = digits.slice(1, 5);
    const prod4 = knownProducts.find(
      (p) => p.code === code4 || Number(p.code) === Number(code4),
    );
    if (prod4) {
      const priceCentavos = Number(digits.slice(5, 12));
      const totalPrice = priceCentavos / 100;
      const weightKg =
        prod4.salePrice > 0
          ? Number((totalPrice / prod4.salePrice).toFixed(3))
          : 1;
      return {
        productCode: prod4.code,
        weightKg,
        totalPrice,
        mode: "price",
      };
    }

    // 2. Tenta código de 5 dígitos (ex.: Toledo/Filizola com PLU '00015' ou '15')
    const code5 = digits.slice(1, 6);
    const prod5 = knownProducts.find(
      (p) => p.code === code5 || Number(p.code) === Number(code5),
    );
    if (prod5) {
      const priceCentavos = Number(digits.slice(6, 12));
      const weightGrams = Number(digits.slice(6, 11));

      // Se o produto tiver preço cadastrado e os centavos resultarem em peso plausível
      if (prod5.salePrice > 0 && priceCentavos > 0) {
        const calculatedWeight = Number((priceCentavos / 100 / prod5.salePrice).toFixed(3));
        if (calculatedWeight > 0 && calculatedWeight <= 50) {
          return {
            productCode: prod5.code,
            weightKg: calculatedWeight,
            totalPrice: priceCentavos / 100,
            mode: "price",
          };
        }
      }

      if (weightGrams > 0) {
        return {
          productCode: prod5.code,
          weightKg: weightGrams / 1000,
          totalPrice: prod5.salePrice > 0 ? (weightGrams / 1000) * prod5.salePrice : undefined,
          mode: "weight",
        };
      }
    }

    // 3. Tenta código de 6 dígitos
    const code6 = digits.slice(1, 7);
    const prod6 = knownProducts.find(
      (p) => p.code === code6 || Number(p.code) === Number(code6),
    );
    if (prod6) {
      const priceCentavos = Number(digits.slice(7, 12));
      const totalPrice = priceCentavos / 100;
      const weightKg =
        prod6.salePrice > 0
          ? Number((totalPrice / prod6.salePrice).toFixed(3))
          : 1;
      return {
        productCode: prod6.code,
        weightKg,
        totalPrice,
        mode: "price",
      };
    }
  }

  // Fallback quando não há produtos passados ou produto ainda não está cadastrado:
  // Se posições 5..7 tiverem '000', interpreta como 4 dígitos de código e preço total em centavos
  if (digits.slice(5, 8) === "000") {
    const code4 = digits.slice(1, 5);
    const priceCentavos = Number(digits.slice(5, 12));
    return {
      productCode: code4,
      weightKg: 1,
      totalPrice: priceCentavos / 100,
      mode: "price",
    };
  }

  // Padrão clássico: 5 dígitos de código + 5 dígitos de peso em gramas
  const code5 = digits.slice(1, 6);
  const weightGrams = Number(digits.slice(6, 11));
  return {
    productCode: code5,
    weightKg: weightGrams > 0 ? weightGrams / 1000 : 1,
    mode: "weight",
  };
}
