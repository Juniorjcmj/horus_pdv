/**
 * Arquivo: src/services/api/companyThemeService.ts
 * Objetivo: ler/gravar na Cloud o tema (cores de acento) da empresa — cor do sistema e cor da frente
 *           de caixa (PDV). A Cloud é a autoridade; cada terminal da empresa herda. Camada aditiva.
 */
import { apiRequest, requireEnvUrl } from "./apiClient";

const COMPANY_THEME_API_URL = requireEnvUrl("VITE_COMPANY_THEME_API_URL");

export type CompanyThemeDto = {
  systemAccent: string | null;
  pdvAccent: string | null;
  updatedAt?: string;
};

export const companyThemeService = {
  async get(): Promise<CompanyThemeDto | null> {
    const response = await apiRequest<CompanyThemeDto | null>(COMPANY_THEME_API_URL, { method: "GET" });
    return response.data ?? null;
  },

  async save(theme: { systemAccent: string | null; pdvAccent: string | null }): Promise<CompanyThemeDto | null> {
    const response = await apiRequest<CompanyThemeDto | null>(COMPANY_THEME_API_URL, {
      method: "PUT",
      body: JSON.stringify(theme),
    });
    return response.data ?? null;
  },
};
