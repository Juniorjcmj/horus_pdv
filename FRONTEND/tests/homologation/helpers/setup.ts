/**
 * Helpers compartilhados para testes de homologação funcional E2E.
 * Reutiliza padrões do smoke test: register → approve → login → operate.
 */
import { execFileSync } from "node:child_process";
import { type APIRequestContext, type Page, expect } from "@playwright/test";

export const APP_URL = process.env.SMOKE_APP_URL ?? "http://localhost:5173";
export const API_URL = process.env.SMOKE_API_URL ?? "http://localhost:5260/api";
export const SQL_PASSWORD = process.env.SMOKE_SQL_PASSWORD ?? "Senha@12345";
export const SQL_DATABASE = process.env.SMOKE_SQL_DATABASE ?? "HorusPdv";
export const RUN_ID = process.env.HOMOLOG_RUN_ID ?? `HOM_${Date.now()}`;
export const SQL_PREFIX = RUN_ID.replace(/[^A-Z0-9_]/gi, "_");

let documentSequence = 0;

// ── SQL helpers ─────────────────────────────────────────────

let sqlContainer: string | null = null;

export function initSqlContainer(): void {
  sqlContainer = resolveSqlContainer();
}

function resolveSqlContainer(): string {
  const configured = process.env.SMOKE_SQL_CONTAINER;
  const candidates = configured ? [configured] : ["sqlserver2025"];
  for (const name of candidates) {
    try {
      const isRunning = execFileSync("docker", ["inspect", "-f", "{{.State.Running}}", name], {
        encoding: "utf8",
        stdio: ["ignore", "pipe", "ignore"],
      }).trim();
      if (isRunning === "true") return name;
    } catch {
      /* next */
    }
  }
  throw new Error(
    `SQL Server Docker não encontrado. Use SMOKE_SQL_CONTAINER ou suba um container chamado ${candidates.join(" ou ")}.`,
  );
}

function sqlcmdPath(): string {
  if (!sqlContainer) return "/opt/mssql-tools18/bin/sqlcmd";
  try {
    execFileSync("docker", ["exec", sqlContainer, "test", "-x", "/opt/mssql-tools18/bin/sqlcmd"]);
    return "/opt/mssql-tools18/bin/sqlcmd";
  } catch {
    return "/opt/mssql-tools/bin/sqlcmd";
  }
}

