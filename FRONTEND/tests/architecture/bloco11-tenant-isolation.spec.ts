import { test, expect } from "@playwright/test";
import { readFileSync } from "fs";
import { join, dirname } from "path";
import { fileURLToPath } from "url";

/**
 * BLOCO 11 — Isolamento Multi-Tenant no Estoque (P0)
 *
 * Testes estruturais que verificam, via análise do código-fonte C#, que todos os
 * UPDATEs e SELECTs de estoque incluem filtro por CompanyId, garantindo
 * isolamento multi-tenant mesmo se o Id do produto for conhecido.
 */

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const API_ROOT = join(__dirname, "..", "..", "..", "API", "NETCORE");

function readCsFile(relativePath: string): string {
  return readFileSync(join(API_ROOT, relativePath), "utf-8");
}

/**
 * Extrai todos os blocos SQL de um arquivo C# (strings entre triple-quotes ou aspas simples).
 * Retorna pares { sql, lineNumber } para diagnóstico.
 */
function extractSqlBlocks(source: string): { sql: string; lineNumber: number }[] {
  const blocks: { sql: string; lineNumber: number }[] = [];

  // Triple-quoted strings (raw string literals C# 11)
  const tripleQuoteRegex = /"""\s*\n([\s\S]*?)"""/g;
  let match: RegExpExecArray | null;
  while ((match = tripleQuoteRegex.exec(source)) !== null) {
    const beforeMatch = source.substring(0, match.index);
    const lineNumber = beforeMatch.split("\n").length;
    blocks.push({ sql: match[1], lineNumber });
  }

  return blocks;
}

/**
 * Filtra blocos SQL que contêm UPDATE na tabela Produtos.
 */
function getStockUpdateBlocks(source: string) {
  return extractSqlBlocks(source).filter(
    (b) =>
      b.sql.toUpperCase().includes("UPDATE") &&
      b.sql.includes("Produtos") &&
      (b.sql.includes("ProductQnt") || b.sql.includes("ProductQnt")),
  );
}

/**
 * Filtra blocos SQL que contêm SELECT na tabela Produtos com UPDLOCK (bloqueio pessimista de estoque).
 */
function getStockSelectBlocks(source: string) {
  return extractSqlBlocks(source).filter(
    (b) =>
      b.sql.toUpperCase().includes("SELECT") &&
      b.sql.includes("Produtos") &&
      b.sql.toUpperCase().includes("UPDLOCK"),
  );
}

