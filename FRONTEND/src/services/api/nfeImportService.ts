/**
 * Arquivo: src/services/api/nfeImportService.ts
 * Objetivo: encapsula chamadas HTTP de importação de produtos a partir de XML de NF-e de compra.
 * Entradas esperadas: recebe o XML lido do arquivo escolhido pelo usuário e os itens revisados
 *           antes de confirmar a gravação.
 */
import { apiRequest } from "./apiClient";

const NFE_IMPORT_API_URL =
  import.meta.env.VITE_NFE_IMPORT_API_URL ?? "http://localhost:5260/api/NfeImport";

export type NfeImportFornecedorPreview = {
  jaExiste: boolean;
  cnpj: string;
  companyName: string;
  fantasyName: string;
  cep: string;
  city: string;
  state: string;
  address: string;
  neighborhood: string;
  number: string;
  telephone: string;
};

export type NfeImportItemPreview = {
  numeroItem: number;
  produtoExistenteId: string | null;
  produtoExistenteNome: string | null;
  productCode: string;
  productName: string;
  gtin: string;
  ncm: string;
  cest: string | null;
  unidadeComercial: string;
  quantidade: string;
  precoCusto: string;
  precoVendaSugerido: string;
};

export type NfeImportPreview = {
  numeroNota: string;
  serie: string;
  fornecedor: NfeImportFornecedorPreview;
  itens: NfeImportItemPreview[];
};

export type NfeImportItemInput = {
  numeroItem: number;
  produtoExistenteId: string | null;
  productCode: string;
  productName: string;
  gtin: string;
  ncm: string;
  cest: string | null;
  unidadeComercial: string;
  quantidade: string;
  precoCusto: string;
  precoVenda: string;
};

export type NfeImportConfirmPayload = {
  fornecedor: {
    cnpj: string;
    companyName: string;
    fantasyName: string;
    cep: string;
    city: string;
    state: string;
    address: string;
    neighborhood: string;
    number: string;
    telephone: string;
  };
  itens: NfeImportItemInput[];
};

export type NfeImportResult = {
  fornecedorCriado: boolean;
  produtosCriados: number;
  produtosAtualizados: number;
};

function readFileAsBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = reader.result as string;
      // FileReader.readAsDataURL devolve "data:<mime>;base64,<conteudo>" — só o conteúdo interessa.
      const base64 = result.includes(",") ? result.split(",", 2)[1] : result;
      resolve(base64);
    };
    reader.onerror = () => reject(reader.error ?? new Error("Falha ao ler o arquivo."));
    reader.readAsDataURL(file);
  });
}

export const nfeImportService = {
  async preview(file: File) {
    const xmlBase64 = await readFileAsBase64(file);
    const response = await apiRequest<NfeImportPreview>(`${NFE_IMPORT_API_URL}/preview`, {
      method: "POST",
      body: JSON.stringify({ xmlBase64 }),
    });
    return response.data ?? null;
  },
  async confirmar(payload: NfeImportConfirmPayload) {
    const response = await apiRequest<NfeImportResult>(`${NFE_IMPORT_API_URL}/confirmar`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
    return response.data ?? null;
  },
};
