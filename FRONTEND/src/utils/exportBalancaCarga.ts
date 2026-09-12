/**
 * Arquivo: src/utils/exportBalancaCarga.ts
 * Objetivo: gera arquivos de carga de produtos para balanças etiquetadoras
 *           (Triunfo Quantum 30 T, Toledo MGV5/6, Filizola) para sincronização via rede.
 */

export interface BalancaExportItem {
  code: string;
  name: string;
  salePrice: number; // Preço em reais (ex.: 8.99)
  unit: string;      // "KG", "UN", "BDJ"
  validityDays?: number;
}

/**
 * Remove acentos e caracteres especiais para compatibilidade com o display da balança.
 */
function sanitizeAscii(str: string): string {
  return str
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-zA-Z0-9\s/.-]/g, "")
    .toUpperCase();
}

/**
 * Formato Padrão Toledo MGV5 / MGV6 (amplamente aceito pelo software da Triunfo Quantum).
 * Layout por linha (ITENSTXT.TXT):
 * - [00..01] Depto (2 digitos, ex "01")
 * - [02..02] Tipo (1 digito: 0 = peso/kg, 1 = unidade)
 * - [03..08] Código PLU (6 digitos com zeros à esquerda)
 * - [09..14] Preço de venda (6 digitos, centavos, ex 000899 = R$ 8,99)
 * - [15..17] Dias de validade (3 digitos, ex "000" para sem validade)
 * - [18..42] Descrição (25 caracteres)
 */
export function generateMgvCargaTxt(items: BalancaExportItem[], depto = "01"): string {
  const lines = items.map((item) => {
    const isWeight = item.unit.trim().toUpperCase() === "KG";
    const typeDigit = isWeight ? "0" : "1";
    const numericCode = item.code.replace(/\D/g, "");
    const codePadded = numericCode.padStart(6, "0").slice(-6);

    // Preço em centavos com 6 dígitos (ex: 8.99 -> 899 -> "000899")
    const priceCents = Math.round(item.salePrice * 100);
    const pricePadded = String(priceCents).padStart(6, "0").slice(-6);

    const validityPadded = String(item.validityDays ?? 0).padStart(3, "0").slice(-3);

    const nameSanitized = sanitizeAscii(item.name);
    const namePadded = nameSanitized.padEnd(25, " ").slice(0, 25);

    return `${depto.padStart(2, "0")}${typeDigit}${codePadded}${pricePadded}${validityPadded}${namePadded}`;
  });

  return lines.join("\r\n") + "\r\n";
}

/**
 * Formato Padrão Triunfo CSV/TXT delimitado (Código;Tipo;Descrição;Preço;Validade).
 * Lido diretamente pelo Software Triunfo (ST).
 */
export function generateTriunfoCargaTxt(items: BalancaExportItem[]): string {
  const lines = items.map((item) => {
    const isWeight = item.unit.trim().toUpperCase() === "KG";
    const typeStr = isWeight ? "P" : "U"; // P = Peso, U = Unidade
    const numericCode = item.code.replace(/\D/g, "");
    const priceFormatted = item.salePrice.toFixed(2).replace(".", ",");
    const nameSanitized = sanitizeAscii(item.name).slice(0, 30);
    const validity = item.validityDays ?? 0;

    return `${numericCode};${typeStr};${nameSanitized};${priceFormatted};${validity}`;
  });

  return lines.join("\r\n") + "\r\n";
}

/**
 * Dispara o download de um arquivo de texto no navegador.
 */
export function downloadTextFile(filename: string, content: string): void {
  const blob = new Blob([content], { type: "text/plain;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}
