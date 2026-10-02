/**
 * Arquivo: src/services/api/regrasEmissaoNfceService.ts
 * Objetivo: serviço cliente da API para configuração das regras de emissão de NFC-e
 *           (formas de pagamento e frequência/intervalo a cada N notas).
 */
import { apiRequest } from "./apiClient";

const REGRAS_API_URL =
  (import.meta.env.VITE_REGRAS_EMISSAO_NFCE_API_URL as string | undefined) ||
  ((import.meta.env.VITE_NFCE_API_URL as string | undefined)
    ? (import.meta.env.VITE_NFCE_API_URL as string).replace(/\/Nfce$/i, "/regras-emissao-nfce")
    : "http://localhost:5260/api/regras-emissao-nfce");

export type RegrasEmissaoNfceDto = {
  companyId: string;
  habilitado: boolean;
  formasPagamentoHabilitadas: string;
  intervaloNotas: number;
  emitirSempreComCpf: boolean;
  contadorVendas: number;
  updatedAt: string;
};

export type SalvarRegrasEmissaoNfcePayload = {
  habilitado: boolean;
  formasPagamentoHabilitadas: string;
  intervaloNotas: number;
  emitirSempreComCpf: boolean;
};

export const regrasEmissaoNfceService = {
  async get(): Promise<RegrasEmissaoNfceDto | null> {
    const res = await apiRequest<RegrasEmissaoNfceDto | null>(REGRAS_API_URL, {
      method: "GET",
    });
    return res.data ?? null;
  },

  async save(payload: SalvarRegrasEmissaoNfcePayload): Promise<RegrasEmissaoNfceDto | null> {
    const res = await apiRequest<RegrasEmissaoNfceDto | null>(REGRAS_API_URL, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
    return res.data ?? null;
  },

  async resetContador(): Promise<{ contadorVendas: number; intervaloNotas: number } | null> {
    const res = await apiRequest<{ contadorVendas: number; intervaloNotas: number } | null>(
      `${REGRAS_API_URL}/reset-contador`,
      {
        method: "POST",
      }
    );
    return res.data ?? null;
  },
};
