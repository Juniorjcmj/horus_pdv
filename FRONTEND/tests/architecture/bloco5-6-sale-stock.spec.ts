import { test, expect } from "@playwright/test";

test.describe("BLOCOS 5 e 6 — Venda Offline Atômica, Estoque e Pull Sync", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/");
    await page.waitForFunction(() => (window as any).__horus_test__ !== undefined);
    await page.evaluate(async () => {
      const { resetDatabase, db } = (window as any).__horus_test__;
      await resetDatabase();

      // Cadastra produto base para os testes de estoque
      await db.products.put({
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
      });
    });
  });

  test("Teste 19 — Venda completa: queueSaleToOutbox persiste atomicamente nas 6 entidades", async ({ page }) => {
    const result = await page.evaluate(async () => {
      const { SaleOutboxAdapter, db } = (window as any).__horus_test__;

      const saleNumber = await SaleOutboxAdapter.queueSaleToOutbox({
        customerName: "Consumidor Teste",
        customerCpf: "123.456.789-00",
        paymentType: "dinheiro",
        totalAmount: "20,00",
        operatorName: "Operador 1",
        items: [
          {
            productCode: "P001",
            productName: "Coca-Cola 2L",
            quantity: 2,
            unitPrice: 10,
            desconto: 0,
          },
        ],
        payments: [
          {
            paymentType: "dinheiro",
            amount: 20,
            cashGiven: 20,
            changeAmount: 0,
          },
        ],
      });

      const salesCount = await db.sales.count();
      const itemsCount = await db.saleItems.count();
      const paymentsCount = await db.payments.count();
      const movementsCount = await db.stockMovements.count();
      const outboxCount = await db.outbox.count();
      const product = await db.products.where("productCode").equals("P001").first();

      return {
        saleNumber,
        salesCount,
        itemsCount,
        paymentsCount,
        movementsCount,
        outboxCount,
        remainingStock: product?.stock,
      };
    });

    expect(result.saleNumber).toContain("OFF-");
    expect(result.salesCount).toBe(1);
    expect(result.itemsCount).toBe(1);
    expect(result.paymentsCount).toBe(1);
    expect(result.movementsCount).toBe(1);
    expect(result.outboxCount).toBe(1);
    expect(result.remainingStock).toBe(18); // 20 - 2
  });

  test("Teste 20 — Rollback da venda: falha provocada na transação impede gravação parcial", async ({ page }) => {
    const result = await page.evaluate(async () => {
      const { db } = (window as any).__horus_test__;

      let caughtError = "";
      try {
        await db.transaction(
          "rw",
          [db.sales, db.saleItems, db.payments, db.stockMovements, db.products, db.outbox],
          async () => {
            // 1. Grava venda
            await db.sales.put({
              id: "sale-abort",
              deviceId: "dev-1",
              tenantId: "t-1",
              sessionId: "sess-1",
              saleNumber: "OFF-FAIL",
              customerId: null,
              customerName: "Abort",
              totalAmount: 100,
              status: "COMPLETED",
              createdAt: new Date().toISOString(),
            });

            // 2. Grava item
            await db.saleItems.put({
              id: "item-abort",
              saleId: "sale-abort",
              productId: "prod-1",
              productCode: "P001",
              productName: "Coca",
              quantity: 5,
              unitPrice: 10,
              discount: 0,
              total: 50,
            });

            // 3. Força exceção antes de completar
            throw new Error("Falha intencional na transação de venda!");
          },
        );
      } catch (err: any) {
        caughtError = err.message;
      }

      const sales = await db.sales.count();
      const items = await db.saleItems.count();
      const outbox = await db.outbox.count();
      const product = await db.products.where("productCode").equals("P001").first();

      return {
        caughtError,
        sales,
        items,
        outbox,
        stock: product?.stock,
      };
    });

    expect(result.caughtError).toBe("Falha intencional na transação de venda!");
    expect(result.sales).toBe(0);
    expect(result.items).toBe(0);
    expect(result.outbox).toBe(0);
    expect(result.stock).toBe(20); // Estoque permaneceu intacto
  });

  test("Teste 21 — Venda offline reduz estoque local imediatamente", async ({ page }) => {
    const result = await page.evaluate(async () => {
      const { SaleOutboxAdapter, db } = (window as any).__horus_test__;

      await SaleOutboxAdapter.queueSaleToOutbox({
        totalAmount: "30,00",
        items: [{ productCode: "P001", productName: "Coca-Cola 2L", quantity: 3, unitPrice: 10, desconto: 0 }],
        payments: [{ paymentType: "dinheiro", amount: 30, cashGiven: 30, changeAmount: 0 }],
      });

      const product = await db.products.where("productCode").equals("P001").first();
      return { stock: product?.stock };
    });

    expect(result.stock).toBe(17); // 20 - 3
  });

  test("Teste 22 — Pull Sync com venda pendente: protege estoque local deduzindo vendas em PENDING", async ({ page }) => {
    // Intercepta a chamada da API de produtos simulando o retorno do servidor com estoque 20
    await page.route("**/api/Produto**", async (route) => {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          success: true,
          data: [
            {
              id: "prod-1",
              productCode: "P001",
              gtin: "789123456001",
              productName: "Coca-Cola 2L",
              productSalePrice: "10,00",
              productUnitPrice: "6,00",
              productQnt: "20,00",
              unidadeComercial: "UN",
              categoriaId: null,
              ativo: true,
            },
          ],
        }),
      });
    });

    const result = await page.evaluate(async () => {
      const { SaleOutboxAdapter, ProductSyncAdapter, db } = (window as any).__horus_test__;

      // 1. Cria venda offline de 4 unidades (Outbox status = PENDING)
      await SaleOutboxAdapter.queueSaleToOutbox({
        totalAmount: "40,00",
        items: [{ productCode: "P001", productName: "Coca-Cola 2L", quantity: 4, unitPrice: 10, desconto: 0 }],
        payments: [{ paymentType: "dinheiro", amount: 40, cashGiven: 40, changeAmount: 0 }],
      });

      // 2. Executa o Pull Sync
      await ProductSyncAdapter.syncProductsFromApi();

      // 3. Verifica se o estoque local gravado protegeu a dedução pendente (20 - 4 = 16)
      const product = await db.products.where("productCode").equals("P001").first();
      return { stock: product?.stock };
    });

    expect(result.stock).toBe(16);
  });

  test("Teste 23 — PROCESSING também protege estoque: Pull Sync mantém dedução para eventos em trânsito", async ({ page }) => {
    await page.route("**/api/Produto**", async (route) => {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          success: true,
          data: [
            {
              id: "prod-1",
              productCode: "P001",
              gtin: "789123456001",
              productName: "Coca-Cola 2L",
              productSalePrice: "10,00",
              productUnitPrice: "6,00",
              productQnt: "20,00",
              unidadeComercial: "UN",
              categoriaId: null,
              ativo: true,
            },
          ],
        }),
      });
    });

    const result = await page.evaluate(async () => {
      const { SaleOutboxAdapter, OutboxRepository, ProductSyncAdapter, db } = (window as any).__horus_test__;

      // 1. Cria venda de 5 unidades
      await SaleOutboxAdapter.queueSaleToOutbox({
        totalAmount: "50,00",
        items: [{ productCode: "P001", productName: "Coca-Cola 2L", quantity: 5, unitPrice: 10, desconto: 0 }],
        payments: [{ paymentType: "dinheiro", amount: 50, cashGiven: 50, changeAmount: 0 }],
      });

      // 2. Coloca o evento em PROCESSING
      const event = await db.outbox.orderBy("sequence").last();
      await OutboxRepository.markProcessing(event.id);

      // 3. Executa Pull Sync
      await ProductSyncAdapter.syncProductsFromApi();

      // 4. Confirma que a dedução foi preservada: 20 - 5 = 15
      const product = await db.products.where("productCode").equals("P001").first();
      return { stock: product?.stock };
    });

    expect(result.stock).toBe(15);
  });
});
