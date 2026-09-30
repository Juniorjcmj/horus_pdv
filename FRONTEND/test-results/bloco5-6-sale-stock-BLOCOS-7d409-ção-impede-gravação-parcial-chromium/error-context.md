# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: bloco5-6-sale-stock.spec.ts >> BLOCOS 5 e 6 — Venda Offline Atômica, Estoque e Pull Sync >> Teste 20 — Rollback da venda: falha provocada na transação impede gravação parcial
- Location: tests\architecture\bloco5-6-sale-stock.spec.ts:93:3

# Error details

```
Test timeout of 60000ms exceeded while running "beforeEach" hook.
```

```
Error: page.waitForFunction: Test timeout of 60000ms exceeded.
```

# Test source

```ts
  1   | import { test, expect } from "@playwright/test";
  2   | 
  3   | test.describe("BLOCOS 5 e 6 — Venda Offline Atômica, Estoque e Pull Sync", () => {
  4   |   test.beforeEach(async ({ page }) => {
  5   |     await page.goto("/");
> 6   |     await page.waitForFunction(() => (window as any).__horus_test__ !== undefined);
      |                ^ Error: page.waitForFunction: Test timeout of 60000ms exceeded.
  7   |     await page.evaluate(async () => {
  8   |       const { resetDatabase, db } = (window as any).__horus_test__;
  9   |       await resetDatabase();
  10  | 
  11  |       // Cadastra produto base para os testes de estoque
  12  |       await db.products.put({
  13  |         id: "prod-1",
  14  |         tenantId: "t-1",
  15  |         barcode: "789123456001",
  16  |         productCode: "P001",
  17  |         productName: "Coca-Cola 2L",
  18  |         salePrice: 10,
  19  |         unitPrice: 10,
  20  |         stock: 20,
  21  |         unit: "UN",
  22  |         categoryId: null,
  23  |         imageUrl: "",
  24  |         marca: null,
  25  |         ncm: "22021000",
  26  |         cfop: "5102",
  27  |         active: true,
  28  |         controlaValidade: false,
  29  |         dataValidade: null,
  30  |         diasAlertaValidade: 0,
  31  |         updatedAt: new Date().toISOString(),
  32  |         version: 1,
  33  |       });
  34  |     });
  35  |   });
  36  | 
  37  |   test("Teste 19 — Venda completa: queueSaleToOutbox persiste atomicamente nas 6 entidades", async ({ page }) => {
  38  |     const result = await page.evaluate(async () => {
  39  |       const { SaleOutboxAdapter, db } = (window as any).__horus_test__;
  40  | 
  41  |       const saleNumber = await SaleOutboxAdapter.queueSaleToOutbox({
  42  |         customerName: "Consumidor Teste",
  43  |         customerCpf: "123.456.789-00",
  44  |         paymentType: "dinheiro",
  45  |         totalAmount: "20,00",
  46  |         operatorName: "Operador 1",
  47  |         items: [
  48  |           {
  49  |             productCode: "P001",
  50  |             productName: "Coca-Cola 2L",
  51  |             quantity: 2,
  52  |             unitPrice: 10,
  53  |             desconto: 0,
  54  |           },
  55  |         ],
  56  |         payments: [
  57  |           {
  58  |             paymentType: "dinheiro",
  59  |             amount: 20,
  60  |             cashGiven: 20,
  61  |             changeAmount: 0,
  62  |           },
  63  |         ],
  64  |       });
  65  | 
  66  |       const salesCount = await db.sales.count();
  67  |       const itemsCount = await db.saleItems.count();
  68  |       const paymentsCount = await db.payments.count();
  69  |       const movementsCount = await db.stockMovements.count();
  70  |       const outboxCount = await db.outbox.count();
  71  |       const product = await db.products.where("productCode").equals("P001").first();
  72  | 
  73  |       return {
  74  |         saleNumber,
  75  |         salesCount,
  76  |         itemsCount,
  77  |         paymentsCount,
  78  |         movementsCount,
  79  |         outboxCount,
  80  |         remainingStock: product?.stock,
  81  |       };
  82  |     });
  83  | 
  84  |     expect(result.saleNumber).toContain("OFF-");
  85  |     expect(result.salesCount).toBe(1);
  86  |     expect(result.itemsCount).toBe(1);
  87  |     expect(result.paymentsCount).toBe(1);
  88  |     expect(result.movementsCount).toBe(1);
  89  |     expect(result.outboxCount).toBe(1);
  90  |     expect(result.remainingStock).toBe(18); // 20 - 2
  91  |   });
  92  | 
  93  |   test("Teste 20 — Rollback da venda: falha provocada na transação impede gravação parcial", async ({ page }) => {
  94  |     const result = await page.evaluate(async () => {
  95  |       const { db } = (window as any).__horus_test__;
  96  | 
  97  |       let caughtError = "";
  98  |       try {
  99  |         await db.transaction(
  100 |           "rw",
  101 |           [db.sales, db.saleItems, db.payments, db.stockMovements, db.products, db.outbox],
  102 |           async () => {
  103 |             // 1. Grava venda
  104 |             await db.sales.put({
  105 |               id: "sale-abort",
  106 |               deviceId: "dev-1",
```