import { test, expect, type Page } from "@playwright/test";

/** Controla a resposta do health check (GET .../me): "ok" → 401 (API acessível), "fail" → erro de rede. */
async function routeHealth(page: Page, mode: { value: "ok" | "fail" }) {
  await page.route("**/me", (route) => (mode.value === "ok" ? route.fulfill({ status: 401, body: "" }) : route.abort()));
}

async function loadService(page: Page) {
  await page.goto("/");
  await page.waitForFunction(() => (window as any).__horus_test__ !== undefined);
  await page.evaluate(async () => {
    const mod = await import("/src/infrastructure/synchronization/ConnectivityService.ts" as string);
    (window as any).__conn = mod.connectivityService;
    (window as any).__API_RESPONDED_EVENT = mod.API_RESPONDED_EVENT;
  });
}

const check = (page: Page) =>
  page.evaluate(async () => {
    const svc = (window as any).__conn;
    await svc._checkHealth();
    return svc.status as string;
  });

test.describe("Conectividade: sem falso 'API indisponível'", () => {
  test("uma falha isolada não marca indisponível; duas seguidas marcam; resposta boa volta a online", async ({ page }) => {
    const mode = { value: "ok" as "ok" | "fail" };
    await routeHealth(page, mode);
    await loadService(page);

    expect(await check(page)).toBe("ONLINE");

    mode.value = "fail";
    expect(await check(page)).toBe("ONLINE"); // 1ª falha: tolera
    expect(await check(page)).toBe("API_UNAVAILABLE"); // 2ª seguida: indisponível

    mode.value = "ok";
    expect(await check(page)).toBe("ONLINE"); // primeira resposta boa recupera
  });

  test("resposta recente de outra chamada da API faz ignorar falha do health check", async ({ page }) => {
    const mode = { value: "ok" as "ok" | "fail" };
    await routeHealth(page, mode);
    await loadService(page);
    expect(await check(page)).toBe("ONLINE");

    // Ex.: uma venda acabou de receber resposta da API
    await page.evaluate(() => {
      const svc = (window as any).__conn;
      window.addEventListener((window as any).__API_RESPONDED_EVENT, svc._handleApiResponded);
      window.dispatchEvent(new CustomEvent((window as any).__API_RESPONDED_EVENT));
    });

    mode.value = "fail";
    expect(await check(page)).toBe("ONLINE");
    expect(await check(page)).toBe("ONLINE");
  });
});
