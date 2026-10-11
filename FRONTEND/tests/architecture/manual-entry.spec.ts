import { test, expect, type Page } from "@playwright/test";
import { mkdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";

const user = { id: "manual-user", companyId: "empresa-principal", name: "Operador", role: "gerente", status: "ativo", mustChangePassword: false };
const product = { id: "existing", productCode: "789123", productName: "Cerveja lata", productUnitPrice: "4,00", productSalePrice: "6,00", productQnt: "18",
  productImageUrl: "", productImageName: "", productSupplier: "", productDescription: "", unidadeComercial: "UN", gtin: "789123", ncm: "22030000",
  lucro: "2,00", margemDesejadaPercentual: "100", fatorConversao: "12", unidadeCompra: "CX", qtdEmbalagem: "12", estoqueMinimo: "0" };
const receipt = { id: "ne-manual", modelo: 0, numeroNota: "CUPOM-1", serie: "", fornecedorNome: "", fornecedorCnpj: "", chaveAcesso: null, criadaEm: "2026-10-11T12:00:00Z",
  dataEmissao: "2026-10-11", valorNota: 78, valorEntrada: 78, quantidadeItens: 2, temXml: false, origem: "manual", usuarioNome: "Operador", temComprovante: true, comprovanteNome: "cupom.pdf" };
async function setup(page: Page, baseURL: string) {
  const state = { requests: [] as Record<string, unknown>[], failure: "", failProducts: false, delay: false };
  await page.route("**/*", async route => {
    const url = new URL(route.request().url());
    if (url.origin === new URL(baseURL).origin) return route.continue();
    if (url.hostname.includes("google")) return route.fulfill({ body: "", contentType: "text/css" });
    let data: unknown = [];
    if (/\/Auth\/me$/i.test(url.pathname)) data = user;
    else if (/\/Produto\/entrada-manual$/i.test(url.pathname)) {
      state.requests.push(route.request().postDataJSON());
      if (state.delay) await new Promise(resolve => setTimeout(resolve, 350));
      if (state.failure === "network") return route.abort("failed");
      if (state.failure === "validation") return route.fulfill({ status: 400, json: { success: false, message: "Custo inválido. Confira o item." } });
      data = { notaEntradaId: receipt.id, produtosCriados: 1, produtosAtualizados: 1 };
    } else if (/\/Produto$/i.test(url.pathname)) {
      if (state.failProducts) return route.fulfill({ status: 503, json: { success: false, message: "Indisponível" } });
      data = [product];
    } else if (/vencimentos\/resumo$/i.test(url.pathname)) data = { vencidos: 0, venceEm7Dias: 0, venceEm15Dias: 0, venceEm30Dias: 0, totalControlados: 0, semDataInformada: 0 };
    else if (/\/notas-entrada\/ne-manual\/comprovante$/i.test(url.pathname)) return route.fulfill({ body: "%PDF-1.4\nCOMPROVANTE", contentType: "application/pdf" });
    else if (/\/notas-entrada\/ne-manual$/i.test(url.pathname)) data = { nota: receipt, entrada: { tipoDocumento: "cupom", observacao: "Compra para reposição", fornecedor: {}, itens: [
      { numeroItem: 1, produtoExistenteId: "existing", productCode: "789123", productName: "Cerveja lata", quantidade: "12", unidadeComercial: "UN", precoCusto: "4", precoVenda: "6", quantidadeCompra: "1", unidadeCompra: "CX", fatorConversao: "12", estoqueAntes: "18", estoqueDepois: "30" }] } };
    return route.fulfill({ json: { success: true, data } });
  });
  await page.addInitScript(user => { localStorage.setItem("horuspdv.auth.user", JSON.stringify(user)); localStorage.setItem("horuspdv.activePage", "cadastro-produto"); localStorage.setItem("horuspdv.theme", "light"); }, user);
  await page.goto("/");
  await page.getByRole("button", { name: "Importar / Cargas" }).click();
  await page.getByRole("button", { name: /Entrada manual Receba/ }).click();
  await expect(page.getByRole("heading", { name: "Entrada manual", exact: true })).toBeVisible();
  return state;
}
async function existing(page: Page) {
  const search = page.getByRole("searchbox", { name: "Buscar por código, descrição ou leitor" });
  await search.fill("789123"); await search.press("Enter");
  await page.getByRole("combobox", { name: "Unidade da compra", exact: true }).selectOption("CX");
  await page.getByLabel("UN por unidade comprada", { exact: true }).fill("12");
  await page.getByLabel("Custo de 1 CX (R$)", { exact: true }).fill("48,00");
  await expect(page.getByLabel("Preço de venda por UN (R$)", { exact: true })).toBeDisabled();
  await expect(page.getByRole("status").filter({ hasText: "Estoque atual" })).toContainText("Após esta entrada: 30 UN");
  await page.getByRole("button", { name: "Adicionar à entrada", exact: true }).click();
}

test("recebe existentes e novos, converte caixa, anexa comprovante e abre histórico", async ({ page, baseURL }) => {
  const state = await setup(page, baseURL!); await existing(page);
  await page.getByRole("button", { name: "Cadastrar novo produto", exact: true }).click();
  await page.getByLabel("Descrição do produto", { exact: true }).fill("Batata KG");
  await page.getByRole("combobox", { name: "Unidade de venda", exact: true }).selectOption("KG");
  await page.getByRole("combobox", { name: "Unidade da compra", exact: true }).selectOption("KG");
  await page.getByLabel("Quantidade comprada", { exact: true }).fill("10");
  await page.getByLabel("Custo de 1 KG (R$)", { exact: true }).fill("3,00");
  await page.getByLabel("Preço de venda por KG (R$)", { exact: true }).fill("5,99");
  await page.getByRole("button", { name: "Adicionar à entrada", exact: true }).click();
  await expect(page.getByRole("region", { name: "Itens da entrada manual" })).toContainText("Novo produto");
  await page.getByLabel("Número do documento (opcional)", { exact: true }).fill("CUPOM-1");
  await page.getByText("Chave, comprovante e observação (opcionais)", { exact: true }).click();
  await page.getByLabel("Observação", { exact: true }).fill("Compra para reposição");
  await page.getByLabel("Foto, PDF ou XML · até 10 MB").setInputFiles({ name: "cupom.pdf", mimeType: "application/pdf", buffer: Buffer.from("%PDF-1.4\nCOMPROVANTE") });
  await page.getByRole("button", { name: "Remover comprovante" }).click();
  await expect(page.getByLabel("Foto, PDF ou XML · até 10 MB")).toHaveValue("");
  await page.getByLabel("Foto, PDF ou XML · até 10 MB").setInputFiles({ name: "cupom.pdf", mimeType: "application/pdf", buffer: Buffer.from("%PDF-1.4\nCOMPROVANTE") });
  await expect(page.getByText("Total da entrada:", { exact: false }).filter({ hasText: "78,00" })).toBeVisible();
  expect(state.requests).toHaveLength(0);
  const folder = join(tmpdir(), "pdv-manual-entry"); mkdirSync(folder, { recursive: true });
  await page.setViewportSize({ width: 1440, height: 1000 }); await page.screenshot({ path: join(folder, "desktop.png"), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 }); await page.screenshot({ path: join(folder, "mobile.png"), fullPage: true });
  await page.getByRole("button", { name: "Confirmar entrada no estoque", exact: true }).scrollIntoViewIfNeeded();
  await page.screenshot({ path: join(folder, "mobile-items.png"), fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.evaluate(() => { document.documentElement.classList.add("dark"); document.documentElement.setAttribute("data-theme", "dark"); });
  await page.setViewportSize({ width: 1440, height: 1000 }); await page.screenshot({ path: join(folder, "dark.png"), fullPage: true });
  await page.getByRole("button", { name: "Confirmar entrada no estoque", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Entrada registrada", exact: true })).toBeVisible();
  expect(state.requests).toHaveLength(1);
  expect(state.requests[0].fornecedorId).toBeNull(); expect(state.requests[0].chaveAcesso).toBeNull();
  const items = state.requests[0].itens as Record<string, unknown>[];
  expect(items[0]).toMatchObject({ produtoExistenteId: "existing", quantidadeCompra: "1", fatorConversao: "12", custoCompra: "48,00", alterarPrecoVenda: false });
  expect(items[1]).toMatchObject({ produtoExistenteId: null, productName: "Batata KG", productCode: "", precoVenda: "5,99" });
  expect(state.requests[0].comprovante).toMatchObject({ nome: "cupom.pdf" });
  await page.getByRole("button", { name: "Ver entrada no histórico", exact: true }).click();
  await expect(page.getByText("Entrada manual · CUPOM-1", { exact: true })).toBeVisible();
  await expect(page.getByText("Sem fornecedor", { exact: true })).toBeVisible();
  await expect(page.getByText(/Estoque: 18 → 30/)).toBeVisible();
  const download = page.waitForEvent("download"); await page.getByRole("button", { name: "Baixar comprovante" }).click();
  const file = await download; expect(file.suggestedFilename()).toBe("cupom.pdf"); expect(readFileSync((await file.path())!, "utf8")).toContain("COMPROVANTE");
});

test("falha de rede bloqueia alterações e repete exatamente a mesma confirmação", async ({ page, baseURL }) => {
  const state = await setup(page, baseURL!); await existing(page); state.failure = "network";
  await page.getByRole("button", { name: "Confirmar entrada no estoque", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("Não foi possível conectar");
  await expect(page.getByLabel("Número do documento (opcional)", { exact: true })).toBeDisabled();
  await expect(page.getByRole("button", { name: "Cadastrar novo produto" })).toBeDisabled();
  state.failure = "";
  await page.getByRole("button", { name: "Tentar confirmar novamente", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Entrada registrada", exact: true })).toBeVisible();
  expect(state.requests).toHaveLength(2); expect(state.requests[1]).toEqual(state.requests[0]);
});

test("validação permite editar e dupla confirmação envia uma única operação", async ({ page, baseURL }) => {
  const state = await setup(page, baseURL!); await existing(page); state.failure = "validation";
  await page.getByRole("button", { name: "Confirmar entrada no estoque", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("Custo inválido");
  await expect(page.getByLabel("Número do documento (opcional)", { exact: true })).toBeEnabled();
  await page.getByRole("button", { name: "Editar Cerveja lata", exact: true }).click();
  await page.getByLabel("Custo de 1 CX (R$)", { exact: true }).fill("60,00");
  await page.getByRole("button", { name: "Salvar alteração do item", exact: true }).click();
  state.failure = ""; state.delay = true;
  await page.getByRole("button", { name: "Confirmar entrada no estoque", exact: true }).evaluate(el => { (el as HTMLButtonElement).click(); (el as HTMLButtonElement).click(); });
  await expect(page.getByRole("heading", { name: "Entrada registrada", exact: true })).toBeVisible();
  expect(state.requests).toHaveLength(2);
  expect((state.requests[1].itens as Record<string, unknown>[])[0].custoCompra).toBe("60,00");
});

test("rascunho não cria produto e volta com proteção para descartar", async ({ page, baseURL }) => {
  const state = await setup(page, baseURL!);
  await page.getByRole("button", { name: "Cadastrar novo produto" }).click();
  await page.getByLabel("Descrição do produto", { exact: true }).fill("Produto em rascunho");
  await page.getByRole("button", { name: "Voltar aos produtos", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("ainda não foi salva");
  await page.getByRole("button", { name: "Continuar preenchendo", exact: true }).click();
  await expect(page.getByLabel("Descrição do produto", { exact: true })).toHaveValue("Produto em rascunho");
  expect(state.requests).toHaveLength(0);
});
