/**
 * Arquivo: src/infrastructure/desktop/desktopGateway.ts
 * Objetivo: Gateway embutido no programa desktop (loja de um caixa só). O programa roda o Quack Gateway em
 *           127.0.0.1; aqui o PDV o ativa (gera o token da loja e entrega ao programa) e se liga a ele como
 *           terminal de caixa — daí em diante a fila "Gateway como fila" funciona sem configurar nada.
 *           No navegador comum (sem `window.quackDesktop.gateway`) tudo aqui é no-op.
 */
import { getCachedDeviceId } from "@/infrastructure/database/deviceId";
import { getGatewayConfig, saveGatewayConfig } from "@/infrastructure/gateway/gatewayConfig";
import { gatewayClient } from "@/infrastructure/gateway/gatewayClient";
import { gatewayTokenService } from "@/services/api/gatewayTokenService";
import { getStoredAuthUser } from "@/utils/authStorage";
import { getDesktopBridge, type DesktopGatewayStatus } from "./bridge";

const DEFAULT_STORE_ID = "loja-01";
const RELINK_MS = 30_000;

export type EmbeddedCloudState = {
  connected: boolean;
  companyMismatch: boolean;
  lastError?: string | null;
  lastSuccessAt?: string | null;
};

export function getDesktopGateway() {
  return getDesktopBridge()?.gateway ?? null;
}

/** URL do Gateway embutido (só a própria máquina). */
export function isEmbeddedGatewayUrl(url: string): boolean {
  return /^http:\/\/(127\.0\.0\.1|localhost)(:\d+)?\/?$/i.test(url.trim());
}

/** O PDV está usando o Gateway embutido do programa (a descoberta pela nuvem não deve sobrescrever). */
export function isUsingEmbeddedGateway(): boolean {
  const config = getGatewayConfig();
  return Boolean(getDesktopGateway()) && config.enabled && isEmbeddedGatewayUrl(config.url);
}

function terminalIdForThisComputer(): string {
  const device = (getCachedDeviceId() || crypto.randomUUID()).replace(/[^a-zA-Z0-9]/g, "");
  return `CAIXA-${device.slice(0, 8).toUpperCase()}`;
}

/**
 * Liga o PDV ao Gateway embutido: grava URL/empresa/terminal na config do Gateway e registra o terminal
 * (registro aberto do Gateway) quando ainda não há credencial. Idempotente; nunca lança.
 */
export async function linkEmbeddedGateway(known?: DesktopGatewayStatus): Promise<DesktopGatewayStatus | null> {
  const bridge = getDesktopGateway();
  if (!bridge) return null;
  try {
    const status = known ?? (await bridge.status());
    const user = getStoredAuthUser();
    if (!status.configured || !status.running || !user?.companyId) return status;
    // Gateway de outra empresa (ex.: outro login neste computador): não liga.
    if (status.companyId && status.companyId !== user.companyId) return status;

    const current = getGatewayConfig();
    const sameTarget = current.url === status.url && current.companyId === user.companyId;
    saveGatewayConfig({
      enabled: true,
      url: status.url,
      companyId: user.companyId,
      storeId: status.storeId || DEFAULT_STORE_ID,
      terminalId: current.terminalId || terminalIdForThisComputer(),
      terminalType: "CASH",
      apiKey: sameTarget ? current.apiKey : "",
    });
    if (!getGatewayConfig().apiKey) await gatewayClient.register();
    return status;
  } catch (err) {
    console.warn("[PDV] Não foi possível ligar ao Gateway do programa:", err);
    return null;
  }
}

/** Liga agora e de novo a cada 30s (o Gateway sobe junto com o programa e pode demorar alguns segundos). */
export function startEmbeddedGatewayLink(): () => void {
  if (!getDesktopGateway()) return () => {};
  void linkEmbeddedGateway();
  const timer = setInterval(() => void linkEmbeddedGateway(), RELINK_MS);
  return () => clearInterval(timer);
}

async function revokeQuietly(tokenId: string) {
  if (!tokenId) return;
  try {
    await gatewayTokenService.revogar(tokenId);
  } catch {
    // token já revogado/sem internet: o gerente revoga pela lista de tokens
  }
}

/**
 * Ativa o Gateway neste computador (admin/gerente, com internet): gera um token da loja, entrega ao programa,
 * espera o Gateway subir e liga o PDV a ele. Reativar troca o token e revoga o anterior.
 */
export async function activateEmbeddedGateway(): Promise<DesktopGatewayStatus> {
  const bridge = getDesktopGateway();
  if (!bridge) throw new Error("Disponível só no programa Quack PDV (desktop), versão 1.1.0 ou mais nova.");
  const user = getStoredAuthUser();
  if (!user?.companyId) throw new Error("Entre no sistema antes de ativar o Gateway.");

  const before = await bridge.status();
  if (!before.bundled) throw new Error("Esta instalação do programa não traz o Gateway. Instale a versão mais nova.");

  const terminalId = getGatewayConfig().terminalId || terminalIdForThisComputer();
  const { token, info } = await gatewayTokenService.gerar(`Caixa ${terminalId} (programa)`);
  let status: DesktopGatewayStatus;
  try {
    status = await bridge.configure({
      token,
      tokenId: info.id,
      companyId: user.companyId,
      storeId: DEFAULT_STORE_ID,
      apiUrl: gatewayTokenService.apiBaseUrl(),
    });
  } catch (err) {
    await revokeQuietly(info.id);
    throw err;
  }
  if (before.tokenId && before.tokenId !== info.id) await revokeQuietly(before.tokenId);

  if (!status.running) throw new Error(status.lastError || "O Gateway não iniciou. Tente de novo.");
  if (status.external) {
    throw new Error(
      `Já existe outro Gateway rodando neste computador (${status.url}). Use aquele ou desinstale o serviço antes.`,
    );
  }
  await linkEmbeddedGateway(status);
  return status;
}

/** Desativa: para o Gateway do programa, revoga o token e volta o PDV ao caminho sem Gateway. */
export async function deactivateEmbeddedGateway(): Promise<DesktopGatewayStatus | null> {
  const bridge = getDesktopGateway();
  if (!bridge) return null;
  const before = await bridge.status();
  const status = await bridge.disable();
  await revokeQuietly(before.tokenId);
  if (isEmbeddedGatewayUrl(getGatewayConfig().url)) saveGatewayConfig({ enabled: false, apiKey: "" });
  return status;
}

/** Conexão do Gateway embutido com a nuvem (token válido? empresa certa?). null = Gateway não responde. */
export async function getEmbeddedCloudState(url: string): Promise<EmbeddedCloudState | null> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 3000);
  try {
    const response = await fetch(`${url.replace(/\/$/, "")}/api/gateway/cloud`, { signal: controller.signal });
    if (!response.ok) return null;
    return (await response.json()) as EmbeddedCloudState;
  } catch {
    return null;
  } finally {
    clearTimeout(timer);
  }
}
