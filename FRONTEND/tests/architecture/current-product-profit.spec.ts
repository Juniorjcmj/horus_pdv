import { test, expect, type Page } from "@playwright/test";
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { productProfitability, proposedProductPrice } from "../../src/utils/productProfitability";

const user = { id: "current-profit", companyId: "empresa-principal", name: "Administrador", role: "administrador", status: "ativo", mustChangePassword: false };
function product(id: string, name: string, cost: string, sale: string, unit = "UN") {
  return { id, productCode: id, productName: name, productUnitPrice: cost, productSalePrice: sale,
    versaoPreco: `${cost.replace(",", ".")}|${sale.replace(",", ".")}`,
    unidadeComercial: unit, unidadeTributavel: unit, productImageUrl: "", productImageName: "", productSupplier: "",
    productDescription: "", productQnt: "100", estoqueMinimo: "0", totalPriceOnProduct: "0,00",
    lucro: "999,99", markupCadastrado: "999", markupPraticado: "999", margemDesejadaPercentual: null,
    unidadeCompra: "CX", fatorConversao: "12", qtdEmbalagem: "12", custoMedio: cost,
    pesoLiquidoKg: "0", pesoBrutoKg: "0", larguraCm: "0", alturaCm: "0", comprimentoCm: "0",
    estoqueMaximo: "0", custoComImposto: cost, custoSemImposto: cost, descontoMaximoPercentual: "0",
    comissaoPercentual: "0", ncm: "", cest: null, cfop: "5102", origemMercadoria: 0, gtin: "SEM GTIN",
    csosnIcms: "102", cstIcms: null, aliquotaIcms: "0,00", cstPis: "07", cstCofins: "07", cstIbsCbs: null, cClassTrib: null };
}
const products = [product("101", "Cerveja lata", "10,00", "15,00"),
  product("102", "Batata KG", "10,00", "8,00", "KG"), product("103", "Custo zerado", "0,00", "5,00"),
  product("104", "Sem custo", "", "5,00"), ...Array.from({ length: 10 }, (_, i) => product(`20${i}`, `Produto ${i}`, "2,00", "3,00"))];

test("calcula pelos preços atuais, preserva centavos e trata custo indisponível", () => {
  expect(productProfitability(products[0])).toMatchObject({ cost: 10, sale: 15, profit: 5, markup: 50 });
  expect(productProfitability(products[0]).margin).toBeCloseTo(33.333333);
  expect(productProfitability(products[1])).toEqual({ cost: 10, sale: 8, profit: -2, margin: -25, markup: -20 });
  expect(productProfitability(products[2])).toEqual({ cost: 0, sale: 5, profit: 5, margin: 100, markup: null });
  expect(productProfitability(products[3])).toEqual({ cost: null, sale: 5, profit: null, margin: null, markup: null });
  expect(productProfitability(product("x", "x", "10,00", "0,00"))).toEqual({ cost: 10, sale: 0, profit: -10, margin: null, markup: -100 });
  expect(productProfitability(product("x", "x", "R$ 1.234,56", "1.334,56"))).toMatchObject({ cost: 1234.56, sale: 1334.56, profit: 100 });
  expect(productProfitability(product("x", "x", "0,10", "0,30"))).toMatchObject({ profit: 0.2, markup: 200 });
  expect(productProfitability(product("x", "x", "inválido", "5,00"))).toMatchObject({ cost: null, profit: null, markup: null });
});

