# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: B-products.spec.ts >> B — Produtos >> B01 — Venda utilizando código de barras
- Location: tests\homologation\B-products.spec.ts:66:3

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator: locator('label:has-text("Produto:") input').first()
Expected: visible
Timeout: 10000ms
Error: element(s) not found

Call log:
  - Expect "toBeVisible" with timeout 10000ms
  - waiting for locator('label:has-text("Produto:") input').first()

```

# Test source

```ts
  1   | /**
  2   |  * CHANGE 08.1 — Categoria B: Produtos — Venda por código de barras
  3   |  *
  4   |  * B01 — Venda utilizando código de barras no PDV
  5   |  *       Preparar produto com barcode/preço/estoque conhecidos.
  6   |  *       Abrir caixa, informar código, validar descrição, qty, preço, subtotal, total.
  7   |  */
  8   | import { test, expect } from "@playwright/test";
  9   | import {
  10  |   RUN_ID,
  11  |   APP_URL,
  12  |   initSqlContainer,
  13  |   registerTestCompany,
  14  |   loginApi,
  15  |   loginBrowserSession,
  16  |   cleanupHomologData,
  17  |   api,
  18  |   productPayload,
  19  |   supplierPayload,
  20  | } from "./helpers/setup";
  21  | 
  22  | type Entity = { id: string };
  23  | type Product = Entity & { productCode: string; productName: string; productSalePrice: string };
  24  | 
  25  | test.describe("B — Produtos", () => {
  26  |   test.describe.configure({ mode: "serial" });
  27  | 
  28  |   let productCode: string;
  29  |   const productLabel = "ProdutoBarcode";
  30  |   const salePrice = "25,00";
  31  |   const expectedSalePriceNum = 25;
  32  | 
  33  |   test.beforeAll(async ({ request }) => {
  34  |     initSqlContainer();
  35  |     cleanupHomologData();
  36  |     await registerTestCompany(request);
  37  |     await loginApi(request);
  38  | 
  39  |     // Criar fornecedor
  40  |     await api<Entity>(request, "/Fornecedor", {
  41  |       method: "POST",
  42  |       body: supplierPayload("Fornecedor B"),
  43  |     });
  44  | 
  45  |     // Criar produto de teste com código de barras, preço e estoque conhecidos
  46  |     const payload = productPayload(productLabel, `${RUN_ID} Fornecedor B`, "50");
  47  |     await api<Product>(request, "/Produto", { method: "POST", body: payload });
  48  |     productCode = payload.productCode;
  49  | 
  50  |     // Abrir caixa via API para poder vender
  51  |     await api(request, "/Caixa/abrir", {
  52  |       method: "POST",
  53  |       body: { openingAmount: "100,00" },
  54  |     });
  55  |   });
  56  | 
  57  |   test.afterAll(async ({ request }) => {
  58  |     await api(request, "/Caixa/fechar", {
  59  |       method: "POST",
  60  |       body: { closingAmount: "200,00", note: `${RUN_ID} cleanup B` },
  61  |       allowFailure: true,
  62  |     });
  63  |     cleanupHomologData();
  64  |   });
  65  | 
  66  |   test("B01 — Venda utilizando código de barras", async ({ page }) => {
  67  |     await loginBrowserSession(page);
  68  | 
  69  |     // Abrir PDV — navegar via localStorage (SPA navigation)
  70  |     await page.evaluate(() => {
  71  |       window.localStorage.setItem("horuspdv.activePage", "vendas");
  72  |     });
  73  |     await page.reload();
  74  |     await page.waitForLoadState("domcontentloaded");
  75  |     await page.waitForTimeout(3_000);
  76  | 
  77  |     // Localizar o campo de busca de produto (label "Produto:")
  78  |     const productInput = page.locator('label:has-text("Produto:") input').first();
> 79  |     await expect(productInput).toBeVisible({ timeout: 10_000 });
      |                                ^ Error: expect(locator).toBeVisible() failed
  80  | 
  81  |     // Informar código de barras do produto
  82  |     await productInput.fill(productCode);
  83  | 
  84  |     // Aguardar o dropdown de produtos aparecer e mostrar o produto
  85  |     const productOption = page.locator(`text=${RUN_ID} ${productLabel}`).first();
  86  |     await expect(productOption).toBeVisible({ timeout: 10_000 });
  87  | 
  88  |     // Selecionar o produto (clicar ou Enter)
  89  |     await productOption.click();
  90  | 
  91  |     // Validar que o produto foi adicionado ao carrinho
  92  |     // O carrinho mostra: nome do produto, quantidade, preço unitário, subtotal
  93  |     const cartArea = page.locator("main");
  94  | 
  95  |     // Validar descrição do produto no carrinho
  96  |     await expect(cartArea.getByText(`${RUN_ID} ${productLabel}`)).toBeVisible({
  97  |       timeout: 5_000,
  98  |     });
  99  | 
  100 |     // Validar quantidade (default: 1)
  101 |     const qtyInput = page.locator('label:has-text("Quantidade") input').first();
  102 |     // Após adicionar, o campo de quantidade pode ter voltado a "1"
  103 | 
  104 |     // Validar preço unitário exibido
  105 |     await expect(cartArea.getByText("25,00").first()).toBeVisible();
  106 | 
  107 |     // Validar total (1 x R$ 25,00 = R$ 25,00) — buscar no rodapé/resumo do carrinho
  108 |     // O subtotal é exibido no cart summary
  109 |     const totalText = cartArea.getByText("25,00");
  110 |     expect(await totalText.count()).toBeGreaterThan(0);
  111 | 
  112 |     // Verificar estado local (IndexedDB) quando possível
  113 |     const localState = await page.evaluate(async () => {
  114 |       const harness = (window as unknown as Record<string, unknown>).__horus_test__ as {
  115 |         db?: {
  116 |           products: {
  117 |             toArray: () => Promise<Array<{ productCode: string; salePrice: number }>>;
  118 |           };
  119 |         };
  120 |       };
  121 |       if (!harness?.db?.products) return null;
  122 |       const products = await harness.db.products.toArray();
  123 |       return products.length;
  124 |     });
  125 | 
  126 |     if (localState !== null) {
  127 |       expect(localState).toBeGreaterThan(0);
  128 |     }
  129 |   });
  130 | });
  131 | 
```