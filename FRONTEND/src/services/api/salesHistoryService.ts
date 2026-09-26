/**
 * Arquivo: src/services/api/salesHistoryService.ts
 * Objetivo: encapsula chamadas HTTP de histórico de vendas, registro de venda e recibos.
 * Entradas esperadas: recebe payloads já validados pelas telas e retorna respostas tipadas da API.
 */
import { apiRequest, requireEnvUrl } from "./apiClient";

const HISTORICO_VENDAS_API_URL = requireEnvUrl("VITE_HISTORICO_VENDAS_API_URL");

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
  itemTotal: string;
  saleDate: string;
  clientSaleId?: string;
  offlineReference?: string;
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
  async list() {
    const response = await apiRequest<SaleHistoryDto[]>(HISTORICO_VENDAS_API_URL);
    return response.data ?? [];
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
};
