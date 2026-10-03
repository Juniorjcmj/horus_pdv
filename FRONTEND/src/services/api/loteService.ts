/**
 * Arquivo: src/services/api/loteService.ts
 * Objetivo: encapsula chamadas HTTP do controle de validade por lote (alertas, registro e prazos por categoria).
 * Entradas esperadas: recebe payloads já validados pelas telas e retorna respostas tipadas da API.
 */
import { apiRequest, requireEnvUrl } from "./apiClient";

const LOTES_API_URL = `${requireEnvUrl("VITE_PRODUTO_API_URL")}/lotes`;

export type LoteFaixa = "vencido" | "critico" | "atencao";

export type LoteAlertaDto = {
  id: string;
  produtoId: string;
  numeroLote: string;
  /** AAAA-MM-DD */
  dataValidade: string;
  diasParaVencer: number;
  qtdInicial: number;
  /** Saldo estimado no consumo FEFO (a venda ainda não baixa lote). */
  qtdEstimada: number;
  origem: string;
  validadePadrao: boolean;
  criadoEm: string;
  productCode: string;
  productName: string;
  categoriaNome: string;
  janelaAlertaDias: number;
  faixa: LoteFaixa;
  custoUnitario: number;
  precoVenda: number;
  valorEmRisco: number;
};

export type LoteAlertasResumoDto = {
  vencidos: number;
  criticos: number;
  atencao: number;
  valorEmRisco: number;
  produtosSemLote: number;
  itens: LoteAlertaDto[];
};

export type CategoriaValidadeDto = {
  categoriaId: string;
  nome: string;
  categoriaPaiId: string | null;
  prazoPadraoDias: number | null;
  diasAlerta: number | null;
};

export type RegistrarLotePayload = {
  produtoId: string;
  /** AAAA-MM-DD */
  dataValidade: string;
  quantidade: number;
  numeroLote?: string;
};

export const loteService = {
  async alertas() {
    const response = await apiRequest<LoteAlertasResumoDto>(`${LOTES_API_URL}/alertas`);
    return response.data;
  },
  async registrar(payload: RegistrarLotePayload) {
    return apiRequest<object>(`${LOTES_API_URL}/registrar`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  async categorias() {
    const response = await apiRequest<CategoriaValidadeDto[]>(`${LOTES_API_URL}/categorias`);
    return response.data ?? [];
  },
  async salvarCategoria(categoriaId: string, payload: { prazoPadraoDias: number | null; diasAlerta: number | null }) {
    return apiRequest<object>(`${LOTES_API_URL}/categorias/${encodeURIComponent(categoriaId)}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
};
