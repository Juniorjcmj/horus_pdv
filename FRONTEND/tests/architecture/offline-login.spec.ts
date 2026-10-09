import { test, expect, type Page } from "@playwright/test";
import type { AuthenticatedUser } from "../../src/utils/authStorage";

const PASSWORD = "SenhaLocal#2026";
const GATEWAY = "http://127.0.0.1:5080";
const user = (id = "op-1"): AuthenticatedUser => ({
  id, companyId: "loja-offline", name: `Operador ${id}`, email: `${id}@teste.invalid`,
  role: "caixa", status: "ativo", createdAt: new Date().toISOString(),
  lastLoginAt: new Date().toISOString(), mustChangePassword: false,
});

async function seedUser(page: Page, operator = user()) {
  await page.evaluate(async ({ operator, password }) => {
    const { userRepository } = await import("/src/infrastructure/database/repositories/UserRepository.ts" as string);
    await userRepository.saveUserForOfflineAuth(operator, password);
  }, { operator, password: PASSWORD });
}

async function login(page: Page, email = user().email, password = PASSWORD) {
  return page.evaluate(async (payload) => {
    const { authService } = await import("/src/services/api/authService.ts" as string);
    try {
      return { result: await authService.login(payload), error: "", status: null };
    } catch (error) {
      return {
        result: null, error: error instanceof Error ? error.message : String(error),
        status: (error as { status?: number }).status ?? null,
      };
    }
  }, { email, password, rememberMe: true });
}

async function enterFromScreen(page: Page, operator = user()) {
  await page.getByRole("textbox", { name: "E-mail", exact: true }).fill(operator.email);
  await page.getByLabel("Senha", { exact: true }).fill(PASSWORD);
  await page.getByRole("button", { name: "Entrar", exact: true }).click();
}

const storedUserId = (page: Page) => page.evaluate(() =>
  JSON.parse(localStorage.getItem("horuspdv.auth.user") || "null")?.id ?? null);

