/**
 * Arquivo: src/services/api/salesHistoryService.ts
 * Objetivo: encapsula chamadas HTTP de histórico de vendas, registro de venda e recibos.
 * Entradas esperadas: recebe payloads já validados pelas telas e retorna respostas tipadas da API.
 */
import { apiRequest, requireEnvUrl } from "./apiClient";

const HISTORICO_VENDAS_API_URL = requireEnvUrl("VITE_HISTORICO_VENDAS_API_URL");

import { type FiscalDocumentDetailDto } from "./fiscalService";

export type SaleHistoryDto = {
  saleNumber: string;
  customerName: string;
  customerCpf: string;
  paymentType: string;
  totalAmount: string;
  operatorName: string;
  productCode: string;
  productName: string;
  quantity: number;
  unitPrice: string;
  desconto?: number;
  promocaoId?: string | null;
  itemTotal: string;
  saleDate: string;
  clientSaleId?: string;
  offlineReference?: string;
  status?: string;
  canceladoEm?: string | null;
  canceladoPorOperadorNome?: string | null;
  canceladoPorSupervisorNome?: string | null;
  canceladoJustificativa?: string | null;
  fiscalDocId?: string | null;
  fiscalModelo?: number | null;
  fiscalNumeroNf?: number | null;
  fiscalSerie?: number | null;
  fiscalStatus?: number | null;
  fiscalChaveAcesso?: string | null;
};

export type CancelledSaleDto = {
  vendaId: string;
  saleNumber: string;
  status: string;
  customerName: string;
  customerCpf: string;
  paymentType: string;
  totalAmount: string;
  operatorName: string;
  saleDate: string;
  canceladoEm: string;
  canceladoPorOperadorNome: string;
  canceladoPorSupervisorNome: string;
  canceladoJustificativa: string;
  totalItens: number;
  totalQuantidadeItens: number;
  itensResumo: string;
  items: SaleHistoryDto[];
};

export type CancelSalePayload = {
  supervisorId: string;
  supervisorPassword: string;
  justificativa: string;
};

export type AutorizarPrecoPayload = {
  supervisorId: string;
  supervisorPassword: string;
  productCode: string;
  precoNovo: number;
  motivo?: string;
};

export type AutorizarPrecoResult = {
  autorizacaoId: string;
  supervisorNome: string;
  precoTabela: number;
  precoNovo: number;
  expiraEm: string;
};

export type SalePaymentItemDto = {
  id: string;
  companyId: string;
  vendaId: string;
  paymentType: string;
  amount: number;
  cashGiven: number;
  changeAmount: number;
  createdAt: string;
};

export type SaleDetailFullDto = {
  vendaId: string;
  saleNumber: string;
  status?: string;
  customerName: string;
  customerCpf: string;
  paymentType: string;
  totalAmount: string;
  operatorName: string;
  saleDate: string;
  clientSaleId?: string;
  offlineReference?: string;
  canceladoEm?: string | null;
  canceladoPorOperadorNome?: string | null;
  canceladoPorSupervisorNome?: string | null;
  canceladoJustificativa?: string | null;
  items: SaleHistoryDto[];
  payments: SalePaymentItemDto[];
  documentoFiscal?: FiscalDocumentDetailDto | null;
};

export type SalePaymentDto = {
  paymentType: string;
  amount: number;
  cashGiven?: number;
  changeAmount?: number;
};

export type RegisterSalePayload = {
  clientSaleId?: string;
  eventId?: string;
  eventType?: string;
  occurredAt?: string;
  offlineReference?: string;
  payloadHash?: string;
  /** true só no reenvio de venda feita offline (SyncEngine). */
  reenvioOffline?: boolean;
  customerName: string;
  customerCpf: string;
  paymentType: string;
  totalAmount: string;
  operatorName: string;
  items: Array<{
    productCode: string;
    productName: string;
    quantity: number;
    unitPrice?: number;
    desconto?: number;
    itemTotal?: number;
    promocaoId?: string | null;
    /** Autorização de gerente para o preço alterado deste item (ver autorizarPreco). */
    autorizacaoPrecoId?: string | null;
  }>;
  payments?: SalePaymentDto[];
};

export type RegisterSaleResponse = {
  saleNumber: string;
  clientSaleId?: string;
  vendaId?: string;
  rows?: SaleHistoryDto[];
  fiscalQueued?: boolean;
  isReplay?: boolean;
};

export const salesHistoryService = {
  async list(desde?: string) {
    const url = desde
      ? `${HISTORICO_VENDAS_API_URL}?desde=${encodeURIComponent(desde)}`
      : HISTORICO_VENDAS_API_URL;
    const response = await apiRequest<SaleHistoryDto[]>(url);
    return response.data ?? [];
  },
  async getDetails(saleNumber: string) {
    const response = await apiRequest<SaleDetailFullDto>(
      `${HISTORICO_VENDAS_API_URL}/${encodeURIComponent(saleNumber)}`
    );
    return response.data ?? null;
  },
  async register(payload: RegisterSalePayload) {
    const response = await apiRequest<RegisterSaleResponse>(HISTORICO_VENDAS_API_URL, {
      method: "POST",
      body: JSON.stringify(payload),
    });
    return response.data;
  },
  async print(saleNumber: string) {
    const response = await apiRequest<{
      saleNumber: string;
      printedAt: string;
      items: number;
      rows: SaleHistoryDto[];
    }>(`${HISTORICO_VENDAS_API_URL}/${saleNumber}/imprimir`, {
      method: "POST",
    });
    return response.data;
  },
  /** Pede ao servidor a autorização (senha de gerente) para vender um produto por preço diferente do cadastrado. */
  async autorizarPreco(payload: AutorizarPrecoPayload) {
    const response = await apiRequest<AutorizarPrecoResult>(`${HISTORICO_VENDAS_API_URL}/autorizar-preco`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
    return response.data;
  },
  async cancel(saleNumber: string, payload: CancelSalePayload) {
    const response = await apiRequest<{
      saleNumber: string;
      canceladoEm: string;
      supervisorNome: string;
      itensEstornados: number;
    }>(`${HISTORICO_VENDAS_API_URL}/${encodeURIComponent(saleNumber)}/cancelar`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
    return response;
  },
  async listCancellations(params?: { de?: string; ate?: string }) {
    const query = new URLSearchParams();
    if (params?.de) query.set("de", params.de);
    if (params?.ate) query.set("ate", params.ate);
    const qs = query.toString();
    const url = `${HISTORICO_VENDAS_API_URL}/cancelamentos${qs ? `?${qs}` : ""}`;
    const response = await apiRequest<CancelledSaleDto[]>(url);
    return response.data ?? [];
  },
};
