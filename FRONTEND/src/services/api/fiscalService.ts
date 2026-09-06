/**
 * Arquivo: src/services/api/fiscalService.ts
 * Objetivo: encapsula chamadas HTTP de consulta, reemissão, cancelamento e inutilização de NFC-e.
 * Entradas esperadas: recebe payloads já validados pelas telas e retorna respostas tipadas da API.
 */
import { apiRequest } from "./apiClient";

const NFCE_API_URL = import.meta.env.VITE_NFCE_API_URL ?? "http://localhost:5260/api/Nfce";

/** Espelha StatusDocumentoFiscal (API/NETCORE/Services/Fiscal/FiscalContracts.cs). */
export const FISCAL_STATUS = {
  Rascunho: 0,
  Assinado: 1,
  Transmitindo: 2,
  Autorizado: 3,
  Rejeitado: 4,
  Denegado: 5,
  Cancelado: 6,
  Inutilizado: 7,
  ContingenciaPendente: 8,
} as const;

export type FiscalStatus = (typeof FISCAL_STATUS)[keyof typeof FISCAL_STATUS];

export type FiscalDocumentDto = {
  id: string;
  saleNumber: string;
  serie: number;
  numeroNf: number;
  status: FiscalStatus;
  chaveAcesso: string | null;
  protocolo: string | null;
  motivoStatus: string | null;
  dhAutorizacao: string | null;
  criadoEm: string;
  tentativas: number;
};

export type FiscalDocumentDetailDto = FiscalDocumentDto & {
  qrCodeUrl: string | null;
};

export function fiscalStatusLabel(status: FiscalStatus): string {
  switch (status) {
    case FISCAL_STATUS.Rascunho:
      return "Rascunho";
    case FISCAL_STATUS.Assinado:
    case FISCAL_STATUS.Transmitindo:
      return "Transmitindo";
    case FISCAL_STATUS.Autorizado:
      return "Autorizado";
    case FISCAL_STATUS.Rejeitado:
      return "Rejeitado";
    case FISCAL_STATUS.Denegado:
      return "Denegado";
    case FISCAL_STATUS.Cancelado:
      return "Cancelado";
    case FISCAL_STATUS.Inutilizado:
      return "Inutilizado";
    case FISCAL_STATUS.ContingenciaPendente:
      return "Contingência";
    default:
      return "—";
  }
}

export function fiscalStatusBadgeClass(status: FiscalStatus): string {
  switch (status) {
    case FISCAL_STATUS.Autorizado:
      return "border border-success/30 bg-success/15 text-success";
    case FISCAL_STATUS.Rejeitado:
    case FISCAL_STATUS.Denegado:
      return "border border-primary/30 bg-primary/15 text-primary";
    case FISCAL_STATUS.Cancelado:
    case FISCAL_STATUS.Inutilizado:
      return "border border-border-secondary bg-bg-primary text-text-secondary";
    default:
      return "border border-accent/30 bg-accent/15 text-accent";
  }
}

export const fiscalService = {
  async list() {
    const response = await apiRequest<FiscalDocumentDto[]>(NFCE_API_URL);
    return response.data ?? [];
  },
  async getBySaleNumber(saleNumber: string) {
    try {
      const response = await apiRequest<FiscalDocumentDetailDto>(`${NFCE_API_URL}/${saleNumber}`);
      return response.data ?? null;
    } catch {
      return null;
    }
  },
  async reemitir(id: string) {
    const response = await apiRequest<object>(`${NFCE_API_URL}/${id}/reemitir`, { method: "POST" });
    return response.message;
  },
  async cancelar(id: string, justificativa: string) {
    const response = await apiRequest<object>(`${NFCE_API_URL}/${id}/cancelar`, {
      method: "POST",
      body: JSON.stringify({ justificativa }),
    });
    return response.message;
  },
  async inutilizar(payload: {
    serie: number;
    numeroInicial: number;
    numeroFinal: number;
    justificativa: string;
  }) {
    const response = await apiRequest<object>(`${NFCE_API_URL}/inutilizar`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
    return response.message;
  },
};
