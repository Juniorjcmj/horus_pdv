import { test, expect, type Page } from "@playwright/test";

/**
 * Mesmo computador usado por duas empresas: as pendências (vendas/caixa offline) de uma nunca podem ser
 * enviadas com o login da outra; ficam guardadas até alguém da empresa dona entrar.
 */
const login = (page: Page, companyId: string) =>
  page.evaluate((id) => {
    localStorage.setItem("horuspdv.auth.user", JSON.stringify({ id: `usr-${id}`, name: "Op", role: "gerente", companyId: id }));
  }, companyId);

const sale = (eventId: string, paymentType = "dinheiro", customerCpf = "") => ({
  eventId, clientSaleId: `cs-${eventId}`, operatorId: "usr-1", customerName: "Cliente", customerCpf, paymentType,
  totalAmount: "20,00", operatorName: "Op", payloadHash: `h-${eventId}`,
  items: [{ productCode: "P1", productName: "Arroz", quantity: 1, unitPrice: 20, desconto: 0, itemTotal: 20 }],
  payments: [{ paymentType, amount: 20, cashGiven: null, changeAmount: null }],
});

async function prepare(page: Page) {
  await page.goto("/");
  await page.waitForFunction(() => (window as any).__horus_test__ !== undefined);
  await page.evaluate(async () => {
    await (window as any).__horus_test__.resetDatabase();
  });
}

