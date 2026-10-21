import { db } from "@/infrastructure/database/dexie";
import { currentTenantId } from "@/infrastructure/database/repositories/OutboxRepository";
import { getStoredAuthUser } from "@/utils/authStorage";
import type { RegisterSalePayload } from "@/services/api/salesHistoryService";
import { apiRequest, requireEnvUrl } from "@/services/api/apiClient";
import { getGatewayConfig } from "./gatewayConfig";

type Policy = { companyId: string; storeId: string | null; required: boolean; state: string; version: number };
type Decision = { state: string; authorizationId: string; saleEventId: string; amountCents: number; expiresAt: string };
export type CreditBudgetView = { grantId: string; customerId: string; freeCents: number; consumedCents: number; reservedCents: number; expiresAt: string };
export function readCreditBudget(document: string): Promise<CreditBudgetView> { return request(`/budget?document=${encodeURIComponent(document.replace(/\D/g, ""))}&amountCents=0`); }
const policyKey = () => `horus-credit-policy:${currentTenantId()}:${getStoredAuthUser()?.id}`;
const cloudUrl = () => requireEnvUrl("VITE_HISTORICO_VENDAS_API_URL").replace(/\/HistoricoVendas\/?$/i, "/gateway-credit");
export function sharedCreditRequired(): boolean {
  try { return (JSON.parse(localStorage.getItem(policyKey()) || "null") as Policy | null)?.required === true; } catch { return false; }
}
export async function refreshCreditPolicy(): Promise<void> {
  const result = await apiRequest<Policy>(`${cloudUrl()}/policy`);
  if (result.data) localStorage.setItem(policyKey(), JSON.stringify(result.data));
}
async function request<T>(path: string, body?: unknown): Promise<T> {
  const config = getGatewayConfig();
  if (!config.url || !config.apiKey || config.companyId !== currentTenantId()) throw new Error("Gateway indisponível para autorizar fiado. Escolha outra forma de pagamento.");
  const response = await fetch(`${config.url.replace(/\/$/, "")}/api/gateway/credit${path}`, {
    method: body === undefined ? "GET" : "POST", signal: AbortSignal.timeout(5000),
    headers: { "Content-Type": "application/json", "X-Terminal-Id": config.terminalId, "X-Terminal-Key": config.apiKey },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    const reason = await response.json().catch(() => ({})) as { error?: string };
    const messages: Record<string, string> = { BUDGET_INSUFFICIENT: "Orçamento de fiado insuficiente.", BUDGET_UNAVAILABLE: "Cliente sem orçamento de fiado provisionado.", GRANT_EXPIRED: "Orçamento de fiado vencido. Solicite atualização ao gerente.", OPERATOR_NOT_AUTHORIZED: "Atualize a autorização do operador para comprar fiado.", CREDIT_DISABLED: "Fiado compartilhado bloqueado no Gateway.", CLOCK_REGRESSED: "Relógio do Gateway precisa de conferência antes de autorizar fiado." };
    throw Object.assign(new Error(messages[reason.error || ""] || reason.error || `Gateway HTTP ${response.status}`), { status: response.status, code: reason.error });
  }
  return response.json() as Promise<T>;
}
export async function refreshOperatorPermit(): Promise<void> {
  const result = await apiRequest<{ permit: string; expiresAt: string }>(`${cloudUrl()}/operator-permit`, { method: "POST", body: JSON.stringify({ terminalId: getGatewayConfig().terminalId }) });
  if (result.data) localStorage.setItem(`${policyKey()}:permit`, JSON.stringify(result.data));
}
export async function reportCreditReadiness(): Promise<void> {
  const pending = await db.outbox.filter(e => e.tenantId === currentTenantId() && e.status !== "PROCESSED" && e.eventType === "SALE_CREATED").count() + await db.creditIntents.where("tenantId").equals(currentTenantId()).count();
  await request("/readiness", { version: 1, pendingLegacy: pending });
}
export async function commitGatewayCredit(payload: RegisterSalePayload): Promise<void> {
  if (navigator.locks) return navigator.locks.request(`horus-credit:${currentTenantId()}`, () => commitCredit(payload));
  return commitCredit(payload);
}
async function commitCredit(payload: RegisterSalePayload): Promise<void> {
  const id = payload.eventId!;
  const tenantId = currentTenantId();
  const conflicting = await db.creditIntents.where("tenantId").equals(tenantId).filter(i => i.id !== id).first();
  if (conflicting) throw new Error("Existe venda fiado com resultado pendente. Recupere a venda anterior antes de iniciar outra.");
  let intent = await db.creditIntents.get(id);
  if (!intent) {
    const cents = Math.round((payload.payments || []).filter(p => p.paymentType.toLowerCase() === "fiado").reduce((sum, p) => sum + p.amount, 0) * 100);
    const budget = await request<{ grantId: string; customerId: string }>(`/budget?document=${encodeURIComponent(payload.customerCpf.replace(/\D/g, ""))}&amountCents=${cents}`);
    const config = getGatewayConfig();
    intent = { id, tenantId, terminalId: config.terminalId, storeId: config.storeId, authorizationId: crypto.randomUUID(), grantId: budget.grantId, customerId: budget.customerId, payload: structuredClone(payload) };
    await db.transaction("rw", db.creditIntents, async () => {
      const other = await db.creditIntents.where("tenantId").equals(tenantId).filter(i => i.id !== id).first();
      if (other) throw new Error("Recupere a intenção de fiado anterior.");
      await db.creditIntents.add(intent!);
    }); // durable BEFORE reserve and commit; never auto-replace an uncertain id.
  }
  if (intent.terminalId !== getGatewayConfig().terminalId || intent.storeId !== getGatewayConfig().storeId) throw new Error("Recupere o fiado usando a identidade original do terminal e da loja.");
  if (intent.tenantId !== tenantId || JSON.stringify(intent.payload.items) !== JSON.stringify(payload.items) || JSON.stringify(intent.payload.payments) !== JSON.stringify(payload.payments) || intent.payload.customerCpf !== payload.customerCpf) throw new Error("Conflito na intenção de fiado. Recupere a venda original.");
  const authorization = intent.authorizationId;
  try {
    let state: Decision | null = null;
    try { state = await request<Decision>(`/${authorization}`); } catch (error) { if ((error as { status?: number }).status !== 404) throw error; }
    if (!state) {
      const permit = JSON.parse(localStorage.getItem(`${policyKey()}:permit`) || "null") as { permit?: string } | null;
      state = await request<Decision>("/reserve", { version: 1, authorizationId: authorization, saleEventId: id, grantId: intent.grantId, customerId: intent.customerId, amountCents: Math.round((intent.payload.payments || []).filter(p => p.paymentType.toLowerCase() === "fiado").reduce((sum, p) => sum + p.amount, 0) * 100), operatorId: intent.payload.operatorId, operatorPermit: permit?.permit || "" });
    }
    if (state.state === "RESERVED") {
      const config = getGatewayConfig();
      state = await request<Decision>(`/${authorization}/commit`, { companyId: tenantId, storeId: config.storeId, terminalId: config.terminalId, eventId: id, eventType: "SALE_CREATED", payload: intent.payload, payloadHash: intent.payload.payloadHash, occurredAt: intent.payload.occurredAt });
    }
    if (state.state !== "COMMITTED") {
      if (state.state === "CANCELLED" || state.state === "EXPIRED") await db.creditIntents.delete(id);
      throw Object.assign(new Error("Reserva de fiado encerrada. Confira os pagamentos antes de tentar novamente."), { status: 409 });
    }
    payload.gatewayCreditAuthorizationId = authorization;
  } catch (error) {
    const code = (error as { code?: string }).code;
    if (code && ["BUDGET_INSUFFICIENT", "BUDGET_UNAVAILABLE", "OPERATOR_NOT_AUTHORIZED", "CREDIT_DISABLED", "GRANT_EXPIRED"].includes(code)) await db.creditIntents.delete(id);
    if (!(error as { status?: number }).status) throw new Error("Resultado de fiado incerto. A venda está guardada; reconecte o Gateway para recuperar a mesma venda.");
    throw error;
  }
}
export async function cancelPendingCreditReservation(): Promise<boolean> {
  const intents = await db.creditIntents.where("tenantId").equals(currentTenantId()).toArray();
  for (const intent of intents) {
    const config = getGatewayConfig();
    if (intent.terminalId !== config.terminalId || intent.storeId !== config.storeId) throw new Error("Use o terminal original para consultar a reserva.");
    let state: Decision;
    try { state = await request<Decision>(`/${intent.authorizationId}`); }
    catch (error) {
      if ((error as { status?: number }).status !== 404) throw error;
      await db.creditIntents.delete(intent.id); continue;
    }
    if (state.state === "COMMITTED") return false;
    if (state.state === "RESERVED") state = await request<Decision>(`/${intent.authorizationId}/cancel`, {});
    if (state.state === "COMMITTED") return false;
    if (state.state === "CANCELLED" || state.state === "EXPIRED") await db.creditIntents.delete(intent.id);
    else throw new Error("Resultado de fiado ainda incerto. Consulte novamente.");
  }
  return true;
}
export async function gatewayCreditCloudResult(payload: RegisterSalePayload): Promise<{ saleNumber: string; isReplay?: boolean }> {
  const result = await request<{ saleNumber?: string; cloudSynced: boolean }>(`/${payload.gatewayCreditAuthorizationId}/result`);
  if (!result.cloudSynced || !result.saleNumber) throw new Error("Venda fiado confirmada no Gateway, aguardando conciliação cloud.");
  return { saleNumber: result.saleNumber, isReplay: true };
}
