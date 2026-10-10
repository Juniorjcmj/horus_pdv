/**
 * Arquivo: src/services/api/nfeImportService.ts
 * Objetivo: encapsula chamadas HTTP de importação de produtos a partir de XML de NF-e de compra.
 * Entradas esperadas: recebe o XML lido do arquivo escolhido pelo usuário e os itens revisados
 *           antes de confirmar a gravação.
 */
import { apiRequest, requireEnvUrl } from "./apiClient";

const NFE_IMPORT_API_URL = requireEnvUrl("VITE_NFE_IMPORT_API_URL");

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
  codigoFornecedor?: string | null;
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

export type NfeImportDocumento = { xmlBase64?: string | null; chaveAcesso?: string | null; modelo: number; numeroNota: string; serie: string };

export type NfeImportPreview = {
  documento?: NfeImportDocumento;
  modelo?: number;
  numeroNota: string;
  serie: string;
  fornecedor: NfeImportFornecedorPreview;
  itens: NfeImportItemPreview[];
};

export type NfeImportItemInput = {
  numeroItem: number;
  produtoExistenteId: string | null;
  produtoExistenteNome?: string | null;
  codigoFornecedor?: string | null;
  productCode: string;
  productName: string;
  gtin: string;
  ncm: string;
  cest: string | null;
  unidadeComercial: string;
  quantidade: string;
  precoCusto: string;
  precoVenda: string;
  /** AAAA-MM-DD, opcional. Sem ela vale o prazo padrão da categoria (se existir). */
  dataValidade?: string | null;
  /** Número/código do lote impresso na embalagem, opcional. */
  numeroLote?: string | null;
  /** Margem desejada (% sobre o custo, pt-BR) do produto NOVO, só quando digitada pelo operador. */
  margemPercentual?: string | null;
};

export type NfeImportConfirmPayload = {
  documento?: NfeImportDocumento;
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
  notaEntradaId?: string;
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
      timeoutMs: 30_000,
    });
    return response.data ? { ...response.data, documento: response.data.documento ?? { xmlBase64, modelo: response.data.modelo ?? 55, numeroNota: response.data.numeroNota, serie: response.data.serie } } : null;
  },
  async previewPorChave(chaveAcesso: string) {
    // Consulta SEFAZ pode levar 10-40s (query + Ciência da Operação + retries)
    const response = await apiRequest<NfeImportPreview>(`${NFE_IMPORT_API_URL}/buscar-sefaz`, {
      method: "POST",
      body: JSON.stringify({ chaveAcesso }),
      timeoutMs: 60_000,
    });
    return response.data ?? null;
  },
  async confirmar(payload: NfeImportConfirmPayload) {
    const response = await apiRequest<NfeImportResult>(`${NFE_IMPORT_API_URL}/confirmar`, {
      method: "POST",
      body: JSON.stringify(payload),
      timeoutMs: 60_000,
    });
    return response.data ?? null;
  },
  async listarNotas(busca = "", pagina = 1) {
    const query = new URLSearchParams({ busca, pagina: String(pagina), tamanhoPagina: "20" });
    const response = await apiRequest<NotaEntradaPagina>(`${NFE_IMPORT_API_URL}/notas-entrada?${query}`);
    if (!response.data) throw new Error("Não foi possível carregar as notas de entrada.");
    return response.data;
  },
  async obterNota(id: string) {
    const response = await apiRequest<NotaEntradaDetalhe>(`${NFE_IMPORT_API_URL}/notas-entrada/${encodeURIComponent(id)}`);
    if (!response.data) throw new Error("Nota de entrada não encontrada.");
    return response.data;
  },
  async baixarXml(nota: NotaEntradaResumo) {
    const controller = new AbortController();
    const timer = window.setTimeout(() => controller.abort(), 30_000);
    try {
      const response = await fetch(`${NFE_IMPORT_API_URL}/notas-entrada/${encodeURIComponent(nota.id)}/xml`, { credentials: "include", signal: controller.signal });
      if (!response.ok) throw new Error(response.status === 404 ? "XML não disponível para esta entrada." : "Não foi possível baixar o XML. Confira sua conexão e sessão.");
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url; link.download = `nota-entrada-${nota.chaveAcesso || nota.id}.xml`;
      document.body.appendChild(link); link.click(); link.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } finally { window.clearTimeout(timer); }
  },
};


export type NotaEntradaResumo = {
  id: string; chaveAcesso: string | null; modelo: number; numeroNota: string; serie: string;
  fornecedorNome: string; fornecedorCnpj: string; dataEmissao: string | null;
  valorNota: number | null; valorEntrada: number; quantidadeItens: number; origem: string;
  temXml: boolean; criadaEm: string; usuarioNome: string;
};
export type NotaEntradaPagina = { notas: NotaEntradaResumo[]; total: number; pagina: number; tamanhoPagina: number };
export type NotaEntradaDetalhe = { nota: NotaEntradaResumo; entrada: { fornecedor: NfeImportFornecedorPreview; itens: NfeImportItemInput[] } };
