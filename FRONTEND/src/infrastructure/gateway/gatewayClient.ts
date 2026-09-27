/**
 * Arquivo: src/infrastructure/gateway/gatewayClient.ts
 * Objetivo: cliente HTTP do terminal para o Local Gateway (registro, heartbeat, publicação e
 *           recuperação de eventos de pedido). Preserva EventId + PayloadHash — a mesma operação
 *           atravessa Terminal → Gateway → Cloud como uma única operação lógica.
 */
import { getGatewayConfig, saveGatewayConfig, type GatewayConfig } from "./gatewayConfig";

export type GatewayStatus = {
  service: string;
  gatewayId: string;
  companyId: string;
  storeId: string;
  bound: boolean;
  terminalAuthRequired: boolean;
};

export type GatewayEventInput = {
  eventId: string;
  eventType: string;
  payload: unknown;
  payloadHash?: string;
  occurredAt?: string;
};

export type GatewayEventDto = {
  seq: number;
  eventId: string;
  eventType: string;
  status: string;
  createdAt: string;
  payload: unknown;
};

function authHeaders(config: GatewayConfig): Record<string, string> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (config.terminalId) headers["X-Terminal-Id"] = config.terminalId;
  if (config.apiKey) headers["X-Terminal-Key"] = config.apiKey;
  return headers;
}

async function request<T>(path: string, init: RequestInit, timeoutMs = 4000): Promise<T> {
  const config = getGatewayConfig();
  if (!config.url) throw new Error("Gateway não configurado (URL ausente).");

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(`${config.url.replace(/\/$/, "")}${path}`, {
      ...init,
      signal: controller.signal,
    });
    if (!response.ok) {
      const error = new Error(`Gateway HTTP ${response.status}`) as Error & { status?: number };
      error.status = response.status;
      throw error;
    }
    return (await response.json()) as T;
  } finally {
    clearTimeout(timer);
  }
}

export const gatewayClient = {
  async getStatus(): Promise<GatewayStatus> {
    return request<GatewayStatus>("/api/gateway/status", { method: "GET" }, 3000);
  },

  /** Registra o terminal e persiste a credencial local retornada. */
  async register(registrationToken: string): Promise<void> {
    const config = getGatewayConfig();
    const result = await request<{ apiKey: string; terminalId: string }>(
      "/api/gateway/register",
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          companyId: config.companyId,
          storeId: config.storeId,
          terminalId: config.terminalId,
          terminalType: config.terminalType,
          registrationToken,
        }),
      },
      5000,
    );
    saveGatewayConfig({ apiKey: result.apiKey });
  },

  async heartbeat(): Promise<void> {
    const config = getGatewayConfig();
    await request("/api/gateway/heartbeat", { method: "POST", headers: authHeaders(config) }, 3000);
  },

  /** Publica um evento de pedido no Gateway (usado quando não há internet). */
  async publishEvent(event: GatewayEventInput): Promise<{ status: string; order?: unknown }> {
    const config = getGatewayConfig();
    return request(
      "/api/gateway/events",
      {
        method: "POST",
        headers: authHeaders(config),
        body: JSON.stringify({
          eventId: event.eventId,
          companyId: config.companyId,
          storeId: config.storeId,
          terminalId: config.terminalId,
          eventType: event.eventType,
          occurredAt: event.occurredAt ?? new Date().toISOString(),
          payloadHash: event.payloadHash,
          payload: event.payload,
        }),
      },
      5000,
    );
  },

  /** Recupera eventos após um cursor (o caixa usa para montar a fila de pedidos offline). */
  async getEvents(after = 0): Promise<{ nextCursor: number; events: GatewayEventDto[] }> {
    const config = getGatewayConfig();
    return request(
      `/api/gateway/events?companyId=${encodeURIComponent(config.companyId)}&after=${after}`,
      { method: "GET", headers: authHeaders(config) },
      5000,
    );
  },
};
