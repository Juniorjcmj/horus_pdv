/**
 * Arquivo: src/services/api/authService.ts
 * Objetivo: encapsula chamadas HTTP de autenticação, cadastro e recuperação de senha.
 * Entradas esperadas: recebe payloads já validados pelas telas e retorna respostas tipadas da API.
 */
import { ApiError, apiRequest, requireEnvUrl } from "./apiClient";
import type { AuthenticatedUser } from "@/utils/authStorage";
import { userRepository } from "@/infrastructure/database/repositories/UserRepository";

const AUTH_API_URL = requireEnvUrl("VITE_AUTH_API_URL");

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
  /** Resultado do salvamento local após um login online. */
  offlineAccessReady?: boolean;
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
    let result: LoginResponse | undefined;
    try {
      const response = await apiRequest<LoginResponse>(`${AUTH_API_URL}/login`, {
        method: "POST",
        body: JSON.stringify(payload),
        skipAuth: true,
      });

      result = response.data;
    } catch (onlineError) {
      // Só indisponibilidade permite autenticação local. 400/401/403 e limite de tentativas
      // (429) continuam sendo recusas do servidor, mesmo se a rede cair logo após a resposta.
      const isNetworkError = onlineError instanceof ApiError &&
        (onlineError.status === 0 || onlineError.status === 408 || onlineError.status >= 500);

      if (isNetworkError) {
        const offlineUser = await userRepository.authenticateOffline(payload.email, payload.password);
        return {
          tokenType: "Bearer" as const,
          expiresInSeconds: 86400,
          sessionId: `sess-offline-${Date.now()}`,
          user: offlineUser,
          offlineAccessReady: true,
        };
      }

      throw onlineError;
    }

    if (!result?.user) return result;

    // Confirma o salvamento antes de liberar a sessão: sair logo após o login não pode
    // interromper a preparação do acesso offline. Falha local não invalida o login online.
    try {
      await userRepository.saveUserForOfflineAuth(result.user, payload.password);
      return { ...result, offlineAccessReady: true };
    } catch {
      return { ...result, offlineAccessReady: false };
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
