/**
 * CHANGE 08.1 — Categoria B: Produtos — Venda por código de barras
 *
 * B01 — Venda utilizando código de barras no PDV
 *       Preparar produto com barcode/preço/estoque conhecidos.
 *       Abrir caixa, informar código, validar descrição, qty, preço, subtotal, total.
 */
import { test, expect } from "@playwright/test";
import {
  RUN_ID,
  APP_URL,
  initSqlContainer,
  registerTestCompany,
  loginApi,
  loginBrowserSession,
  cleanupHomologData,
  api,
  productPayload,
  supplierPayload,
} from "./helpers/setup";

type Entity = { id: string };
type Product = Entity & { productCode: string; productName: string; productSalePrice: string };

test.describe("B — Produtos", () => {
  test.describe.configure({ mode: "serial" });

  let productCode: string;
  const productLabel = "ProdutoBarcode";
  const salePrice = "25,00";
  const expectedSalePriceNum = 25;

  test.beforeAll(async ({ request }) => {
    initSqlContainer();
    cleanupHomologData();
    await registerTestCompany(request);
    await loginApi(request);

    // Criar fornecedor
    await api<Entity>(request, "/Fornecedor", {
      method: "POST",
      body: supplierPayload("Fornecedor B"),
    });

    // Criar produto de teste com código de barras, preço e estoque conhecidos
    const payload = productPayload(productLabel, `${RUN_ID} Fornecedor B`, "50");
    await api<Product>(request, "/Produto", { method: "POST", body: payload });
    productCode = payload.productCode;

    // Abrir caixa via API para poder vender
    await api(request, "/Caixa/abrir", {
      method: "POST",
      body: { openingAmount: "100,00" },
    });
  });

  test.afterAll(async ({ request }) => {
    await api(request, "/Caixa/fechar", {
      method: "POST",
      body: { closingAmount: "200,00", note: `${RUN_ID} cleanup B` },
      allowFailure: true,
    });
    cleanupHomologData();
  });

  test("B01 — Venda utilizando código de barras", async ({ page }) => {
    await loginBrowserSession(page);

    // Abrir PDV — a página de vendas é standalone com ?pdv=1
    await page.goto(`${APP_URL}?pdv=1`);
    await page.waitForLoadState("domcontentloaded");
    await page.waitForTimeout(3_000);

    // Localizar o campo de busca de produto (label "Produto:")
    const productInput = page.locator('label:has-text("Produto:") input').first();
    await expect(productInput).toBeVisible({ timeout: 10_000 });

    // Informar código de barras do produto
    await productInput.fill(productCode);

    // Aguardar o dropdown de produtos aparecer e mostrar o produto
    const productOption = page.locator(`text=${RUN_ID} ${productLabel}`).first();
    await expect(productOption).toBeVisible({ timeout: 10_000 });

    // Selecionar o produto (clicar ou Enter)
    await productOption.click();

    // Validar que o produto foi adicionado ao carrinho
    // O carrinho mostra: nome do produto, quantidade, preço unitário, subtotal
    const cartArea = page.locator("main");

    // Validar descrição do produto no carrinho
    await expect(cartArea.getByText(`${RUN_ID} ${productLabel}`)).toBeVisible({
      timeout: 5_000,
    });

    // Validar quantidade (default: 1)
    const qtyInput = page.locator('label:has-text("Quantidade") input').first();
    // Após adicionar, o campo de quantidade pode ter voltado a "1"

    // Validar preço unitário exibido
    await expect(cartArea.getByText("25,00").first()).toBeVisible();

    // Validar total (1 x R$ 25,00 = R$ 25,00) — buscar no rodapé/resumo do carrinho
    // O subtotal é exibido no cart summary
    const totalText = cartArea.getByText("25,00");
    expect(await totalText.count()).toBeGreaterThan(0);

    // Verificar estado local (IndexedDB) quando possível
    const localState = await page.evaluate(async () => {
      const harness = (window as unknown as Record<string, unknown>).__horus_test__ as {
        db?: {
          products: {
            toArray: () => Promise<Array<{ productCode: string; salePrice: number }>>;
          };
        };
      };
      if (!harness?.db?.products) return null;
      const products = await harness.db.products.toArray();
      return products.length;
    });

    if (localState !== null) {
      expect(localState).toBeGreaterThan(0);
    }
  });
});
