/**
 * Arquivo: src/services/api/authService.ts
 * Objetivo: encapsula chamadas HTTP de autenticação, cadastro e recuperação de senha.
 * Entradas esperadas: recebe payloads já validados pelas telas e retorna respostas tipadas da API.
 */
import { apiRequest } from "./apiClient";
import type { AuthenticatedUser } from "@/utils/authStorage";
import { userRepository } from "@/infrastructure/database/repositories/UserRepository";

const AUTH_API_URL = import.meta.env.VITE_AUTH_API_URL ?? "http://localhost:5260/api/Auth";

export type LoginPayload = {
  email: string;
  password: string;
  rememberMe: boolean;
  recaptchaToken?: string;
};

export type LoginResponse = {
  tokenType: "Bearer";
  expiresInSeconds: number;
  sessionId: string;
  user: AuthenticatedUser;
};

export type RegisterPayload = {
  cnpj: string;
  name: string;
  email: string;
  phone: string;
  password: string;
  confirmPassword: string;
  recaptchaToken?: string;
};

export type ForgotPasswordResponse = {
  accepted: boolean;
  maskedEmail?: string;
  resetToken?: string;
  expiresAt?: string;
};

export const authService = {
  async login(payload: LoginPayload) {
    try {
      const response = await apiRequest<LoginResponse>(`${AUTH_API_URL}/login`, {
        method: "POST",
        body: JSON.stringify(payload),
        skipAuth: true,
      });

      if (response.data?.user) {
        // Salva hash local para permitir login offline posterior
        void userRepository.saveUserForOfflineAuth(response.data.user, payload.password);
      }

      return response.data;
    } catch (onlineError) {
      // Se for falha de conectividade ou rede indisponível, tenta login offline local
      const isNetworkError =
        onlineError instanceof Error &&
        (onlineError.message.includes("Failed to fetch") ||
          onlineError.message.includes("NetworkError") ||
          onlineError.message.includes("Network Error") ||
          onlineError.message.includes("Load failed") ||
          onlineError.message.includes("timeout") ||
          onlineError.message.includes("servidor") ||
          (typeof navigator !== "undefined" && !navigator.onLine));

      if (isNetworkError) {
        const offlineUser = await userRepository.authenticateOffline(payload.email, payload.password);
        return {
          tokenType: "Bearer" as const,
          expiresInSeconds: 86400,
          sessionId: `sess-offline-${Date.now()}`,
          user: offlineUser,
        };
      }

      throw onlineError;
    }
  },

  async me() {
    const response = await apiRequest<AuthenticatedUser>(`${AUTH_API_URL}/me`);
    return response.data;
  },

  async updateMe(payload: { name: string; email: string; phone: string }) {
    const response = await apiRequest<AuthenticatedUser>(`${AUTH_API_URL}/me`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
    return response.data;
  },

  async logout() {
    await apiRequest<object>(`${AUTH_API_URL}/logout`, { method: "POST" });
  },

  async changePassword(currentPassword: string, nextPassword: string) {
    await apiRequest<object>(`${AUTH_API_URL}/change-password`, {
      method: "POST",
      body: JSON.stringify({ currentPassword, nextPassword }),
    });
  },

  async forgotPassword(cnpj: string, email: string, recaptchaToken?: string) {
    const response = await apiRequest<ForgotPasswordResponse>(`${AUTH_API_URL}/forgot-password`, {
      method: "POST",
      body: JSON.stringify({ cnpj, email, recaptchaToken }),
      skipAuth: true,
    });
    return response.data;
  },

  async resetPassword(token: string, nextPassword: string, confirmPassword: string, recaptchaToken?: string) {
    await apiRequest<AuthenticatedUser>(`${AUTH_API_URL}/reset-password`, {
      method: "POST",
      body: JSON.stringify({ token, nextPassword, confirmPassword, recaptchaToken }),
      skipAuth: true,
    });
  },

  async register(payload: RegisterPayload) {
    const response = await apiRequest<AuthenticatedUser>(`${AUTH_API_URL}/register`, {
      method: "POST",
      body: JSON.stringify(payload),
      skipAuth: true,
    });
    return response.data;
  },
};
