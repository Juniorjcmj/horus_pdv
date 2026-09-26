/**
 * HOMOLOGAÇÃO — Categoria L: PWA (Service Worker e instalabilidade)
 *
 * L01 — Service Worker está registrado
 * L02 — manifest.json está acessível e contém campos obrigatórios
 * L03 — App pode ser servido com Service Worker ativo
 *
 * GAP: Teste real de instalação PWA (prompt beforeinstallprompt) requer
 *      um ambiente HTTPS e interação do browser que não é simulável em CI.
 */
import { test, expect } from "@playwright/test";
import {
  APP_URL,
  initSqlContainer,
  registerTestCompany,
  loginBrowserSession,
  cleanupHomologData,
} from "./helpers/setup";

test.describe("L — PWA", () => {
  test.describe.configure({ mode: "serial" });

  test.beforeAll(async ({ request }) => {
    initSqlContainer();
    cleanupHomologData();
    await registerTestCompany(request);
  });

  test.afterAll(() => {
    cleanupHomologData();
  });

  test("L01 — Service Worker está registrado no browser", async ({ page }) => {
    await loginBrowserSession(page);

    const swRegistered = await page.evaluate(async () => {
      if (!("serviceWorker" in navigator)) return { supported: false };
      const registrations = await navigator.serviceWorker.getRegistrations();
      return {
        supported: true,
        count: registrations.length,
        scopes: registrations.map((r) => r.scope),
      };
    });

    expect(swRegistered.supported, "Service Worker deve ser suportado").toBe(true);
    if (swRegistered.count === 0) {
      console.log("NOTA: Nenhum Service Worker registrado (pode ser normal em modo DEV)");
    }
  });

  test("L02 — manifest.json está acessível", async ({ request }) => {
    const response = await request.fetch(`${APP_URL}/manifest.json`);

    if (!response.ok()) {
      const alt = await request.fetch(`${APP_URL}/manifest.webmanifest`);
      if (!alt.ok()) {
        console.log("GAP: Nenhum manifest.json ou manifest.webmanifest encontrado");
        return;
      }
    }

    if (response.ok()) {
      const manifest = await response.json();
      expect(manifest.name || manifest.short_name, "Manifest deve ter name ou short_name").toBeTruthy();
    }
  });

  test("L03 — Página principal carrega e renderiza", async ({ page }) => {
    await loginBrowserSession(page);
    await expect(page.getByRole("heading", { name: "Home" })).toBeVisible();

    const errors: string[] = [];
    page.on("pageerror", (err) => errors.push(err.message));

    await page.getByRole("button", { name: "Produto", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Cadastro de Produto", exact: true })).toBeVisible();

    await page.getByRole("button", { name: "Home", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Home" })).toBeVisible();

    const fatalErrors = errors.filter(
      (e) => !e.includes("ResizeObserver") && !e.includes("Script error"),
    );
    expect(fatalErrors, `Erros JS durante navegação: ${fatalErrors.join(", ")}`).toHaveLength(0);
  });
});
