import { test, expect } from "@playwright/test";
import { registerAndLogin, loginOnly, openCashRegister, seedTestProducts } from "./helpers/api-auth";

const API_URL = process.env.API_URL ?? "http://localhost:5260/api";

let savedEmail = "";
let savedPassword = "";

test.describe("BLOCOS 2 e 3 — Idempotência de Vendas e Concorrência de CASH_MOVEMENT", () => {
  test.beforeAll(async ({ request }) => {
    const session = await registerAndLogin(request);
    savedEmail = session.email;
    savedPassword = session.password;
    await seedTestProducts(session.companyId);
    await openCashRegister(request);
  });

  test.beforeEach(async ({ request }) => {
    await loginOnly(request, savedEmail, savedPassword);
    await openCashRegister(request);
  });

  test("Teste 8 — Nova venda: envio inicial com EventId e PayloadHash processa com sucesso", async ({
    request,
  }) => {
    const eventId = `test-sale-evt-${Date.now()}-1`;
    const payload = {
      eventId,
      payloadHash: "hash-inicial-sale-01",
      customerName: "Cliente Teste Idempotencia",
      customerCpf: "123.456.789-00",
      paymentType: "Dinheiro",
      totalAmount: "100,00",
      operatorName: "Operador Teste",
      items: [
        {
          productCode: "PROD-TEST-01",
          productName: "Produto Teste 1",
          quantity: 1,
          unitPrice: 100.0,
          desconto: 0,
        },
      ],
      payments: [
        {
          paymentType: "Dinheiro",
          amount: 100.0,
          cashGiven: 100.0,
          changeAmount: 0,
        },
      ],
    };

    const response = await request.post(`${API_URL}/HistoricoVendas`, {
      data: payload,
      headers: { "Content-Type": "application/json" },
    });

    const status = response.status();
    const raw = await response.text();
    let body: any;
    try {
      body = JSON.parse(raw);
    } catch {
      body = { raw };
    }

    expect([200, 201], `Status ${status}, body: ${raw}`).toContain(status);
    expect(body.success).toBe(true);
    expect(body.data?.isReplay).toBeFalsy();
  });

  test("Teste 9 — Replay da mesma venda: reenvio com mesmo EventId e PayloadHash retorna 200 OK com isReplay: true", async ({
    request,
  }) => {
    const eventId = `test-sale-evt-${Date.now()}-2`;
    const payload = {
      eventId,
      payloadHash: "hash-sale-replay-02",
      customerName: "Cliente Replay Test",
      paymentType: "Dinheiro",
      totalAmount: "50,00",
      operatorName: "Operador Teste",
      items: [
        {
          productCode: "PROD-TEST-02",
          productName: "Produto Replay",
          quantity: 1,
          unitPrice: 50.0,
          desconto: 0,
        },
      ],
      payments: [
        {
          paymentType: "Dinheiro",
          amount: 50.0,
          cashGiven: 50.0,
          changeAmount: 0,
        },
      ],
    };

    // 1o Envio
    const res1 = await request.post(`${API_URL}/HistoricoVendas`, {
      data: payload,
      headers: { "Content-Type": "application/json" },
    });
    expect([200, 201]).toContain(res1.status());

    // 2o Envio (Replay identico)
    const res2 = await request.post(`${API_URL}/HistoricoVendas`, {
      data: payload,
      headers: { "Content-Type": "application/json" },
    });

    expect(res2.status()).toBe(200);
    const body2 = await res2.json();
    expect(body2.success).toBe(true);
    expect(body2.data?.isReplay).toBe(true);
  });

  test("Teste 10 — Adulteracao de payload de venda: mesmo EventId com PayloadHash alterado retorna 409 Conflict", async ({
    request,
  }) => {
    const eventId = `test-sale-evt-${Date.now()}-3`;
    const originalPayload = {
      eventId,
      payloadHash: "hash-original-sale-03",
      customerName: "Cliente Original",
      paymentType: "Dinheiro",
      totalAmount: "75,00",
      operatorName: "Operador Teste",
      items: [
        { productCode: "PROD-03", quantity: 1, unitPrice: 75.0, desconto: 0 },
      ],
      payments: [
        { paymentType: "Dinheiro", amount: 75.0, cashGiven: 75.0, changeAmount: 0 },
      ],
    };

    // 1o Envio
    const res1 = await request.post(`${API_URL}/HistoricoVendas`, {
      data: originalPayload,
      headers: { "Content-Type": "application/json" },
    });
    expect([200, 201]).toContain(res1.status());

    // 2o Envio adulterado (mesmo EventId, hash diferente / valor modificado)
    const tamperedPayload = {
      ...originalPayload,
      totalAmount: "999,99",
      payloadHash: "hash-tampered-diferente",
    };

    const res2 = await request.post(`${API_URL}/HistoricoVendas`, {
      data: tamperedPayload,
      headers: { "Content-Type": "application/json" },
    });

    expect(res2.status()).toBe(409);
    const body2 = await res2.json();
    expect(body2.success).toBe(false);
    expect(body2.message).toMatch(/conflito|diverg|adulterado|já processado/i);
  });

  test("Teste 11 — Novo CASH_MOVEMENT: envio inicial com EventId e PayloadHash processa com sucesso", async ({
    request,
  }) => {
    const eventId = `test-mov-evt-${Date.now()}-1`;
    const payload = {
      tipo: "Reforco",
      valor: "80,00",
      motivo: "Fundo de troco inicial teste",
      eventId,
      payloadHash: "hash-mov-01",
    };

    const response = await request.post(`${API_URL}/Caixa/movimento`, {
      data: payload,
      headers: { "Content-Type": "application/json" },
    });

    const rawBody = await response.text();
    expect([200, 201], `Status ${response.status()}, body: ${rawBody}`).toContain(response.status());
    const body = JSON.parse(rawBody);
    expect(body.success).toBe(true);
  });

  test("Teste 12 — Replay de CASH_MOVEMENT: reenvio com mesmo EventId e PayloadHash retorna 200 OK com isReplay: true", async ({
    request,
  }) => {
    const eventId = `test-mov-evt-${Date.now()}-2`;
    const payload = {
      tipo: "Sangria",
      valor: "40,00",
      motivo: "Sangria de teste idempotencia",
      eventId,
      payloadHash: "hash-mov-replay-02",
    };

    // 1o Envio
    const res1 = await request.post(`${API_URL}/Caixa/movimento`, {
      data: payload,
      headers: { "Content-Type": "application/json" },
    });
    expect([200, 201]).toContain(res1.status());

    // 2o Envio (Replay)
    const res2 = await request.post(`${API_URL}/Caixa/movimento`, {
      data: payload,
      headers: { "Content-Type": "application/json" },
    });

    expect(res2.status()).toBe(200);
    const body2 = await res2.json();
    expect(body2.success).toBe(true);
    expect(body2.data?.isReplay).toBe(true);
  });

  test("Teste 13 — Adulteracao de CASH_MOVEMENT: mesmo EventId com PayloadHash alterado retorna 409 Conflict", async ({
    request,
  }) => {
    const eventId = `test-mov-evt-${Date.now()}-3`;
    const originalPayload = {
      tipo: "Reforco",
      valor: "30,00",
      motivo: "Reforco teste",
      eventId,
      payloadHash: "hash-original-mov-03",
    };

    // 1o Envio
    const res1 = await request.post(`${API_URL}/Caixa/movimento`, {
      data: originalPayload,
      headers: { "Content-Type": "application/json" },
    });
    expect([200, 201]).toContain(res1.status());

    // 2o Envio com valor/tipo/hash alterado
    const tamperedPayload = {
      tipo: "Sangria",
      valor: "300,00",
      motivo: "Alterado indevidamente",
      eventId,
      payloadHash: "hash-tampered-mov-03",
    };

    const res2 = await request.post(`${API_URL}/Caixa/movimento`, {
      data: tamperedPayload,
      headers: { "Content-Type": "application/json" },
    });

    expect(res2.status()).toBe(409);
    const body2 = await res2.json();
    expect(body2.success).toBe(false);
    expect(body2.message).toMatch(/conflito|diverg|adulterado|já processado/i);
  });

  test("Teste 14 — Concorrencia real de CASH_MOVEMENT: requisicoes simultaneas via Promise.all com mesmo EventId resultam em exatamente 1 insercao e 1 replay sem erro 500", async ({
    request,
  }) => {
    const eventId = `test-mov-concurrent-${Date.now()}-4`;
    const payload = {
      tipo: "Reforco",
      valor: "150,00",
      motivo: "Teste de concorrencia real Promise.all",
      eventId,
      payloadHash: "hash-concurrent-mov-04",
    };

    // Dispara requisicoes rigorosamente simultaneas
    const [resA, resB] = await Promise.all([
      request.post(`${API_URL}/Caixa/movimento`, {
        data: payload,
        headers: { "Content-Type": "application/json" },
      }),
      request.post(`${API_URL}/Caixa/movimento`, {
        data: payload,
        headers: { "Content-Type": "application/json" },
      }),
    ]);

    const statusA = resA.status();
    const statusB = resB.status();

    // Nenhuma das requisicoes pode falhar com erro 500 ou violacao de chave primaria nao tratada
    expect([200, 201]).toContain(statusA);
    expect([200, 201]).toContain(statusB);

    const bodyA = await resA.json();
    const bodyB = await resB.json();

    expect(bodyA.success).toBe(true);
    expect(bodyB.success).toBe(true);

    const replays = [bodyA.data?.isReplay, bodyB.data?.isReplay].filter(Boolean);
    // Exatamente uma requisicao deve ser a criadora e a outra tratada como replay
    expect(replays.length).toBe(1);
  });
});
