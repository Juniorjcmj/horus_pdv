import { expect, test, type Page } from "@playwright/test";

const MILK_CODE = "7890000000001";
const COFFEE_CODE = "7890000000002";
const RICE_GTIN = "7890000000003";
const PACKAGED_CODE = "2999990001006";
const milkRow = (page: Page) => page.getByRole("row").filter({ hasText: "Leite scanner" });
const coffeeRow = (page: Page) => page.getByRole("row").filter({ hasText: "Café scanner" });

async function scan(page: Page, code: string, ending = "Enter") {
  // Real keyboard events, including browser input defaults and React controlled fields.
  await page.keyboard.type(code, { delay: 1 });
  await page.keyboard.press(ending);
}

async function openPos(page: Page, baseURL: string, installed = true) {
  const writes: string[] = [];
  const user = { id: "gerente-scanner", companyId: "loja-scanner", name: "Gerente", email: "teste@teste.invalid",
    role: "gerente", status: "ativo", createdAt: "2026-10-09T00:00:00Z", lastLoginAt: "2026-10-09T00:00:00Z", mustChangePassword: false };
  const products = [
    { id: "leite", productName: "Leite scanner", productCode: MILK_CODE, unidadeComercial: "UN", productSalePrice: "10,00" },
    { id: "cafe", productName: "Café scanner", productCode: COFFEE_CODE, unidadeComercial: "UN", productSalePrice: "20,00" },
    { id: "arroz", productName: "Arroz GTIN", productCode: "SKU-ARROZ", gtin: RICE_GTIN, unidadeComercial: "UN", productSalePrice: "15,00" },
    { id: "balanca", productName: "Queijo balança", productCode: "2691", unidadeComercial: "KG", productSalePrice: "10,00" },
    { id: "embalado", productName: "Produto embalado", productCode: "SKU-EMBALADO", gtin: PACKAGED_CODE,
      unidadeComercial: "UN", productSalePrice: "5,00" },
  ].map(product => ({ productUnitPrice: "5,00", productQnt: "9999", productSupplier: "", ...product }));
  await page.route("**/*", route => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.origin === new URL(baseURL).origin) return route.continue();
    if (request.method() !== "GET") writes.push(`${request.method()} ${url.pathname}`);
    const data = url.pathname.endsWith("/Auth/me") ? user
      : url.pathname.endsWith("/Produto") ? products
      : url.pathname.endsWith("/Caixa/status") ? { state: "aberto", canSell: true, blockReason: "", serverNow: new Date().toISOString(),
        currentSession: { id: "sessao-scanner", status: "Aberto", openedAt: new Date().toISOString(), openingAmount: "100,00",
          closingAmount: "0,00", operatorId: user.id, operatorName: user.name, closedById: "", closedByName: "", note: "",
          elapsedMinutes: 1, expectedCashAmount: "100,00", movimentos: [], paymentBreakdown: [] }, lastSession: null, history: [] }
      : url.pathname.endsWith("/Empresa") ? null : [];
    return route.fulfill({ json: { success: true, data } });
  });
  await page.addInitScript(({ user, installed }) => {
    localStorage.setItem("horuspdv.auth.user", JSON.stringify(user));
    localStorage.setItem("horuspdv.activePage", "vendas");
    if (installed) window.quackDesktop = { savePendingBackup: async () => "" };
  }, { user, installed });
  await page.goto("/");
  const product = page.getByRole("textbox", { name: /^Produto:/ });
  await product.fill(MILK_CODE);
  await expect(page.getByText("Leite scanner", { exact: true }).first()).toBeVisible();
  await product.fill("");
  await page.getByText("Lista de itens:", { exact: true }).click();
  return { product, quantity: page.getByRole("textbox", { name: "Quantidade (volume):", exact: true }), writes };
}

test("lê após clicar fora da busca e adiciona uma única vez", async ({ page, baseURL }) => {
  const { product } = await openPos(page, baseURL!);
  await scan(page, MILK_CODE);
  await expect(milkRow(page)).toHaveCount(1);
  await expect(milkRow(page).getByRole("cell").nth(3)).toHaveText("1");
  await expect(product).toBeFocused();
  await expect(product).toHaveValue("");
});

test("restaura a quantidade anterior ao leitor e impede usar o código como quantidade", async ({ page, baseURL }) => {
  const { quantity } = await openPos(page, baseURL!);
  await quantity.fill("3");
  await scan(page, MILK_CODE);
  await expect(milkRow(page).getByRole("cell").nth(3)).toHaveText("3");
  await expect(quantity).toHaveValue("1");
});

test("substitui a pesquisa anterior pelo código exato", async ({ page, baseURL }) => {
  const { product } = await openPos(page, baseURL!);
  await product.fill("Café");
  await scan(page, MILK_CODE);
  await expect(milkRow(page)).toHaveCount(1);
  await expect(coffeeRow(page)).toHaveCount(0);
});

test("leituras repetidas somam unidades sem duplicar o Enter", async ({ page, baseURL }) => {
  await openPos(page, baseURL!);
  await scan(page, MILK_CODE);
  await scan(page, MILK_CODE);
  await expect(milkRow(page)).toHaveCount(1);
  await expect(milkRow(page).getByRole("cell").nth(3)).toHaveText("2");
});

