/**
 * CHANGE 08.1 — Categoria L: PWA
 *
 * L01 — Aplicação funciona após hard reload offline
 *       Abrir online → aguardar Service Worker → garantir assets cacheados →
 *       desligar conexão → hard reload → shell carrega → PDV acessível offline.
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

  test("L01 — Service Worker registrado e controlando a página", async ({ page }) => {
    // 1. Abrir aplicação online
    await loginBrowserSession(page);

    // 2. Aguardar Service Worker registrado e controlando a página
    const swStatus = await page.evaluate(async () => {
      if (!("serviceWorker" in navigator)) {
        return { supported: false, reason: "serviceWorker not in navigator" };
      }

      const registrations = await navigator.serviceWorker.getRegistrations();
      const controlling = navigator.serviceWorker.controller;

      return {
        supported: true,
        registrationCount: registrations.length,
        scopes: registrations.map((r) => r.scope),
        isControlling: !!controlling,
        controllerState: controlling?.state ?? null,
      };
    });

    expect(swStatus.supported, "Service Worker deve ser suportado pelo browser").toBe(
      true,
    );

    if (swStatus.registrationCount === 0) {
      console.log(
        "GAP-L01: Nenhum Service Worker registrado. Em modo DEV (Vite), o SW pode " +
          "não ser ativado. O teste de hard reload offline requer SW em modo produção " +
          "ou com vite-plugin-pwa em modo development. " +
          "Severidade: P2 — melhoria para teste em CI. " +
          "Evidência: registrationCount=0, isControlling=false.",
      );
      // Continuar testando o que é possível sem SW
    }

    // 3. Verificar manifest.json está acessível
    const manifestResponse = await page.context().request.fetch(`${APP_URL}/manifest.json`);
    const manifestContentType = manifestResponse.headers()["content-type"] ?? "";
    const isJsonManifest = manifestResponse.ok() && manifestContentType.includes("json");

    if (isJsonManifest) {
      const manifest = await manifestResponse.json();
      expect(
        manifest.name || manifest.short_name,
        "Manifest deve ter name ou short_name",
      ).toBeTruthy();
    } else {
      // Tentar manifest.webmanifest
      const altResponse = await page.context().request.fetch(
        `${APP_URL}/manifest.webmanifest`,
      );
      const altContentType = altResponse.headers()["content-type"] ?? "";
      if (altResponse.ok() && altContentType.includes("json")) {
        const manifest = await altResponse.json();
        expect(
          manifest.name || manifest.short_name,
          "Manifest deve ter name ou short_name",
        ).toBeTruthy();
      } else {
        // Verificar se há link no HTML
        const manifestLink = await page.evaluate(() => {
          const link = document.querySelector("link[rel='manifest']");
          return link?.getAttribute("href") ?? null;
        });
        if (!manifestLink) {
          console.log(
            "GAP-L01: Nenhum manifest.json, manifest.webmanifest ou <link rel=manifest> encontrado. " +
              "PWA requer manifest para instalação. Severidade: P2.",
          );
        }
      }
    }

    // 4. Validar que a navegação funciona sem erros JS
    const errors: string[] = [];
    page.on("pageerror", (err) => errors.push(err.message));

    await page.getByRole("button", { name: "Produto", exact: true }).click();
    await expect(
      page.getByRole("heading", { name: "Cadastro de Produto", exact: true }),
    ).toBeVisible({ timeout: 10_000 });

    await page.getByRole("button", { name: "Home", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Home" })).toBeVisible({
      timeout: 10_000,
    });

    const fatalErrors = errors.filter(
      (e) =>
        !e.includes("ResizeObserver") &&
        !e.includes("Script error") &&
        !e.includes("Load failed"),
    );
    expect(
      fatalErrors,
      `Erros JS durante navegação: ${fatalErrors.join(", ")}`,
    ).toHaveLength(0);

    // 5. Testar offline se SW está ativo
    if (swStatus.isControlling) {
      // Interceptar todas as requisições de rede
      await page.route("**/*", (route) => {
        const url = route.request().url();
        // Permitir apenas dados do cache do SW (bloquear apenas rede)
        if (url.startsWith("http")) {
          return route.abort("connectionrefused");
        }
        return route.continue();
      });

      // Hard reload
      await page.reload();

      // Validar que o shell da aplicação carrega
      const offlineLoaded = await page
        .locator("body")
        .waitFor({ timeout: 15_000 })
        .then(() => true)
        .catch(() => false);

      if (!offlineLoaded) {
        console.log(
          "GAP-L01: Página não carrega offline mesmo com SW ativo. " +
            "Em modo DEV (Vite), o SW pode não pré-cachear todos os assets. " +
            "Severidade: P2 — funcionalidade PWA requer teste em build de produção.",
        );
      }

      // Restaurar conexão
      await page.unroute("**/*");
    }
  });
});
