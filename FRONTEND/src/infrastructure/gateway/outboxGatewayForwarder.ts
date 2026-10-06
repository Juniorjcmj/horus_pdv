/**
 * Arquivo: src/infrastructure/gateway/outboxGatewayForwarder.ts
 * Objetivo: "Gateway como fila". Sem internet (nuvem inacessível), entrega os eventos pendentes da fila
 *           do navegador (vendas, abertura/fechamento, sangria/reforço) ao Local Gateway da loja, que os
 *           guarda em disco (SQLite) e repassa à nuvem quando a internet volta. Com internet nada muda:
 *           a venda continua indo direto à nuvem (número e NFC-e na hora).
 *
 * Idempotência: o evento vai ao Gateway com o MESMO EventId/PayloadHash da fila local. Entregue, fica
 * FORWARDED; quando a internet volta o PDV ainda reenvia direto à nuvem — replay se o Gateway já entregou.
 * Eventos sem operatorId (fila antiga) não vão ao Gateway: seguem pelo caminho de hoje (direto à nuvem).
 */
import { getForwardableEvents, markForwarded } from "@/infrastructure/database/repositories/OutboxRepository";
import { getGatewayConfig } from "./gatewayConfig";
import { gatewayClient } from "./gatewayClient";
import { probeGateway } from "./gatewayDiscovery";
import { ensureConnected } from "./orderGatewayTransport";

/** Tipos que o Gateway sabe repassar à nuvem (mesmos nomes do CloudEndpointSyncClient do Gateway). */
const FORWARDABLE_TYPES = new Set(["SALE_CREATED", "CASH_OPEN", "CASH_CLOSE", "CASH_MOVEMENT"]);

export type ForwardResult = { forwarded: number; skipped: number; reason?: string };

function parsePayload(raw: string): Record<string, unknown> | null {
  try {
    const value = typeof raw === "string" ? JSON.parse(raw) : raw;
    return value && typeof value === "object" && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

function hasOperator(payload: Record<string, unknown> | null): boolean {
  return typeof payload?.operatorId === "string" && payload.operatorId.trim() !== "";
}

/**
 * Entrega ao Gateway os eventos PENDING elegíveis. Retorna quantos foram entregues. Nunca lança:
 * qualquer falha deixa o evento como estava (continua na fila local, nada se perde).
 */
export async function forwardPendingToGateway(): Promise<ForwardResult> {
  const config = getGatewayConfig();
  if (!config.enabled || !config.url) return { forwarded: 0, skipped: 0, reason: "Gateway não configurado" };

  // Identidade do terminal no Gateway (auto-identificação por IP/token, já usada nos pedidos).
  if (!(await ensureConnected())) return { forwarded: 0, skipped: 0, reason: "terminal não identificado no Gateway" };
  if (!(await probeGateway(true))) return { forwarded: 0, skipped: 0, reason: "Gateway indisponível" };

  const events = await getForwardableEvents();
  let forwarded = 0;
  let skipped = 0;

  for (const event of events) {
    const payload = parsePayload(event.payload);
    if (!FORWARDABLE_TYPES.has(event.eventType) || !hasOperator(payload)) {
      skipped++;
      continue;
    }

    try {
      const ack = await gatewayClient.publishEvent({
        eventId: event.id,
        eventType: event.eventType,
        payload,
        payloadHash: event.payloadHash,
        occurredAt: event.occurredAt,
      });
      if (ack?.status === "accepted" || ack?.status === "replay") {
        await markForwarded(event.id);
        forwarded++;
      } else {
        skipped++;
      }
    } catch (err) {
      const status = (err as { status?: number })?.status ?? 0;
      if (status >= 400 && status < 500) {
        // Gateway recusou este evento (ex.: dados inválidos): fica na fila local e vai direto à nuvem depois.
        skipped++;
        continue;
      }
      // Gateway caiu no meio: para e tenta no próximo ciclo.
      return { forwarded, skipped, reason: err instanceof Error ? err.message : String(err) };
    }
  }

  return { forwarded, skipped };
}
