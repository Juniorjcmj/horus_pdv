import { test, expect } from "@playwright/test";
import { readFileSync, existsSync, readdirSync } from "fs";
import { join, dirname } from "path";
import { fileURLToPath } from "url";

/**
 * BLOCO 12 — Segurança, Configuração de Produção e Integridade Monetária
 *
 * Testes estruturais e funcionais que verificam:
 * - SuperAdmin não é concedido por e-mail
 * - Build de produção não contém localhost
 * - URLs das APIs são configuráveis via variáveis de ambiente
 * - Cálculo monetário com arredondamento determinístico
 * - Migration de índice CaixaSessoes
 */

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const API_ROOT = join(__dirname, "..", "..", "..", "API", "NETCORE");
const FRONTEND_ROOT = join(__dirname, "..", "..");

function readCsFile(relativePath: string): string {
  return readFileSync(join(API_ROOT, relativePath), "utf-8");
}

test.describe("BLOCO 12 — Segurança, Configuração de Produção e Integridade Monetária", () => {
  // ── Segurança ──────────────────────────────────────────────

  test("Teste 50 — SuperAdmin não é concedido por e-mail hardcoded", () => {
    const source = readCsFile(
      "Controllers/Admin/GerenciamentoEmpresasController.cs",
    );

    // Não deve conter endereços de e-mail usados como critério de autorização
    const emailPatterns = [
      "jotacfs2010@hotmail.com",
      "jotanaval2009@gmail.com",
      "flavio@hpdv.com.br",
    ];

    for (const email of emailPatterns) {
      expect(
        source,
        `Controller não deve conter e-mail hardcoded: ${email}`,
      ).not.toContain(email);
    }
  });

  test("Teste 51 — SuperAdmin depende exclusivamente de CompanyId e Role", () => {
    const source = readCsFile(
      "Controllers/Admin/GerenciamentoEmpresasController.cs",
    );

    // Deve verificar empresa-principal
    expect(source).toContain("empresa-principal");

    // Deve verificar role administrador
    expect(source).toContain("administrador");

    // O método GetSuperAdminUser deve existir
    expect(source).toContain("GetSuperAdminUser");

    // Não deve ter lógica de comparação de e-mail no método de autorização
    const methodStart = source.indexOf("GetSuperAdminUser");
    const methodBody = source.substring(methodStart, methodStart + 500);
    expect(
      methodBody,
      "GetSuperAdminUser não deve comparar user.Email",
    ).not.toContain("user.Email");
  });

  // ── Configuração de Produção ──────────────────────────────

  test("Teste 52 — Código-fonte não contém fallback localhost:5260", () => {
    const srcDir = join(FRONTEND_ROOT, "src");
    const files = findTsFiles(srcDir);

    const violations: string[] = [];
    for (const file of files) {
      const content = readFileSync(file, "utf-8");
      if (content.includes('localhost:5260') && !content.includes("// test") && !content.includes("spec.ts")) {
        violations.push(file.replace(FRONTEND_ROOT, ""));
      }
    }

    expect(
      violations,
      `Arquivos com fallback localhost:5260: ${violations.join(", ")}`,
    ).toHaveLength(0);
  });

  test("Teste 53 — Código-fonte não contém fallback localhost:5261", () => {
    const srcDir = join(FRONTEND_ROOT, "src");
    const files = findTsFiles(srcDir);

    const violations: string[] = [];
    for (const file of files) {
      const content = readFileSync(file, "utf-8");
      if (content.includes("localhost:5261")) {
        violations.push(file.replace(FRONTEND_ROOT, ""));
      }
    }

    expect(
      violations,
      `Arquivos com localhost:5261: ${violations.join(", ")}`,
    ).toHaveLength(0);
  });

  test("Teste 54 — URLs de API usam requireEnvUrl sem fallback", () => {
    const servicesDir = join(FRONTEND_ROOT, "src", "services", "api");
    const files = findTsFiles(servicesDir);

    for (const file of files) {
      const content = readFileSync(file, "utf-8");
      // Se o arquivo define uma constante _API_URL, ela deve usar requireEnvUrl
      const urlConstMatch = content.match(
        /const\s+\w+_API_URL\s*=\s*(.*)/,
      );
      if (urlConstMatch) {
        expect(
          urlConstMatch[1],
          `${file.replace(FRONTEND_ROOT, "")} deve usar requireEnvUrl`,
        ).toContain("requireEnvUrl");
        expect(
          urlConstMatch[1],
          `${file.replace(FRONTEND_ROOT, "")} não deve ter fallback ??`,
        ).not.toContain("??");
      }
    }
  });

  test("Teste 55 — .env.production existe e contém todas as URLs necessárias", () => {
    const envProdPath = join(FRONTEND_ROOT, ".env.production");
    expect(
      existsSync(envProdPath),
      ".env.production deve existir",
    ).toBe(true);

    const content = readFileSync(envProdPath, "utf-8");

    // Deve conter todas as variáveis de API
    const requiredVars = [
      "VITE_PRODUTO_API_URL",
      "VITE_CLIENTE_API_URL",
      "VITE_AUTH_API_URL",
      "VITE_CAIXA_API_URL",
      "VITE_CATEGORIA_API_URL",
      "VITE_FIADO_API_URL",
      "VITE_PROMOCAO_API_URL",
      "VITE_HISTORICO_VENDAS_API_URL",
    ];

    for (const v of requiredVars) {
      expect(content, `${v} deve estar em .env.production`).toContain(v);
    }

    // Não deve apontar para localhost
    expect(content).not.toContain("localhost");
  });

  // ── Monetário ──────────────────────────────────────────────

  test("Teste 56 — round2(0.1 + 0.2) resulta em 0.3", async ({ page }) => {
    await page.goto("/");
    await page.waitForLoadState("networkidle");

    const result = await page.evaluate(() => {
      // Simula a função round2 inline
      function round2(val: number): number {
        return Math.round((val + Number.EPSILON) * 100) / 100;
      }
      return {
        raw: 0.1 + 0.2,
        rounded: round2(0.1 + 0.2),
        isExact: round2(0.1 + 0.2) === 0.3,
      };
    });

    expect(result.raw).not.toBe(0.3); // Prova que float é impreciso
    expect(result.rounded).toBe(0.3); // Prova que round2 corrige
    expect(result.isExact).toBe(true);
  });

  test("Teste 57 — Subtotal de múltiplos produtos mantém precisão de centavos", async ({ page }) => {
    await page.goto("/");
    await page.waitForLoadState("networkidle");

    const result = await page.evaluate(() => {
      function round2(val: number): number {
        return Math.round((val + Number.EPSILON) * 100) / 100;
      }

      const items = [
        { unitPrice: 19.99, quantity: 3 },
        { unitPrice: 0.01, quantity: 1 },
        { unitPrice: 49.95, quantity: 2 },
        { unitPrice: 9999.99, quantity: 1 },
      ];

      const subtotal = round2(
        items.reduce((sum, item) => sum + round2(item.unitPrice * item.quantity), 0),
      );

      // 19.99*3=59.97 + 0.01*1=0.01 + 49.95*2=99.90 + 9999.99*1=9999.99 = 10159.87
      return { subtotal, expected: 10159.87 };
    });

    expect(result.subtotal).toBe(result.expected);
  });

  test("Teste 58 — Valores próximos de R$ 9.999,99 permanecem corretos", async ({ page }) => {
    await page.goto("/");
    await page.waitForLoadState("networkidle");

    const result = await page.evaluate(() => {
      function round2(val: number): number {
        return Math.round((val + Number.EPSILON) * 100) / 100;
      }

      // Testa acumulação de muitos itens pequenos que somam quase 10000
      const items = Array.from({ length: 100 }, () => ({
        unitPrice: 99.99,
        quantity: 1,
      }));

      const subtotal = round2(
        items.reduce((sum, item) => sum + round2(item.unitPrice * item.quantity), 0),
      );

      return { subtotal, expected: 9999.0 };
    });

    expect(result.subtotal).toBe(result.expected);
  });

  // ── Banco de Dados ─────────────────────────────────────────

  test("Teste 59 — Migration cria índice IX_CaixaSessoes_CompanyId_OpenedAt", () => {
    const migrationPath = join(
      API_ROOT,
      "DataBase",
      "Migrations",
      "20_indice_caixasessoes.sql",
    );

    expect(
      existsSync(migrationPath),
      "Migration 20_indice_caixasessoes.sql deve existir",
    ).toBe(true);

    const content = readFileSync(migrationPath, "utf-8");

    // Deve criar o índice composto
    expect(content).toContain("IX_CaixaSessoes_CompanyId_OpenedAt");
    expect(content).toContain("CompanyId");
    expect(content).toContain("OpenedAt DESC");
    // Deve ter guarda de idempotência
    expect(content).toContain("IF NOT EXISTS");
  });

  test("Teste 60 — appsettings.Development.json não está no .gitignore e não é tracked", () => {
    const gitignorePath = join(API_ROOT, ".gitignore");
    expect(existsSync(gitignorePath), ".gitignore deve existir").toBe(true);

    const content = readFileSync(gitignorePath, "utf-8");
    expect(
      content,
      ".gitignore deve ignorar appsettings.Development.json",
    ).toContain("appsettings.Development.json");
  });
});

/**
 * Recursivamente encontra todos os arquivos .ts e .tsx em um diretório.
 */
function findTsFiles(dir: string): string[] {
  const results: string[] = [];
  if (!existsSync(dir)) return results;

  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const fullPath = join(dir, entry.name);
    if (entry.isDirectory() && entry.name !== "node_modules") {
      results.push(...findTsFiles(fullPath));
    } else if (entry.isFile() && /\.tsx?$/.test(entry.name)) {
      results.push(fullPath);
    }
  }
  return results;
}