test("código desconhecido mantém a busca e não adiciona a primeira correspondência parcial", async ({ page, baseURL }) => {
  const { product } = await openPos(page, baseURL!);
  const partialCode = MILK_CODE.slice(0, -1);
  await scan(page, partialCode);
  await expect(page.getByText("Produto não encontrado.", { exact: true })).toBeVisible();
  await expect(product).toHaveValue(partialCode);
  await expect(milkRow(page)).toHaveCount(0);
});

test("encontra GTIN mesmo quando o código interno do produto é diferente", async ({ page, baseURL }) => {
  await openPos(page, baseURL!);
  await scan(page, RICE_GTIN);
  await expect(page.getByRole("row").filter({ hasText: "Arroz GTIN" })).toHaveCount(1);
});

test("mantém a interpretação da etiqueta de balança", async ({ page, baseURL }) => {
  await openPos(page, baseURL!);
  const base = "226910000685";
  const sum = [...base].reduce((sum, digit, index) => sum + Number(digit) * (index % 2 ? 3 : 1), 0);
  await scan(page, base + (10 - sum % 10) % 10);
  const row = page.getByRole("row").filter({ hasText: "Queijo balança" });
  await expect(row.getByRole("cell").nth(3)).toHaveText("0.685");
});

test("prioriza o produto com código completo cadastrado antes de interpretar uma etiqueta", async ({ page, baseURL }) => {
  await openPos(page, baseURL!);
  await scan(page, PACKAGED_CODE);
  const row = page.getByRole("row").filter({ hasText: "Produto embalado" });
  await expect(row.getByRole("cell").nth(3)).toHaveText("1");
});

test("digitação manual e atalho de quantidade continuam funcionando", async ({ page, baseURL }) => {
  const { product, quantity } = await openPos(page, baseURL!);
  await product.fill("Leite scanner");
  await page.keyboard.press("F4");
  await expect(quantity).toBeFocused();
  await page.keyboard.type("1234", { delay: 120 });
  await expect(quantity).toHaveValue("1234");
  await page.keyboard.press("Enter");
  await expect(milkRow(page).getByRole("cell").nth(3)).toHaveText("1234");
  await expect(quantity).toHaveValue("1");
  await expect(product).toHaveValue("");
});

test("leitura no pagamento preserva o valor, não confirma venda e aguarda fechar a janela", async ({ page, baseURL }) => {
  const { writes } = await openPos(page, baseURL!);
  await scan(page, MILK_CODE);
  await page.keyboard.press("F12");
  await expect(page.getByRole("heading", { name: "Pagamento", exact: true })).toBeVisible();
  const amount = page.getByRole("textbox", { name: "Valor entregue em dinheiro", exact: true });
  await amount.fill("2000");
  await expect(amount).toHaveValue("20,00");
  await scan(page, COFFEE_CODE);
  await expect(amount).toHaveValue("20,00");
  await expect(page.getByRole("heading", { name: "Pagamento", exact: true })).toBeVisible();
  await expect(coffeeRow(page)).toHaveCount(0);
  expect(writes.filter(write => /\/Venda\b/i.test(write))).toHaveLength(0);
  await page.getByRole("button", { name: "Voltar", exact: true }).click();
  await expect(coffeeRow(page)).toHaveCount(1);
  expect(writes.filter(write => /\/Venda\b/i.test(write))).toHaveLength(0);
});

test("lê com terminador Tab sem depender do foco", async ({ page, baseURL }) => {
  const { product } = await openPos(page, baseURL!);
  await scan(page, MILK_CODE, "Tab");
  await expect(milkRow(page)).toHaveCount(1);
  await expect(product).toBeFocused();
});

test("preserva o campo de cliente e aguarda fechar todas as janelas", async ({ page, baseURL }) => {
  await openPos(page, baseURL!);
  await scan(page, MILK_CODE);
  await page.keyboard.press("F12");
  await page.getByRole("radio", { name: /Fiado/ }).click();
  await expect(page.getByRole("heading", { name: "Selecionar Cliente para Fiado" })).toBeVisible();
  const customer = page.getByPlaceholder("Pesquise por nome, CPF ou celular...");
  await customer.fill("Maria");
  await scan(page, COFFEE_CODE);
  await expect(customer).toHaveValue("Maria");
  await page.getByRole("button", { name: "Fechar seleção de cliente" }).click();
  await expect(coffeeRow(page)).toHaveCount(0);
  await page.getByRole("button", { name: "Voltar", exact: true }).click();
  await expect(coffeeRow(page)).toHaveCount(1);
});

test("o Enter do leitor não aciona o botão que recebeu foco", async ({ page, baseURL }) => {
  await openPos(page, baseURL!);
  await scan(page, MILK_CODE);
  await page.getByRole("button", { name: /PAGAMENTO \(F12\)/ }).focus();
  await scan(page, COFFEE_CODE);
  await expect(coffeeRow(page)).toHaveCount(1);
  await expect(page.getByRole("heading", { name: "Pagamento", exact: true })).toHaveCount(0);
});

test("usa o catálogo local quando cai a internet", async ({ page, baseURL, context }) => {
  await openPos(page, baseURL!);
  await context.setOffline(true);
  await scan(page, MILK_CODE);
  await expect(milkRow(page)).toHaveCount(1);
});

test("a captura fora da busca fica restrita ao aplicativo instalado", async ({ page, baseURL }) => {
  await openPos(page, baseURL!, false);
  await scan(page, MILK_CODE);
  await expect(milkRow(page)).toHaveCount(0);
});