test.describe("Mesmo computador, duas empresas", () => {
  test("pendência guarda a empresa e só aparece/vai para a nuvem com o login dela", async ({ page }) => {
    await prepare(page);
    await login(page, "empresa-A");
    const r = await page.evaluate(async (s) => {
      const { SaleOutboxAdapter, OutboxRepository, db } = (window as any).__horus_test__;
      await SaleOutboxAdapter.queueSaleToOutbox(s);
      const tenant = (await db.outbox.get("ev-A1"))?.tenantId;
      const comA = { pend: (await OutboxRepository.getPendingEvents()).length, count: await OutboxRepository.getPendingCount() };
      localStorage.setItem("horuspdv.auth.user", JSON.stringify({ id: "usr-B", name: "Op", role: "gerente", companyId: "empresa-B" }));
      const comB = {
        pend: (await OutboxRepository.getPendingEvents()).length,
        fwd: (await OutboxRepository.getForwardableEvents()).length,
        count: await OutboxRepository.getPendingCount(),
      };
      localStorage.setItem("horuspdv.auth.user", JSON.stringify({ id: "usr-A", name: "Op", role: "gerente", companyId: "empresa-A" }));
      const deVoltaA = (await OutboxRepository.getPendingEvents()).map((e: any) => e.id);
      return { tenant, comA, comB, deVoltaA };
    }, sale("ev-A1"));

    expect(r.tenant).toBe("empresa-A");
    expect(r.comA).toEqual({ pend: 1, count: 1 });
    expect(r.comB).toEqual({ pend: 0, fwd: 0, count: 0 });
    expect(r.deVoltaA).toEqual(["ev-A1"]);
  });

  test("com o login da B, o envio à nuvem não manda a venda da A", async ({ page }) => {
    await prepare(page);
    await login(page, "empresa-A");
    await page.evaluate(async (s) => {
      await (window as any).__horus_test__.SaleOutboxAdapter.queueSaleToOutbox(s);
    }, sale("ev-A2"));
    await login(page, "empresa-B");
    await page.evaluate(async (s) => {
      await (window as any).__horus_test__.SaleOutboxAdapter.queueSaleToOutbox(s);
    }, sale("ev-B1"));

    const enviados: string[] = [];
    await page.route("**/api/HistoricoVendas**", async (route) => {
      if (route.request().method() === "POST") enviados.push(route.request().postDataJSON()?.eventId);
      return route.fulfill({ json: { success: true, data: { saleNumber: "1" } } });
    });
    const status = await page.evaluate(async () => {
      const { syncEngine, db } = (window as any).__horus_test__;
      const { connectivityService } = await import("/src/infrastructure/synchronization/ConnectivityService.ts" as string);
      (connectivityService as any).isOnline = () => true;
      await syncEngine.syncNow();
      return { a: (await db.outbox.get("ev-A2"))?.status, b: (await db.outbox.get("ev-B1"))?.status };
    });
    // Controle: a venda da B (logada) foi enviada; a da A ficou guardada.
    expect(enviados).toContain("ev-B1");
    expect(enviados).not.toContain("ev-A2");
    expect(status).toEqual({ a: "PENDING", b: "PROCESSED" });
  });

  test("pendência antiga (sem empresa) é adotada pela empresa logada", async ({ page }) => {
    await prepare(page);
    await login(page, "empresa-A");
    const r = await page.evaluate(async () => {
      const { OutboxRepository, db } = (window as any).__horus_test__;
      await db.outbox.put({
        id: "ev-antigo", deviceId: "d", tenantId: "", storeId: "", eventType: "SALE_CREATED", aggregateType: "Sale",
        aggregateId: "s-1", payload: "{}", sequence: 1, occurredAt: new Date().toISOString(), createdAt: new Date().toISOString(),
        status: "PENDING", retryCount: 0, lastAttemptAt: null, lastError: null,
      });
      const antes = (await OutboxRepository.getPendingEvents()).length; // sem empresa: continua visível
      const adotadas = await OutboxRepository.adoptUntaggedEvents();
      const tenant = (await db.outbox.get("ev-antigo"))?.tenantId;
      localStorage.setItem("horuspdv.auth.user", JSON.stringify({ id: "usr-B", name: "Op", role: "gerente", companyId: "empresa-B" }));
      const comB = (await OutboxRepository.getPendingEvents()).length;
      return { antes, adotadas, tenant, comB };
    });
    expect(r).toEqual({ antes: 1, adotadas: 1, tenant: "empresa-A", comB: 0 });
  });

  test("fiado offline da A não entra no saldo do cliente na B", async ({ page }) => {
    await prepare(page);
    await login(page, "empresa-A");
    await page.evaluate(async (s) => {
      await (window as any).__horus_test__.SaleOutboxAdapter.queueSaleToOutbox(s);
    }, sale("ev-fiado-A", "fiado", "111.222.333-44"));
    await login(page, "empresa-B");

    const saldo = await page.evaluate(async () => {
      const { CustomerSyncAdapter, customerService, db } = (window as any).__horus_test__;
      const original = customerService.list;
      customerService.list = async () => [{ id: "cli-B", customerName: "Cliente", document: "111.222.333-44", limiteCredito: 100, saldoDevedor: 5 }];
      try {
        await CustomerSyncAdapter.syncCustomersFromApi();
      } finally {
        customerService.list = original;
      }
      return (await db.customers.toArray())[0]?.saldoDevedor;
    });
    expect(saldo).toBe(5);
  });

  test("trocar de empresa limpa catálogo, clientes e caixa locais, mas guarda as pendências", async ({ page }) => {
    await prepare(page);
    const r = await page.evaluate(async () => {
      const { db } = (window as any).__horus_test__;
      const mod = await import("/src/infrastructure/database/localTenant.ts" as string);
      const primeira = await mod.ensureLocalTenant("empresa-A"); // primeira vez: só registra
      await db.products.put({ id: "p1", tenantId: "", barcode: "1", productCode: "P1", categoryId: "", updatedAt: "" });
      await db.customers.put({ id: "c1", tenantId: "", cpfCnpj: "1", updatedAt: "" });
      await db.outbox.put({
        id: "ev-guardado", deviceId: "d", tenantId: "empresa-A", storeId: "", eventType: "SALE_CREATED", aggregateType: "Sale",
        aggregateId: "s-1", payload: "{}", sequence: 1, occurredAt: "", createdAt: "", status: "PENDING", retryCount: 0,
        lastAttemptAt: null, lastError: null,
      });
      const mesma = await mod.ensureLocalTenant("empresa-A");
      const produtosMesma = await db.products.count();
      const outra = await mod.ensureLocalTenant("empresa-B");
      return {
        primeira, mesma, produtosMesma, outra,
        produtos: await db.products.count(), clientes: await db.customers.count(),
        pendencia: (await db.outbox.get("ev-guardado"))?.status,
      };
    });
    expect(r).toEqual({ primeira: false, mesma: false, produtosMesma: 1, outra: true, produtos: 0, clientes: 0, pendencia: "PENDING" });
  });
});
