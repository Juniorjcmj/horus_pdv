/**
 * Arquivo: src/services/api/reportService.ts
 * Objetivo: encapsula chamadas HTTP de geração de relatórios operacionais.
 * Entradas esperadas: recebe payloads já validados pelas telas e retorna respostas tipadas da API.
 */
import type { ReportFilterValues, ReportResult } from "@/components/Admin/ReportsPage/reportResultTypes";
import { apiRequest, requireEnvUrl } from "./apiClient";

const RELATORIOS_API_URL = requireEnvUrl("VITE_RELATORIOS_API_URL");

export const reportService = {
  async generate(reportId: string, filters: ReportFilterValues) {
    const response = await apiRequest<ReportResult>(`${RELATORIOS_API_URL}/Gerar`, {
      method: "POST",
      body: JSON.stringify({ reportId, filters }),
    });
    return response.data ?? { columns: [], rows: [] };
  },
};