export function runSql(sql: string): void {
  if (!sqlContainer) throw new Error("Container SQL não resolvido. Chame initSqlContainer() primeiro.");
  const args = [
    "exec", sqlContainer, sqlcmdPath(),
    "-S", "localhost", "-U", "sa", "-P", SQL_PASSWORD,
    "-C", "-d", SQL_DATABASE, "-b", "-Q", sql,
  ];
  execFileSync("docker", args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
}

export function querySqlScalar(sql: string): string | null {
  if (!sqlContainer) throw new Error("Container SQL não resolvido.");
  const args = [
    "exec", sqlContainer, sqlcmdPath(),
    "-S", "localhost", "-U", "sa", "-P", SQL_PASSWORD,
    "-C", "-d", SQL_DATABASE, "-h", "-1", "-W", "-Q", sql,
  ];
  const output = execFileSync("docker", args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
  return output.split(/\r?\n/).map((l) => l.trim()).filter(Boolean)[0] ?? null;
}

export function escapeSql(value: string): string {
  return value.replace(/'/g, "''");
}

// ── Cleanup ─────────────────────────────────────────────────

export function cleanupHomologData(): void {
  const like = `${escapeSql(SQL_PREFIX)}%`;
  runSql(`
    DELETE FROM VendaItens
      WHERE VendaId IN (
        SELECT v.Id FROM Vendas v
        WHERE v.CustomerName LIKE N'${like}'
           OR EXISTS (SELECT 1 FROM VendaItens i WHERE i.VendaId = v.Id AND i.ProductCode LIKE N'${like}')
      );
    DELETE FROM Vendas
      WHERE CustomerName LIKE N'${like}'
         OR Id IN (SELECT VendaId FROM VendaItens WHERE ProductCode LIKE N'${like}');
    DELETE FROM Produtos WHERE ProductCode LIKE N'${like}' OR ProductName LIKE N'${like}';
    DELETE FROM Clientes WHERE CustomerName LIKE N'${like}' OR Email LIKE N'${like.toLowerCase()}';
    DELETE FROM Fornecedores WHERE FantasyName LIKE N'${like}' OR CompanyName LIKE N'${like}';
    DELETE FROM CaixaSessoes WHERE OperatorName LIKE N'${like}' OR Note LIKE N'${like}';
    DELETE FROM PasswordResetTokens WHERE Email LIKE N'${like.toLowerCase()}';
    DELETE FROM Sessoes WHERE UserId IN (SELECT Id FROM Usuarios WHERE Email LIKE N'${like.toLowerCase()}' OR Name LIKE N'${like}');
    DELETE FROM Usuarios WHERE Email LIKE N'${like.toLowerCase()}' OR Name LIKE N'${like}';
  `);
}

// ── Document generation ─────────────────────────────────────

export function generateCpf(): string {
  const base = randomDigits(9);
  const firstDigit = cpfDigit(base);
  const secondDigit = cpfDigit([...base, firstDigit]);
  return formatCpf([...base, firstDigit, secondDigit].join(""));
}

export function generateCnpj(): string {
  const base = randomDigits(12);
  const firstDigit = cnpjDigit(base);
  const secondDigit = cnpjDigit([...base, firstDigit]);
  return formatCnpj([...base, firstDigit, secondDigit].join(""));
}

function cpfDigit(numbers: number[]): number {
  const sum = numbers.reduce((acc, d, i) => acc + d * (numbers.length + 1 - i), 0);
  const mod = (sum * 10) % 11;
  return mod === 10 ? 0 : mod;
}

function cnpjDigit(numbers: number[]): number {
  const weights =
    numbers.length === 12
      ? [5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]
      : [6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2];
  const sum = numbers.reduce((acc, d, i) => acc + d * weights[i], 0);
  const rest = sum % 11;
  return rest < 2 ? 0 : 11 - rest;
}

function randomDigits(length: number): number[] {
  documentSequence += 1;
  const seed = `${Date.now()}${documentSequence}`.padEnd(length, "7");
  return Array.from({ length }, (_, i) => Number(seed[i % seed.length]));
}

function formatCpf(v: string): string {
  return v.replace(/(\d{3})(\d{3})(\d{3})(\d{2})/, "$1.$2.$3-$4");
}

function formatCnpj(v: string): string {
  return v.replace(/(\d{2})(\d{3})(\d{3})(\d{4})(\d{2})/, "$1.$2.$3/$4-$5");
}

export function slug(value: string): string {
  return value
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-z0-9]+/gi, "_")
    .replace(/^_+|_+$/g, "")
    .toLowerCase();
}

// ── Auth & session ──────────────────────────────────────────

export type LoginData = {
  user: {
    id: string;
    companyId: string;
    cpf: string;
    name: string;
    email: string;
    phone: string;
    role: string;
    status: string;
    createdAt: string;
    lastLoginAt: string;
    mustChangePassword: boolean;
  };
};

export type ApiResponse<T> = {
  success: boolean;
  message?: string;
  data?: T;
};

const PASSWORD = `Hom@${Date.now().toString().slice(-6)}Aa`;
let registeredEmail: string | null = null;

export function getCredentials() {
  return {
    email: registeredEmail ?? `${RUN_ID.toLowerCase()}@hom.test`,
    password: PASSWORD,
  };
}

/**
 * Registra uma empresa de teste via API pública, aprova via SQL e retorna o email.
 */
export async function registerTestCompany(request: APIRequestContext): Promise<{ email: string; companyId: string }> {
  const email = `${RUN_ID.toLowerCase()}@hom.test`;
  const cnpj = generateCnpj();

  const regRes = await request.post(`${API_URL}/Auth/register`, {
    data: {
      cnpj,
      name: `${RUN_ID} Empresa Homologacao`,
      email,
      phone: "(11) 99999-0000",
      password: PASSWORD,
      confirmPassword: PASSWORD,
      recaptchaToken: "homolog-test",
    },
    headers: { "Content-Type": "application/json" },
  });

  const raw = await regRes.text();
  expect(regRes.ok(), `Register failed: ${raw}`).toBeTruthy();

  let body: ApiResponse<{ companyId?: string; user?: { companyId?: string } }>;
  try {
    body = JSON.parse(raw);
  } catch {
    throw new Error(`Register response não é JSON: ${raw}`);
  }

  const companyId = body.data?.companyId ?? body.data?.user?.companyId ?? "";

  if (companyId) {
    runSql(`UPDATE Empresas SET Status = N'aprovada', ReviewedAt = SYSDATETIMEOFFSET(), ReviewedBy = N'homolog-auto' WHERE Id = N'${companyId}'`);
  } else {
    runSql(`UPDATE Empresas SET Status = N'aprovada', ReviewedAt = SYSDATETIMEOFFSET(), ReviewedBy = N'homolog-auto' WHERE Status = N'pendente' AND Id <> N'empresa-principal'`);
  }

  registeredEmail = email;
  return { email, companyId };
}

/**
 * Faz login via API e retorna dados do usuário.
 */
export async function loginApi(request: APIRequestContext): Promise<LoginData> {
  const creds = getCredentials();
  const res = await request.post(`${API_URL}/Auth/login`, {
    data: {
      email: creds.email,
      password: creds.password,
      rememberMe: true,
      recaptchaToken: "homolog-test",
    },
    headers: { "Content-Type": "application/json" },
  });

  const raw = await res.text();
  const payload = raw ? (JSON.parse(raw) as ApiResponse<LoginData>) : null;
  expect(res.ok(), `Login failed: ${raw}`).toBeTruthy();
  expect(payload?.success, `Login failed: ${raw}`).toBeTruthy();
  return payload!.data!;
}

/**
 * Faz login no browser: API login → localStorage injection → CustomEvent dispatch.
 */
export async function loginBrowserSession(page: Page): Promise<LoginData> {
  const creds = getCredentials();
  const response = await page.context().request.post(`${API_URL}/Auth/login`, {
    data: {
      email: creds.email,
      password: creds.password,
      rememberMe: true,
      recaptchaToken: "homolog-test",
    },
    headers: { "Content-Type": "application/json" },
  });
  const raw = await response.text();
  const payload = raw ? (JSON.parse(raw) as ApiResponse<LoginData>) : null;
  expect(response.ok(), `Login browser failed: ${raw}`).toBeTruthy();
  expect(payload?.data?.user?.companyId, `Login: sem companyId: ${raw}`).toBeTruthy();

  const user = payload!.data!.user;
  await page.addInitScript((authenticatedUser) => {
    window.localStorage.setItem("horuspdv.auth.user", JSON.stringify(authenticatedUser));
    window.localStorage.setItem("horuspdv.auth.remember", "1");
    window.localStorage.setItem("horuspdv.activePage", "home");
  }, user);
  await page.goto(APP_URL);
  await page.evaluate((authenticatedUser) => {
    window.localStorage.setItem("horuspdv.auth.user", JSON.stringify(authenticatedUser));
    window.localStorage.setItem("horuspdv.auth.remember", "1");
    window.localStorage.setItem("horuspdv.activePage", "home");
    window.dispatchEvent(new CustomEvent("horuspdv-auth-change", { detail: { user: authenticatedUser } }));
  }, user);
  await expect(page.getByRole("heading", { name: "Home" })).toBeVisible({ timeout: 20_000 });
  return payload!.data!;
}

/**
 * Helper genérico de API (mesma assinatura do smoke test).
 */
export async function api<T>(
  request: APIRequestContext,
  path: string,
  options: {
    method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
    body?: unknown;
    allowFailure?: boolean;
  } = {},
): Promise<T> {
  const response = await request.fetch(`${API_URL}${path}`, {
    method: options.method ?? "GET",
    data: options.body,
    headers: { "Content-Type": "application/json" },
  });

  const raw = await response.text();
  let payload: ApiResponse<T>;
  try {
    payload = raw ? JSON.parse(raw) : { success: response.ok() };
  } catch {
    payload = { success: response.ok(), message: raw };
  }

  if (!options.allowFailure) {
    expect(response.ok(), `${options.method ?? "GET"} ${path}: ${raw}`).toBeTruthy();
    expect(payload.success, `${options.method ?? "GET"} ${path}: ${raw}`).toBeTruthy();
  }

  return payload.data as T;
}

// ── Payload factories ───────────────────────────────────────

export function productPayload(label: string, supplierName: string, quantity: string) {
  const unit = "10,00";
  const total = (Number(quantity) * 10).toFixed(2).replace(".", ",");
  return {
    productImageUrl: "",
    productImageName: "",
    productName: `${RUN_ID} ${label}`,
    productCode: `${SQL_PREFIX}_${slug(label).toUpperCase()}`,
    productSupplier: supplierName,
    productDescription: "Produto criado pela homologação",
    productQnt: quantity,
    productUnitPrice: unit,
    productSalePrice: "25,00",
    totalPriceOnProduct: total,
  };
}

export function supplierPayload(label: string) {
  return {
    companyName: `${RUN_ID} ${label} Ltda`,
    fantasyName: `${RUN_ID} ${label}`,
    cnpj: generateCnpj(),
    cep: "01310-100",
    city: "São Paulo",
    state: "SP",
    address: "Avenida Paulista",
    neighborhood: "Bela Vista",
    streetComplement: "Conjunto homolog",
    number: "1578",
    referencePoint: "MASP",
    telephone: "(11) 3333-1111",
    cellphone: "(11) 94444-1111",
    email: `${RUN_ID.toLowerCase()}_${slug(label)}@fornecedor.test`,
  };
}

export function customerPayload(label: string) {
  return {
    customerName: `${RUN_ID} ${label}`,
    document: generateCpf(),
    birthDate: "01/01/1990",
    age: "36",
    cep: "01310-100",
    city: "São Paulo",
    state: "SP",
    address: "Avenida Paulista",
    neighborhood: "Bela Vista",
    streetComplement: "Apto homolog",
    number: "1578",
    referencePoint: "MASP",
    telephone: "(11) 3333-2222",
    cellphone: "(11) 95555-2222",
    email: `${RUN_ID.toLowerCase()}_${slug(label)}@cliente.test`,
  };
}

// ── Page navigation ─────────────────────────────────────────

export async function openAppPage(page: Page, key: string, title: string): Promise<void> {
  if (key === "editar-perfil") {
    await page.getByRole("button", { name: new RegExp(`${RUN_ID}`, "i") }).click();
    await page.getByRole("button", { name: "Meu Perfil", exact: true }).click();
  } else if (key === "configuracoes") {
    await page.getByRole("button", { name: new RegExp(`${RUN_ID}`, "i") }).click();
    await page.getByRole("button", { name: "Configurações", exact: true }).click();
  } else {
    const labels: Record<string, string> = {
      home: "Home",
      "cadastro-cliente": "Cliente",
      "cadastro-produto": "Produto",
      "historico-vendas": "Histórico de Vendas",
      caixa: "Abertura e Fechamento",
      fiscal: "Fiscal NFC-e / NF-e",
    };
    await page.getByRole("button", { name: labels[key] ?? key, exact: true }).click();
  }
  await expect(page.getByRole("heading", { name: title, exact: true })).toBeVisible({ timeout: 15_000 });
}
