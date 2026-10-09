import { test, expect, type Page } from "@playwright/test";

const user = { id: "admin-ui", companyId: "empresa-principal", name: "Administrador", email: "admin@teste.invalid",
  role: "administrador", status: "ativo", createdAt: "2026-10-09T00:00:00Z", lastLoginAt: "2026-10-09T00:00:00Z", mustChangePassword: false };
const cards = [{ label: "Vendas hoje", value: "R$ 1.250,00", helper: "Vendas finalizadas", color: "#152238", trend: [1, 2, 3] }];
const noExpiry = { vencidos: 0, venceEm7Dias: 0, venceEm15Dias: 0, venceEm30Dias: 0, totalControlados: 0, semDataInformada: 0 };

async function setup(page: Page, baseURL: string, activePage = "home", role = "administrador", theme = "light") {
  const operator = { ...user, role };
  await page.route("**/*", route => {
    const url = new URL(route.request().url());
    if (url.origin === new URL(baseURL).origin) return route.continue();
    if (url.hostname.includes("google")) return route.fulfill({ body: "", contentType: "text/css" });
    let data: unknown = [];
    if (/\/api\/Auth\/me$/i.test(url.pathname)) data = operator;
    if (/\/api\/Home$/i.test(url.pathname)) data = { cards };
    if (/vencimentos\/resumo$/i.test(url.pathname)) data = noExpiry;
    if (/\/api\/Relatorio\/Gerar$/i.test(url.pathname)) data = { columns: [], rows: [] };
    return route.fulfill({ json: { success: true, data } });
  });
  await page.addInitScript(({ operator, activePage, theme }) => {
    localStorage.setItem("horuspdv.auth.user", JSON.stringify(operator));
    localStorage.setItem("horuspdv.activePage", activePage);
    localStorage.setItem("horuspdv.theme", theme);
  }, { operator, activePage, theme });
}

test("mantém a navegação das principais rotinas administrativas", async ({ page, baseURL }) => {
  await setup(page, baseURL!);
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.goto("/");
  for (const [label, key] of [
    ["Cliente", "cadastro-cliente"], ["Fornecedor", "cadastro-fornecedor"], ["Produto", "cadastro-produto"],
    ["Estoque e Inventário", "estoque"], ["Histórico de Vendas", "historico-vendas"], ["Relatórios", "relatorios"],
    ["Controle de Validade", "validade"], ["Fiscal NFC-e / NF-e", "fiscal"], ["NF-e Modelo 55", "nfe-emissao"],
    ["Novo Pedido", "pedidos"], ["Visão geral", "home"],
  ]) {
    await page.locator("#admin-sidebar").getByRole("button", { name: label, exact: true }).click();
    await expect(page.locator("main")).toHaveAttribute("data-active-page", key);
    await expect(page.locator("main h1").first()).toBeVisible();
  }
  expect(errors).toEqual([]);
});

test("menu recolhido mantém nomes acessíveis e expande no celular", async ({ page, baseURL }) => {
  await setup(page, baseURL!);
  await page.goto("/");
  await page.getByRole("button", { name: "Recolher menu lateral" }).click();
  await expect(page.locator("#admin-sidebar").getByRole("button", { name: "Produto", exact: true })).toHaveAttribute("title", "Produto");
  await page.setViewportSize({ width: 375, height: 812 });
  await expect(page.locator("#admin-sidebar")).toHaveAttribute("inert", "");
  const trigger = page.getByRole("button", { name: "Abrir menu", exact: true });
  await trigger.click();
  await expect(page.getByRole("dialog", { name: "Menu principal" })).toBeVisible();
  await expect(page.locator("#admin-sidebar").getByRole("button", { name: "Produto", exact: true })).toContainText("Produto");
  await expect(page.locator("#admin-content")).toHaveAttribute("inert", "");
  await page.keyboard.press("Escape");
  await expect(trigger).toBeFocused();
  await expect(page.locator("#admin-sidebar")).toHaveAttribute("inert", "");
  await trigger.click();
  await page.setViewportSize({ width: 1366, height: 900 });
  await expect(page.locator("#admin-content")).not.toHaveAttribute("inert", "");
});

