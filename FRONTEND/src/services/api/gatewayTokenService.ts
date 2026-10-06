/**
 * Arquivo: src/services/api/gatewayTokenService.ts
 * Objetivo: tokens por loja do Local Gateway (gerar, listar, revogar) — Configurações → Local Gateway.
 *           O token em texto só vem na resposta de "gerar"; depois a API devolve apenas o prefixo.
 */
import { apiRequest, requireEnvUrl } from "./apiClient";

const GATEWAY_TOKEN_API_URL = requireEnvUrl("VITE_GATEWAY_TOKEN_API_URL");

export type GatewayTokenInfo = {
  id: string;
  storeId?: string | null;
  nome: string;
  tokenPrefix: string;
  createdAt: string;
  createdBy?: string | null;
  lastUsedAt?: string | null;
  revokedAt?: string | null;
};

export const gatewayTokenService = {
  /** Endereço base da API (ex.: https://api-pdv.quacksistemas.com.br), que o Gateway usa para falar com a nuvem. */
  apiBaseUrl() {
    return GATEWAY_TOKEN_API_URL.replace(/\/api\/GatewayToken\/?$/i, "").replace(/\/+$/, "");
  },

  async listar() {
    const response = await apiRequest<GatewayTokenInfo[]>(GATEWAY_TOKEN_API_URL);
    return response.data ?? [];
  },

  /** Gera um token novo. O campo `token` é o segredo: mostrar uma única vez. */
  async gerar(nome: string, storeId?: string) {
    const response = await apiRequest<{ token: string; info: GatewayTokenInfo }>(GATEWAY_TOKEN_API_URL, {
      method: "POST",
      body: JSON.stringify({ nome, storeId: storeId || null }),
    });
    if (!response.data?.token) throw new Error("A API não devolveu o token.");
    return response.data;
  },

  async revogar(id: string) {
    await apiRequest<object>(`${GATEWAY_TOKEN_API_URL}/${encodeURIComponent(id)}/revogar`, { method: "POST" });
  },
};
