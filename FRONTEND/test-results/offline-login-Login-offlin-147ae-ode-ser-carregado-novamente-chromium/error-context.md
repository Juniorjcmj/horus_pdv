# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: offline-login.spec.ts >> Login offline no aplicativo com Gateway ativo >> após falha o script do Google pode ser carregado novamente
- Location: tests\architecture\offline-login.spec.ts:230:3

# Error details

```
Error: expect(received).toBe(expected) // Object.is equality

Expected: "op-1"
Received: null

Call Log:
- Timeout 10000ms exceeded while waiting on the predicate
```

# Page snapshot

```yaml
- main [ref=e3]:
  - generic [ref=e5]:
    - generic [ref=e6]:
      - generic [ref=e7]:
        - generic [ref=e8]:
          - img "Quack Sistemas" [ref=e9]
          - generic [ref=e10]:
            - generic [ref=e11]: Quack Sistemas
            - generic [ref=e12]: Soluções para seu negócio
        - heading "Gestão de vendas com fluxo rápido para operação de balcão." [level=1] [ref=e13]
      - paragraph [ref=e14]: Acesse vendas, clientes, produtos, caixa e relatórios em um único painel.
    - generic [ref=e16]:
      - heading "Bem-vindo de volta!" [level=2] [ref=e17]
      - paragraph [ref=e18]: Use seu e-mail e senha para acessar o painel.
      - generic [ref=e19]:
        - button "← Conhecer o Quack Sistemas (Apresentação do Sistema)" [ref=e21] [cursor=pointer]
        - generic [ref=e22]:
          - generic [ref=e23]: E-mail
          - generic [ref=e24]:
            - img
            - textbox "E-mail" [ref=e25]:
              - /placeholder: usuario@hpdv.com.br
              - text: op-1@teste.invalid
        - generic [ref=e26]:
          - generic [ref=e27]: Senha
          - generic [ref=e28]:
            - img
            - textbox "Senha Mostrar senha" [ref=e29]:
              - /placeholder: ••••••••
              - text: SenhaLocal#2026
            - button "Mostrar senha" [ref=e30] [cursor=pointer]:
              - img [ref=e31]
        - generic [ref=e34]:
          - generic [ref=e35]:
            - checkbox "Manter sessão conectada" [checked] [ref=e36]
            - text: Manter sessão conectada
          - button "Esqueci minha senha" [ref=e37] [cursor=pointer]
        - button "Entrar" [ref=e38] [cursor=pointer]:
          - img [ref=e39]
          - text: Entrar
        - generic [ref=e42]:
          - generic [ref=e43]: Não tem uma conta?
          - button "Criar cadastro" [ref=e44] [cursor=pointer]
        - paragraph [ref=e45]: Validação de segurança obrigatória
```

# Test source

