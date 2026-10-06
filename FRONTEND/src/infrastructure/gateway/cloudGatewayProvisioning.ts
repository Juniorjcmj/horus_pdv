/**
 * Arquivo: src/infrastructure/gateway/cloudGatewayProvisioning.ts
 * Objetivo: aprendizado automático do endereço do Local Gateway a partir da Cloud (opção C).
 *   Enquanto o terminal está ONLINE, busca na Cloud o endereço do Gateway da empresa e o grava no
 *   cache local (`gatewayConfig`). Quando a internet cai, o terminal já sabe onde o Gateway está —
 *   zero configuração no terminal. Camada 100% ADITIVA: silenciosa em qualquer falha; nunca bloqueia
 *   vendas/caixa e não altera o fluxo homologado (que continua falando direto com a Cloud).
 */
import { getStoredAuthUser } from "@/utils/authStorage";
import { connectivityService } from "@/infrastructure/synchronization/ConnectivityService";
import { gatewayConfigService } from "@/services/api/gatewayConfigService";
import { isUsingEmbeddedGateway } from "@/infrastructure/desktop/desktopGateway";
import { getGatewayConfig, saveGatewayConfig } from "./gatewayConfig";

let inFlight = false;

/**
 * Busca o endereço do Gateway na Cloud e atualiza o cache local. Requer estar online e autenticado.
 * - endereço presente: grava `url`/`enabled`/`storeId` (precedência sobre URL manual anterior);
 * - `enabled=false` na Cloud: desliga o uso do Gateway sem apagar credencial/terminalId;
 * - sem endereço (null): mantém a config atual (URL manual, se houver, segue como fallback);
 * - qualquer erro (offline, sessão, endpoint): silencioso, sem efeito colateral.
 */
export async function learnGatewayFromCloud(): Promise<void> {
  if (inFlight) return;
  if (typeof navigator !== "undefined" && !navigator.onLine) return;

  const user = getStoredAuthUser();
  if (!user?.companyId) return;
  // Programa desktop com o Gateway embutido ativo: ele é o Gateway deste caixa.
  if (isUsingEmbeddedGateway()) return;

  inFlight = true;
  try {
    const cloud = await gatewayConfigService.get();
    if (!cloud || !cloud.gatewayUrl) {
      // Cloud não tem endereço: não sobrescreve o que já houver (URL manual permanece como fallback).
      return;
    }

    const current = getGatewayConfig();
    const changed =
      current.url !== cloud.gatewayUrl ||
      current.enabled !== cloud.enabled ||
      (current.updatedAt ?? "") !== (cloud.updatedAt ?? "");
    if (!changed) return;

    saveGatewayConfig({
      enabled: cloud.enabled,
      url: cloud.gatewayUrl,
      companyId: user.companyId,
      storeId: cloud.storeId ?? current.storeId,
      updatedAt: cloud.updatedAt ?? "",
    });
  } catch {
    // Silencioso por contrato — a descoberta nunca pode atrapalhar a operação.
  } finally {
    inFlight = false;
  }
}

/**
 * Inicia o aprendizado: aprende uma vez agora e revalida a cada vez que o terminal fica ONLINE.
 * Retorna uma função para cancelar a inscrição. Deve ser chamado no bootstrap (autenticado).
 */
export function startCloudGatewayProvisioning(): () => void {
  void learnGatewayFromCloud();

  const unsubscribe = connectivityService.subscribe((status) => {
    if (status === "ONLINE" || status === "SYNCING") {
      void learnGatewayFromCloud();
    }
  });

  return unsubscribe;
}
