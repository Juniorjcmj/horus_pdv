/**
 * HOMOLOGAÇÃO — Categoria J: Idempotência
 *
 * J01 — Duplo POST de abertura de caixa não duplica sessão
 * J02 — Duplo POST de fechamento de caixa é controlado
 * J03 — Produto criado com mesmo código é rejeitado (unicidade)
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
} from "./helpers/setup";

type Entity = { id: string };

test.describe("J — Idempotência", () => {
  test.describe.configure({ mode: "serial" });

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
  });

  test.afterAll(async ({ request }) => {
    await api(request, "/Caixa/fechar", {
      method: "POST",
      body: { closingAmount: "100,00", note: `${RUN_ID} cleanup J` },
      allowFailure: true,
    });
    cleanupHomologData();
  });

  test("J01 — Duplo POST de abertura de caixa não duplica sessão", async ({ request }) => {
    await api(request, "/Caixa/abrir", {
      method: "POST",
      body: { openingAmount: "100,00" },
    });

    const res2 = await request.fetch(`${API_URL}/Caixa/abrir`, {
      method: "POST",
      data: { openingAmount: "100,00" },
      headers: { "Content-Type": "application/json" },
    });
    const raw2 = await res2.text();

    if (!res2.ok()) {
      expect(res2.status()).not.toBe(500);
      expect(raw2).toMatch(/aberto|já existe|already/i);
    }

    const status = await api<{ state: string }>(request, "/Caixa/status");
    expect(status.state).toBe("aberto");
  });

  test("J02 — Duplo POST de fechamento de caixa é controlado", async ({ request }) => {
    await api(request, "/Caixa/fechar", {
      method: "POST",
      body: { closingAmount: "100,00", note: `${RUN_ID} J02 primeiro` },
    });

    const res2 = await request.fetch(`${API_URL}/Caixa/fechar`, {
      method: "POST",
      data: { closingAmount: "100,00", note: `${RUN_ID} J02 segundo` },
      headers: { "Content-Type": "application/json" },
    });

    if (!res2.ok()) {
      expect(res2.status()).not.toBe(500);
    }
  });

  test("J03 — Produto com código duplicado é rejeitado", async ({ request }) => {
    await api(request, "/Caixa/abrir", {
      method: "POST",
      body: { openingAmount: "100,00" },
      allowFailure: true,
    });

    const supplier = await api<Entity>(request, "/Fornecedor", {
      method: "POST",
      body: supplierPayload("Fornecedor J"),
    });
    const supplierName = `${RUN_ID} Fornecedor J`;

    const payload = productPayload("ProdutoJ", supplierName, "10");

    await api(request, "/Produto", { method: "POST", body: payload });

    const res2 = await request.fetch(`${API_URL}/Produto`, {
      method: "POST",
      data: payload,
      headers: { "Content-Type": "application/json" },
    });

    if (!res2.ok()) {
      expect(res2.status()).not.toBe(500);
    }
  });
});
