/**
 * HOMOLOGAÇÃO — Categoria A: Login e Autenticação
 *
 * A01 — Login online com credenciais válidas
 * A02 — Login com senha incorreta exibe mensagem de erro
 * A03 — Sessão persistida via cookie HttpOnly (re-login sem digitar senha)
 */
import { test, expect } from "@playwright/test";
import {
  APP_URL,
  API_URL,
  RUN_ID,
  initSqlContainer,
  registerTestCompany,
  loginBrowserSession,
  getCredentials,
  cleanupHomologData,
  api,
  type LoginData,
} from "./helpers/setup";

test.describe("A — Login e Autenticação", () => {
  test.describe.configure({ mode: "serial" });

  test.beforeAll(async ({ request }) => {
    initSqlContainer();
    cleanupHomologData();
    await registerTestCompany(request);
  });

  test.afterAll(() => {
    cleanupHomologData();
  });

  test("A01 — Login online com credenciais válidas", async ({ page }) => {
    const loginData = await loginBrowserSession(page);
    expect(loginData.user.companyId).toBeTruthy();
    expect(loginData.user.email).toContain(RUN_ID.toLowerCase());
    await expect(page.getByRole("heading", { name: "Home" })).toBeVisible();
  });

  test("A02 — Login com senha incorreta exibe mensagem de erro", async ({ page }) => {
    await page.goto(APP_URL);
    await expect(page.getByRole("heading", { name: /bem-vindo de volta/i })).toBeVisible({
      timeout: 15_000,
    });

    await page.getByLabel(/e-mail/i).fill(getCredentials().email);
    await page.getByLabel(/senha/i).fill("SenhaErrada@123Xx");
    await page.getByRole("button", { name: /entrar/i }).click();

    await expect(
      page.getByText(/credenciais inválidas|e-mail ou senha|incorret/i),
    ).toBeVisible({ timeout: 10_000 });
  });

  test("A03 — Sessão persistida — cookie HttpOnly permite re-autenticação", async ({ request }) => {
    const creds = getCredentials();
    const loginRes = await request.post(`${API_URL}/Auth/login`, {
      data: {
        email: creds.email,
        password: creds.password,
        rememberMe: true,
        recaptchaToken: "homolog-test",
      },
      headers: { "Content-Type": "application/json" },
    });
    expect(loginRes.ok()).toBeTruthy();

    const me = await api<LoginData["user"]>(request, "/Auth/me");
    expect(me.companyId).toBeTruthy();
    expect(me.email).toBe(creds.email);
  });
});
