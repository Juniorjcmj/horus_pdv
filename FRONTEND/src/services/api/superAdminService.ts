/**
 * Arquivo: FRONTEND/src/services/api/superAdminService.ts
 * Objetivo: encapsula requisições para os endpoints de SuperAdmin de Gerenciamento Geral de Empresas.
 * Acesso restrito a administradores da empresa-principal.
 */
import { apiRequest, type ApiResponse } from "./apiClient";

const SUPER_ADMIN_API_URL =
  import.meta.env.VITE_SUPER_ADMIN_API_URL ?? "http://localhost:5260/api/Admin/Empresas";

export type EmpresaStatus = "pendente" | "aprovada" | "rejeitada" | "bloqueada";

export type EmpresaAdminItem = {
  id: string;
  fantasyName: string;
  corporateName: string;
  cnpj: string;
  email: string;
  phone: string;
  mobile: string;
  city: string;
  uf: string;
  status: EmpresaStatus;
  createdAt: string;
  reviewedAt?: string | null;
  reviewedBy?: string | null;
  rejectionReason?: string | null;
  adminUserName?: string | null;
  adminUserEmail?: string | null;
  adminUserPhone?: string | null;
  totalUsers: number;
  totalProducts: number;
  totalSales: number;
};

export type EmpresasMetricas = {
  total: number;
  pendentes: number;
  aprovadas: number;
  rejeitadas: number;
  bloqueadas: number;
  requireApprovalForNewCompanies: boolean;
};

export type EmpresasAdminListResult = {
  items: EmpresaAdminItem[];
  totalCount: number;
  page: number;
  pageSize: number;
};

export type ListEmpresasParams = {
  search?: string;
  status?: string;
  page?: number;
  pageSize?: number;
};

export const superAdminService = {
  async getMetrics(): Promise<EmpresasMetricas> {
    const response = await apiRequest<EmpresasMetricas>(`${SUPER_ADMIN_API_URL}/metricas`);
    return (
      response.data ?? {
        total: 0,
        pendentes: 0,
        aprovadas: 0,
        rejeitadas: 0,
        bloqueadas: 0,
        requireApprovalForNewCompanies: true,
      }
    );
  },

  async listCompanies(params: ListEmpresasParams = {}): Promise<EmpresasAdminListResult> {
    const searchParams = new URLSearchParams();
    if (params.search?.trim()) searchParams.append("search", params.search.trim());
    if (params.status && params.status !== "todas") searchParams.append("status", params.status);
    if (params.page && params.page > 1) searchParams.append("page", String(params.page));
    if (params.pageSize) searchParams.append("pageSize", String(params.pageSize));

    const qs = searchParams.toString() ? `?${searchParams.toString()}` : "";
    const response = await apiRequest<EmpresasAdminListResult>(`${SUPER_ADMIN_API_URL}${qs}`);
    return (
      response.data ?? {
        items: [],
        totalCount: 0,
        page: 1,
        pageSize: 20,
      }
    );
  },

  async approveCompany(companyId: string): Promise<ApiResponse<unknown>> {
    return apiRequest<unknown>(`${SUPER_ADMIN_API_URL}/${encodeURIComponent(companyId)}/aprovar`, {
      method: "POST",
    });
  },

  async rejectCompany(companyId: string, reason: string): Promise<ApiResponse<unknown>> {
    return apiRequest<unknown>(`${SUPER_ADMIN_API_URL}/${encodeURIComponent(companyId)}/rejeitar`, {
      method: "POST",
      body: JSON.stringify({ reason }),
    });
  },

  async blockCompany(companyId: string, reason: string): Promise<ApiResponse<unknown>> {
    return apiRequest<unknown>(`${SUPER_ADMIN_API_URL}/${encodeURIComponent(companyId)}/bloquear`, {
      method: "POST",
      body: JSON.stringify({ reason }),
    });
  },

  async reactivateCompany(companyId: string): Promise<ApiResponse<unknown>> {
    return apiRequest<unknown>(`${SUPER_ADMIN_API_URL}/${encodeURIComponent(companyId)}/reativar`, {
      method: "POST",
    });
  },

  async updateCompanyCredentials(
    companyId: string,
    data: { newEmail?: string; newPassword?: string }
  ): Promise<ApiResponse<unknown>> {
    return apiRequest<unknown>(
      `${SUPER_ADMIN_API_URL}/${encodeURIComponent(companyId)}/credenciais`,
      {
        method: "PUT",
        body: JSON.stringify(data),
      }
    );
  },

  async deleteCompany(companyId: string): Promise<ApiResponse<unknown>> {
    return apiRequest<unknown>(`${SUPER_ADMIN_API_URL}/${encodeURIComponent(companyId)}`, {
      method: "DELETE",
    });
  },

  async getConfig(): Promise<{ requireApprovalForNewCompanies: boolean }> {
    const response = await apiRequest<{ requireApprovalForNewCompanies: boolean }>(
      `${SUPER_ADMIN_API_URL}/configuracao`
    );
    return response.data ?? { requireApprovalForNewCompanies: true };
  },

  async updateConfig(requireApprovalForNewCompanies: boolean): Promise<ApiResponse<unknown>> {
    return apiRequest<unknown>(`${SUPER_ADMIN_API_URL}/configuracao`, {
      method: "PUT",
      body: JSON.stringify({ requireApprovalForNewCompanies }),
    });
  },
};
