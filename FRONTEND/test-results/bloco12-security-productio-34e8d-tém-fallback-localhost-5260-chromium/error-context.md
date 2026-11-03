# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: bloco12-security-production.spec.ts >> BLOCO 12 — Segurança, Configuração de Produção e Integridade Monetária >> Teste 52 — Código-fonte não contém fallback localhost:5260
- Location: tests\architecture\bloco12-security-production.spec.ts:74:3

# Error details

```
Error: Arquivos com fallback localhost:5260: \src\services\api\regrasEmissaoNfceService.ts

expect(received).toHaveLength(expected)

Expected length: 0
Received length: 1
Received array:  ["\\src\\services\\api\\regrasEmissaoNfceService.ts"]
```

# Test source

```ts
  1   | import { test, expect } from "@playwright/test";
  2   | import { readFileSync, existsSync, readdirSync } from "fs";
  3   | import { join, dirname } from "path";
  4   | import { fileURLToPath } from "url";
  5   | 
  6   | /**
  7   |  * BLOCO 12 — Segurança, Configuração de Produção e Integridade Monetária
  8   |  *
  9   |  * Testes estruturais e funcionais que verificam:
  10  |  * - SuperAdmin não é concedido por e-mail
  11  |  * - Build de produção não contém localhost
  12  |  * - URLs das APIs são configuráveis via variáveis de ambiente
  13  |  * - Cálculo monetário com arredondamento determinístico
  14  |  * - Migration de índice CaixaSessoes
  15  |  */
  16  | 
  17  | const __filename = fileURLToPath(import.meta.url);
  18  | const __dirname = dirname(__filename);
  19  | const API_ROOT = join(__dirname, "..", "..", "..", "API", "NETCORE");
  20  | const FRONTEND_ROOT = join(__dirname, "..", "..");
  21  | 
  22  | function readCsFile(relativePath: string): string {
  23  |   return readFileSync(join(API_ROOT, relativePath), "utf-8");
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
> 89  |     ).toHaveLength(0);
      |       ^ Error: Arquivos com fallback localhost:5260: \src\services\api\regrasEmissaoNfceService.ts
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
  124 |         ).toContain("requireEnvUrl");
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
```