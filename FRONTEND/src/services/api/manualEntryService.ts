import { apiRequest, requireEnvUrl } from "./apiClient";
import type { NfeImportItemInput, NfeImportResult } from "./nfeImportService";

export type ManualEntryItem = NfeImportItemInput & {
  quantidadeCompra: string; unidadeCompra: string; fatorConversao: string; custoCompra: string;
  alterarPrecoVenda: boolean; estoqueAntes?: string; estoqueDepois?: string;
};
export type ManualEntryPayload = {
  operacaoId: string; dataCompra: string; fornecedorId: string | null; tipoDocumento: string;
  numeroDocumento: string; chaveAcesso: string | null; observacao: string;
  comprovante: { nome: string; base64: string } | null; itens: ManualEntryItem[];
};

export const manualEntryService = {
  async confirm(payload: ManualEntryPayload) {
    const response = await apiRequest<NfeImportResult>(`${requireEnvUrl("VITE_PRODUTO_API_URL")}/entrada-manual`, {
      method: "POST", body: JSON.stringify(payload), timeoutMs: 60_000,
    });
    if (!response.data?.notaEntradaId) throw new Error("A confirmação não trouxe o número da entrada. Tente confirmar novamente com os mesmos dados.");
    return response.data;
  },
};

export function manualNumber(value: string) {
  const text = value.trim();
  if (!/^(?:\d+(?:[.,]\d{1,4})?|\d{1,3}(?:\.\d{3})+(?:,\d{1,4})?)$/.test(text)) return NaN;
  return Number(text.includes(",") ? text.replace(/\./g, "").replace(",", ".") : text);
}

export function manualConversion(item: ManualEntryItem) {
  const purchased = manualNumber(item.quantidadeCompra);
  const factor = manualNumber(item.fatorConversao);
  const packCost = manualNumber(item.custoCompra);
  return { quantity: purchased * factor, cost: factor > 0 ? Math.round((packCost / factor + Number.EPSILON) * 10000) / 10000 : NaN,
    total: Math.round((purchased * packCost + Number.EPSILON) * 100) / 100 };
}

export function readManualAttachment(file: File): Promise<{ nome: string; base64: string }> {
  if (file.size === 0 || file.size > 10 * 1024 * 1024 || !/\.(pdf|png|jpe?g|xml)$/i.test(file.name))
    return Promise.reject(new Error("Selecione JPG, PNG, PDF ou XML com até 10 MB."));
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve({ nome: file.name, base64: String(reader.result).split(",")[1] });
    reader.onerror = () => reject(new Error("Não foi possível ler o comprovante. Selecione novamente."));
    reader.readAsDataURL(file);
  });
}