test.describe("Login offline no aplicativo com Gateway ativo", () => {
  test.beforeEach(async ({ page, baseURL }) => {
    // Contexto novo e dados fictícios; nenhuma chamada chega à API da loja ou ao Google.
    await page.route("**/*", (route) => {
      if (new URL(route.request().url()).origin === new URL(baseURL!).origin) return route.continue();
      return route.fulfill({ status: 503, json: { success: false, message: "API indisponível" } });
    });
    await page.route(`${GATEWAY}/**`, (route) => {
      if (route.request().url().endsWith("/status")) {
        return route.fulfill({ json: {
          service: "HorusGateway", bound: true, companyId: "loja-offline", storeId: "loja-01",
          gatewayId: "gateway-teste", terminalAuthRequired: true,
        } });
      }
      return route.fulfill({ json: { status: "accepted" } });
    });
    await page.addInitScript(({ gateway }) => {
      window.grecaptcha = { ready: (callback) => callback(), execute: async () => "token-teste" };
      window.quackDesktop = {
        savePendingBackup: async () => "",
        gateway: {
          status: async () => ({ bundled: true, configured: true, running: true, managed: true,
            external: false, url: gateway, companyId: "loja-offline", storeId: "loja-01",
            gatewayId: "gateway-teste", tokenId: "token-loja-teste", lastError: "" }),
        },
      } as typeof window.quackDesktop;
      localStorage.setItem("horus-gateway-config", JSON.stringify({ enabled: true, url: gateway,
        companyId: "loja-offline", storeId: "loja-01", terminalId: "CAIXA-TESTE", terminalType: "CASH", apiKey: "chave-teste" }));
    }, { gateway: GATEWAY });
    await page.goto("/?login=1");
    await expect(page.getByRole("button", { name: "Entrar", exact: true })).toBeVisible();
    // Sincronização é testada pela suíte do Gateway. Aqui o envio é explícito para inspecionar
    // os eventos da troca antes que um ciclo em segundo plano os processe.
    await page.evaluate(async () => {
      const { syncEngine } = await import("/src/infrastructure/synchronization/SyncEngine.ts" as string);
      syncEngine.start = () => () => {};
    });
    await seedUser(page);
  });

  test("sem rede detectada permite login local", async ({ page }) => {
    await page.evaluate(() => Object.defineProperty(navigator, "onLine", { configurable: true, value: false }));
    expect((await login(page)).result?.user.id).toBe(user().id);
  });

  test("falha do fetch permite login local com Wi-Fi conectado", async ({ page }) => {
    await page.route("**/api/Auth/login", (route) => route.abort("connectionfailed"));
    expect((await login(page)).result?.user.id).toBe(user().id);
  });

  test("demora da API permite login local após o timeout", async ({ page }) => {
    await page.route("**/api/Auth/login", () => {});
    expect((await login(page)).result?.user.id).toBe(user().id);
  });

  for (const status of [200, 401]) {
    test(`conexão interrompida ao ler a resposta HTTP ${status}`, async ({ page }) => {
      const attempt = await page.evaluate(async ({ status, email, password }) => {
        const { authService } = await import("/src/services/api/authService.ts" as string);
        const originalFetch = window.fetch;
        window.fetch = async (input, init) => {
          if (!String(input).endsWith("/Auth/login")) return originalFetch(input, init);
          const response = new Response("", { status, headers: { "Content-Type": "application/json" } });
          response.json = () => new Promise((_, reject) => {
            init?.signal?.addEventListener("abort", () => reject(new DOMException("Resposta interrompida", "AbortError")), { once: true });
          });
          return response;
        };
        try {
          const result = await authService.login({ email, password, rememberMe: true });
          return { id: result?.user.id, status: null };
        } catch (error) {
          return { id: null, status: (error as { status?: number }).status };
        } finally { window.fetch = originalFetch; }
      }, { status, email: user().email, password: PASSWORD });
      if (status === 200) expect(attempt.id).toBe(user().id);
      else expect(attempt).toMatchObject({ id: null, status: 401 });
    });
  }

  for (const status of [408, 500, 502, 503, 504]) {
    test(`HTTP ${status} permite login local`, async ({ page }) => {
      await page.route("**/api/Auth/login", (route) => route.fulfill({ status, contentType: "text/html", body: "Serviço indisponível" }));
      expect((await login(page)).result?.user.id).toBe(user().id);
    });
  }

  test("503 com JSON inválido preserva a possibilidade de login local", async ({ page }) => {
    await page.route("**/api/Auth/login", (route) => route.fulfill({ status: 503, contentType: "application/json", body: "{" }));
    expect((await login(page)).result?.user.id).toBe(user().id);
  });

  for (const status of [400, 401, 403, 429]) {
    test(`HTTP ${status} continua recusado mesmo com credenciais locais válidas`, async ({ page }) => {
      await page.route("**/api/Auth/login", async (route) => {
        // A rede pode cair depois de o servidor recusar: isso não deve liberar acesso local.
        await page.evaluate(() => Object.defineProperty(navigator, "onLine", { configurable: true, value: false }));
        await route.fulfill({ status, json: { success: false, message: "Acesso recusado" } });
      });
      const attempt = await login(page);
      expect(attempt.result).toBeNull();
      expect(attempt.status).toBe(status);
      expect(attempt.error).toBe("Acesso recusado");
    });
  }

  test("senha errada e usuário sem preparo offline continuam bloqueados", async ({ page }) => {
    expect((await login(page, user().email, "SenhaErrada")).error).toBe("Senha incorreta.");
    expect((await login(page, "ausente@teste.invalid")).error).toContain("Usuário não cadastrado para acesso offline");
  });

  test("a troca de operador não renova os sete dias sem validação online", async ({ page }) => {
    await page.evaluate(async () => {
      const { db } = await import("/src/infrastructure/database/dexie.ts" as string);
      await db.users.update("op-1", { lastOnlineLoginAt: new Date(Date.now() - 8 * 86400_000).toISOString() });
    });
    expect((await login(page)).error).toContain("Período máximo de operação offline excedido");
  });

  test("login online aguarda o preparo das credenciais locais", async ({ page }) => {
    const operator = user("novo-operador");
    await page.route("**/api/Auth/login", (route) => route.fulfill({ json: { success: true,
      data: { tokenType: "Bearer", expiresInSeconds: 86400, sessionId: "sess-online", user: operator } } }));
    const state = await page.evaluate(async ({ operator, password }) => {
      const { authService } = await import("/src/services/api/authService.ts" as string);
      const { userRepository } = await import("/src/infrastructure/database/repositories/UserRepository.ts" as string);
      const save = userRepository.saveUserForOfflineAuth.bind(userRepository);
      let release!: () => void;
      const gate = new Promise<void>((resolve) => { release = resolve; });
      userRepository.saveUserForOfflineAuth = async (...args: Parameters<typeof save>) => { await gate; await save(...args); };
      let settled = false;
      try {
        const pending = authService.login({ email: operator.email, password, rememberMe: true }).then((result: unknown) => { settled = true; return result; });
        await new Promise((resolve) => setTimeout(resolve, 100));
        const returnedBeforeSave = settled;
        release();
        const result = await pending;
        const cached = await userRepository.getByEmail(operator.email);
        return { returnedBeforeSave, result, cached: Boolean(cached?.passwordHash) };
      } finally { release(); userRepository.saveUserForOfflineAuth = save; }
    }, { operator, password: PASSWORD });
    expect(state.returnedBeforeSave).toBe(false);
    expect(state.cached).toBe(true);
    expect(state.result).toMatchObject({ offlineAccessReady: true });
  });

  test("falha do armazenamento avisa sem invalidar o login online", async ({ page }) => {
    await page.route("**/api/Auth/login", (route) => route.fulfill({ json: { success: true,
      data: { tokenType: "Bearer", expiresInSeconds: 86400, sessionId: "sess-online", user: user() } } }));
    await page.evaluate(async () => {
      const { userRepository } = await import("/src/infrastructure/database/repositories/UserRepository.ts" as string);
      userRepository.saveUserForOfflineAuth = async () => { throw new DOMException("Disco cheio", "QuotaExceededError"); };
    });
    await enterFromScreen(page);
    await expect.poll(() => storedUserId(page)).toBe(user().id);
    await expect(page.getByText("Você entrou, mas não foi possível preparar seu acesso sem internet", { exact: false })).toBeVisible();
  });

  test("Google indisponível não bloqueia a entrada offline pela tela", async ({ page }) => {
    await page.evaluate(() => { delete window.grecaptcha; });
    await page.route("https://www.google.com/recaptcha/**", (route) => route.abort("connectionfailed"));
    await enterFromScreen(page);
    await expect.poll(() => storedUserId(page)).toBe(user().id);
  });

  test("reCAPTCHA pendente tem prazo e permite a tentativa offline", async ({ page }) => {
    await page.evaluate(() => { window.grecaptcha = { ready: () => {}, execute: async () => "" }; });
    await enterFromScreen(page);
    await expect.poll(() => storedUserId(page), { timeout: 15_000 }).toBe(user().id);
  });

  test("sem rede detectada a tela pula o reCAPTCHA", async ({ page }) => {
    await page.evaluate(() => {
      Object.defineProperty(navigator, "onLine", { configurable: true, value: false });
      window.grecaptcha = { ready: () => { throw new Error("Não deve chamar o Google offline"); }, execute: async () => "" };
    });
    await enterFromScreen(page);
    await expect.poll(() => storedUserId(page), { timeout: 2_000 }).toBe(user().id);
  });

  test("falha do Google mantém a recusa do servidor quando ele responde", async ({ page }) => {
    await page.evaluate(() => { window.grecaptcha = { ready: (callback) => callback(), execute: async () => { throw new Error("Google indisponível"); } }; });
    await page.route("**/api/Auth/login", (route) => route.fulfill({ status: 400, json: { success: false, message: "Validação de segurança obrigatória" } }));
    await enterFromScreen(page);
    await expect(page.getByText("Validação de segurança obrigatória", { exact: true })).toBeVisible();
    expect(await storedUserId(page)).toBeNull();
  });

  test("após falha o script do Google pode ser carregado novamente", async ({ page }) => {
    await page.evaluate(() => { delete window.grecaptcha; });
    let scriptRequests = 0;
    await page.route("https://www.google.com/recaptcha/**", (route) => {
      if (++scriptRequests === 1) return route.abort("connectionfailed");
      return route.fulfill({ contentType: "application/javascript", body: 'window.grecaptcha={ready:callback=>callback(),execute:async()=>"token-recuperado"};' });
    });
    await page.route("**/api/Auth/login", (route) => {
      if (route.request().postDataJSON().recaptchaToken !== "token-recuperado") {
        return route.fulfill({ status: 400, json: { success: false, message: "Validação de segurança obrigatória" } });
      }
      return route.fulfill({ json: { success: true, data: { tokenType: "Bearer", expiresInSeconds: 86400, sessionId: "sess-online", user: user() } } });
    });
    await enterFromScreen(page);
    await expect(page.getByText("Validação de segurança obrigatória", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "Entrar", exact: true }).click();
    await expect.poll(() => storedUserId(page)).toBe(user().id);
    expect(scriptRequests).toBe(2);
  });

  test("fechar caixa, sair e entrar com outro operador preserva os eventos para o Gateway", async ({ page }) => {
    await seedUser(page, user("op-2"));
    const delivered: Array<{ eventType: string; payload: { operatorId: string } }> = [];
    await page.route(`${GATEWAY}/api/gateway/events`, (route) => {
      delivered.push(route.request().postDataJSON());
      return route.fulfill({ json: { status: "accepted" } });
    });
    await enterFromScreen(page);
    await expect.poll(() => storedUserId(page)).toBe("op-1");
    await page.evaluate(async () => {
      const { openCashLocal, closeCashLocal } = await import("/src/infrastructure/database/repositories/CashSessionRepository.ts" as string);
      await openCashLocal("100,00", "op-1", "Operador op-1", "abre-primeiro");
      await closeCashLocal("100,00", "Troca de operador", undefined, "op-1", "Operador op-1", "fecha-primeiro");
    });
    await page.getByRole("button", { name: /Operador op-1/ }).click();
    await page.getByRole("button", { name: "Sair", exact: true }).click();
    await expect(page.getByRole("button", { name: "Entrar", exact: true })).toBeVisible();
    await enterFromScreen(page, user("op-2"));
    await expect.poll(() => storedUserId(page)).toBe("op-2");
    const status = await page.evaluate(async () => {
      const { openCashLocal } = await import("/src/infrastructure/database/repositories/CashSessionRepository.ts" as string);
      const { forwardPendingToGateway } = await import("/src/infrastructure/gateway/outboxGatewayForwarder.ts" as string);
      const status = await openCashLocal("50,00", "op-2", "Operador op-2", "abre-segundo");
      return { status, sent: await forwardPendingToGateway() };
    });
    expect(status.status.canSell).toBe(true);
    expect(status.status.currentSession.operatorId).toBe("op-2");
    expect(status.sent.forwarded).toBe(3);
    expect(delivered.map((event) => [event.eventType, event.payload.operatorId])).toEqual([
      ["CASH_OPEN", "op-1"], ["CASH_CLOSE", "op-1"], ["CASH_OPEN", "op-2"],
    ]);
  });
});
