/**
 * Arquivo: src/services/api/fiscalService.ts
 * Objetivo: encapsula chamadas HTTP de consulta, reemissão, cancelamento e inutilização de NFC-e.
 * Entradas esperadas: recebe payloads já validados pelas telas e retorna respostas tipadas da API.
 */
import { apiRequest, requireEnvUrl } from "./apiClient";

const NFCE_API_URL = requireEnvUrl("VITE_NFCE_API_URL");
const NFE_API_URL = requireEnvUrl("VITE_NFE_API_URL");

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
  totalAmount?: string | null;
  customerName?: string | null;
  customerCpf?: string | null;
  paymentType?: string | null;
  hasXml?: boolean;
  hasCancelXml?: boolean;
};

export type FiscalDocumentItemDto = {
  productCode: string;
  productName: string;
  quantity: number;
  unitPrice: string;
  itemTotal: string;
  ncm?: string | null;
  cest?: string | null;
  cfop?: string | null;
  unidadeComercial?: string | null;
};

export type FiscalDocumentDetailDto = FiscalDocumentDto & {
  qrCodeUrl: string | null;
};

export type CancelarComSupervisorPayload = {
  supervisorId: string;
  supervisorPassword: string;
  justificativa: string;
};

export type CancelarComSupervisorResult = {
  documentoId: string;
  protocoloCancelamento?: string;
  motivoStatus?: string;
  supervisorNome?: string;
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
  async buscarPorCodigo(codigo: string) {
    try {
      const response = await apiRequest<FiscalDocumentDetailDto>(
        `${NFCE_API_URL}/buscar/${encodeURIComponent(codigo.trim())}`
      );
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
  async cancelarComSupervisor(id: string, payload: CancelarComSupervisorPayload) {
    const response = await apiRequest<CancelarComSupervisorResult>(
      `${NFCE_API_URL}/${encodeURIComponent(id)}/cancelar-com-supervisor`,
      {
        method: "POST",
        body: JSON.stringify(payload),
      }
    );
    return response;
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
  async exportarXmlsMes(ano: number, mes: number): Promise<void> {
    const url = `${NFCE_API_URL}/exportar-mes?ano=${encodeURIComponent(ano)}&mes=${encodeURIComponent(mes)}`;
    const response = await fetch(url, {
      credentials: "include",
    });

    if (!response.ok) {
      let errorMessage = "Erro ao exportar XMLs.";
      try {
        const errorJson = (await response.json()) as { message?: string };
        if (errorJson?.message) errorMessage = errorJson.message;
      } catch {
        // Usa mensagem padrão caso não seja json
      }
      throw new Error(errorMessage);
    }

    const blob = await response.blob();
    const downloadUrl = window.URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = downloadUrl;
    link.download = `NFCe_XMLs_${ano}_${String(mes).padStart(2, "0")}.zip`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    window.URL.revokeObjectURL(downloadUrl);
  },
  async downloadXml(id: string, chave: string, tipo?: "autorizado" | "cancelamento"): Promise<void> {
    const query = tipo ? `?tipo=${encodeURIComponent(tipo)}` : "";
    const url = `${NFCE_API_URL}/${encodeURIComponent(id)}/xml${query}`;
    const response = await fetch(url, {
      credentials: "include",
    });

    if (!response.ok) {
      let errorMessage = "Erro ao baixar XML.";
      try {
        const errorJson = (await response.json()) as { message?: string };
        if (errorJson?.message) errorMessage = errorJson.message;
      } catch {
        // Fallback
      }
      throw new Error(errorMessage);
    }

    const blob = await response.blob();
    const downloadUrl = window.URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = downloadUrl;
    const sufixo = tipo === "cancelamento" ? "-procEventoCanc.xml" : "-nfe.xml";
    link.download = `${chave || id}${sufixo}`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    window.URL.revokeObjectURL(downloadUrl);
  },
  async getItens(id: string): Promise<FiscalDocumentItemDto[]> {
    const response = await apiRequest<FiscalDocumentItemDto[]>(`${NFCE_API_URL}/${encodeURIComponent(id)}/itens`);
    return response.data ?? [];
  },
};

// ---------------------------------------------------------------------------
// NF-e modelo 55 (venda para empresas)
// ---------------------------------------------------------------------------

export type NfeDestinatario = {
  cpfCnpj: string;
  nome: string;
  indIeDest: number;
  inscricaoEstadual?: string | null;
  logradouro: string;
  numero: string;
  complemento?: string | null;
  bairro: string;
  codigoMunicipioIbge: string;
  nomeMunicipio: string;
  uf: string;
  cep: string;
  fone?: string | null;
  email?: string | null;
};

export type EmitirNfePayload = {
  saleNumber: string;
  destinatario: NfeDestinatario;
  naturezaOperacao?: string;
  modalidadeFrete?: number;
};

export const nfeService = {
  async emitir(payload: EmitirNfePayload) {
    const response = await apiRequest<{ documentoId: string }>(`${NFE_API_URL}/emitir`, {
      method: "POST",
      body: JSON.stringify(payload),
      timeoutMs: 30_000,
    });
    return response;
  },

  async list() {
    const response = await apiRequest<FiscalDocumentDto[]>(NFE_API_URL);
    return response.data ?? [];
  },

  async cancelar(id: string, justificativa: string) {
    const response = await apiRequest<object>(`${NFE_API_URL}/${encodeURIComponent(id)}/cancelar`, {
      method: "POST",
      body: JSON.stringify({ justificativa }),
    });
    return response.message;
  },

  async downloadXml(id: string, chave: string): Promise<void> {
    const url = `${NFE_API_URL}/${encodeURIComponent(id)}/xml`;
    const response = await fetch(url, { credentials: "include" });

    if (!response.ok) {
      let errorMessage = "Erro ao baixar XML da NF-e.";
      try {
        const errorJson = (await response.json()) as { message?: string };
        if (errorJson?.message) errorMessage = errorJson.message;
      } catch {
        /* fallback */
      }
      throw new Error(errorMessage);
    }

    const blob = await response.blob();
    const downloadUrl = window.URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = downloadUrl;
    link.download = `nfe-${chave || id}.xml`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    window.URL.revokeObjectURL(downloadUrl);
  },
};
