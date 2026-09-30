/**
 * Arquivo: src/infrastructure/gateway/gatewayConfig.ts
 * Objetivo: configuração local do Local Gateway no terminal (URL, credencial, identidade), persistida
 *           em localStorage. Camada ADICIONAL — o terminal só usa o Gateway quando não há internet.
 *           Todos os acessos a localStorage são protegidos (funciona mesmo sem storage disponível).
 */

const KEY = "horus-gateway-config";

export type GatewayConfig = {
  /** Habilita o uso do Gateway como fallback offline (default: false — nada muda no fluxo cloud). */
  enabled: boolean;
  /** URL base do Gateway na LAN (ex.: http://192.168.0.10:5080). */
  url: string;
  companyId: string;
  storeId: string;
  terminalId: string;
  terminalType: "ORDER" | "CASH";
  /** Credencial local emitida pelo Gateway no registro (apiKey). */
  apiKey: string;
  /** Carimbo (ISO) do endereço aprendido da Cloud — usado para revalidação/cache. */
  updatedAt?: string;
};

const EMPTY: GatewayConfig = {
  enabled: false,
  url: "",
  companyId: "",
  storeId: "",
  terminalId: "",
  terminalType: "ORDER",
  apiKey: "",
  updatedAt: "",
};

export function getGatewayConfig(): GatewayConfig {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return { ...EMPTY };
    return { ...EMPTY, ...(JSON.parse(raw) as Partial<GatewayConfig>) };
  } catch {
    return { ...EMPTY };
  }
}

export function saveGatewayConfig(patch: Partial<GatewayConfig>): GatewayConfig {
  const next = { ...getGatewayConfig(), ...patch };
  try {
    localStorage.setItem(KEY, JSON.stringify(next));
  } catch {
    // storage indisponível (aba privada, cota) — segue em memória apenas
  }
  return next;
}

/** True quando há configuração mínima para falar com o Gateway. */
export function isGatewayConfigured(config: GatewayConfig = getGatewayConfig()): boolean {
  return config.enabled && !!config.url && !!config.companyId && !!config.terminalId;
}

/** Limpa a credencial (ex.: ao trocar de terminal/empresa). */
export function clearGatewayCredential(): void {
  saveGatewayConfig({ apiKey: "" });
}
