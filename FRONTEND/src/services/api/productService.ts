/**
 * Arquivo: src/services/api/productService.ts
 * Objetivo: encapsula chamadas HTTP de cadastro, estoque e manutenção de produtos.
 * Entradas esperadas: recebe payloads já validados pelas telas e retorna respostas tipadas da API.
 */
import { apiRequest, requireEnvUrl } from "./apiClient";

const PRODUTO_API_URL = requireEnvUrl("VITE_PRODUTO_API_URL");

export type ProductDto = {
  id: string;
  productImageUrl: string;
  productImageName: string;
  productName: string;
  productCode: string;
  productSupplier: string;
  productDescription: string;
  productQnt: string;
  estoqueMinimo: string;
  productUnitPrice: string;
  productSalePrice: string;
  totalPriceOnProduct: string;
  lucro: string;
  margemDesejadaPercentual: string | null;
  categoriaId?: string | null;
  categoriaNome?: string | null;

  // Controle de validade
  dataValidade?: string | null;
  controlaValidade?: boolean;
  diasAlertaValidade?: number;
  diasRestantes?: number | null;

  // Unidades de medida (compra/conversão)
  unidadeCompra: string;
  fatorConversao: string;
  qtdEmbalagem: string;

  // Marca e fabricante
  marca?: string | null;
  fabricante?: string | null;
  referenciaFabricante?: string | null;

  // Peso e dimensões
  pesoLiquidoKg: string;
  pesoBrutoKg: string;
  larguraCm: string;
  alturaCm: string;
  comprimentoCm: string;

  // Estoque expandido
  estoqueMaximo: string;
  localizacaoEstoque?: string | null;

  // Dados de custo detalhados
  custoMedio: string;
  custoComImposto: string;
  custoSemImposto: string;

  // Campos comerciais
  descontoMaximoPercentual: string;
  comissaoPercentual: string;
  markupCadastrado: string;
  markupPraticado: string;

  // Dados fiscais (NFC-e modelo 65)
  ncm: string;
  cest: string | null;
  cfop: string;
  origemMercadoria: number;
  unidadeComercial: string;
  unidadeTributavel: string;
  gtin: string;
  csosnIcms: string | null;
  cstIcms: string | null;
  aliquotaIcms: string;
  cstPis: string;
  cstCofins: string;
  cstIbsCbs: string | null;
  cClassTrib: string | null;
};

export type ProductPayload = Omit<ProductDto, "id">;

export type VencimentoResumo = {
  vencidos: number;
  venceEm7Dias: number;
  venceEm15Dias: number;
  venceEm30Dias: number;
  totalControlados: number;
  semDataInformada: number;
};

export const productService = {
  async list() {
    const response = await apiRequest<ProductDto[]>(PRODUTO_API_URL);
    return response.data ?? [];
  },
  async create(payload: ProductPayload) {
    const response = await apiRequest<ProductDto>(PRODUTO_API_URL, {
      method: "POST",
      body: JSON.stringify(payload),
    });
    return response.data;
  },
  async update(id: string, payload: ProductPayload) {
    const response = await apiRequest<ProductDto>(`${PRODUTO_API_URL}/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
    return response.data;
  },
  async remove(id: string) {
    await apiRequest<object>(`${PRODUTO_API_URL}/${id}`, { method: "DELETE" });
  },
  async importarLegado() {
    const response = await apiRequest<{ rowsAffected: number }>(`${PRODUTO_API_URL}/importar-legado`, {
      method: "POST",
    });
    return response;
  },
  async listVencimentos(dias: number = 15) {
    const response = await apiRequest<ProductDto[]>(`${PRODUTO_API_URL}/vencimentos?dias=${dias}`);
    return response.data ?? [];
  },
  async getResumoVencimentos() {
    const response = await apiRequest<VencimentoResumo>(`${PRODUTO_API_URL}/vencimentos/resumo`);
    return response.data;
  },
  async updateValidade(id: string, dataValidade: string | null) {
    const response = await apiRequest<object>(`${PRODUTO_API_URL}/${id}/validade`, {
      method: "PUT",
      body: JSON.stringify({ dataValidade }),
    });
    return response;
  },

  async adjustStock(
    id: string,
    payload: { tipo: "entrada" | "saida"; quantidade: number; motivo: string },
  ) {
    const response = await apiRequest<object>(`${PRODUTO_API_URL}/${id}/ajuste-estoque`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
    return response;
  },
};
