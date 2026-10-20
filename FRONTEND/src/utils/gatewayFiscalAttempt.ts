import { db } from "@/infrastructure/database/dexie";
import { currentTenantId, OUTBOX_CHANGED_EVENT } from "@/infrastructure/database/repositories/OutboxRepository";
import { fiscalService, GatewayFiscalHttpError, type FiscalDocumentDetailDto } from "@/services/api/fiscalService";
import type { RegisterSalePayload } from "@/services/api/salesHistoryService";
import { computeSalePayloadHash } from "./cryptoHash";
import { parseDanfeXml } from "./danfeXml";
import { getGatewayConfig } from "@/infrastructure/gateway/gatewayConfig";

export function gatewayDetailFromXml(xml: string, saleNumber: string): FiscalDocumentDetailDto {
  const d = parseDanfeXml(xml);
  if (!d.contingencia) throw new Error("A nota recebida não corresponde à tentativa de contingência.");
  return { id: `gw-${d.chave}`, saleNumber, serie: Number(d.serie), numeroNf: Number(d.numero),
    status: d.autorizado ? 3 : 8, chaveAcesso: d.chave, protocolo: d.protocolo, dhAutorizacao: d.autorizadoEm,
    motivoStatus: null, criadoEm: d.emitidoEm, tentativas: 0, qrCodeUrl: d.qrCode, xmlDocumento: xml, tpEmis: 9 };
}
/**
 * Liberação manual: envia à nuvem as vendas presas aguardando contingência, sem XML local.
 * A API aplica as regras de emissão da empresa e decide se cada venda gera NFC-e.
 */
export async function releaseFiscalPendingSales(): Promise<number> {
  const tenant = currentTenantId();
  const entries = await db.outbox.where("status").equals("FISCAL_PENDING").filter(e => e.tenantId === tenant).toArray();
  let released = 0;
  for (const entry of entries) {
    const payload = JSON.parse(entry.payload) as RegisterSalePayload;
    if (payload.xmlNfceContingencia) continue;
    delete payload.gatewayFiscalAttempt;
    payload.payloadHash = await computeSalePayloadHash(payload);
    await db.transaction("rw", db.outbox, async () => {
      const latest = await db.outbox.get(entry.id);
      if (latest?.status !== "FISCAL_PENDING" || latest.tenantId !== tenant) return;
      await db.outbox.update(entry.id, { payload: JSON.stringify(payload), payloadHash: payload.payloadHash, status: "PENDING", retryCount: 0, lastError: null });
      released++;
    });
  }
  if (released) window.dispatchEvent(new CustomEvent(OUTBOX_CHANGED_EVENT));
  return released;
}

let resolving = false;
/** Uma tentativa desconhecida permanece fora da sincronização até recuperar o mesmo XML original. */
export async function resolveGatewayFiscalAttempts(forceId?: string): Promise<Map<string, FiscalDocumentDetailDto>> {
  const results = new Map<string, FiscalDocumentDetailDto>();
  if (resolving) return results;
  const run = async () => {
    resolving = true;
    try {
      const tenant = currentTenantId();
      const entries = await db.outbox.where("status").equals("FISCAL_PENDING").filter(e => e.tenantId === tenant && (!forceId || e.id === forceId)).toArray();
      for (const entry of entries) {
        if (!forceId && entry.lastAttemptAt && Date.now() - Date.parse(entry.lastAttemptAt) < 30000) continue;
        let posting = false;
        try {
          const payload = JSON.parse(entry.payload) as RegisterSalePayload, attempt = payload.gatewayFiscalAttempt;
          if (!attempt || !payload.clientSaleId || attempt.payload.vendaId !== payload.clientSaleId || tenant !== currentTenantId()) continue;
          await db.outbox.update(entry.id, { lastAttemptAt: new Date().toISOString() });
          // Usa a configuração atual: tentativas antigas gravaram uma URL padrão errada (localhost:5050).
          const gw = getGatewayConfig();
          const gwUrl = gw.url || attempt.gatewayUrl, key = gw.apiKey;
          let response = await fiscalService.consultarContingenciaGatewayLocal(gwUrl, key, attempt.payload.terminalId, payload.clientSaleId);
          if (!response) { posting = true; response = await fiscalService.emitirContingenciaGatewayLocal(gwUrl, key, attempt.payload); }
          const xml = response?.xmlDocumento || response?.xmlAssinado;
          if (!response?.success || !xml) throw new Error("O Gateway ainda não confirmou o documento original.");
          const detail = gatewayDetailFromXml(xml, payload.offlineReference || entry.aggregateId);
          payload.xmlNfceContingencia = xml;
          delete payload.gatewayFiscalAttempt;
          payload.payloadHash = await computeSalePayloadHash(payload);
          if (tenant !== currentTenantId()) break;
          await db.transaction("rw", db.outbox, async () => {
            const latest = await db.outbox.get(entry.id);
            if (latest?.status !== "FISCAL_PENDING" || latest.tenantId !== tenant) return;
            await db.outbox.update(entry.id, { payload: JSON.stringify(payload), payloadHash: payload.payloadHash, status: "PENDING", lastError: null });
          });
          results.set(payload.clientSaleId, detail);
        } catch (error) {
          if (tenant !== currentTenantId()) break;
          if (posting && error instanceof GatewayFiscalHttpError && [400, 422].includes(error.status)) {
            const payload = JSON.parse(entry.payload) as RegisterSalePayload;
            delete payload.gatewayFiscalAttempt;
            payload.payloadHash = await computeSalePayloadHash(payload);
            await db.outbox.update(entry.id, { payload: JSON.stringify(payload), payloadHash: payload.payloadHash, status: "PENDING", lastError: null });
          } else {
            await db.outbox.update(entry.id, { lastError: "Aguardando recuperar o documento original no Gateway. A venda permanece guardada neste caixa." });
          }
        }
      }
      if (entries.length) window.dispatchEvent(new CustomEvent(OUTBOX_CHANGED_EVENT));
    } finally { resolving = false; }
  };
  if (navigator.locks) await navigator.locks.request("horus-pdv-gateway-sign", { ifAvailable: !forceId }, lock => lock ? run() : undefined);
  else await run();
  return results;
}
