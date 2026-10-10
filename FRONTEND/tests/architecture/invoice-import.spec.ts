import { expect, test } from "@playwright/test";
const key = "33261036716865000104651160000104891000214277";
const supplier = { id: "supplier-test", cnpj: "36.716.865/0001-04", companyName: "FORNECEDOR TESTE", fantasyName: "MERCADO TESTE", cep: "20000000", city: "Rio de Janeiro", state: "RJ", address: "AV TESTE", neighborhood: "CENTRO", number: "1", telephone: "", streetComplement: "", referencePoint: "", cellphone: "", email: "" };
const product = { id: "product-test", productName: "Produto cadastrado", productCode: "7896035210018", productUnitPrice: "6,00", productSalePrice: "10,00", productQnt: "5", unidadeComercial: "UN", ncm: "25010020", gtin: "7896035210018" };

test.beforeEach(async ({ page, baseURL }) => {
  const user = { id: "invoice-user", companyId: "invoice-company", name: "Teste", role: "gerente", status: "ativo", email: "test@teste.invalid", createdAt: new Date().toISOString(), mustChangePassword: false };
  await page.route("**/*", route => {
    if (new URL(route.request().url()).origin === new URL(baseURL!).origin) return route.continue();
    const path = new URL(route.request().url()).pathname;
    const data = path.endsWith("/Auth/me") ? user : path.endsWith("/Fornecedor") ? [supplier] : path.endsWith("/Produto") ? [product] : [];
    return route.fulfill({ json: { success: true, data } });
  });
  await page.addInitScript(user => {
    localStorage.setItem("horuspdv.auth.user", JSON.stringify(user));
    localStorage.setItem("horuspdv.activePage", "cadastro-produto");
  }, user);
  await page.goto("/");
  await page.getByRole("button", { name: "Importar / Cargas" }).click();
  await page.getByRole("button", { name: "Entrada de NF-e / NFC-e" }).click();
});

test("chave NFC-e abre entrada, preserva fornecedor e envia itens revisados sem chamar distribuição NF-e", async ({ page }) => {
  let queries = 0;
  const writes: Record<string, unknown>[] = [];
  await page.route("**/NfeImport/buscar-sefaz", route => { queries++; return route.fulfill({ status: 400 }); });
  await page.route("**/NfeImport/confirmar", route => { writes.push(route.request().postDataJSON()); return route.fulfill({ json: { success: true, data: { produtosCriados: 1, produtosAtualizados: 0, fornecedorCriado: false } } }); });
  await page.getByRole("textbox", { name: "Chave de acesso da nota" }).fill(key);
  await expect(page.getByRole("status").filter({ hasText: "NFC-e (modelo 65)" })).toContainText("10489");
  await page.getByRole("button", { name: "Digitar itens do cupom" }).click();
  await expect(page.getByRole("textbox", { name: "Razão social", exact: true })).toHaveValue(supplier.companyName);
  await page.getByRole("button", { name: /Confirmar entrada/ }).click();
  await expect(page.getByText("Confira os itens:", { exact: false })).toBeVisible();
  expect(writes).toHaveLength(0);
  await page.getByRole("textbox", { name: "Descrição item 1", exact: true }).fill("Sal grosso");
  await page.getByRole("textbox", { name: "Quantidade item 1", exact: true }).fill("4");
  await page.getByRole("textbox", { name: "Custo item 1", exact: true }).fill("745");
  await page.getByRole("textbox", { name: "Venda item 1", exact: true }).fill("1000");
  await page.getByRole("button", { name: "Adicionar item", exact: true }).click();
  await expect(page.getByRole("textbox", { name: "Descrição item 2" })).toBeVisible();
  await page.getByRole("button", { name: "Remover item 2" }).click();
  await page.getByRole("button", { name: /Confirmar entrada/ }).click();
  await expect.poll(() => writes.length).toBe(1);
  expect(queries).toBe(0);
  expect(writes[0]).toMatchObject({ fornecedor: { cnpj: supplier.cnpj, address: supplier.address, cep: supplier.cep }, itens: [{ quantidade: "4", precoCusto: "7,45", precoVenda: "10,00", productName: "Sal grosso", gtin: "SEM GTIN" }] });
});

test("cupom vincula produto existente e conserva preço de venda", async ({ page }) => {
  await page.getByRole("textbox", { name: "Chave de acesso da nota" }).fill(key);
  await page.getByRole("button", { name: "Digitar itens do cupom" }).click();
  await page.getByRole("textbox", { name: "Custo item 1", exact: true }).fill("745");
  await page.getByRole("button", { name: "Atrelar a produto existente..." }).click();
  await page.getByRole("button", { name: "Vincular", exact: true }).click();
  await expect(page.getByRole("textbox", { name: "Descrição item 1" })).toHaveValue(product.productName);
  await expect(page.getByRole("textbox", { name: "Venda item 1", exact: true })).toHaveValue("10,00");
  await expect(page.getByRole("textbox", { name: "Venda item 1", exact: true })).toBeDisabled();
  await expect(page.getByText("Entrada de estoque", { exact: true })).toBeVisible();
});

test("XML NFC-e pode ser enviado e revisado", async ({ page }) => {
  let xmlBase64 = "";
  await page.route("**/NfeImport/preview", route => {
    xmlBase64 = route.request().postDataJSON().xmlBase64;
    return route.fulfill({ json: { success: true, data: { modelo: 65, numeroNota: "10489", serie: "116", fornecedor: { ...supplier, jaExiste: true }, itens: [{ numeroItem: 1, produtoExistenteId: null, produtoExistenteNome: null, productName: "Sal grosso", productCode: "7896035210018", gtin: "7896035210018", ncm: "25010020", cest: null, unidadeComercial: "UN", quantidade: "4", precoCusto: "7,45", precoVendaSugerido: "10,00" }] } } });
  });
  await page.getByRole("button", { name: "Upload de Arquivo XML (Manual)" }).click();
  const xml = '<NFe xmlns="http://www.portalfiscal.inf.br/nfe"><infNFe><ide><mod>65</mod></ide></infNFe></NFe>';
  await page.locator('input[type="file"]').setInputFiles({ name: "cupom.xml", mimeType: "application/xml", buffer: Buffer.from(xml) });
  await expect(page.getByText(/NFC-e 10489 · Série 116/)).toBeVisible();
  expect(Buffer.from(xmlBase64, "base64").toString()).toBe(xml);
  await expect(page.getByRole("button", { name: /Confirmar importação/ })).toBeVisible();
});

test("chave com dígito errado é bloqueada antes da consulta", async ({ page }) => {
  let queries = 0;
  await page.route("**/NfeImport/buscar-sefaz", route => { queries++; return route.fulfill({ status: 400 }); });
  await page.getByRole("textbox", { name: "Chave de acesso da nota" }).fill(key.slice(0, -1) + "8");
  await page.getByRole("button", { name: "Consultar e Baixar Nota" }).click();
  await expect(page.getByText("Chave inválida. Confira os 44 dígitos de uma NF-e ou NFC-e.", { exact: true })).toBeVisible();
  expect(queries).toBe(0);
});