test("decisão de preço usa as quatro fórmulas, arredonda e recusa bases inválidas", () => {
  expect(proposedProductPrice(10, "sale", "18,00").sale).toBe(18);
  expect(proposedProductPrice(10, "profit", "8").sale).toBe(18);
  expect(proposedProductPrice(10, "markup", "80").sale).toBe(18);
  expect(proposedProductPrice(10, "margin", "50").sale).toBe(20);
  expect(proposedProductPrice(10, "margin", "-25").sale).toBe(8);
  expect(proposedProductPrice(10, "profit", "-2").sale).toBe(8);
  expect(proposedProductPrice(3, "markup", "33.33").sale).toBe(4);
  expect(proposedProductPrice(0, "sale", "5").sale).toBe(5);
  for (const [cost, metric, value] of [[null, "sale", "5"], [0, "markup", "50"], [10, "margin", "100"], [10, "markup", "-100"], [10, "sale", ""], [10, "sale", "1.234,56"]] as const)
    expect(proposedProductPrice(cost, metric, value).error).not.toBeNull();
  expect(productProfitability({ ...products[0], productUnitPrice: "10,00", versaoPreco: "10.004|15" }).cost).toBe(10.004);
});

test("altera os quatro indicadores, confirma uma vez e atualiza a lista filtrada", async ({ page, baseURL }) => {
  const state = await setup(page, baseURL!);
  await page.getByRole("searchbox", { name: "Código ou descrição do produto" }).fill("101");
  await page.getByRole("button", { name: "Cerveja lata", exact: true }).click();
  const sale = page.getByLabel("Preço de venda (R$)", { exact: true });
  const profit = page.getByLabel("Lucro bruto (R$)", { exact: true });
  const margin = page.getByLabel("Lucro bruto (%)", { exact: true });
  const markup = page.getByLabel("Markup (%)", { exact: true });
  await sale.fill("20,00"); await expect(profit).toHaveValue("10,00"); await expect(markup).toHaveValue("100,00"); await expect(margin).toHaveValue("50,00");
  await profit.fill("8,00"); await expect(sale).toHaveValue("18,00"); await expect(markup).toHaveValue("80,00");
  await margin.fill("60"); await expect(sale).toHaveValue("25,00"); await expect(profit).toHaveValue("15,00");
  await markup.fill("80"); await expect(sale).toHaveValue("18,00"); await expect(margin).toHaveValue("44,44");
  expect(state.saves).toHaveLength(0); state.delaySave = true;
  await page.getByRole("button", { name: "Salvar preço de venda", exact: true }).evaluate(el => { (el as HTMLButtonElement).click(); (el as HTMLButtonElement).click(); });
  await expect(page.getByRole("status")).toContainText("Preço de venda salvo");
  expect(state.saves).toEqual([{ precoVenda: "18,00", versaoConsultada: "10.00|15.00" }]);
  await page.getByRole("button", { name: "Voltar", exact: true }).click();
  await expect(page.getByRole("searchbox", { name: "Código ou descrição do produto" })).toHaveValue("101");
  await expect(page.locator("tbody tr")).toContainText("R$ 18,00");
  await expect(page.locator("tbody tr")).toContainText("80,00%");
});

