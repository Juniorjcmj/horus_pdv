import { test, expect } from "@playwright/test";
import sql from "mssql";

const API_URL = process.env.API_URL ?? "http://localhost:5260/api";

const SQL_CONFIG: sql.config = {
  server: process.env.SQL_SERVER ?? "localhost",
  port: parseInt(process.env.SQL_PORT ?? "1433"),
  database: process.env.SQL_DATABASE ?? "HorusPdv",
  user: "sa",
  password: process.env.SQL_PASSWORD ?? "Senha@12345",
  options: { encrypt: true, trustServerCertificate: true },
};

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

async function runSql(query: string): Promise<void> {
  const pool = await sql.connect(SQL_CONFIG);
  try {
    await pool.request().query(query);
  } finally {
    await pool.close();
  }
}

test.describe("BLOCO 9 — Integracao do Fluxo Completo Offline-First", () => {
  test("Teste 31 — Fluxo integrado real: Abertura -> Venda -> Movimento -> Fechamento via IndexedDB -> Outbox -> API -> SQL Server", async ({
    page,
  }) => {
    // 1. Registra, aprova e autentica
    const runId = `ARCH9_${Date.now()}`;
    const email = `${runId.toLowerCase()}@arch9.test`;
    const password = `Teste@${Date.now().toString().slice(-6)}Aa`;
    const cnpj = generateCnpj();

    const regRes = await page.context().request.post(`${API_URL}/Auth/register`, {
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
    expect(regRes.ok(), `Register failed: ${await regRes.text()}`).toBeTruthy();

    // Aprovar empresa via SQL
    const regBody = await regRes.json();
    const companyId = regBody.data?.companyId || regBody.data?.user?.companyId;
    if (companyId) {
      await runSql(`UPDATE Empresas SET Status = N'aprovada', ReviewedAt = SYSDATETIMEOFFSET(), ReviewedBy = N'arch-test-auto' WHERE Id = N'${companyId}';`);
    } else {
      await runSql(`UPDATE Empresas SET Status = N'aprovada', ReviewedAt = SYSDATETIMEOFFSET(), ReviewedBy = N'arch-test-auto' WHERE Status = N'pendente' AND Id <> N'empresa-principal';`);
    }

    // Seed do produto FLOW-01 no SQL Server para que a venda sincronize
    const seedCompanyId = companyId || "empresa-principal";
    await runSql(`
      IF NOT EXISTS (SELECT 1 FROM Produtos WHERE ProductCode = N'FLOW-01' AND CompanyId = N'${seedCompanyId}')
      INSERT INTO Produtos (Id, CompanyId, ProductCode, ProductName, ProductUnitPrice, ProductSalePrice, ProductQnt, UnidadeComercial, Gtin)
      VALUES (NEWID(), N'${seedCompanyId}', N'FLOW-01', N'Item Fluxo Integrado', 25.0, 25.0, 1000, N'UN', N'FLOW-01')
    `);

    const loginRes = await page.context().request.post(`${API_URL}/Auth/login`, {
      data: {
        email,
        password,
        rememberMe: true,
        recaptchaToken: "arch-test",
      },
      headers: { "Content-Type": "application/json" },
    });
    expect(loginRes.ok(), `Login failed: ${await loginRes.text()}`).toBeTruthy();

    const loginBody = await loginRes.json();
    const user = loginBody.data?.user;

    // Injeta auth no localStorage do browser para que o app carregue autenticado
    await page.addInitScript((authenticatedUser) => {
      window.localStorage.setItem("horuspdv.auth.user", JSON.stringify(authenticatedUser));
      window.localStorage.setItem("horuspdv.auth.remember", "1");
    }, user);

    // 2. Carrega o app e espera o test harness
    await page.goto("/");
    await page.waitForFunction(() => !!(window as any).__horus_test__, { timeout: 15_000 });
    await page.evaluate(async () => {
      await (window as any).__horus_test__.resetDatabase();
    });

    // 3. Executa fluxo completo offline no browser
    const localFlowResult = await page.evaluate(async (testCompanyId) => {
      const {
        CashSessionRepository,
        SaleOutboxAdapter,
        db,
      } = (window as any).__horus_test__;

      // Seed do produto no IndexedDB para a venda
      await db.products.put({
        id: "prod-flow-1",
        tenantId: testCompanyId,
        barcode: "FLOW-01",
        productCode: "FLOW-01",
        productName: "Item Fluxo Integrado",
        salePrice: 25.0,
        unitPrice: 25.0,
        stock: 20,
        unit: "UN",
        categoryId: null,
        imageUrl: "",
        marca: null,
        ncm: "",
        cfop: "",
        active: true,
        controlaValidade: false,
        dataValidade: null,
        diasAlertaValidade: 0,
        updatedAt: new Date().toISOString(),
        version: 1,
      });

      // Passo 1: Abertura de Caixa Offline
      const session = await CashSessionRepository.openCashLocal("100,00", "op-1", "Operador Fluxo");

      // Passo 2: Venda Offline
      const salePayload = {
        companyId: testCompanyId,
        customerName: "Consumidor Fluxo",
        customerCpf: "",
        paymentType: "Dinheiro",
        totalAmount: "50,00",
        operatorName: "Operador Fluxo",
        items: [
          {
            productCode: "FLOW-01",
            productName: "Item Fluxo Integrado",
            quantity: 2,
            unitPrice: 25.0,
            desconto: 0,
          },
        ],
        payments: [
          {
            paymentType: "Dinheiro",
            amount: 50.0,
            cashGiven: 50.0,
            changeAmount: 0,
          },
        ],
      };
      const saleResult = await SaleOutboxAdapter.queueSaleToOutbox(salePayload);

      // Passo 3: Movimentacao (Sangria) Offline
      await CashSessionRepository.registerMovementLocal(
        "Sangria",
        "20,00",
        "Recolhimento cofre",
      );

      // Passo 4: Fechamento de Caixa Offline
      await CashSessionRepository.closeCashLocal("130,00", "Fechamento teste integrado", "Venda em dinheiro nao contabilizada no servidor de teste");

      // Verificacao antes do Sync ordenado por sequence
      const pendingEvents = await db.outbox.where("status").equals("PENDING").sortBy("sequence");
      const updatedProduct = await db.products.where("productCode").equals("FLOW-01").first();

      return {
        sessionId: session.currentSession?.id,
        saleNumber: saleResult,
        pendingCount: pendingEvents.length,
        stockAfterSale: updatedProduct?.stock,
        eventTypes: pendingEvents.map((e: any) => e.eventType),
      };
    }, seedCompanyId);

    // Validacoes do estado offline imediato
    expect(localFlowResult.sessionId).toBeTruthy();
    expect(localFlowResult.saleNumber).toBeTruthy();
    expect(localFlowResult.pendingCount).toBe(4);
    expect(localFlowResult.stockAfterSale).toBe(18); // 20 - 2 = 18
    expect(localFlowResult.eventTypes).toEqual([
      "CASH_OPEN",
      "SALE_CREATED",
      "CASH_MOVEMENT",
      "CASH_CLOSE",
    ]);

    // 4. Aciona o SyncEngine para sincronizar o lote com o backend real
    //    Retries necessarios porque o timeout padrao da API e curto (5s) e o backend
    //    em Docker pode ser lento em operacoes iniciais.
    const syncResult = await page.evaluate(async () => {
      const { syncEngine, db } = (window as any).__horus_test__;

      // Tenta sincronizar ate 3x para cobrir timeouts esporadicos
      for (let attempt = 0; attempt < 3; attempt++) {
        await syncEngine.syncNow();
        const remaining = await db.outbox.where("status").anyOf(["PENDING", "PROCESSING"]).count();
        if (remaining === 0) break;
        // Espera breve antes de re-tentar
        await new Promise((r) => setTimeout(r, 1000));
      }

      // Consulta estado apos a sincronizacao
      const processedEvents = await db.outbox.where("status").equals("PROCESSED").toArray();
      const pendingRemaining = await db.outbox.where("status").equals("PENDING").toArray();
      const failedEvents = await db.outbox.where("status").equals("FAILED").toArray();
      const deduplicatedRecords = await db.processedEvents.toArray();

      return {
        processedCount: processedEvents.length,
        pendingRemainingCount: pendingRemaining.length,
        failedCount: failedEvents.length,
        failedErrors: failedEvents.map((e: any) => e.lastError),
        failedTypes: failedEvents.map((e: any) => e.eventType),
        pendingTypes: pendingRemaining.map((e: any) => e.eventType),
        pendingErrors: pendingRemaining.map((e: any) => e.lastError),
        dedupCount: deduplicatedRecords.length,
      };
    });

    // Validacoes pos-sincronizacao
    expect(syncResult.pendingRemainingCount, `Pending: ${JSON.stringify({ types: syncResult.pendingTypes, errors: syncResult.pendingErrors })} | Failed: ${JSON.stringify({ types: syncResult.failedTypes, errors: syncResult.failedErrors })}`).toBe(0);
    expect(syncResult.processedCount).toBe(4);
    expect(syncResult.dedupCount).toBe(4);
  });
});
