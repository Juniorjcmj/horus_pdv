/**
 * Arquivo: src/infrastructure/desktop/bridge.ts
 * Objetivo: tipos da ponte `window.quackDesktop`, exposta pelo programa desktop (DESKTOP/preload.js).
 *           No navegador comum ela não existe — quem usa sempre confere antes.
 */

/** Estado do Gateway embutido no programa (DESKTOP/gateway.js). Nunca traz o token. */
export type DesktopGatewayStatus = {
  /** O programa instalado traz o HorusGateway.exe. */
  bundled: boolean;
  /** Ativado (token + empresa gravados no programa). */
  configured: boolean;
  /** Há um Gateway respondendo em `url`. */
  running: boolean;
  /** O processo é do programa (false quando `external`). */
  managed: boolean;
  /** Já havia outro Gateway na porta (ex.: Serviço do Windows): o programa não subiu o seu. */
  external: boolean;
  url: string;
  companyId: string;
  storeId: string;
  gatewayId: string;
  tokenId: string;
  lastError: string;
};

export type DesktopGatewayConfigureInput = {
  token: string;
  tokenId?: string;
  companyId: string;
  storeId?: string;
  apiUrl: string;
};

export type QuackDesktopBridge = {
  savePendingBackup: (json: string) => Promise<string>;
  /** Ausente nas versões do programa anteriores à 1.1.0. */
  gateway?: {
    status: () => Promise<DesktopGatewayStatus>;
    configure: (input: DesktopGatewayConfigureInput) => Promise<DesktopGatewayStatus>;
    disable: () => Promise<DesktopGatewayStatus>;
  };
};

declare global {
  interface Window {
    quackDesktop?: QuackDesktopBridge;
  }
}

export function getDesktopBridge(): QuackDesktopBridge | undefined {
  return typeof window !== "undefined" ? window.quackDesktop : undefined;
}
