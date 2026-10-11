import { test, expect } from "./browser";
import { accessKey, fiscalXml } from "./fixtures";

const payload = {
  clientSaleId: "sale-original", offlineReference: "OFF-ORIGINAL", eventId: "event-original", reenvioOffline: true,
  customerName: "Cliente", customerCpf: "", paymentType: "dinheiro", totalAmount: "8,00", operatorName: "Teste",
  items: [{ productCode: "P-XML", quantity: 0.5, unitPrice: 20, desconto: 2 }],
  payments: [{ paymentType: "dinheiro", amount: 10, cashGiven: 10, changeAmount: 2 }],
  gatewayFiscalAttempt: { gatewayUrl: "https://gateway.exemplo.invalid", payload: { vendaId: "sale-original", terminalId: "PDV-TESTE", itens: [], pagamentos: [], valorTroco: 2 } },
};

test("Gateway recupera o mesmo XML antes de qualquer POST e vincula seu conteúdo ao hash da venda", async ({ page }) => {
  const xml = fiscalXml({ contingency: true, authorized: false });
  const result = await page.evaluate(async ({ xml, payload }) => {
    const { fiscalService } = await import("/src/services/api/fiscalService.ts" as string);
    const { resolveGatewayFiscalAttempts } = await import("/src/utils/gatewayFiscalAttempt.ts" as string);
    const { enqueueEvent } = await import("/src/infrastructure/database/repositories/OutboxRepository.ts" as string);
    const { db } = await import("/src/infrastructure/database/dexie.ts" as string);
    const { computeSalePayloadHash } = await import("/src/utils/cryptoHash.ts" as string);
    localStorage.setItem("horuspdv.auth.user", JSON.stringify({ companyId: "EMP-A" }));
    const calls: string[] = [];
    fiscalService.consultarContingenciaGatewayLocal = async (_url: string, _key: string, terminalId: string, saleId: string) => { calls.push(`GET:${terminalId}:${saleId}`); return { success: true, xmlDocumento: xml }; };
    fiscalService.emitirContingenciaGatewayLocal = async () => { calls.push("POST"); throw new Error("Reemissão indevida"); };
    const oldHash = await computeSalePayloadHash(payload);
    await enqueueEvent({ id: payload.eventId, eventType: "SALE_CREATED", aggregateType: "SALE", aggregateId: payload.offlineReference, payload, payloadHash: oldHash, waitForFiscal: true });
    const details = await resolveGatewayFiscalAttempts(payload.eventId);
    const event = await db.outbox.get(payload.eventId), saved = JSON.parse(event.payload);
    return { calls, status: event.status, xml: saved.xmlNfceContingencia, attempt: saved.gatewayFiscalAttempt ?? null, hash: event.payloadHash, savedHash: saved.payloadHash, calculatedHash: await computeSalePayloadHash(saved), oldHash, detail: details.get(payload.clientSaleId) };
  }, { xml, payload });

  expect(result.calls).toEqual(["GET:PDV-TESTE:sale-original"]);
  expect(result.status).toBe("PENDING");
  expect(result.xml).toBe(xml);
  expect(result.attempt).toBeNull();
  expect(result.hash).toBe(result.savedHash);
  expect(result.hash).toBe(result.calculatedHash);
  expect(result.hash).not.toBe(result.oldHash);
  expect(result.detail).toMatchObject({ status: 8, chaveAcesso: accessKey("9"), xmlDocumento: xml, protocolo: null, saleNumber: "OFF-ORIGINAL" });
});

test("404 permite repetir apenas a tentativa com os mesmos IDs e payload original", async ({ page }) => {
  const xml = fiscalXml({ contingency: true, authorized: false });
  const result = await page.evaluate(async ({ xml, payload }) => {
    const { fiscalService } = await import("/src/services/api/fiscalService.ts" as string);
    const { resolveGatewayFiscalAttempts } = await import("/src/utils/gatewayFiscalAttempt.ts" as string);
    const { enqueueEvent } = await import("/src/infrastructure/database/repositories/OutboxRepository.ts" as string);
    const { db } = await import("/src/infrastructure/database/dexie.ts" as string);
    localStorage.setItem("horuspdv.auth.user", JSON.stringify({ companyId: "EMP-A" }));
    const calls: { method: string; payload?: unknown }[] = [];
    fiscalService.consultarContingenciaGatewayLocal = async () => { calls.push({ method: "GET" }); return null; };
    fiscalService.emitirContingenciaGatewayLocal = async (_url: string, _key: string, original: unknown) => { calls.push({ method: "POST", payload: original }); return { success: true, xmlAssinado: xml }; };
    await enqueueEvent({ id: payload.eventId, eventType: "SALE_CREATED", aggregateType: "SALE", aggregateId: payload.offlineReference, payload, waitForFiscal: true });
    await resolveGatewayFiscalAttempts(payload.eventId);
    const saved = await db.outbox.get(payload.eventId);
    return { calls, status: saved.status, xml: JSON.parse(saved.payload).xmlNfceContingencia };
  }, { xml, payload });
  expect(result.calls).toEqual([{ method: "GET" }, { method: "POST", payload: payload.gatewayFiscalAttempt.payload }]);
  expect(result.status).toBe("PENDING");
  expect(result.xml).toBe(xml);
});

