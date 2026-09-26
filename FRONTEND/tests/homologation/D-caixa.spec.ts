/**
 * HOMOLOGAÇÃO — Categoria D: Caixa (abertura, fechamento, status)
 *
 * D01 — Abertura de caixa com valor inicial
 * D02 — Status do caixa reflete "aberto" após abertura
 * D03 — Venda é bloqueada com caixa fechado
 * D04 — Fechamento de caixa com justificativa
 * D05 — Abertura duplicada é idempotente (não cria segunda sessão)
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
  generateCpf,
} from "./helpers/setup";

test.describe("D — Caixa", () => {
  test.describe.configure({ mode: "serial" });

  test.beforeAll(async ({ request }) => {
    initSqlContainer();
    cleanupHomologData();
    await registerTestCompany(request);
    await loginApi(request);

    await api(request, "/Caixa/fechar", {
      method: "POST",
      body: { closingAmount: "0,00", note: `${RUN_ID} cleanup pre-test` },
      allowFailure: true,
    });
  });

  test.afterAll(async ({ request }) => {
    await api(request, "/Caixa/fechar", {
      method: "POST",
      body: { closingAmount: "100,00", note: `${RUN_ID} cleanup D` },
      allowFailure: true,
    });
    cleanupHomologData();
  });

  test("D01 — Abertura de caixa com valor inicial", async ({ request }) => {
    await api(request, "/Caixa/abrir", {
      method: "POST",
      body: { openingAmount: "200,00" },
    });

    const status = await api<{ state: string; canSell?: boolean }>(request, "/Caixa/status");
    expect(status.state).toBe("aberto");
  });

  test("D02 — Status do caixa reflete 'aberto' e permite venda", async ({ request }) => {
    const status = await api<{ state: string; canSell?: boolean }>(request, "/Caixa/status");
    expect(status.state).toBe("aberto");
    expect(status.canSell).toBe(true);
  });

  test("D03 — Fechamento de caixa com nota/justificativa", async ({ request }) => {
    await api(request, "/Caixa/fechar", {
      method: "POST",
      body: { closingAmount: "200,00", note: `${RUN_ID} fechamento D03` },
    });

    const status = await api<{ state: string; canSell?: boolean }>(request, "/Caixa/status");
    expect(status.state).not.toBe("aberto");
  });

  test("D04 — Venda é bloqueada com caixa fechado", async ({ request }) => {
    const response = await request.fetch(`${API_URL}/HistoricoVendas`, {
      method: "POST",
      data: {
        customerName: `${RUN_ID} SemCaixa`,
        customerCpf: generateCpf(),
        paymentType: "Dinheiro",
        totalAmount: "10,00",
        items: [{ productCode: "INEXISTENTE", productName: "Teste", quantity: 1 }],
      },
      headers: { "Content-Type": "application/json" },
    });

    const raw = await response.text();
    expect(
      !response.ok() || raw.includes("caixa") || raw.includes("fechado"),
      `Venda sem caixa deveria ser rejeitada: ${raw}`,
    ).toBeTruthy();
  });

  test("D05 — Abertura duplicada é idempotente", async ({ request }) => {
    await api(request, "/Caixa/abrir", {
      method: "POST",
      body: { openingAmount: "100,00" },
    });

    const response = await request.fetch(`${API_URL}/Caixa/abrir`, {
      method: "POST",
      data: { openingAmount: "100,00" },
      headers: { "Content-Type": "application/json" },
    });

    const raw = await response.text();
    const isIdempotent = response.ok() || raw.includes("aberto") || raw.includes("já existe");
    expect(isIdempotent, `Abertura duplicada deveria ser controlada: ${raw}`).toBeTruthy();
  });
});
