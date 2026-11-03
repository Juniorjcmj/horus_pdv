# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: bloco12-security-production.spec.ts >> BLOCO 12 — Segurança, Configuração de Produção e Integridade Monetária >> Teste 54 — URLs de API usam requireEnvUrl sem fallback
- Location: tests\architecture\bloco12-security-production.spec.ts:110:3

# Error details

```
Error: \src\services\api\regrasEmissaoNfceService.ts deve usar requireEnvUrl

expect(received).toContain(expected) // indexOf

Expected substring: "requireEnvUrl"
Received string:    "(import.meta.env.VITE_REGRAS_EMISSAO_NFCE_API_URL as string | undefined) ||"
```

# Test source

```ts
  24  | }
  25  | 
  26  | test.describe("BLOCO 12 — Segurança, Configuração de Produção e Integridade Monetária", () => {
  27  |   // ── Segurança ──────────────────────────────────────────────
  28  | 
  29  |   test("Teste 50 — SuperAdmin não é concedido por e-mail hardcoded", () => {
  30  |     const source = readCsFile(
  31  |       "Controllers/Admin/GerenciamentoEmpresasController.cs",
  32  |     );
  33  | 
  34  |     // Não deve conter endereços de e-mail usados como critério de autorização
  35  |     const emailPatterns = [
  36  |       "jotacfs2010@hotmail.com",
  37  |       "jotanaval2009@gmail.com",
  38  |       "flavio@hpdv.com.br",
  39  |     ];
  40  | 
  41  |     for (const email of emailPatterns) {
  42  |       expect(
  43  |         source,
  44  |         `Controller não deve conter e-mail hardcoded: ${email}`,
  45  |       ).not.toContain(email);
  46  |     }
  47  |   });
  48  | 
  49  |   test("Teste 51 — SuperAdmin depende exclusivamente de CompanyId e Role", () => {
  50  |     const source = readCsFile(
  51  |       "Controllers/Admin/GerenciamentoEmpresasController.cs",
  52  |     );
  53  | 
  54  |     // Deve verificar empresa-principal
  55  |     expect(source).toContain("empresa-principal");
  56  | 
  57  |     // Deve verificar role administrador
  58  |     expect(source).toContain("administrador");
  59  | 
  60  |     // O método GetSuperAdminUser deve existir
  61  |     expect(source).toContain("GetSuperAdminUser");
  62  | 
  63  |     // Não deve ter lógica de comparação de e-mail no método de autorização
  64  |     const methodStart = source.indexOf("GetSuperAdminUser");
  65  |     const methodBody = source.substring(methodStart, methodStart + 500);
  66  |     expect(
  67  |       methodBody,
  68  |       "GetSuperAdminUser não deve comparar user.Email",
  69  |     ).not.toContain("user.Email");
  70  |   });
  71  | 
  72  |   // ── Configuração de Produção ──────────────────────────────
  73  | 
  74  |   test("Teste 52 — Código-fonte não contém fallback localhost:5260", () => {
  75  |     const srcDir = join(FRONTEND_ROOT, "src");
  76  |     const files = findTsFiles(srcDir);
  77  | 
  78  |     const violations: string[] = [];
  79  |     for (const file of files) {
  80  |       const content = readFileSync(file, "utf-8");
  81  |       if (content.includes('localhost:5260') && !content.includes("// test") && !content.includes("spec.ts")) {
  82  |         violations.push(file.replace(FRONTEND_ROOT, ""));
  83  |       }
  84  |     }
  85  | 
  86  |     expect(
  87  |       violations,
  88  |       `Arquivos com fallback localhost:5260: ${violations.join(", ")}`,
  89  |     ).toHaveLength(0);
  90  |   });
  91  | 
  92  |   test("Teste 53 — Código-fonte não contém fallback localhost:5261", () => {
  93  |     const srcDir = join(FRONTEND_ROOT, "src");
  94  |     const files = findTsFiles(srcDir);
  95  | 
  96  |     const violations: string[] = [];
  97  |     for (const file of files) {
  98  |       const content = readFileSync(file, "utf-8");
  99  |       if (content.includes("localhost:5261")) {
  100 |         violations.push(file.replace(FRONTEND_ROOT, ""));
  101 |       }
  102 |     }
  103 | 
  104 |     expect(
  105 |       violations,
  106 |       `Arquivos com localhost:5261: ${violations.join(", ")}`,
  107 |     ).toHaveLength(0);
  108 |   });
  109 | 
  110 |   test("Teste 54 — URLs de API usam requireEnvUrl sem fallback", () => {
  111 |     const servicesDir = join(FRONTEND_ROOT, "src", "services", "api");
  112 |     const files = findTsFiles(servicesDir);
  113 | 
  114 |     for (const file of files) {
  115 |       const content = readFileSync(file, "utf-8");
  116 |       // Se o arquivo define uma constante _API_URL, ela deve usar requireEnvUrl
  117 |       const urlConstMatch = content.match(
  118 |         /const\s+\w+_API_URL\s*=\s*(.*)/,
  119 |       );
  120 |       if (urlConstMatch) {
  121 |         expect(
  122 |           urlConstMatch[1],
  123 |           `${file.replace(FRONTEND_ROOT, "")} deve usar requireEnvUrl`,
> 124 |         ).toContain("requireEnvUrl");
      |           ^ Error: \src\services\api\regrasEmissaoNfceService.ts deve usar requireEnvUrl
  125 |         expect(
  126 |           urlConstMatch[1],
  127 |           `${file.replace(FRONTEND_ROOT, "")} não deve ter fallback ??`,
  128 |         ).not.toContain("??");
  129 |       }
  130 |     }
  131 |   });
  132 | 
  133 |   test("Teste 55 — .env.production existe e contém todas as URLs necessárias", () => {
  134 |     const envProdPath = join(FRONTEND_ROOT, ".env.production");
  135 |     expect(
  136 |       existsSync(envProdPath),
  137 |       ".env.production deve existir",
  138 |     ).toBe(true);
  139 | 
  140 |     const content = readFileSync(envProdPath, "utf-8");
  141 | 
  142 |     // Deve conter todas as variáveis de API
  143 |     const requiredVars = [
  144 |       "VITE_PRODUTO_API_URL",
  145 |       "VITE_CLIENTE_API_URL",
  146 |       "VITE_AUTH_API_URL",
  147 |       "VITE_CAIXA_API_URL",
  148 |       "VITE_CATEGORIA_API_URL",
  149 |       "VITE_FIADO_API_URL",
  150 |       "VITE_PROMOCAO_API_URL",
  151 |       "VITE_HISTORICO_VENDAS_API_URL",
  152 |     ];
  153 | 
  154 |     for (const v of requiredVars) {
  155 |       expect(content, `${v} deve estar em .env.production`).toContain(v);
  156 |     }
  157 | 
  158 |     // Não deve apontar para localhost
  159 |     expect(content).not.toContain("localhost");
  160 |   });
  161 | 
  162 |   // ── Monetário ──────────────────────────────────────────────
  163 | 
  164 |   test("Teste 56 — round2(0.1 + 0.2) resulta em 0.3", async ({ page }) => {
  165 |     await page.goto("/");
  166 |     await page.waitForLoadState("networkidle");
  167 | 
  168 |     const result = await page.evaluate(() => {
  169 |       // Simula a função round2 inline
  170 |       function round2(val: number): number {
  171 |         return Math.round((val + Number.EPSILON) * 100) / 100;
  172 |       }
  173 |       return {
  174 |         raw: 0.1 + 0.2,
  175 |         rounded: round2(0.1 + 0.2),
  176 |         isExact: round2(0.1 + 0.2) === 0.3,
  177 |       };
  178 |     });
  179 | 
  180 |     expect(result.raw).not.toBe(0.3); // Prova que float é impreciso
  181 |     expect(result.rounded).toBe(0.3); // Prova que round2 corrige
  182 |     expect(result.isExact).toBe(true);
  183 |   });
  184 | 
  185 |   test("Teste 57 — Subtotal de múltiplos produtos mantém precisão de centavos", async ({ page }) => {
  186 |     await page.goto("/");
  187 |     await page.waitForLoadState("networkidle");
  188 | 
  189 |     const result = await page.evaluate(() => {
  190 |       function round2(val: number): number {
  191 |         return Math.round((val + Number.EPSILON) * 100) / 100;
  192 |       }
  193 | 
  194 |       const items = [
  195 |         { unitPrice: 19.99, quantity: 3 },
  196 |         { unitPrice: 0.01, quantity: 1 },
  197 |         { unitPrice: 49.95, quantity: 2 },
  198 |         { unitPrice: 9999.99, quantity: 1 },
  199 |       ];
  200 | 
  201 |       const subtotal = round2(
  202 |         items.reduce((sum, item) => sum + round2(item.unitPrice * item.quantity), 0),
  203 |       );
  204 | 
  205 |       // 19.99*3=59.97 + 0.01*1=0.01 + 49.95*2=99.90 + 9999.99*1=9999.99 = 10159.87
  206 |       return { subtotal, expected: 10159.87 };
  207 |     });
  208 | 
  209 |     expect(result.subtotal).toBe(result.expected);
  210 |   });
  211 | 
  212 |   test("Teste 58 — Valores próximos de R$ 9.999,99 permanecem corretos", async ({ page }) => {
  213 |     await page.goto("/");
  214 |     await page.waitForLoadState("networkidle");
  215 | 
  216 |     const result = await page.evaluate(() => {
  217 |       function round2(val: number): number {
  218 |         return Math.round((val + Number.EPSILON) * 100) / 100;
  219 |       }
  220 | 
  221 |       // Testa acumulação de muitos itens pequenos que somam quase 10000
  222 |       const items = Array.from({ length: 100 }, () => ({
  223 |         unitPrice: 99.99,
  224 |         quantity: 1,
```