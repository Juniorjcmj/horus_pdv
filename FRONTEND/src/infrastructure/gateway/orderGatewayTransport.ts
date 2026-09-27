/**
 * Arquivo: src/infrastructure/gateway/orderGatewayTransport.ts
 * Objetivo: decisão de destino do terminal, conforme o contrato:
 *             1) COM internet  → Cloud (comportamento atual, inalterado);
 *             2) SEM internet + Gateway disponível → Gateway (LAN), para o terminal falar com o caixa;
 *             3) SEM internet e SEM Gateway → offline local (Outbox/IndexedDB atual).
 *           Camada ADICIONAL: não altera o fluxo de vendas/caixa → cloud. Serve o fluxo pedido → caixa
 *           quando a loja está sem internet.
 */
import { connectivityService } from "@/infrastructure/synchronization/ConnectivityService";
import { getGatewayConfig } from "./gatewayConfig";
import { probeGateway } from "./gatewayDiscovery";
import { gatewayClient, type GatewayEventInput } from "./gatewayClient";

export type TerminalDestination = "CLOUD" | "GATEWAY" | "LOCAL";

/**
 * Escolhe o destino de uma operação de pedido. Cloud é sempre o ponto principal; o Gateway só entra
 * quando não há internet.
 */
export async function chooseOrderDestination(): Promise<TerminalDestination> {
  if (connectivityService.isOnline()) return "CLOUD";
  const config = getGatewayConfig();
  if (!config.enabled) return "LOCAL";
  const gatewayUp = await probeGateway();
  return gatewayUp ? "GATEWAY" : "LOCAL";
}

export type PublishOrderResult = {
  destination: TerminalDestination;
  delivered: boolean;
  order?: unknown;
  error?: string;
};

/**
 * Publica um evento de pedido pelo caminho offline (Gateway) quando aplicável.
 * Retorna delivered=false quando o destino é CLOUD (o chamador segue o fluxo cloud normal) ou LOCAL
 * (o chamador mantém na Outbox local, exatamente como hoje).
 */
export async function publishOrderEventOffline(event: GatewayEventInput): Promise<PublishOrderResult> {
  const destination = await chooseOrderDestination();
  if (destination !== "GATEWAY") {
    return { destination, delivered: false };
  }

  try {
    const ack = await gatewayClient.publishEvent(event);
    return { destination, delivered: true, order: ack.order };
  } catch (err) {
    // Gateway caiu no meio → o chamador deve manter o evento na Outbox local (nada é perdido).
    return { destination: "LOCAL", delivered: false, error: err instanceof Error ? err.message : String(err) };
  }
}
