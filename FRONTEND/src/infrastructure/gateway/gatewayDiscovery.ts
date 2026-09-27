/**
 * Arquivo: src/infrastructure/gateway/gatewayDiscovery.ts
 * Objetivo: descoberta/verificação do Local Gateway na LAN. O terminal só procura o Gateway quando
 *           NÃO há internet. Confirma que o Gateway achado pertence à mesma empresa (isolamento).
 *           A resolução da URL segue: (1) URL configurada/manual; mDNS/hostname fica a cargo da
 *           configuração da rede (ex.: horus-gateway.local apontando para o IP fixo/DHCP reservation).
 */
import { getGatewayConfig, isGatewayConfigured } from "./gatewayConfig";
import { gatewayClient } from "./gatewayClient";

let cache: { available: boolean; checkedAt: number } = { available: false, checkedAt: 0 };
const PROBE_TTL_MS = 10_000;

/** URL configurada do Gateway (ou vazio). */
export function resolveGatewayUrl(): string {
  return getGatewayConfig().url;
}

/**
 * Verifica se o Gateway está acessível e é da empresa correta. Resultado cacheado por alguns segundos
 * para não sondar a cada operação. `force` ignora o cache.
 */
export async function probeGateway(force = false): Promise<boolean> {
  if (!isGatewayConfigured()) {
    cache = { available: false, checkedAt: Date.now() };
    return false;
  }

  const now = Date.now();
  if (!force && now - cache.checkedAt < PROBE_TTL_MS) {
    return cache.available;
  }

  const config = getGatewayConfig();
  try {
    const status = await gatewayClient.getStatus();
    const available = status.bound && status.companyId === config.companyId;
    cache = { available, checkedAt: now };
    return available;
  } catch {
    cache = { available: false, checkedAt: now };
    return false;
  }
}

/** Último resultado conhecido sem sondar novamente. */
export function isGatewayAvailable(): boolean {
  return cache.available;
}
