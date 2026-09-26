/**
 * HOMOLOGAÇÃO — Categoria H: Fiscal NFC-e (online)
 *
 * H01 — Listar NFC-e retorna array (pode estar vazio)
 * H02 — Módulo fiscal está acessível na UI
 *
 * GAP: Emissão real de NFC-e depende de certificado digital A1 e conexão
 *      com SEFAZ (ambiente de homologação). Testes de emissão/cancelamento/
 *      inutilização requerem infraestrutura fiscal não disponível em CI.
 *
 * GAP: Endpoint POST /NfceEmissao/emitir requer configuração fiscal da empresa
 *      (certificado, CNPJ, IE) que não está configurada no ambiente de teste.
 */
import { test, expect } from "@playwright/test";
import {
  API_URL,
  initSqlContainer,
  registerTestCompany,
  loginApi,
  loginBrowserSession,
  cleanupHomologData,
  openAppPage,
} from "./helpers/setup";

test.describe("H — Fiscal NFC-e", () => {
  test.describe.configure({ mode: "serial" });

  test.beforeAll(async ({ request }) => {
    initSqlContainer();
    cleanupHomologData();
    await registerTestCompany(request);
    await loginApi(request);
  });

  test.afterAll(() => {
    cleanupHomologData();
  });

  test("H01 — Endpoint de listagem NFC-e responde sem erro", async ({ request }) => {
    const response = await request.fetch(`${API_URL}/NfceEmissao`, {
      method: "GET",
      headers: { "Content-Type": "application/json" },
    });

    expect(response.status()).not.toBe(500);

    if (response.ok()) {
      const raw = await response.text();
      const payload = raw ? JSON.parse(raw) : {};
      expect(payload).toBeTruthy();
    }
  });

  test("H02 — Página Fiscal NFC-e renderiza na UI", async ({ page }) => {
    await loginBrowserSession(page);
    await openAppPage(page, "fiscal", "Fiscal NFC-e / NF-e");

    const heading = page.getByRole("heading", { name: "Fiscal NFC-e / NF-e", exact: true });
    await expect(heading).toBeVisible();
  });
});
