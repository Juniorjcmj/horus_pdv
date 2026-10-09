import { test, expect, type Page } from "@playwright/test";
import type { ProductDto, ProductPayload } from "../../src/services/api/productService";

async function fillRequiredFields(page: Page) {
  await page.getByRole("textbox", { name: "Descrição do Produto *", exact: true }).fill("Arroz 5 kg");
  await page.getByRole("textbox", { name: "Valor de Custo *", exact: true }).fill("1000");
  await page.getByRole("textbox", { name: "Valor de Venda *", exact: true }).fill("1500");
}

test.describe("Cadastro com descrição, custo e venda obrigatórios", () => {
  let writes: ProductPayload[];

  test.beforeEach(async ({ page, baseURL }) => {
    writes = [];
    const products: ProductDto[] = [];
    const user = { id: "gerente-teste", companyId: "loja-teste", name: "Gerente", email: "teste@teste.invalid",
      role: "gerente", status: "ativo", createdAt: "2026-10-09T00:00:00Z", lastLoginAt: "2026-10-09T00:00:00Z", mustChangePassword: false };
    await page.route("**/*", (route) => {
      if (new URL(route.request().url()).origin === new URL(baseURL!).origin) return route.continue();
      return route.fulfill({ json: { success: true, data: [] } });
    });
    await page.route("**/api/Auth/me", (route) => route.fulfill({ json: { success: true, data: user } }));
    await page.route("**/api/Produto**", (route) => {
      if (route.request().method() === "GET") return route.fulfill({ json: { success: true, data: products } });
      const payload = route.request().postDataJSON() as ProductPayload;
      writes.push(payload);
      const saved: ProductDto = { ...payload, id: "produto-teste", productCode: payload.productCode || "P-CODIGO-GERADO" };
      products.splice(0, products.length, saved);
      return route.fulfill({ json: { success: true, data: saved } });
    });
    await page.addInitScript((operator) => {
      localStorage.setItem("horuspdv.auth.user", JSON.stringify(operator));
      localStorage.setItem("horuspdv.activePage", "cadastro-produto");
    }, user);
    await page.goto("/");
    await page.getByRole("button", { name: "Novo produto", exact: true }).click();
  });

  test("salva apenas os três campos sem fornecedor, código ou estoque inicial", async ({ page }) => {
    await fillRequiredFields(page);
    await page.getByRole("button", { name: "Criar produto", exact: true }).click();
    await expect(page.getByText("Produto cadastrado com sucesso.", { exact: true })).toBeVisible();
    expect(writes).toHaveLength(1);
    expect(writes[0]).toMatchObject({ productName: "Arroz 5 kg", productSupplier: "", productCode: "",
      productDescription: "", productQnt: "0", productUnitPrice: "10,00", productSalePrice: "15,00" });
    await expect(page.getByText("P-CODIGO-GERADO", { exact: true })).toBeVisible();
  });

  for (const field of ["Descrição do Produto *", "Valor de Custo *", "Valor de Venda *"]) {
    test(`bloqueia quando falta ${field}`, async ({ page }) => {
      await fillRequiredFields(page);
      await page.getByRole("textbox", { name: field, exact: true }).fill("");
      await page.getByRole("button", { name: "Criar produto", exact: true }).click();
      await expect(page.getByText("Preencha a descrição, o valor de custo e o valor de venda.", { exact: true })).toBeVisible();
      expect(writes).toHaveLength(0);
    });
  }

  for (const field of ["Valor de Custo *", "Valor de Venda *"]) {
    test(`bloqueia ${field} zerado`, async ({ page }) => {
      await fillRequiredFields(page);
      await page.getByRole("textbox", { name: field, exact: true }).fill("0");
      await page.getByRole("button", { name: "Criar produto", exact: true }).click();
      const message = field.includes("Custo") ? "O valor de custo deve ser maior que zero." : "O valor de venda deve ser maior que zero.";
      await expect(page.getByText(message, { exact: true })).toBeVisible();
      expect(writes).toHaveLength(0);
    });
  }

  test("permite editar produto sem fornecedor e mantém o código gerado", async ({ page }) => {
    await fillRequiredFields(page);
    await page.getByRole("button", { name: "Criar produto", exact: true }).click();
    await expect(page.getByText("Produto cadastrado com sucesso.", { exact: true })).toBeVisible();
    const row = page.getByRole("row").filter({ hasText: "P-CODIGO-GERADO" });
    await row.getByRole("button", { name: /ações/i }).click();
    await page.getByRole("button", { name: "Editar", exact: true }).click();
    await page.getByRole("textbox", { name: "Valor de Venda *", exact: true }).fill("1800");
    await page.getByRole("button", { name: "Salvar produto", exact: true }).click();
    await expect(page.getByText("Produto atualizado com sucesso.", { exact: true })).toBeVisible();
    expect(writes[1]).toMatchObject({ productSupplier: "", productCode: "P-CODIGO-GERADO", productSalePrice: "18,00" });
  });
});
