/**
 * Arquivo: src/services/api/integracaoTokenService.ts
 * Objetivo: tokens de integração para agentes de IA (servidor MCP) — gerar, listar, revogar. Somente leitura,
 *           a não ser que o token seja gerado com permissão para editar produtos e ajustar estoque.
 *           O token em texto só vem na resposta de "gerar"; depois a API devolve apenas o prefixo.
 */
import { apiRequest, requireEnvUrl } from "./apiClient";

const INTEGRACAO_TOKEN_API_URL = requireEnvUrl("VITE_INTEGRACAO_TOKEN_API_URL");

export type IntegracaoTokenInfo = {
  id: string;
  nome: string;
  tokenPrefix: string;
  createdAt: string;
  createdBy?: string | null;
  lastUsedAt?: string | null;
  revokedAt?: string | null;
  /** true = o agente pode editar produtos e ajustar estoque (sempre simulando antes). */
  permiteEscritaProdutos?: boolean;
};

export const integracaoTokenService = {
  /** Endereço do servidor MCP (ex.: https://api-pdv.quacksistemas.com.br/mcp), que o agente usa para conectar. */
  mcpUrl() {
    return `${INTEGRACAO_TOKEN_API_URL.replace(/\/api\/IntegracaoToken\/?$/i, "").replace(/\/+$/, "")}/mcp`;
  },

  async listar() {
    const response = await apiRequest<IntegracaoTokenInfo[]>(INTEGRACAO_TOKEN_API_URL);
    return response.data ?? [];
  },

  /** Gera um token novo. O campo `token` é o segredo: mostrar uma única vez. */
  async gerar(nome: string, permiteEditarProdutos = false) {
    const response = await apiRequest<{ token: string; info: IntegracaoTokenInfo }>(INTEGRACAO_TOKEN_API_URL, {
      method: "POST",
      body: JSON.stringify({ nome, permiteEditarProdutos }),
    });
    if (!response.data?.token) throw new Error("A API não devolveu o token.");
    return response.data;
  },

  async revogar(id: string) {
    await apiRequest<object>(`${INTEGRACAO_TOKEN_API_URL}/${encodeURIComponent(id)}/revogar`, { method: "POST" });
  },
};
