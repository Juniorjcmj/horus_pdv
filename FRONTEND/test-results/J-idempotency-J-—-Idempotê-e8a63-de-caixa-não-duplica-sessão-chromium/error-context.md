# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: J-idempotency.spec.ts >> J — Idempotência >> J01 — Duplo POST de abertura de caixa não duplica sessão
- Location: tests\homologation\J-idempotency.spec.ts:48:3

# Error details

```
Error: SQL Server Docker não encontrado. Use SMOKE_SQL_CONTAINER ou suba um container chamado sqlserver2025.
```

```
Error: Container SQL não resolvido. Chame initSqlContainer() primeiro.
```

# Test source

```ts
  1   | /**
  2   |  * Helpers compartilhados para testes de homologação funcional E2E.
  3   |  * Reutiliza padrões do smoke test: register → approve → login → operate.
  4   |  */
  5   | import { execFileSync } from "node:child_process";
  6   | import { type APIRequestContext, type Page, expect } from "@playwright/test";
  7   | 
  8   | export const APP_URL = process.env.SMOKE_APP_URL ?? "http://localhost:5173";
  9   | export const API_URL = process.env.SMOKE_API_URL ?? "http://localhost:5260/api";
  10  | export const SQL_PASSWORD = process.env.SMOKE_SQL_PASSWORD ?? "Senha@12345";
  11  | export const SQL_DATABASE = process.env.SMOKE_SQL_DATABASE ?? "HorusPdv";
  12  | export const RUN_ID = process.env.HOMOLOG_RUN_ID ?? `HOM_${Date.now()}`;
  13  | export const SQL_PREFIX = RUN_ID.replace(/[^A-Z0-9_]/gi, "_");
  14  | 
  15  | let documentSequence = 0;
  16  | 
  17  | // ── SQL helpers ─────────────────────────────────────────────
  18  | 
  19  | let sqlContainer: string | null = null;
  20  | 
  21  | export function initSqlContainer(): void {
  22  |   sqlContainer = resolveSqlContainer();
  23  | }
  24  | 
  25  | function resolveSqlContainer(): string {
  26  |   const configured = process.env.SMOKE_SQL_CONTAINER;
  27  |   const candidates = configured ? [configured] : ["sqlserver2025"];
  28  |   for (const name of candidates) {
  29  |     try {
  30  |       const isRunning = execFileSync("docker", ["inspect", "-f", "{{.State.Running}}", name], {
  31  |         encoding: "utf8",
  32  |         stdio: ["ignore", "pipe", "ignore"],
  33  |       }).trim();
  34  |       if (isRunning === "true") return name;
  35  |     } catch {
  36  |       /* next */
  37  |     }
  38  |   }
  39  |   throw new Error(
  40  |     `SQL Server Docker não encontrado. Use SMOKE_SQL_CONTAINER ou suba um container chamado ${candidates.join(" ou ")}.`,
  41  |   );
  42  | }
  43  | 
  44  | function sqlcmdPath(): string {
  45  |   if (!sqlContainer) return "/opt/mssql-tools18/bin/sqlcmd";
  46  |   try {
  47  |     execFileSync("docker", ["exec", sqlContainer, "test", "-x", "/opt/mssql-tools18/bin/sqlcmd"]);
  48  |     return "/opt/mssql-tools18/bin/sqlcmd";
  49  |   } catch {
  50  |     return "/opt/mssql-tools/bin/sqlcmd";
  51  |   }
  52  | }
  53  | 
  54  | export function runSql(sql: string): void {
> 55  |   if (!sqlContainer) throw new Error("Container SQL não resolvido. Chame initSqlContainer() primeiro.");
      |                            ^ Error: Container SQL não resolvido. Chame initSqlContainer() primeiro.
  56  |   const args = [
  57  |     "exec", sqlContainer, sqlcmdPath(),
  58  |     "-S", "localhost", "-U", "sa", "-P", SQL_PASSWORD,
  59  |     "-C", "-d", SQL_DATABASE, "-b", "-Q", sql,
  60  |   ];
  61  |   execFileSync("docker", args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
  62  | }
  63  | 
  64  | export function querySqlScalar(sql: string): string | null {
  65  |   if (!sqlContainer) throw new Error("Container SQL não resolvido.");
  66  |   const args = [
  67  |     "exec", sqlContainer, sqlcmdPath(),
  68  |     "-S", "localhost", "-U", "sa", "-P", SQL_PASSWORD,
  69  |     "-C", "-d", SQL_DATABASE, "-h", "-1", "-W", "-Q", sql,
  70  |   ];
  71  |   const output = execFileSync("docker", args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
  72  |   return output.split(/\r?\n/).map((l) => l.trim()).filter(Boolean)[0] ?? null;
  73  | }
  74  | 
  75  | export function escapeSql(value: string): string {
  76  |   return value.replace(/'/g, "''");
  77  | }
  78  | 
  79  | // ── Cleanup ─────────────────────────────────────────────────
  80  | 
  81  | export function cleanupHomologData(): void {
  82  |   const like = `${escapeSql(SQL_PREFIX)}%`;
  83  |   runSql(`
  84  |     DELETE FROM VendaItens
  85  |       WHERE VendaId IN (
  86  |         SELECT v.Id FROM Vendas v
  87  |         WHERE v.CustomerName LIKE N'${like}'
  88  |            OR EXISTS (SELECT 1 FROM VendaItens i WHERE i.VendaId = v.Id AND i.ProductCode LIKE N'${like}')
  89  |       );
  90  |     DELETE FROM Vendas
  91  |       WHERE CustomerName LIKE N'${like}'
  92  |          OR Id IN (SELECT VendaId FROM VendaItens WHERE ProductCode LIKE N'${like}');
  93  |     DELETE FROM Produtos WHERE ProductCode LIKE N'${like}' OR ProductName LIKE N'${like}';
  94  |     DELETE FROM Clientes WHERE CustomerName LIKE N'${like}' OR Email LIKE N'${like.toLowerCase()}';
  95  |     DELETE FROM Fornecedores WHERE FantasyName LIKE N'${like}' OR CompanyName LIKE N'${like}';
  96  |     DELETE FROM CaixaSessoes WHERE OperatorName LIKE N'${like}' OR Note LIKE N'${like}';
  97  |     DELETE FROM PasswordResetTokens WHERE Email LIKE N'${like.toLowerCase()}';
  98  |     DELETE FROM Sessoes WHERE UserId IN (SELECT Id FROM Usuarios WHERE Email LIKE N'${like.toLowerCase()}' OR Name LIKE N'${like}');
  99  |     DELETE FROM Usuarios WHERE Email LIKE N'${like.toLowerCase()}' OR Name LIKE N'${like}';
  100 |   `);
  101 | }
  102 | 
  103 | // ── Document generation ─────────────────────────────────────
  104 | 
  105 | export function generateCpf(): string {
  106 |   const base = randomDigits(9);
  107 |   const firstDigit = cpfDigit(base);
  108 |   const secondDigit = cpfDigit([...base, firstDigit]);
  109 |   return formatCpf([...base, firstDigit, secondDigit].join(""));
  110 | }
  111 | 
  112 | export function generateCnpj(): string {
  113 |   const base = randomDigits(12);
  114 |   const firstDigit = cnpjDigit(base);
  115 |   const secondDigit = cnpjDigit([...base, firstDigit]);
  116 |   return formatCnpj([...base, firstDigit, secondDigit].join(""));
  117 | }
  118 | 
  119 | function cpfDigit(numbers: number[]): number {
  120 |   const sum = numbers.reduce((acc, d, i) => acc + d * (numbers.length + 1 - i), 0);
  121 |   const mod = (sum * 10) % 11;
  122 |   return mod === 10 ? 0 : mod;
  123 | }
  124 | 
  125 | function cnpjDigit(numbers: number[]): number {
  126 |   const weights =
  127 |     numbers.length === 12
  128 |       ? [5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]
  129 |       : [6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2];
  130 |   const sum = numbers.reduce((acc, d, i) => acc + d * weights[i], 0);
  131 |   const rest = sum % 11;
  132 |   return rest < 2 ? 0 : 11 - rest;
  133 | }
  134 | 
  135 | function randomDigits(length: number): number[] {
  136 |   documentSequence += 1;
  137 |   const seed = `${Date.now()}${documentSequence}`.padEnd(length, "7");
  138 |   return Array.from({ length }, (_, i) => Number(seed[i % seed.length]));
  139 | }
  140 | 
  141 | function formatCpf(v: string): string {
  142 |   return v.replace(/(\d{3})(\d{3})(\d{3})(\d{2})/, "$1.$2.$3-$4");
  143 | }
  144 | 
  145 | function formatCnpj(v: string): string {
  146 |   return v.replace(/(\d{2})(\d{3})(\d{3})(\d{4})(\d{2})/, "$1.$2.$3/$4-$5");
  147 | }
  148 | 
  149 | export function slug(value: string): string {
  150 |   return value
  151 |     .normalize("NFD")
  152 |     .replace(/[\u0300-\u036f]/g, "")
  153 |     .replace(/[^a-z0-9]+/gi, "_")
  154 |     .replace(/^_+|_+$/g, "")
  155 |     .toLowerCase();
```