for (const scenario of [
  { name: "timeout desconhecido", kind: "timeout", xml: "" },
  { name: "XML normal no lugar de contingência", kind: "xml", xml: fiscalXml() },
  { name: "XML de contingência incompleto", kind: "xml", xml: fiscalXml({ contingency: true, authorized: false, signed: false }) },
  { name: "erro 422 durante consulta", kind: "query-rejected", xml: "" },
]) {
  test(`${scenario.name} mantém FISCAL_PENDING e a tentativa original, sem reemitir ou sincronizar`, async ({ page }) => {
    const result = await page.evaluate(async ({ payload, scenario }) => {
      const { fiscalService, GatewayFiscalHttpError } = await import("/src/services/api/fiscalService.ts" as string);
      const { resolveGatewayFiscalAttempts } = await import("/src/utils/gatewayFiscalAttempt.ts" as string);
      const { enqueueEvent } = await import("/src/infrastructure/database/repositories/OutboxRepository.ts" as string);
      const { db } = await import("/src/infrastructure/database/dexie.ts" as string);
      localStorage.setItem("horuspdv.auth.user", JSON.stringify({ companyId: "EMP-A" }));
      let posts = 0;
      fiscalService.consultarContingenciaGatewayLocal = async () => {
        if (scenario.kind === "timeout") throw new TypeError("Resposta desconhecida");
        if (scenario.kind === "query-rejected") throw new GatewayFiscalHttpError(422, "Consulta recusada");
        return { success: true, xmlDocumento: scenario.xml };
      };
      fiscalService.emitirContingenciaGatewayLocal = async () => { posts++; return {}; };
      await enqueueEvent({ id: payload.eventId, eventType: "SALE_CREATED", aggregateType: "SALE", aggregateId: payload.offlineReference, payload, waitForFiscal: true });
      const details = await resolveGatewayFiscalAttempts(payload.eventId), event = await db.outbox.get(payload.eventId), saved = JSON.parse(event.payload);
      return { posts, count: details.size, status: event.status, attempt: saved.gatewayFiscalAttempt, xml: saved.xmlNfceContingencia ?? null, error: event.lastError };
    }, { payload, scenario });
    expect(result.posts).toBe(0);
    expect(result.count).toBe(0);
    expect(result.status).toBe("FISCAL_PENDING");
    expect(result.attempt).toEqual(payload.gatewayFiscalAttempt);
    expect(result.xml).toBeNull();
    expect(result.error).toContain("documento original");
  });
}

test("resolver não consulta pendências de outra empresa nem tentativas com vendaId divergente", async ({ page }) => {
  const result = await page.evaluate(async payload => {
    const { fiscalService } = await import("/src/services/api/fiscalService.ts" as string);
    const { resolveGatewayFiscalAttempts } = await import("/src/utils/gatewayFiscalAttempt.ts" as string);
    const { enqueueEvent } = await import("/src/infrastructure/database/repositories/OutboxRepository.ts" as string);
    const { db } = await import("/src/infrastructure/database/dexie.ts" as string);
    localStorage.setItem("horuspdv.auth.user", JSON.stringify({ companyId: "EMP-A" }));
    let calls = 0;
    fiscalService.consultarContingenciaGatewayLocal = async () => { calls++; throw new Error("Consulta indevida"); };
    await enqueueEvent({ id: "other-tenant", tenantId: "EMP-B", eventType: "SALE_CREATED", aggregateType: "SALE", aggregateId: payload.offlineReference, payload, waitForFiscal: true });
    const mismatch = { ...payload, gatewayFiscalAttempt: { ...payload.gatewayFiscalAttempt, payload: { ...payload.gatewayFiscalAttempt.payload, vendaId: "sale-different" } } };
    await enqueueEvent({ id: "mismatch", eventType: "SALE_CREATED", aggregateType: "SALE", aggregateId: payload.offlineReference, payload: mismatch, waitForFiscal: true });
    await resolveGatewayFiscalAttempts();
    return { calls, statuses: (await db.outbox.toArray()).map((event: any) => event.status) };
  }, payload);
  expect(result.calls).toBe(0);
  expect(result.statuses).toEqual(["FISCAL_PENDING", "FISCAL_PENDING"]);
});
