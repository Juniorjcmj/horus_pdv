/**
 * Arquivo: src/services/api/fiadoService.ts
 * Objetivo: encapsula chamadas HTTP para controle de fiado, conta corrente, devedores e extrato.
 */
import { apiRequest, requireEnvUrl } from "./apiClient";

const FIADO_API_URL = requireEnvUrl("VITE_FIADO_API_URL");

export type FiadoMovimento = {
  id: string;
  companyId: string;
  clienteId: string;
  clienteNome: string;
  clienteDocument: string;
  tipo: 1 | 2; // 1 = Débito, 2 = Crédito/Recebimento
  valor: number;
  saldoAnterior: number;
  saldoAtual: number;
  vendaId?: string | null;
  formaPagamento?: string | null;
  observacao?: string | null;
  operadorNome: string;
  criadoEm: string;
};

export type FiadoDevedor = {
  clienteId: string;
  clienteNome: string;
  document: string;
  telephone: string;
  cellphone: string;
  limiteCredito: number;
  saldoDevedor: number;
  ultimaCompra?: string | null;
  diasSemPagamento?: number | null;
};

export type FiadoResumo = {
  totalAReceber: number;
  quantidadeDevedores: number;
  inadimplencia30Dias: number;
  inadimplencia60Dias: number;
  maiorDebito: number;
};

export type RecebimentoRequest = {
  clienteId: string;
  valor: number;
  formaPagamento: string;
  observacao?: string;
};

export const fiadoService = {
  async receber(payload: RecebimentoRequest) {
    const response = await apiRequest<FiadoMovimento>(`${FIADO_API_URL}/receber`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
    return response.data;
  },

  async extrato(clienteId: string, dataInicio?: string, dataFim?: string) {
    const params = new URLSearchParams();
    if (dataInicio) params.append("dataInicio", dataInicio);
    if (dataFim) params.append("dataFim", dataFim);
    const qs = params.toString() ? `?${params.toString()}` : "";
    const response = await apiRequest<FiadoMovimento[]>(`${FIADO_API_URL}/extrato/${clienteId}${qs}`);
    return response.data ?? [];
  },

  async listarDevedores(busca?: string) {
    const params = new URLSearchParams();
    if (busca) params.append("busca", busca);
    const qs = params.toString() ? `?${params.toString()}` : "";
    const response = await apiRequest<FiadoDevedor[]>(`${FIADO_API_URL}/devedores${qs}`);
    return response.data ?? [];
  },

  async resumo() {
    const response = await apiRequest<FiadoResumo>(`${FIADO_API_URL}/resumo`);
    return response.data;
  },
};
