/**
 * Arquivo: src/services/api/cashRegisterService.ts
 * Objetivo: encapsula chamadas HTTP de abertura, fechamento, sangria/reforço e status de caixa.
 * Entradas esperadas: recebe payloads já validados pelas telas e retorna respostas tipadas da API.
 */
import { apiRequest, requireEnvUrl } from "./apiClient";

const CAIXA_API_URL = requireEnvUrl("VITE_CAIXA_API_URL");

export type CashMovementType = "Reforco" | "Sangria";

export type CashMovementDto = {
  id: string;
  tipo: CashMovementType | string;
  valor: string;
  motivo: string;
  createdAt: string;
  operatorName: string;
};

export type PaymentBreakdownDto = {
  paymentType: string;
  total: string;
};

export type CashRegisterSessionDto = {
  id: string;
  status: string;
  openedAt: string;
  closedAt?: string | null;
  openingAmount: string;
  closingAmount: string;
  operatorId: string;
  operatorName: string;
  closedById: string;
  closedByName: string;
  note: string;
  elapsedMinutes: number;
  expectedCashAmount?: string | null;
  differenceAmount?: string | null;
  differenceReason?: string | null;
  movimentos: CashMovementDto[];
  paymentBreakdown?: PaymentBreakdownDto[] | null;
};

export type CashRegisterStatusDto = {
  state: "aberto" | "fechado" | "expirado" | string;
  canSell: boolean;
  blockReason: string;
  serverNow: string;
  currentSession?: CashRegisterSessionDto | null;
  lastSession?: CashRegisterSessionDto | null;
  history: CashRegisterSessionDto[];
  isReplay?: boolean;
};

export const cashRegisterService = {
  async status() {
    const response = await apiRequest<CashRegisterStatusDto>(`${CAIXA_API_URL}/status`);
    return response.data;
  },
  async open(openingAmount: string, eventId?: string, payloadHash?: string) {
    const response = await apiRequest<CashRegisterStatusDto>(`${CAIXA_API_URL}/abrir`, {
      method: "POST",
      body: JSON.stringify({ openingAmount, eventId, payloadHash }),
    });
    return response.data;
  },
  async close(closingAmount: string, note = "", differenceReason?: string, eventId?: string, payloadHash?: string) {
    const response = await apiRequest<CashRegisterStatusDto>(`${CAIXA_API_URL}/fechar`, {
      method: "POST",
      body: JSON.stringify({ closingAmount, note, differenceReason: differenceReason || null, eventId, payloadHash }),
    });
    return response.data;
  },
  async registrarMovimento(
    tipo: CashMovementType,
    valor: string,
    motivo: string,
    eventId?: string,
    payloadHash?: string,
  ) {
    const response = await apiRequest<CashRegisterStatusDto>(`${CAIXA_API_URL}/movimento`, {
      method: "POST",
      body: JSON.stringify({ tipo, valor, motivo, eventId, payloadHash }),
    });
    return response.data;
  },
};
