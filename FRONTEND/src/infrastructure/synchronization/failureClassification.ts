/**
 * Arquivo: src/infrastructure/synchronization/failureClassification.ts
 * Objetivo: classifica falhas de envio do outbox. Só RECUSA DE NEGÓCIO (4xx, exceto 401/403/408/429)
 *           leva o evento a FAILED; toda falha transitória (rede, timeout, 5xx, 401/403, 408, 429) é
 *           reagendada com backoff, sem limite de tentativas — o mesmo critério do Local Gateway.
 */

/** Status HTTP do erro, quando houver (0 = sem resposta / erro de rede). */
export function errorStatus(err: unknown): number {
  if (err && typeof err === "object" && "status" in err) {
    const status = Number((err as { status?: unknown }).status);
    return Number.isFinite(status) ? status : 0;
  }
  return 0;
}

/**
 * A nuvem respondeu e RECUSOU o evento por regra de negócio/validação: 4xx exceto 401/403 (sessão/token,
 * recuperável), 408 e 429 (tentar de novo). Erro sem status é transitório.
 */
export function isBusinessRejection(err: unknown): boolean {
  const status = errorStatus(err);
  return status >= 400 && status < 500 && status !== 401 && status !== 403 && status !== 408 && status !== 429;
}

/** Eventos de caixa (abertura, movimento, fechamento) formam uma cadeia por terminal. */
export function isCashEventType(eventType: string): boolean {
  return eventType.startsWith("CASH_");
}

/**
 * Versões anteriores abandonavam o evento (FAILED) após esgotar 10 tentativas por falha de rede. Esses
 * eventos têm `retryCount >= limite`; recusas de negócio vão a FAILED logo na primeira resposta.
 */
export const LEGACY_EXHAUSTED_RETRIES = 10;
