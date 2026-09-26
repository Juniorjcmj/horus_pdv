/**
 * Helper de autenticacao para testes de arquitetura que chamam a API diretamente.
 * Cria uma conta de teste, aprova via SQL direto e faz login.
 */
import sql from "mssql";
import { type APIRequestContext } from "@playwright/test";

const API_URL = process.env.API_URL ?? "http://localhost:5260/api";

const SQL_CONFIG: sql.config = {
  server: process.env.SQL_SERVER ?? "localhost",
  port: parseInt(process.env.SQL_PORT ?? "1433"),
  database: process.env.SQL_DATABASE ?? "HorusPdv",
  user: "sa",
  password: process.env.SQL_PASSWORD ?? "Senha@12345",
  options: {
    encrypt: true,
    trustServerCertificate: true,
  },
};

/** Gera CNPJ fake valido (apenas digitos). */
function generateCnpj(): string {
  const n = () => Math.floor(Math.random() * 9);
  const digits = [n(), n(), n(), n(), n(), n(), n(), n(), 0, 0, 0, 1];

  const calc = (d: number[], w: number[]): number => {
    const sum = d.reduce((s, v, i) => s + v * w[i], 0);
    const r = sum % 11;
    return r < 2 ? 0 : 11 - r;
  };

  digits.push(calc(digits, [5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]));
  digits.push(calc(digits, [6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]));
  return digits.join("");
}

/** Executa SQL diretamente no banco via mssql (tedious). */
async function runSql(query: string): Promise<void> {
  const pool = await sql.connect(SQL_CONFIG);
  try {
    await pool.request().query(query);
  } finally {
    await pool.close();
  }
}

export interface AuthSession {
  companyId: string;
  userId: string;
  email: string;
  password: string;
}

/**
 * Faz login com credenciais existentes (sem registrar).
 * Util para re-autenticar em cada test quando o request fixture e test-scoped.
 */
export async function loginOnly(
  request: APIRequestContext,
  email: string,
  password: string,
): Promise<void> {
  const loginRes = await request.post(`${API_URL}/Auth/login`, {
    data: { email, password, rememberMe: true, recaptchaToken: "arch-test" },
    headers: { "Content-Type": "application/json" },
  });
  if (!loginRes.ok()) {
    const raw = await loginRes.text();
    throw new Error(`Login failed (${loginRes.status()}): ${raw}`);
  }
}

/**
 * Registra uma empresa de teste, aprova via SQL e faz login.
 * O `request` context do Playwright persiste o cookie HttpOnly automaticamente.
 */
export async function registerAndLogin(request: APIRequestContext): Promise<AuthSession> {
  const runId = `ARCH_${Date.now()}`;
  const email = `${runId.toLowerCase()}@arch.test`;
  const password = `Teste@${Date.now().toString().slice(-6)}Aa`;
  const cnpj = generateCnpj();

  // 1. Cadastro publico
  const regRes = await request.post(`${API_URL}/Auth/register`, {
    data: {
      cnpj,
      name: `${runId} Empresa Teste`,
      email,
      phone: "(11) 99999-0000",
      password,
      confirmPassword: password,
      recaptchaToken: "arch-test",
    },
    headers: { "Content-Type": "application/json" },
  });

  if (!regRes.ok()) {
    const raw = await regRes.text();
    throw new Error(`Register failed (${regRes.status()}): ${raw}`);
  }

  // 2. Aprovar a empresa via SQL direto
  const regBody = await regRes.json();
  const companyId = regBody.data?.companyId || regBody.data?.user?.companyId;
  if (companyId) {
    await runSql(`UPDATE Empresas SET Status = N'aprovada', ReviewedAt = SYSDATETIMEOFFSET(), ReviewedBy = N'arch-test-auto' WHERE Id = N'${companyId}'`);
  } else {
    await runSql(`UPDATE Empresas SET Status = N'aprovada', ReviewedAt = SYSDATETIMEOFFSET(), ReviewedBy = N'arch-test-auto' WHERE Status = N'pendente' AND Id <> N'empresa-principal'`);
  }

  // 3. Login (persiste cookie no request context)
  const loginRes = await request.post(`${API_URL}/Auth/login`, {
    data: {
      email,
      password,
      rememberMe: true,
      recaptchaToken: "arch-test",
    },
    headers: { "Content-Type": "application/json" },
  });

  if (!loginRes.ok()) {
    const raw = await loginRes.text();
    throw new Error(`Login failed (${loginRes.status()}): ${raw}`);
  }

  const body = await loginRes.json();
  return {
    companyId: body.data?.user?.companyId ?? "",
    userId: body.data?.user?.id ?? "",
    email,
    password,
  };
}

/**
 * Cria produtos de teste no banco para a empresa indicada.
 * Os testes de idempotencia usam PROD-TEST-01, PROD-TEST-02, PROD-03.
 */
export async function seedTestProducts(companyId: string): Promise<void> {
  const products = [
    { code: "PROD-TEST-01", name: "Produto Teste 1", price: 100.0 },
    { code: "PROD-TEST-02", name: "Produto Replay", price: 50.0 },
    { code: "PROD-03", name: "Produto Original", price: 75.0 },
  ];
  const values = products
    .map(
      (p) =>
        `(NEWID(), N'${companyId}', N'${p.code}', N'${p.name}', ${p.price}, ${p.price}, 1000, N'UN', N'${p.code}')`,
    )
    .join(",\n");
  await runSql(`
    IF NOT EXISTS (SELECT 1 FROM Produtos WHERE ProductCode = N'PROD-TEST-01' AND CompanyId = N'${companyId}')
    INSERT INTO Produtos (Id, CompanyId, ProductCode, ProductName, ProductUnitPrice, ProductSalePrice, ProductQnt, UnidadeComercial, Gtin)
    VALUES ${values}
  `);
}

/**
 * Abre o caixa via API (necessario para vendas e movimentos).
 */
export async function openCashRegister(request: APIRequestContext): Promise<void> {
  const res = await request.post(`${API_URL}/Caixa/abrir`, {
    data: { openingAmount: "100,00" },
    headers: { "Content-Type": "application/json" },
  });

  // Ignora "ja existe caixa aberto" — idempotente
  if (!res.ok()) {
    const raw = await res.text();
    if (!raw.includes("aberto")) {
      throw new Error(`Open cash failed (${res.status()}): ${raw}`);
    }
  }
}