test("oferece recuperação dos indicadores sem bloquear atalhos", async ({ page, baseURL }) => {
  await setup(page, baseURL!);
  let fail = true;
  await page.route("**/api/Home", route => fail
    ? route.fulfill({ status: 503, json: { success: false, message: "Indisponível" } })
    : route.fulfill({ json: { success: true, data: { cards } } }));
  await page.goto("/");
  await expect(page.getByRole("alert")).toContainText("Não foi possível atualizar os indicadores");
  await expect(page.getByRole("button", { name: /Produtos Cadastrar produtos/ })).toBeEnabled();
  fail = false;
  await page.getByRole("button", { name: "Tentar novamente", exact: true }).click();
  await expect(page.getByText("R$ 1.250,00", { exact: true })).toBeVisible();
});

for (const [key, endpoint, noun] of [
  ["cadastro-cliente", "Cliente", "cliente"],
  ["cadastro-fornecedor", "Fornecedor", "fornecedor"],
  ["cadastro-produto", "Produto", "produto"],
  ["conta-de-usuario", "Usuario", "usuário"],
]) {
  test(`distingue falha de lista vazia no cadastro de ${noun}`, async ({ page, baseURL }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await setup(page, baseURL!, key);
    let fail = true;
    await page.route(`**/api/${endpoint}`, route => fail
      ? route.fulfill({ status: 503, json: { success: false, message: "Indisponível" } })
      : route.fulfill({ json: { success: true, data: [] } }));
    await page.goto("/");
    await expect(page.getByRole("alert")).toContainText("Não foi possível carregar");
    fail = false;
    await page.getByRole("button", { name: "Tentar novamente", exact: true }).click();
    const empty = page.getByText(`Nenhum ${noun} cadastrado`, { exact: true });
    await expect(empty).toBeVisible();
    const rect = await empty.boundingBox();
    expect(rect!.x).toBeGreaterThanOrEqual(0);
    expect(rect!.x + rect!.width).toBeLessThanOrEqual(375);
  });
}

test("seleciona e limpa fornecedor usando teclado no cadastro de produto", async ({ page, baseURL }) => {
  await setup(page, baseURL!, "cadastro-produto");
  await page.route("**/api/Fornecedor", route => route.fulfill({ json: { success: true, data: [
    { id: "f-1", fantasyName: "Atacado Norte", companyName: "Atacado Norte", cnpj: "", city: "", cellphone: "", telephone: "" },
    { id: "f-2", fantasyName: "Atacado Sul", companyName: "Atacado Sul", cnpj: "", city: "", cellphone: "", telephone: "" },
  ] } }));
  await page.goto("/");
  await page.getByRole("button", { name: "Novo produto", exact: true }).click();
  const field = page.getByRole("combobox", { name: "Fornecedor (opcional)", exact: true });
  await field.fill("Sul");
  await field.press("ArrowDown");
  await expect(field).toHaveAttribute("aria-activedescendant", /options-0$/);
  await field.press("Enter");
  await expect(field).toHaveValue("Atacado Sul");
  await field.fill("");
  await field.press("Escape");
  await expect(field).toHaveValue("");
  await expect(field).toHaveAttribute("aria-expanded", "false");
});

test("cadastra fornecedor pela opção de criação usando Enter", async ({ page, baseURL }) => {
  await setup(page, baseURL!, "cadastro-produto");
  await page.goto("/");
  await page.getByRole("button", { name: "Novo produto", exact: true }).click();
  const field = page.getByRole("combobox", { name: "Fornecedor (opcional)", exact: true });
  await field.fill("Fornecedor novo");
  await field.press("Enter");
  await expect(page.getByRole("heading", { name: "Cadastrar fornecedor", exact: true })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "Razão social *", exact: true })).toHaveValue("Fornecedor novo");
});

test("menu da conta recebe foco e devolve ao gatilho com Escape", async ({ page, baseURL }) => {
  await setup(page, baseURL!);
  await page.goto("/");
  const trigger = page.getByRole("button", { name: "Menu de Administrador", exact: true });
  await trigger.click();
  await expect(page.getByRole("menuitem", { name: "Meu Perfil" })).toBeFocused();
  await page.keyboard.press("ArrowDown");
  await expect(page.getByRole("menuitem", { name: "Configurações" })).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(trigger).toBeFocused();
  await expect(trigger).toHaveAttribute("aria-expanded", "false");
});

