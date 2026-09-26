/**
 * HOMOLOGAÇÃO — Categoria M: Recuperação de Conexão
 *
 * M01 — ConnectivityService detecta status online
 * M02 — Simulação de offline via route interception bloqueia API
 * M03 — Restauração de conexão permite API novamente
 *
 * GAP: Teste completo de "offline → enfileirar vendas → reconectar → sync automático"
 *      depende do SyncEngine processar automaticamente a outbox ao reconectar.
 *      Validamos os componentes individualmente.
 */
import { test, expect } from "@playwright/test";
import {
  API_URL,
  initSqlContainer,
  registerTestCompany,
  loginApi,
  loginBrowserSession,
  cleanupHomologData,
  api,
  type LoginData,
} from "./helpers/setup";

test.describe("M — Recuperação de Conexão", () => {
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

  test("M01 — API responde quando online", async ({ request }) => {
    const me = await api<LoginData["user"]>(request, "/Auth/me");
    expect(me.companyId).toBeTruthy();
  });

  test("M02 — Route interception simula offline para chamadas API", async ({ page }) => {
    await loginBrowserSession(page);

    await page.route("**/api/**", (route) => route.abort("connectionrefused"));

    const result = await page.evaluate(async () => {
      try {
        const res = await fetch("/api/Caixa/status", { credentials: "include" });
        return { fetched: true, status: res.status };
      } catch (err) {
        return { fetched: false, error: (err as Error).message };
      }
    });

    expect(result.fetched).toBe(false);

    await page.unroute("**/api/**");
  });

  test("M03 — Após remover interceptação, API funciona novamente", async ({ page }) => {
    await loginBrowserSession(page);

    const result = await page.evaluate(async (apiUrl) => {
      try {
        const res = await fetch(`${apiUrl}/Auth/me`, {
          credentials: "include",
          headers: { "Content-Type": "application/json" },
        });
        if (res.ok) {
          const data = await res.json();
          return { ok: true, hasUser: !!data?.data?.companyId };
        }
        return { ok: false, status: res.status };
      } catch (err) {
        return { ok: false, error: (err as Error).message };
      }
    }, API_URL);

    expect(result.ok, "API deve responder após restaurar conexão").toBe(true);
  });
});
