import { expect, test } from "@playwright/test";
const nota = { id: "ne-test", modelo: 55, numeroNota: "10490", serie: "116", fornecedorNome: "FORNECEDOR DO TESTE", fornecedorCnpj: "36716865000104", chaveAcesso: "33261036716865000104551160000104901000214270", criadaEm: "2026-10-10T12:37:00Z", dataEmissao: "2026-10-10T09:37:00-03:00", valorNota: 37.25, valorEntrada: 16, quantidadeItens: 1, temXml: true, origem: "xml", usuarioNome: "Operador" };

test.beforeEach(async ({ page, baseURL }) => {
  const user = { id: "test", companyId: "test-company", name: "Operador", role: "gerente", status: "ativo", email: "test@teste.invalid", mustChangePassword: false };
  await page.route("**/*", route => {
    const url = new URL(route.request().url());
    if (url.origin === new URL(baseURL!).origin) return route.continue();
    if (url.pathname.endsWith("/NfeImport/notas-entrada/ne-test/xml")) return route.fulfill({ contentType: "application/xml", body: "<NFe>XML ORIGINAL</NFe>" });
    const data = url.pathname.endsWith("/Auth/me") ? user : url.pathname.endsWith("/NfeImport/notas-entrada")
      ? { notas: [nota], total: 1, pagina: 1, tamanhoPagina: 20 } : url.pathname.endsWith("/NfeImport/notas-entrada/ne-test")
      ? { nota, entrada: { fornecedor: {}, itens: [{ numeroItem: 1, productName: "Sal grosso", productCode: "SKU-TESTE", quantidade: "2", precoCusto: "8,00", unidadeComercial: "UN" }] } } : [];
    return route.fulfill({ json: { success: true, data } });
  });
  await page.addInitScript(user => { localStorage.setItem("horuspdv.auth.user", JSON.stringify(user)); localStorage.setItem("horuspdv.activePage", "cadastro-produto"); }, user);
  await page.goto("/");
  await page.getByRole("button", { name: "Importar / Cargas" }).click();
  await page.getByRole("button", { name: /Notas de entrada/ }).click();
  await expect(page.getByRole("heading", { name: "Notas de entrada", exact: true })).toBeVisible();
});

test("histórico pesquisa notas, mostra itens e baixa o XML preservado", async ({ page }) => {
  await expect(page.getByText("XML armazenado", { exact: true })).toBeVisible();
  await page.getByRole("textbox", { name: "Fornecedor, número ou chave da nota" }).fill("10490");
  const search = page.waitForRequest(request => request.url().includes("/notas-entrada?") && new URL(request.url()).searchParams.get("busca") === "10490");
  await page.getByRole("button", { name: "Buscar", exact: true }).click(); await search;
  await page.getByRole("button", { name: "Ver NF-e 10490 · Série 116" }).click();
  await expect(page.getByText("SKU-TESTE", { exact: true })).toBeVisible();
  await expect(page.getByText("R$ 16,00", { exact: true })).toBeVisible();
  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "Baixar XML original" }).click();
  expect((await download).suggestedFilename()).toContain(nota.chaveAcesso);
  await page.getByRole("button", { name: "Voltar às notas" }).click();
  await expect(page.getByText("XML armazenado", { exact: true })).toBeVisible();
});

test("cupom digitado informa ausência de XML e não oferece download", async ({ page }) => {
  await page.route("**/NfeImport/notas-entrada/ne-test", route => route.fulfill({ json: { success: true, data: { nota: { ...nota, modelo: 65, temXml: false, origem: "digitada", valorNota: null }, entrada: { itens: [] } } } }));
  await page.getByRole("button", { name: "Ver NF-e 10490 · Série 116" }).click();
  await expect(page.getByText(/A chave e os itens foram armazenados/)).toBeVisible();
  await expect(page.getByRole("button", { name: "Baixar XML original" })).toHaveCount(0);
});

test("erro de consulta permite tentar novamente e trata histórico vazio", async ({ page }) => {
  let requests = 0;
  await page.route("**/NfeImport/notas-entrada?**", route => {
    requests++;
    return route.fulfill(requests === 1 ? { status: 503, json: { success: false, message: "Servidor indisponível. Tente novamente." } }
      : { json: { success: true, data: { notas: [], total: 0, pagina: 1, tamanhoPagina: 20 } } });
  });
  await page.getByRole("button", { name: "Atualizar", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("Servidor indisponível");
  await page.getByRole("button", { name: "Tentar novamente" }).click();
  await expect(page.getByText("Ainda não há notas de entrada armazenadas", { exact: true })).toBeVisible();
});

test("histórico mantém controles acessíveis em tela estreita", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole("button", { name: "Buscar", exact: true })).toBeVisible();
  const viewportFits = await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
  expect(viewportFits).toBe(true);
  await expect(page.locator('div[role="region"][aria-label="Notas de entrada"]')).toBeVisible();
});