test("valida, descarta e recupera falha de rede ou conflito sem perder o ajuste", async ({ page, baseURL }) => {
  const state = await setup(page, baseURL!);
  await page.getByRole("button", { name: "Cerveja lata", exact: true }).click();
  const sale = page.getByLabel("Preço de venda (R$)", { exact: true });
  await page.getByLabel("Lucro bruto (%)", { exact: true }).fill("100");
  await expect(page.getByRole("alert")).toContainText("menor que 100%");
  await expect(page.getByRole("button", { name: "Salvar preço de venda", exact: true })).toBeDisabled();
  await sale.fill("18"); await page.getByRole("button", { name: "Voltar", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("ainda não foi salvo");
  await page.getByRole("button", { name: "Continuar ajustando" }).click();
  state.saveFailure = "network";
  await page.getByRole("button", { name: "Salvar preço de venda", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("Não foi possível conectar");
  await expect(sale).toHaveValue("18,00");
  state.saveFailure = "";
  await page.getByRole("button", { name: "Salvar preço de venda", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("Preço de venda salvo");
  expect(state.saves[1]).toEqual(state.saves[0]);
  await sale.fill("20"); state.saveFailure = "conflict";
  await page.getByRole("button", { name: "Salvar preço de venda", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("mudou desde esta consulta");
  await expect(page.getByRole("button", { name: "Salvar preço de venda", exact: true })).toBeDisabled();
  state.products[0] = product("101", "Cerveja lata", "12,00", "18,00");
  await page.getByRole("button", { name: "Atualizar preços e refazer ajuste" }).click();
  await expect(sale).toHaveValue("18,00"); await expect(page.getByLabel("Lucro bruto (R$)", { exact: true })).toHaveValue("6,00");
  await sale.fill("21"); await page.getByRole("button", { name: "Desfazer ajuste" }).click(); await expect(sale).toHaveValue("18,00");
});

async function setup(page: Page, baseURL: string, activePage = "relatorios", fail = false) {
  const state = { fail, products: [...products], reportRequests: 0, saves: [] as Record<string, string>[], saveFailure: "", delaySave: false };
  await page.route("**/*", async route => {
    const url = new URL(route.request().url());
    if (url.origin === new URL(baseURL).origin) return route.continue();
    if (url.hostname.includes("google")) return route.fulfill({ body: "", contentType: "text/css" });
    let data: unknown = [];
    if (/\/api\/Auth\/me$/i.test(url.pathname)) data = user;
    else if (/\/api\/Produto\/[^/]+\/preco-venda$/i.test(url.pathname)) {
      const payload = route.request().postDataJSON(); state.saves.push(payload);
      if (state.delaySave) await new Promise(resolve => setTimeout(resolve, 200));
      if (state.saveFailure === "network") return route.abort("failed");
      if (state.saveFailure === "conflict") return route.fulfill({ status: 409, json: { success: false, message: "O custo ou preço de venda mudou desde esta consulta. Atualize os preços." } });
      const id = url.pathname.split("/").at(-2)!;
      const current = state.products.find(p => p.id === id)!;
      data = { ...current, productSalePrice: payload.precoVenda, versaoPreco: `${current.productUnitPrice.replace(",", ".")}|${payload.precoVenda.replace(",", ".")}` };
      state.products = state.products.map(p => p.id === id ? data as typeof current : p);
    } else if (/\/api\/Produto$/i.test(url.pathname)) {
      if (state.fail) return route.fulfill({ status: 503, json: { success: false, message: "Indisponível" } });
      data = state.products;
    } else if (/vencimentos\/resumo$/i.test(url.pathname)) data = { vencidos: 0, venceEm7Dias: 0, venceEm15Dias: 0, venceEm30Dias: 0, totalControlados: 0, semDataInformada: 0 };
    else if (/\/api\/Relatorio\/Gerar$/i.test(url.pathname)) state.reportRequests++;
    return route.fulfill({ json: { success: true, data } });
  });
  await page.addInitScript(({ user, activePage }) => {
    localStorage.setItem("horuspdv.auth.user", JSON.stringify(user));
    localStorage.setItem("horuspdv.activePage", activePage);
    localStorage.setItem("horuspdv.theme", "light");
  }, { user, activePage });
  await page.goto("/");
  if (activePage === "relatorios") await page.getByRole("button", { name: /Lucro e markup sem período Consulte/ }).click();
  return state;
}
const summary = (page: Page) => page.getByRole("region", { name: "Lucro e markup do produto", exact: true });

test("consulta sem datas, filtra, abre produto e preserva filtro ao voltar; atualiza preços", async ({ page, baseURL }) => {
  const state = await setup(page, baseURL!);
  await expect(page.locator("input[type=date]")).toHaveCount(0);
  await expect(page.locator("tbody tr")).toHaveCount(10);
  await expect(page.getByRole("columnheader", { name: "Lucro bruto (%)", exact: true })).toBeVisible();
  await expect(page.locator("tbody tr").first()).toContainText("33,33%");
  const folder = join(tmpdir(), "pdv-current-profit"); mkdirSync(folder, { recursive: true });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.screenshot({ path: join(folder, "desktop-list.png"), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: join(folder, "mobile-list.png"), fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.getByRole("button", { name: "Próxima", exact: true }).click();
  await expect(page.locator("tbody tr")).toHaveCount(4);
  const search = page.getByRole("searchbox", { name: "Código ou descrição do produto" });
  await search.fill("101");
  await page.getByRole("button", { name: "Cerveja lata", exact: true }).click();
  await expect(summary(page)).toContainText("R$ 5,00");
  await expect(summary(page)).toContainText("50,00%");
  await expect(page.getByLabel("Lucro bruto (%)", { exact: true })).toHaveValue("33,33");
  await expect(summary(page)).not.toContainText("999");
  await page.getByRole("button", { name: "Voltar", exact: true }).click();
  await expect(search).toHaveValue("101");
  state.products[0] = product("101", "Cerveja lata", "12,00", "15,00");
  await page.getByRole("button", { name: "Atualizar preços" }).click();
  await expect(page.locator("tbody tr")).toContainText("25,00%");
  await page.getByRole("button", { name: "Cerveja lata", exact: true }).click();
  await expect(summary(page)).toContainText("R$ 3,00");
  await expect(page.getByLabel("Lucro bruto (%)", { exact: true })).toHaveValue("20,00");
  expect(state.reportRequests).toBe(0);
});

test("erro permite recuperar e identifica prejuízo por kg, custo zero e custo ausente", async ({ page, baseURL }) => {
  const state = await setup(page, baseURL!, "relatorios", true);
  await expect(page.getByRole("alert")).toContainText("Não foi possível carregar os preços");
  state.fail = false;
  await page.getByRole("button", { name: "Tentar novamente", exact: true }).click();
  await page.getByRole("button", { name: "Batata KG", exact: true }).click();
  await expect(summary(page)).toContainText("Valores para 1 kg");
  await expect(summary(page)).toContainText("-20,00%");
  await expect(page.getByLabel("Lucro bruto (%)", { exact: true })).toHaveValue("-25,00");
  await expect(summary(page)).toContainText("prejuízo de R$ 2,00 por 1 kg");
  await page.getByRole("button", { name: "Voltar", exact: true }).click();
  await page.getByRole("button", { name: "Custo zerado", exact: true }).click();
  await expect(summary(page)).toContainText("O custo está zerado");
  await expect(page.getByLabel("Lucro bruto (%)", { exact: true })).toHaveValue("100,00");
  await expect(summary(page).locator("dd").last()).toHaveText("—");
  await page.getByRole("button", { name: "Voltar", exact: true }).click();
  await page.getByRole("button", { name: "Sem custo", exact: true }).click();
  await expect(summary(page)).toContainText("Complete os preços");
  await expect(page.getByLabel("Lucro bruto (R$)", { exact: true })).toHaveValue("");
  await expect(page.getByLabel("Lucro bruto (%)", { exact: true })).toHaveValue("");
});

test("ação no cadastro abre a mesma consulta e layout funciona no celular e tema escuro", async ({ page, baseURL }) => {
  const errors: string[] = []; page.on("pageerror", error => errors.push(error.message));
  await setup(page, baseURL!, "cadastro-produto");
  const row = page.locator("tbody tr").filter({ hasText: "Cerveja lata" });
  await row.getByRole("button", { name: "Abrir ações" }).click();
  await page.getByRole("menuitem", { name: "Ver lucro e markup", exact: true }).click();
  await expect(summary(page)).toContainText("50,00%");
  const folder = join(tmpdir(), "pdv-current-profit"); mkdirSync(folder, { recursive: true });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.screenshot({ path: join(folder, "desktop-summary.png"), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: join(folder, "mobile-summary.png"), fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.evaluate(() => { document.documentElement.classList.add("dark"); document.documentElement.setAttribute("data-theme", "dark"); });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await expect(page.getByLabel("Preço de venda (R$)", { exact: true })).toBeEnabled();
  await expect(page.getByLabel("Preço de venda (R$)", { exact: true })).toHaveCSS("color", "rgb(242, 244, 248)");
  await page.screenshot({ path: join(folder, "dark-summary.png"), fullPage: true });
  await page.getByRole("button", { name: "Voltar", exact: true }).click();
  await expect(row).toBeVisible();
  expect(errors).toEqual([]);
});
