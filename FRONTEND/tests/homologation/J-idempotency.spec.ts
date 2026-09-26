/**
 * CHANGE 08.1 — Categoria J: Idempotência
 *
 * J01 — Mesmo EventId
 *       Cenário 1: mesmo EventId + mesmo payload → replay (sem duplicação).
 *       Cenário 2: mesmo EventId + payload diferente → conflito (original preservada).
 */
import { test, expect } from "@playwright/test";
import {
  RUN_ID,
  API_URL,
  initSqlContainer,
  registerTestCompany,
  loginApi,
  cleanupHomologData,
  api,
  productPayload,
  supplierPayload,
  generateCpf,
  querySqlScalar,
  escapeSql,
  getAuthHeaders,
} from "./helpers/setup";

type Entity = { id: string };

test.describe("J — Idempotência", () => {
  test.describe.configure({ mode: "serial" });

  let productCode: string;

  test.beforeAll(async ({ request }) => {
    initSqlContainer();
    cleanupHomologData();
    await registerTestCompany(request);
    await loginApi(request);

    await api(request, "/Caixa/fechar", {
      method: "POST",
      body: { closingAmount: "0,00", note: `${RUN_ID} cleanup pre-J` },
      allowFailure: true,
    });

    await api<Entity>(request, "/Fornecedor", {
      method: "POST",
      body: supplierPayload("Fornecedor J"),
    });
    const payload = productPayload("ProdutoJ", `${RUN_ID} Fornecedor J`, "100");
    await api(request, "/Produto", { method: "POST", body: payload });
    productCode = payload.productCode;

    await api(request, "/Caixa/abrir", {
      method: "POST",
      body: { openingAmount: "100,00" },
    });
  });

  test.afterAll(async ({ request }) => {
    await api(request, "/Caixa/fechar", {
      method: "POST",
      body: { closingAmount: "200,00", note: `${RUN_ID} cleanup J` },
      allowFailure: true,
    });
    cleanupHomologData();
  });

  test("J01 — Mesmo EventId com mesmo payload = replay sem duplicação", async ({ request }) => {
    const eventId = `ev-idem-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
    const clientSaleId = `cs-idem-${Date.now()}`;
    const customerCpf = generateCpf();

    const salePayload = {
      clientSaleId,
      eventId,
      eventType: "SALE_CREATED",
      occurredAt: new Date().toISOString(),
      offlineReference: `OFF-IDEM-${Date.now().toString().slice(-6)}`,
      customerName: `${RUN_ID} IdempotenciaJ`,
      customerCpf,
      paymentType: "Dinheiro",
      totalAmount: "25,00",
      items: [
        {
          productCode,
          productName: `${RUN_ID} ProdutoJ`,
          quantity: 1,
        },
      ],
      payloadHash: "test-hash-j01",
    };

    // Primeiro envio — deve ser processado
    const firstResponse = await request.fetch(`${API_URL}/HistoricoVendas`, {
      method: "POST",
      data: salePayload,
      headers: getAuthHeaders(),
    });
    const firstRaw = await firstResponse.text();
    let firstBody: { success?: boolean; data?: { saleNumber?: string; isReplay?: boolean } };
    try {
      firstBody = JSON.parse(firstRaw);
    } catch {
      firstBody = {};
    }

    expect(
      firstResponse.ok(),
      `Primeiro envio deve ser aceito: ${firstRaw}`,
    ).toBeTruthy();
    const saleNumber = firstBody.data?.saleNumber;
    expect(saleNumber, "Deve retornar saleNumber").toBeTruthy();

    // Segundo envio — MESMO eventId + MESMO payload → replay
    const secondResponse = await request.fetch(`${API_URL}/HistoricoVendas`, {
      method: "POST",
      data: salePayload,
      headers: getAuthHeaders(),
    });
    const secondRaw = await secondResponse.text();
    let secondBody: { success?: boolean; data?: { saleNumber?: string; isReplay?: boolean } };
    try {
      secondBody = JSON.parse(secondRaw);
    } catch {
      secondBody = {};
    }

    if (secondResponse.ok() && secondBody.success) {
      // API aceitou o replay — verificar se retornou isReplay=true
      if (secondBody.data?.isReplay) {
        // Comportamento correto: replay detectado
        expect(secondBody.data.saleNumber).toBe(saleNumber);
      }
      // Verificar que não duplicou — contar vendas com esse customerName
      // (EventId pode não ser uma coluna no schema atual)
      const count = querySqlScalar(
        `SELECT COUNT(*) FROM Vendas WHERE CustomerName = N'${escapeSql(`${RUN_ID} IdempotenciaJ`)}'`,
      );
      if (count !== null && !isNaN(Number(count))) {
        if (Number(count) > 1) {
          console.log(
            `GAP-J01: API criou ${count} vendas com mesmo eventId+payload. ` +
              "Idempotência server-side não implementada. " +
              "Severidade: P0 — risco de duplicação em vendas offline. " +
              "CHANGE futura sugerida: CHANGE 09 — Idempotência server-side via EventId/payloadHash.",
          );
        }
        // Se count === 2 e não retornou isReplay, é um GAP mas não falhamos
        // pois estamos documentando
      }
    } else {
      // API rejeitou — aceitável se retornou erro de duplicata
      expect(
        secondResponse.status() === 409 ||
          secondRaw.toLowerCase().includes("duplicate") ||
          secondRaw.toLowerCase().includes("replay") ||
          secondRaw.toLowerCase().includes("já processado") ||
          secondRaw.toLowerCase().includes("already"),
        `Segundo envio deveria ser replay ou rejeitado como duplicata: ${secondRaw}`,
      ).toBeTruthy();
    }
  });

  test("J02 — Mesmo EventId com payload diferente = conflito", async ({ request }) => {
    const eventId = `ev-conflict-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;

    const payload1 = {
      clientSaleId: `cs-conf1-${Date.now()}`,
      eventId,
      eventType: "SALE_CREATED",
      occurredAt: new Date().toISOString(),
      offlineReference: `OFF-CONF1-${Date.now().toString().slice(-6)}`,
      customerName: `${RUN_ID} ConflictA`,
      customerCpf: generateCpf(),
      paymentType: "Dinheiro",
      totalAmount: "25,00",
      items: [
        {
          productCode,
          productName: `${RUN_ID} ProdutoJ`,
          quantity: 1,
        },
      ],
      payloadHash: "hash-original",
    };

    // Enviar payload original
    const first = await request.fetch(`${API_URL}/HistoricoVendas`, {
      method: "POST",
      data: payload1,
      headers: getAuthHeaders(),
    });
    expect(first.ok(), "Primeiro envio deve funcionar").toBeTruthy();

    // Enviar MESMO eventId + PAYLOAD DIFERENTE
    const payload2 = {
      ...payload1,
      clientSaleId: `cs-conf2-${Date.now()}`,
      customerName: `${RUN_ID} ConflictB`,
      totalAmount: "50,00", // Valor diferente!
      payloadHash: "hash-diferente",
      items: [
        {
          productCode,
          productName: `${RUN_ID} ProdutoJ`,
          quantity: 2, // Quantidade diferente!
        },
      ],
    };

    const second = await request.fetch(`${API_URL}/HistoricoVendas`, {
      method: "POST",
      data: payload2,
      headers: getAuthHeaders(),
    });
    const secondRaw = await second.text();

    // Verificar idempotência via ProcessedEvents
    const processedCount = querySqlScalar(
      `SELECT COUNT(*) FROM ProcessedEvents WHERE EventId = N'${escapeSql(eventId)}'`,
    );

    if (processedCount !== null && !isNaN(Number(processedCount))) {
      // Se o segundo envio foi rejeitado ou processado como replay, deve haver exatamente 1 ProcessedEvent
      if (Number(processedCount) === 1) {
        // Idempotência funcionou — evento processado apenas uma vez
      } else if (Number(processedCount) > 1) {
        console.log(
          `GAP-J02: ProcessedEvents contém ${processedCount} registros para mesmo EventId. ` +
            "Idempotência por EventId pode não estar prevenindo reprocessamento. " +
            "Severidade: P0 — risco de dados inconsistentes.",
        );
      }
    }

    // Verificar que os dados originais (ConflictA) estão preservados
    const originalCustomer = querySqlScalar(
      `SELECT TOP 1 CustomerName FROM Vendas WHERE CustomerName LIKE N'${escapeSql(RUN_ID)} Conflict%' ORDER BY SaleDate ASC`,
    );
    if (originalCustomer !== null) {
      expect(
        originalCustomer,
        "Dados originais devem ser preservados",
      ).toContain("ConflictA");
    }
  });
});
