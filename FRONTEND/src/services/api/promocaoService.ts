/**
 * Arquivo: src/services/api/promocaoService.ts
 * Objetivo: encapsula chamadas HTTP para promoções e preços dinâmicos.
 */
import { apiRequest } from "./apiClient";

const PROMOCAO_API_URL =
  import.meta.env.VITE_PROMOCAO_API_URL ?? "http://localhost:5260/api/Promocao";

export type TipoPromocao =
  | "desconto_percentual"
  | "desconto_valor"
  | "preco_fixo"
  | "leve_x_pague_y"
  | "combo_quantidade"
  | "preco_atacado";

export type Promocao = {
  id: string;
  companyId: string;
  nome: string;
  tipo: TipoPromocao;
  valorDesconto: number | null;
  precoFixo: number | null;
  quantidadeLeva: number | null;
  quantidadePaga: number | null;
  quantidadeMinima: number | null;
  inicioVigencia: string;
  fimVigencia: string;
  ativa: boolean;
  categoriaId: string | null;
  categoriaNome?: string | null;
  criadoPor: string;
  criadoEm: string;
  produtoIds: string[];
};

export type PromocaoPayload = {
  id?: string;
  nome: string;
  tipo: TipoPromocao;
  valorDesconto?: number | null;
  precoFixo?: number | null;
  quantidadeLeva?: number | null;
  quantidadePaga?: number | null;
  quantidadeMinima?: number | null;
  inicioVigencia: string;
  fimVigencia: string;
  ativa?: boolean;
  categoriaId?: string | null;
  produtoIds?: string[];
};

export type PromocaoResultado = {
  promocaoId: string;
  nome: string;
  quantidadeVendas: number;
  receitaBruta: number;
  receitaLiquida: number;
  descontoTotal: number;
  margemLiquida: number;
};

export const promocaoService = {
  async list() {
    const response = await apiRequest<Promocao[]>(PROMOCAO_API_URL);
    return response.data ?? [];
  },

  async listAtivas() {
    const response = await apiRequest<Promocao[]>(`${PROMOCAO_API_URL}/ativas`);
    return response.data ?? [];
  },

  async getById(id: string) {
    const response = await apiRequest<Promocao>(`${PROMOCAO_API_URL}/${id}`);
    return response.data;
  },

  async getResultado(id: string) {
    const response = await apiRequest<PromocaoResultado>(`${PROMOCAO_API_URL}/${id}/resultado`);
    return response.data;
  },

  async create(payload: PromocaoPayload) {
    const response = await apiRequest<Promocao>(PROMOCAO_API_URL, {
      method: "POST",
      body: JSON.stringify(payload),
    });
    return response.data;
  },

  async update(id: string, payload: PromocaoPayload) {
    const response = await apiRequest<Promocao>(`${PROMOCAO_API_URL}/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
    return response.data;
  },

  async toggleStatus(id: string, ativa: boolean) {
    const response = await apiRequest<{ success: boolean }>(`${PROMOCAO_API_URL}/${id}/status`, {
      method: "PATCH",
      body: JSON.stringify({ ativa }),
    });
    return response.success;
  },

  async remove(id: string) {
    const response = await apiRequest<{ success: boolean }>(`${PROMOCAO_API_URL}/${id}`, {
      method: "DELETE",
    });
    return response.success;
  },
};
