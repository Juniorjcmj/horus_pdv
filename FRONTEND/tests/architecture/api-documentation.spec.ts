import { test, expect, type Page } from "@playwright/test";
import { readFile } from "node:fs/promises";

const admin = { id: "documentation-admin", companyId: "empresa-principal", name: "Administrador", email: "admin@teste.invalid",
  role: "administrador", status: "ativo", createdAt: "2026-10-11T00:00:00Z", lastLoginAt: "2026-10-11T00:00:00Z", mustChangePassword: false };
const document = { openapi: "3.0.1", info: { title: "Quack Sistemas — API do PDV", version: "v1" },
  paths: { "/api/Produto": { get: { responses: { "200": { description: "Produtos" } } } } }, components: { schemas: {} } };

async function setup(page: Page, baseURL: string, companyId = "empresa-principal") {
  const user = { ...admin, companyId };
  let failure = 0;
  let invalid = false;
  let calls = 0;
  await page.route("**/*", async route => {
    const url = new URL(route.request().url());
    if (url.origin === new URL(baseURL).origin) return route.continue();
    if (url.hostname.includes("google")) return route.fulfill({ body: "", contentType: "text/css" });
    const success = (data: unknown) => route.fulfill({ json: { success: true, data } });
    if (url.pathname.endsWith("/documentacao-api")) {
      calls++;
      await new Promise(resolve => setTimeout(resolve, 400));
      if (failure) return route.fulfill({ status: failure, json: { success: false, message: "Download recusado pelo servidor." } });
      return route.fulfill({ json: invalid ? { success: true, data: [] } : document, headers: { "Cache-Control": "no-store" } });
    }
    if (/\/Auth\/me$/i.test(url.pathname)) return success(user);
    if (/\/Empresas\/metricas$/i.test(url.pathname)) return success({ total: 0, pendentes: 0, aprovadas: 0, rejeitadas: 0, bloqueadas: 0, requireApprovalForNewCompanies: true });
    if (/\/Admin\/Empresas$/i.test(url.pathname)) return success({ items: [], totalCount: 0, page: 1, pageSize: 15 });
    return success([]);
  });
  await page.addInitScript(user => {
    localStorage.setItem("horuspdv.auth.user", JSON.stringify(user));
    localStorage.setItem("horuspdv.activePage", "gerenciamento-geral");
    localStorage.setItem("horuspdv.theme", "light");
  }, user);
  await page.goto("/");
  return { fail: (status: number) => { failure = status; }, invalid: () => { invalid = true; }, calls: () => calls };
}

test("baixa JSON válido sem sair da tela e mostra carregamento", async ({ page, baseURL }, testInfo) => {
  const server = await setup(page, baseURL!);
  const requested = page.waitForEvent("download");
  await page.getByRole("button", { name: "Baixar documentação JSON", exact: true }).click();
  await expect(page.getByRole("button", { name: "Preparando JSON…", exact: true })).toBeDisabled();
  const download = await requested;
  expect(download.suggestedFilename()).toBe("quack-pdv-api-openapi.json");
  expect(JSON.parse(await readFile((await download.path())!, "utf8"))).toEqual(document);
  expect(server.calls()).toBe(1);
  await expect(page.locator("main")).toHaveAttribute("data-active-page", "gerenciamento-geral");
  const panel = page.getByRole("region", { name: "Documentação da API", exact: true });
  await expect(panel.getByRole("status")).toContainText("Download solicitado");
  await panel.scrollIntoViewIfNeeded();
  await page.screenshot({ path: testInfo.outputPath("documentacao-desktop.png") });
  await page.setViewportSize({ width: 390, height: 844 });
  await panel.scrollIntoViewIfNeeded();
  await page.screenshot({ path: testInfo.outputPath("documentacao-mobile.png") });
});

test("API antiga mostra orientação de atualização e permite tentar novamente", async ({ page, baseURL }) => {
  const server = await setup(page, baseURL!);
  server.fail(404);
  await page.getByRole("button", { name: "Baixar documentação JSON", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("Atualize a API do servidor");
  await expect(page.getByRole("button", { name: "Baixar documentação JSON", exact: true })).toBeEnabled();
  server.fail(0);
  const requested = page.waitForEvent("download");
  await page.getByRole("button", { name: "Baixar documentação JSON", exact: true }).click();
  await requested;
  expect(server.calls()).toBe(2);
});

test("recusa da API não baixa erro como documentação", async ({ page, baseURL }) => {
  const server = await setup(page, baseURL!);
  server.fail(403);
  const downloads: string[] = [];
  page.on("download", download => downloads.push(download.suggestedFilename()));
  await page.getByRole("button", { name: "Baixar documentação JSON", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("Download recusado pelo servidor");
  expect(downloads).toHaveLength(0);
});

test("resposta sem OpenAPI não gera arquivo inválido", async ({ page, baseURL }) => {
  const server = await setup(page, baseURL!);
  server.invalid();
  const downloads: string[] = [];
  page.on("download", download => downloads.push(download.suggestedFilename()));
  await page.getByRole("button", { name: "Baixar documentação JSON", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("OpenAPI válida");
  expect(downloads).toHaveLength(0);
});

test("administrador de loja não tem acesso ao download da plataforma", async ({ page, baseURL }) => {
  await setup(page, baseURL!, "outra-loja");
  await expect(page.getByRole("button", { name: "Baixar documentação JSON", exact: true })).toHaveCount(0);
});