test.describe("BLOCO 11 — Isolamento Multi-Tenant no Estoque (P0)", () => {
  // ── ProdutoAB.cs ──────────────────────────────────────────────

  test("Teste 43 — ProdutoAB.BaixarEstoqueAsync SELECT inclui CompanyId", () => {
    const source = readCsFile(
      "Repositories/DatabaseAccess/ProdutoAB.cs",
    );
    const selects = getStockSelectBlocks(source);

    expect(selects.length, "Deve haver pelo menos 1 SELECT com UPDLOCK em ProdutoAB").toBeGreaterThanOrEqual(1);

    for (const block of selects) {
      expect(
        block.sql,
        `SELECT na linha ~${block.lineNumber} de ProdutoAB.cs deve filtrar por CompanyId`,
      ).toContain("CompanyId");
    }
  });

  test("Teste 44 — ProdutoAB.BaixarEstoqueAsync UPDATE inclui CompanyId no WHERE", () => {
    const source = readCsFile(
      "Repositories/DatabaseAccess/ProdutoAB.cs",
    );
    const updates = getStockUpdateBlocks(source);

    expect(updates.length, "Deve haver pelo menos 1 UPDATE de estoque em ProdutoAB").toBeGreaterThanOrEqual(1);

    for (const block of updates) {
      const whereClause = block.sql.substring(
        block.sql.toUpperCase().indexOf("WHERE"),
      );
      expect(
        whereClause,
        `UPDATE na linha ~${block.lineNumber} de ProdutoAB.cs deve ter CompanyId no WHERE`,
      ).toContain("CompanyId");
    }
  });

  test("Teste 45 — ProdutoAB.BaixarEstoqueAsync recebe companyId como parâmetro", () => {
    const source = readCsFile(
      "Repositories/DatabaseAccess/ProdutoAB.cs",
    );

    // Verifica que a assinatura do método inclui string companyId
    const methodSignature = source.match(
      /BaixarEstoqueAsync\s*\(([\s\S]*?)\)/,
    );
    expect(methodSignature, "Método BaixarEstoqueAsync deve existir").toBeTruthy();
    expect(
      methodSignature![1],
      "BaixarEstoqueAsync deve receber companyId como parâmetro",
    ).toContain("companyId");
  });

  // ── HistoricoVendasAB.cs ──────────────────────────────────────

  test("Teste 46 — HistoricoVendasAB.BaixarEstoqueAsync UPDATE inclui CompanyId no WHERE", () => {
    const source = readCsFile(
      "Repositories/DatabaseAccess/HistoricoVendasAB.cs",
    );

    // Localiza a definição do método BaixarEstoqueAsync (não a chamada)
    const methodDefPattern = /static\s+async\s+Task.*BaixarEstoqueAsync\s*\(/;
    const methodDefMatch = methodDefPattern.exec(source);
    expect(methodDefMatch, "Definição de BaixarEstoqueAsync deve existir").toBeTruthy();
    const methodStart = methodDefMatch!.index;
    const methodEnd = source.indexOf(
      "BaixarEstoqueComPrecoFixoAsync",
      methodStart,
    );
    const methodBody = source.substring(methodStart, methodEnd > 0 ? methodEnd : undefined);
    const lines = methodBody.split("\n");

    // Encontra linhas com UPDATE Produtos e verifica que o WHERE correspondente inclui CompanyId
    const updateLineIndices = lines
      .map((line, i) => (line.includes("UPDATE") && line.includes("Produtos") ? i : -1))
      .filter((i) => i !== -1);

    expect(updateLineIndices.length, "Deve haver pelo menos 1 UPDATE Produtos em BaixarEstoqueAsync").toBeGreaterThanOrEqual(1);

    for (const idx of updateLineIndices) {
      // Captura as próximas linhas até encontrar o WHERE e fim do SQL (;)
      const sqlBlock = lines.slice(idx, idx + 10).join("\n");
      expect(
        sqlBlock,
        `UPDATE em BaixarEstoqueAsync deve ter CompanyId no WHERE`,
      ).toContain("CompanyId");
    }
  });

  test("Teste 47 — HistoricoVendasAB.BaixarEstoqueComPrecoFixoAsync UPDATE inclui CompanyId no WHERE", () => {
    const source = readCsFile(
      "Repositories/DatabaseAccess/HistoricoVendasAB.cs",
    );

    // Localiza o método BaixarEstoqueComPrecoFixoAsync
    const methodStart = source.indexOf("BaixarEstoqueComPrecoFixoAsync(");
    expect(methodStart, "Método BaixarEstoqueComPrecoFixoAsync deve existir").toBeGreaterThan(-1);

    const methodBody = source.substring(methodStart);

    const updates = extractSqlBlocks(methodBody).filter(
      (b) =>
        b.sql.toUpperCase().includes("UPDATE") &&
        b.sql.includes("Produtos"),
    );

    expect(updates.length, "Deve haver pelo menos 1 UPDATE em BaixarEstoqueComPrecoFixoAsync").toBeGreaterThanOrEqual(1);

    for (const block of updates) {
      const whereClause = block.sql.substring(
        block.sql.toUpperCase().indexOf("WHERE"),
      );
      expect(
        whereClause,
        `UPDATE em BaixarEstoqueComPrecoFixoAsync deve ter CompanyId no WHERE`,
      ).toContain("CompanyId");
    }
  });

  test("Teste 48 — Todos os SELECTs com UPDLOCK em HistoricoVendasAB incluem CompanyId", () => {
    const source = readCsFile(
      "Repositories/DatabaseAccess/HistoricoVendasAB.cs",
    );
    const selects = getStockSelectBlocks(source);

    expect(selects.length, "Deve haver SELECTs com UPDLOCK em HistoricoVendasAB").toBeGreaterThanOrEqual(1);

    for (const block of selects) {
      expect(
        block.sql,
        `SELECT com UPDLOCK na linha ~${block.lineNumber} deve filtrar por CompanyId`,
      ).toContain("CompanyId");
    }
  });

  test("Teste 49 — Nenhum UPDATE de estoque em todo o repositório usa WHERE Id = @Id sem CompanyId", () => {
    const files = [
      "Repositories/DatabaseAccess/ProdutoAB.cs",
      "Repositories/DatabaseAccess/HistoricoVendasAB.cs",
    ];

    for (const file of files) {
      const source = readCsFile(file);
      const updates = extractSqlBlocks(source).filter(
        (b) =>
          b.sql.toUpperCase().includes("UPDATE") &&
          b.sql.includes("Produtos") &&
          b.sql.includes("ProductQnt"),
      );

      for (const block of updates) {
        const whereClause = block.sql.substring(
          block.sql.toUpperCase().indexOf("WHERE"),
        );

        // O WHERE deve conter CompanyId — se contém Id mas não CompanyId, é uma falha de isolamento
        if (whereClause.includes("@Id")) {
          expect(
            whereClause,
            `UPDATE de estoque em ${file} (linha ~${block.lineNumber}) tem WHERE Id sem CompanyId — FALHA DE ISOLAMENTO MULTI-TENANT`,
          ).toContain("CompanyId");
        }
      }
    }
  });
});
