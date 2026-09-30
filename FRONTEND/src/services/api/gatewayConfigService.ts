/**
 * Arquivo: src/services/api/gatewayConfigService.ts
 * Objetivo: ler da Cloud o endereço LAN do Local Gateway (Quack Gateway) da empresa do usuário.
 *           A Cloud é a autoridade; o terminal aprende esse endereço enquanto online e o usa no
 *           fallback offline. Camada aditiva — não altera o fluxo homologado de vendas/caixa.
 */
import { apiRequest, requireEnvUrl } from "./apiClient";

const GATEWAY_CONFIG_API_URL = requireEnvUrl("VITE_GATEWAY_CONFIG_API_URL");

export type CloudGatewayConfig = {
  gatewayUrl: string;
  enabled: boolean;
  storeId?: string | null;
  updatedAt?: string;
};

export const gatewayConfigService = {
  /** Obtém o endereço do Gateway da empresa (ou null quando não cadastrado). Só funciona online. */
  async get(): Promise<CloudGatewayConfig | null> {
    const response = await apiRequest<CloudGatewayConfig | null>(GATEWAY_CONFIG_API_URL, {
      method: "GET",
    });
    return response.data ?? null;
  },

  /** Cadastra/edita o endereço do Gateway (admin/gerente). */
  async save(config: {
    gatewayUrl: string;
    enabled: boolean;
    storeId?: string | null;
  }): Promise<CloudGatewayConfig | null> {
    const response = await apiRequest<CloudGatewayConfig | null>(GATEWAY_CONFIG_API_URL, {
      method: "PUT",
      body: JSON.stringify(config),
    });
    return response.data ?? null;
  },
};
