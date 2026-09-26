/**
 * Arquivo: src/services/api/userService.ts
 * Objetivo: encapsula chamadas HTTP de contas de usuários e permissões.
 * Entradas esperadas: recebe payloads já validados pelas telas e retorna respostas tipadas da API.
 */
import type { AdminUser } from "@/components/Admin/UsersPage";
import type { UserFormState, UserStatus } from "@/components/Admin/UsersPage";
import { apiRequest, requireEnvUrl } from "./apiClient";

const USUARIOS_API_URL = requireEnvUrl("VITE_USUARIOS_API_URL");

export const userService = {
  async list() {
    const response = await apiRequest<AdminUser[]>(USUARIOS_API_URL);
    return response.data ?? [];
  },
  async create(payload: UserFormState) {
    const response = await apiRequest<AdminUser>(USUARIOS_API_URL, {
      method: "POST",
      body: JSON.stringify(payload),
    });
    return response.data;
  },
  async update(id: string, payload: UserFormState) {
    const response = await apiRequest<AdminUser>(`${USUARIOS_API_URL}/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
    return response.data;
  },
  async updateStatus(id: string, status: UserStatus) {
    const response = await apiRequest<AdminUser>(`${USUARIOS_API_URL}/${id}/status`, {
      method: "PATCH",
      body: JSON.stringify({ status }),
    });
    return response.data;
  },
  async resetPassword(id: string) {
    const response = await apiRequest<{
      user: AdminUser;
      accepted: boolean;
      maskedEmail?: string;
      resetToken?: string;
      expiresAt?: string;
    }>(
      `${USUARIOS_API_URL}/${id}/resetar-senha`,
      { method: "POST" },
    );
    return response.data;
  },
};