```ts
  146 |   }
  147 | 
  148 |   test("senha errada e usuário sem preparo offline continuam bloqueados", async ({ page }) => {
  149 |     expect((await login(page, user().email, "SenhaErrada")).error).toBe("Senha incorreta.");
  150 |     expect((await login(page, "ausente@teste.invalid")).error).toContain("Usuário não cadastrado para acesso offline");
  151 |   });
  152 | 
  153 |   test("a troca de operador não renova os sete dias sem validação online", async ({ page }) => {
  154 |     await page.evaluate(async () => {
  155 |       const { db } = await import("/src/infrastructure/database/dexie.ts" as string);
  156 |       await db.users.update("op-1", { lastOnlineLoginAt: new Date(Date.now() - 8 * 86400_000).toISOString() });
  157 |     });
  158 |     expect((await login(page)).error).toContain("Período máximo de operação offline excedido");
  159 |   });
  160 | 
  161 |   test("login online aguarda o preparo das credenciais locais", async ({ page }) => {
  162 |     const operator = user("novo-operador");
  163 |     await page.route("**/api/Auth/login", (route) => route.fulfill({ json: { success: true,
  164 |       data: { tokenType: "Bearer", expiresInSeconds: 86400, sessionId: "sess-online", user: operator } } }));
  165 |     const state = await page.evaluate(async ({ operator, password }) => {
  166 |       const { authService } = await import("/src/services/api/authService.ts" as string);
  167 |       const { userRepository } = await import("/src/infrastructure/database/repositories/UserRepository.ts" as string);
  168 |       const save = userRepository.saveUserForOfflineAuth.bind(userRepository);
  169 |       let release!: () => void;
  170 |       const gate = new Promise<void>((resolve) => { release = resolve; });
  171 |       userRepository.saveUserForOfflineAuth = async (...args: Parameters<typeof save>) => { await gate; await save(...args); };
  172 |       let settled = false;
  173 |       try {
  174 |         const pending = authService.login({ email: operator.email, password, rememberMe: true }).then((result: unknown) => { settled = true; return result; });
  175 |         await new Promise((resolve) => setTimeout(resolve, 100));
  176 |         const returnedBeforeSave = settled;
  177 |         release();
  178 |         const result = await pending;
  179 |         const cached = await userRepository.getByEmail(operator.email);
  180 |         return { returnedBeforeSave, result, cached: Boolean(cached?.passwordHash) };
  181 |       } finally { release(); userRepository.saveUserForOfflineAuth = save; }
  182 |     }, { operator, password: PASSWORD });
  183 |     expect(state.returnedBeforeSave).toBe(false);
  184 |     expect(state.cached).toBe(true);
  185 |     expect(state.result).toMatchObject({ offlineAccessReady: true });
  186 |   });
  187 | 
  188 |   test("falha do armazenamento avisa sem invalidar o login online", async ({ page }) => {
  189 |     await page.route("**/api/Auth/login", (route) => route.fulfill({ json: { success: true,
  190 |       data: { tokenType: "Bearer", expiresInSeconds: 86400, sessionId: "sess-online", user: user() } } }));
  191 |     await page.evaluate(async () => {
  192 |       const { userRepository } = await import("/src/infrastructure/database/repositories/UserRepository.ts" as string);
  193 |       userRepository.saveUserForOfflineAuth = async () => { throw new DOMException("Disco cheio", "QuotaExceededError"); };
  194 |     });
  195 |     await enterFromScreen(page);
  196 |     await expect.poll(() => storedUserId(page)).toBe(user().id);
  197 |     await expect(page.getByText("Você entrou, mas não foi possível preparar seu acesso sem internet", { exact: false })).toBeVisible();
  198 |   });
  199 | 
  200 |   test("Google indisponível não bloqueia a entrada offline pela tela", async ({ page }) => {
  201 |     await page.evaluate(() => { delete window.grecaptcha; });
  202 |     await page.route("https://www.google.com/recaptcha/**", (route) => route.abort("connectionfailed"));
  203 |     await enterFromScreen(page);
  204 |     await expect.poll(() => storedUserId(page)).toBe(user().id);
  205 |   });
  206 | 
  207 |   test("reCAPTCHA pendente tem prazo e permite a tentativa offline", async ({ page }) => {
  208 |     await page.evaluate(() => { window.grecaptcha = { ready: () => {}, execute: async () => "" }; });
  209 |     await enterFromScreen(page);
  210 |     await expect.poll(() => storedUserId(page), { timeout: 15_000 }).toBe(user().id);
  211 |   });
  212 | 
  213 |   test("sem rede detectada a tela pula o reCAPTCHA", async ({ page }) => {
  214 |     await page.evaluate(() => {
  215 |       Object.defineProperty(navigator, "onLine", { configurable: true, value: false });
  216 |       window.grecaptcha = { ready: () => { throw new Error("Não deve chamar o Google offline"); }, execute: async () => "" };
  217 |     });
  218 |     await enterFromScreen(page);
  219 |     await expect.poll(() => storedUserId(page), { timeout: 2_000 }).toBe(user().id);
  220 |   });
  221 | 
  222 |   test("falha do Google mantém a recusa do servidor quando ele responde", async ({ page }) => {
  223 |     await page.evaluate(() => { window.grecaptcha = { ready: (callback) => callback(), execute: async () => { throw new Error("Google indisponível"); } }; });
  224 |     await page.route("**/api/Auth/login", (route) => route.fulfill({ status: 400, json: { success: false, message: "Validação de segurança obrigatória" } }));
  225 |     await enterFromScreen(page);
  226 |     await expect(page.getByText("Validação de segurança obrigatória", { exact: true })).toBeVisible();
  227 |     expect(await storedUserId(page)).toBeNull();
  228 |   });
  229 | 
  230 |   test("após falha o script do Google pode ser carregado novamente", async ({ page }) => {
  231 |     await page.evaluate(() => { delete window.grecaptcha; });
  232 |     let scriptRequests = 0;
  233 |     await page.route("https://www.google.com/recaptcha/**", (route) => {
  234 |       if (++scriptRequests === 1) return route.abort("connectionfailed");
  235 |       return route.fulfill({ contentType: "application/javascript", body: 'window.grecaptcha={ready:callback=>callback(),execute:async()=>"token-recuperado"};' });
  236 |     });
  237 |     await page.route("**/api/Auth/login", (route) => {
  238 |       if (route.request().postDataJSON().recaptchaToken !== "token-recuperado") {
  239 |         return route.fulfill({ status: 400, json: { success: false, message: "Validação de segurança obrigatória" } });
  240 |       }
  241 |       return route.fulfill({ json: { success: true, data: { tokenType: "Bearer", expiresInSeconds: 86400, sessionId: "sess-online", user: user() } } });
  242 |     });
  243 |     await enterFromScreen(page);
  244 |     await expect(page.getByText("Validação de segurança obrigatória", { exact: true })).toBeVisible();
  245 |     await page.getByRole("button", { name: "Entrar", exact: true }).click();
> 246 |     await expect.poll(() => storedUserId(page)).toBe(user().id);
      |     ^ Error: expect(received).toBe(expected) // Object.is equality
  247 |     expect(scriptRequests).toBe(2);
  248 |   });
  249 | 
  250 |   test("fechar caixa, sair e entrar com outro operador preserva os eventos para o Gateway", async ({ page }) => {
  251 |     await seedUser(page, user("op-2"));
  252 |     const delivered: Array<{ eventType: string; payload: { operatorId: string } }> = [];
  253 |     await page.route(`${GATEWAY}/api/gateway/events`, (route) => {
  254 |       delivered.push(route.request().postDataJSON());
  255 |       return route.fulfill({ json: { status: "accepted" } });
  256 |     });
  257 |     await enterFromScreen(page);
  258 |     await expect.poll(() => storedUserId(page)).toBe("op-1");
  259 |     await page.evaluate(async () => {
  260 |       const { openCashLocal, closeCashLocal } = await import("/src/infrastructure/database/repositories/CashSessionRepository.ts" as string);
  261 |       await openCashLocal("100,00", "op-1", "Operador op-1", "abre-primeiro");
  262 |       await closeCashLocal("100,00", "Troca de operador", undefined, "op-1", "Operador op-1", "fecha-primeiro");
  263 |     });
  264 |     await page.getByRole("button", { name: /Operador op-1/ }).click();
  265 |     await page.getByRole("menuitem", { name: "Sair", exact: true }).click();
  266 |     await expect(page.getByRole("button", { name: "Entrar", exact: true })).toBeVisible();
  267 |     await enterFromScreen(page, user("op-2"));
  268 |     await expect.poll(() => storedUserId(page)).toBe("op-2");
  269 |     const status = await page.evaluate(async () => {
  270 |       const { openCashLocal } = await import("/src/infrastructure/database/repositories/CashSessionRepository.ts" as string);
  271 |       const { forwardPendingToGateway } = await import("/src/infrastructure/gateway/outboxGatewayForwarder.ts" as string);
  272 |       const status = await openCashLocal("50,00", "op-2", "Operador op-2", "abre-segundo");
  273 |       return { status, sent: await forwardPendingToGateway() };
  274 |     });
  275 |     expect(status.status.canSell).toBe(true);
  276 |     expect(status.status.currentSession.operatorId).toBe("op-2");
  277 |     expect(status.sent.forwarded).toBe(3);
  278 |     expect(delivered.map((event) => [event.eventType, event.payload.operatorId])).toEqual([
  279 |       ["CASH_OPEN", "op-1"], ["CASH_CLOSE", "op-1"], ["CASH_OPEN", "op-2"],
  280 |     ]);
  281 |   });
  282 | });
  283 | 
```