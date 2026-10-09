import { test, expect, type Page } from "@playwright/test";
import type { CustomerDto, CustomerPayload } from "../../src/services/api/customerService";

async function fillMinimum(page: Page, phoneField = "Telefone *", number = "1133334444", name = "Maria Silva") {
  await page.getByRole("textbox", { name: "Nome *", exact: true }).fill(name);
  await page.getByRole("textbox", { name: phoneField, exact: true }).fill(number);
}

test.describe("Cliente com nome e telefone obrigatórios", () => {
  let writes: CustomerPayload[];
  let customers: CustomerDto[];
  test.beforeEach(async ({ page, baseURL }) => {
    writes = [];
    customers = [];
    const user = { id: "admin-teste", companyId: "loja-teste", name: "Administrador", email: "teste@teste.invalid",
      role: "administrador", status: "ativo", createdAt: "2026-10-09T00:00:00Z", lastLoginAt: "2026-10-09T00:00:00Z", mustChangePassword: false };
    await page.route("**/*", route => {
      if (new URL(route.request().url()).origin === new URL(baseURL!).origin) return route.continue();
      return route.fulfill({ json: { success: true, data: [] } });
    });
    await page.route("**/api/Auth/me", route => route.fulfill({ json: { success: true, data: user } }));
    await page.route("**/api/Cliente**", route => {
      if (route.request().method() === "GET") return route.fulfill({ json: { success: true, data: customers } });
      const payload = route.request().postDataJSON() as CustomerPayload;
      writes.push(payload);
      const id = route.request().method() === "PUT" ? new URL(route.request().url()).pathname.split("/").pop()! : `cliente-${writes.length}`;
      const saved = { ...payload, id };
      const index = customers.findIndex(customer => customer.id === id);
      if (index === -1) customers.push(saved); else customers[index] = saved;
      return route.fulfill({ json: { success: true, data: saved } });
    });
    await page.addInitScript(operator => {
      localStorage.setItem("horuspdv.auth.user", JSON.stringify(operator));
      localStorage.setItem("horuspdv.activePage", "cadastro-cliente");
    }, user);
    await page.goto("/");
    await page.getByRole("button", { name: "Novo cliente", exact: true }).click();
  });

  test("salva só nome e telefone fixo, deixando os demais dados vazios", async ({ page }) => {
    await fillMinimum(page);
    await page.getByRole("button", { name: "Criar cliente", exact: true }).click();
    await expect(page.getByText("Cliente cadastrado com sucesso.", { exact: true })).toBeVisible();
    expect(writes).toHaveLength(1);
    expect(writes[0]).toMatchObject({ customerName: "Maria Silva", telephone: "(11) 3333-4444", cellphone: "", document: "",
      birthDate: "", age: "", cep: "", city: "", state: "", address: "", neighborhood: "", number: "", email: "" });
    await expect(page.getByRole("cell", { name: "(11) 3333-4444", exact: true })).toBeVisible();
  });

  test("aceita celular no lugar de telefone fixo", async ({ page }) => {
    await fillMinimum(page, "Celular", "11999998888");
    await page.getByRole("button", { name: "Criar cliente", exact: true }).click();
    await expect(page.getByText("Cliente cadastrado com sucesso.", { exact: true })).toBeVisible();
    expect(writes[0]).toMatchObject({ telephone: "", cellphone: "(11) 99999-8888", document: "" });
  });

  test("aceita número de celular também no campo Telefone", async ({ page }) => {
    await fillMinimum(page, "Telefone *", "11999998888");
    await page.getByRole("button", { name: "Criar cliente", exact: true }).click();
    await expect(page.getByText("Cliente cadastrado com sucesso.", { exact: true })).toBeVisible();
    expect(writes[0].telephone).toBe("(11) 99999-8888");
  });

  for (const [field, message] of [["Nome *", "Informe o nome do cliente."], ["Telefone *", "Informe um telefone ou celular com DDD."]]) {
    test(`bloqueia quando falta ${field}`, async ({ page }) => {
      await fillMinimum(page);
      await page.getByRole("textbox", { name: field, exact: true }).fill("");
      await page.getByRole("button", { name: "Criar cliente", exact: true }).click();
      await expect(page.getByText(message, { exact: true })).toBeVisible();
      expect(writes).toHaveLength(0);
    });
  }

  test("rejeita telefone incompleto e documento inválido se preenchido", async ({ page }) => {
    await fillMinimum(page, "Telefone *", "123");
    await page.getByRole("button", { name: "Criar cliente", exact: true }).click();
    await expect(page.getByText("Informe um telefone ou celular válido com DDD (10 ou 11 dígitos).", { exact: true })).toBeVisible();
    await page.getByRole("textbox", { name: "Telefone *", exact: true }).fill("1133334444");
    await page.getByRole("textbox", { name: "Documento (CPF/CNPJ)", exact: true }).fill("11111111111");
    await page.getByRole("button", { name: "Criar cliente", exact: true }).click();
    await expect(page.getByText("Documento inválido.", { exact: true })).toBeVisible();
    expect(writes).toHaveLength(0);
  });

  test("cadastra dois clientes sem documento e permite editar mantendo os dados opcionais vazios", async ({ page }) => {
    await fillMinimum(page);
    await page.getByRole("button", { name: "Criar cliente", exact: true }).click();
    await expect(page.getByText("Cliente cadastrado com sucesso.", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "Novo cliente", exact: true }).click();
    await fillMinimum(page, "Telefone *", "1144445555", "João Silva");
    await page.getByRole("button", { name: "Criar cliente", exact: true }).click();
    await expect(page.getByRole("cell", { name: "João Silva", exact: true })).toBeVisible();
    const row = page.getByRole("row").filter({ hasText: "Maria Silva" });
    await row.getByRole("button", { name: "Abrir ações", exact: true }).click();
    await page.getByRole("menuitem", { name: "Editar", exact: true }).click();
    await page.getByRole("textbox", { name: "Telefone *", exact: true }).fill("1155556666");
    await page.getByRole("button", { name: "Salvar cliente", exact: true }).click();
    await expect(page.getByText("Cliente atualizado com sucesso.", { exact: true })).toBeVisible();
    expect(customers).toHaveLength(2);
    expect(writes[2]).toMatchObject({ telephone: "(11) 5555-6666", document: "", birthDate: "", address: "" });
  });

  for (const width of [1366, 375]) {
    test(`explica os dois dados obrigatórios e mantém os demais opcionais em ${width}px`, async ({ page }, testInfo) => {
      await page.setViewportSize({ width, height: 900 });
      await expect(page.getByText("Informe nome e telefone ou celular. Os demais dados são opcionais.", { exact: true })).toBeVisible();
      for (const field of ["Documento (CPF/CNPJ)", "CEP", "Cidade", "Endereço", "Bairro", "Número", "Celular", "E-mail"]) {
        await expect(page.getByRole("textbox", { name: field, exact: true })).not.toHaveAttribute("required");
      }
      await page.screenshot({ path: testInfo.outputPath(`cliente-${width}.png`) });
    });
  }

  test("mantém as exigências do fornecedor ao compartilhar os campos de contato", async ({ page }) => {
    await page.getByRole("button", { name: "Fechar formulário", exact: true }).click();
    await page.locator("#admin-sidebar").getByRole("button", { name: "Fornecedor", exact: true }).click();
    await page.getByRole("button", { name: "Novo fornecedor", exact: true }).click();
    await expect(page.getByRole("textbox", { name: "CEP *", exact: true })).toBeVisible();
    await expect(page.getByRole("textbox", { name: "Celular *", exact: true })).toBeVisible();
    await expect(page.getByRole("textbox", { name: "Telefone", exact: true })).toBeVisible();
    await page.getByRole("button", { name: "Criar fornecedor", exact: true }).click();
    await expect(page.getByText("Preencha os campos obrigatórios.", { exact: true })).toBeVisible();
    expect(writes).toHaveLength(0);
  });

  test("cadastro rápido salva sem documento e só exige nome e telefone", async ({ page }) => {
    await page.getByRole("button", { name: "Fechar formulário", exact: true }).click();
    // Exercita o mesmo componente usado no caixa, sem iniciar uma venda ou acessar serviços da loja.
    await page.evaluate(async () => {
      const { mountQuickCustomer } = await import("/tests/fixtures/customer-quick.fixture.tsx" as string);
      mountQuickCustomer();
    });
    const dialog = page.getByRole("dialog", { name: "Cadastro Rápido de Cliente", exact: true });
    await expect(dialog.locator("input[required]")).toHaveCount(2);
    await dialog.getByRole("textbox", { name: "Nome Completo *", exact: true }).fill("Cliente Rápido");
    await dialog.getByRole("textbox", { name: "Telefone / celular *", exact: true }).fill("1133334444");
    await dialog.getByRole("button", { name: "Salvar e Selecionar Cliente", exact: true }).click();
    await expect(page.locator("#customer-quick-test")).toHaveAttribute("data-saved", "cliente-1");
    expect(writes[0]).toMatchObject({ customerName: "Cliente Rápido", document: "", cellphone: "(11) 3333-4444", address: "" });
  });
});
