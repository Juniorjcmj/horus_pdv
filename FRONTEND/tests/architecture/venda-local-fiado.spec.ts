import { test, expect } from "@playwright/test";

const CUSTOMER = {
  id: "cli-1",
  tenantId: "",
  name: "Maria Fiado",
  cpfCnpj: "111.222.333-44",
  phone: "",
  email: "",
  limiteCredito: 100,
  saldoDevedor: 30,
  updatedAt: new Date().toISOString(),
  version: 1,
};

const PRODUCT = {
  id: "prod-1",
  tenantId: "t-1",
  barcode: "789123456001",
  productCode: "P001",
  productName: "Coca-Cola 2L",
  salePrice: 10,
  unitPrice: 10,
  stock: 20,
  unit: "UN",
  categoryId: null,
  imageUrl: "",
  marca: null,
  ncm: "22021000",
  cfop: "5102",
  active: true,
  controlaValidade: false,
  dataValidade: null,
  diasAlertaValidade: 0,
  updatedAt: new Date().toISOString(),
  version: 1,
};

function salePayload(paymentType: string, amount: number) {
  return {
    customerName: CUSTOMER.name,
    customerCpf: CUSTOMER.cpfCnpj,
    paymentType,
    totalAmount: amount.toFixed(2).replace(".", ","),
    operatorName: "Operador 1",
    items: [{ productCode: "P001", productName: "Coca-Cola 2L", quantity: 2, unitPrice: amount / 2, desconto: 0 }],
    payments: [{ paymentType, amount, cashGiven: null, changeAmount: null }],
    payloadHash: "",
  };
}

test.describe("Venda sempre no IndexedDB e saldo do fiado protegido", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/");
    await page.waitForFunction(() => (window as any).__horus_test__ !== undefined);
    await page.evaluate(
      async ({ customer, product }) => {
        const { resetDatabase, db } = (window as any).__horus_test__;
        await resetDatabase();
        await db.customers.put(customer);
        await db.products.put(product);
      },
      { customer: CUSTOMER, product: PRODUCT },
    );
  });

  test("Venda online é gravada no IndexedDB sem entrar no outbox e sem baixar estoque local", async ({ page }) => {
    const result = await page.evaluate(async (payload) => {
      const { SaleOutboxAdapter, db } = (window as any).__horus_test__;
      await SaleOutboxAdapter.saveSyncedSaleLocally(payload, "V-1001");
      const sale = await db.sales.where("saleNumber").equals("V-1001").first();
      return {
        origin: sale?.origin,
        items: sale ? await db.saleItems.where("saleId").equals(sale.id).count() : 0,
        payments: sale ? await db.payments.where("saleId").equals(sale.id).count() : 0,
        outbox: await db.outbox.count(),
        stock: (await db.products.get("prod-1"))?.stock,
        saldo: (await db.customers.get("cli-1"))?.saldoDevedor,
      };
    }, salePayload("dinheiro", 20));

    expect(result.origin).toBe("ONLINE");
    expect(result.items).toBe(1);
    expect(result.payments).toBe(1);
    expect(result.outbox).toBe(0);
    expect(result.stock).toBe(20);
    expect(result.saldo).toBe(30);
  });

  test("Venda fiado offline soma o saldo devedor no IndexedDB e enfileira no outbox", async ({ page }) => {
    const result = await page.evaluate(async (payload) => {
      const { SaleOutboxAdapter, CustomerSyncAdapter, db } = (window as any).__horus_test__;
      await SaleOutboxAdapter.queueSaleToOutbox(payload);
      return {
        outbox: await db.outbox.count(),
        stock: (await db.products.get("prod-1"))?.stock,
        saldo: await CustomerSyncAdapter.getLocalCustomerDebt("11122233344"),
      };
    }, salePayload("fiado", 50));

    expect(result.outbox).toBe(1);
    expect(result.stock).toBe(18);
    expect(result.saldo).toBe(80);
  });

  test("Sync de clientes mantém o fiado offline pendente somado ao saldo do servidor", async ({ page }) => {
    const result = await page.evaluate(async (payload) => {
      const { SaleOutboxAdapter, CustomerSyncAdapter, customerService, db } = (window as any).__horus_test__;
      await SaleOutboxAdapter.queueSaleToOutbox(payload);

      // Servidor ainda não recebeu a venda offline: devolve o saldo antigo (30)
      const originalList = customerService.list;
      customerService.list = async () => [
        { id: "cli-1", customerName: "Maria Fiado", document: "111.222.333-44", limiteCredito: 100, saldoDevedor: 30 },
      ];
      try {
        await CustomerSyncAdapter.syncCustomersFromApi();
        const comPendente = (await db.customers.get("cli-1"))?.saldoDevedor;

        // Venda sincronizada: servidor já devolve 80 e o outbox fica PROCESSED — não pode somar de novo
        const evt = (await db.outbox.toArray())[0];
        await db.outbox.update(evt.id, { status: "PROCESSED" });
        customerService.list = async () => [
          { id: "cli-1", customerName: "Maria Fiado", document: "111.222.333-44", limiteCredito: 100, saldoDevedor: 80 },
        ];
        await CustomerSyncAdapter.syncCustomersFromApi();
        const aposSync = (await db.customers.get("cli-1"))?.saldoDevedor;
        return { comPendente, aposSync };
      } finally {
        customerService.list = originalList;
      }
    }, salePayload("fiado", 50));

    expect(result.comPendente).toBe(80);
    expect(result.aposSync).toBe(80);
  });

  test("Pede armazenamento persistente ao navegador sem lançar erro", async ({ page }) => {
    const status = await page.evaluate(async () => {
      const { persistentStorage } = (window as any).__horus_test__;
      return persistentStorage.requestPersistentStorage();
    });
    expect(["granted", "denied", "unsupported"]).toContain(status);
  });

  test("getOldestUnsyncedAt devolve a pendência mais antiga e null com a fila limpa", async ({ page }) => {
    const result = await page.evaluate(async (payload) => {
      const { SaleOutboxAdapter, OutboxRepository, db } = (window as any).__horus_test__;
      const vazio = await OutboxRepository.getOldestUnsyncedAt();
      await SaleOutboxAdapter.queueSaleToOutbox({ ...payload, occurredAt: "2026-10-05T08:00:00.000Z" });
      await SaleOutboxAdapter.queueSaleToOutbox({ ...payload, occurredAt: "2026-10-05T10:00:00.000Z" });
      const maisAntiga = await OutboxRepository.getOldestUnsyncedAt();
      for (const evt of await db.outbox.toArray()) await db.outbox.update(evt.id, { status: "PROCESSED" });
      const aposSync = await OutboxRepository.getOldestUnsyncedAt();
      return { vazio, maisAntiga, aposSync };
    }, salePayload("dinheiro", 20));

    expect(result.vazio).toBeNull();
    expect(result.maisAntiga).toBe("2026-10-05T08:00:00.000Z");
    expect(result.aposSync).toBeNull();
  });
});