test("menu de ações da tabela funciona com setas e Escape", async ({ page, baseURL }) => {
  await setup(page, baseURL!, "cadastro-cliente");
  await page.route("**/api/Cliente", route => route.fulfill({ json: { success: true, data: [
    { id: "c-1", customerName: "Cliente de teste", document: "", city: "", cellphone: "", email: "", saldoDevedor: 0 },
  ] } }));
  await page.goto("/");
  const trigger = page.getByRole("button", { name: "Abrir ações", exact: true });
  await trigger.click();
  await expect(page.getByRole("menuitem", { name: "Editar", exact: true })).toBeFocused();
  await page.keyboard.press("ArrowDown");
  await expect(page.getByRole("menuitem", { name: "Excluir", exact: true })).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(trigger).toBeFocused();
});

test("calendário anuncia mês e ano e devolve foco ao fechar", async ({ page, baseURL }) => {
  await setup(page, baseURL!, "cadastro-cliente");
  await page.goto("/");
  await page.getByRole("button", { name: "Novo cliente", exact: true }).click();
  const trigger = page.locator('button[aria-haspopup="dialog"]').first();
  await trigger.click();
  const dialog = page.getByRole("dialog", { name: "Escolher data", exact: true });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole("combobox", { name: "Mês", exact: true })).toBeVisible();
  await expect(dialog.getByRole("combobox", { name: "Ano", exact: true })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(trigger).toBeFocused();
});

test("relatório recupera erro sem travar o botão ou apagar resultado anterior", async ({ page, baseURL }) => {
  await setup(page, baseURL!, "relatorios");
  let fail = false;
  await page.route("**/api/Relatorio/Gerar", route => fail
    ? route.fulfill({ status: 503, json: { success: false, message: "Indisponível" } })
    : route.fulfill({ json: { success: true, data: { columns: [{ key: "teste", label: "Descrição" }], rows: [{ teste: "Resultado preservado" }] } } }));
  await page.goto("/");
  await page.getByRole("button", { name: /^Vendas por Período/ }).click();
  const generate = page.getByRole("button", { name: "Gerar relatório", exact: true });
  await generate.click();
  await expect(page.getByText("Resultado preservado", { exact: true })).toBeVisible();
  fail = true;
  await generate.click();
  await expect(page.getByRole("alert")).toContainText("Não foi possível gerar o relatório");
  await expect(generate).toBeEnabled();
  await expect(page.getByText("Resultado preservado", { exact: true })).toBeVisible();
  fail = false;
  await generate.click();
  await expect(page.getByRole("alert")).toHaveCount(0);
});

test("paginação usa cores de ação e indica a página no tema escuro", async ({ page, baseURL }) => {
  await setup(page, baseURL!, "cadastro-cliente", "administrador", "dark");
  await page.goto("/");
  const current = page.getByRole("button", { name: "Página 1", exact: true });
  await expect(current).toHaveAttribute("aria-current", "page");
  const style = await current.evaluate(element => ({ background: getComputedStyle(element).backgroundColor, color: getComputedStyle(element).color }));
  expect(style.background).toBe("rgb(30, 58, 95)");
  expect(style.color).toBe("rgb(255, 255, 255)");
  expect((await current.boundingBox())!.height).toBeGreaterThanOrEqual(44);
});

test("título longo cabe em 320 px e a tabela permite rolagem por teclado", async ({ page, baseURL }) => {
  await page.setViewportSize({ width: 320, height: 812 });
  await setup(page, baseURL!, "cadastro-fornecedor");
  await page.goto("/");
  const heading = page.locator("main h1");
  await expect(heading).toBeVisible();
  const rect = await heading.boundingBox();
  expect(rect!.x + rect!.width).toBeLessThanOrEqual(320);
  const table = page.getByRole("region", { name: "Fornecedores", exact: true });
  await table.focus();
  await page.keyboard.press("ArrowRight");
  await expect.poll(() => table.evaluate(element => element.scrollLeft)).toBeGreaterThan(0);
});

test("preserva restrição fiscal para gerente sem perfil financeiro", async ({ page, baseURL }) => {
  await setup(page, baseURL!, "home", "gerente");
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Visão geral", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: /Fiscal NFC-e/ })).toHaveCount(0);
  await expect(page.locator("#admin-sidebar").getByRole("button", { name: "Produto", exact: true })).toBeVisible();
});
