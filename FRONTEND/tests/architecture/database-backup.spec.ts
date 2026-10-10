import { test, expect, type Page } from "@playwright/test";
import { readFile } from "node:fs/promises";

const admin = { id: "backup-admin", companyId: "empresa-principal", name: "Administrador", email: "backup@teste.invalid",
  role: "administrador", status: "ativo", createdAt: "2026-10-10T00:00:00Z", lastLoginAt: "2026-10-10T00:00:00Z", mustChangePassword: false };
const id = "11111111-2222-3333-4444-555555555555";
const fileName = "quack-pdv-completo-teste.bak";
const bytes = Buffer.from("ARQUIVO-BAK-DE-TESTE-DO-DOWNLOAD");

async function setup(page: Page, baseURL: string, companyId = "empresa-principal") {
  const user = { ...admin, companyId };
  let status = "gerando";
  let starts = 0;
  let failStart = false;
  let expiresAt = new Date(Date.now() + 6 * 60 * 60 * 1000).toISOString();
  const state = () => ({ id, status, fileName, createdAt: new Date().toISOString(), expiresAt, sizeBytes: 1048576, message: status === "falhou" ? "Pasta do SQL indisponível." : null });
  await page.route("**/*", route => {
    const url = new URL(route.request().url());
    if (url.origin === new URL(baseURL).origin) return route.continue();
    if (url.hostname.includes("google")) return route.fulfill({ body: "", contentType: "text/css" });
    const success = (data: unknown) => route.fulfill({ json: { success: true, data } });
    if (/\/backup$/i.test(url.pathname) && route.request().method() === "POST") {
      starts++;
      if (failStart) return route.fulfill({ status: 503, json: { success: false, message: "Configure a pasta compartilhada de backups no servidor." } });
      return success(state());
    }
    if (url.pathname.endsWith(`/backup/${id}/arquivo`)) return route.fulfill({ body: bytes, contentType: "application/octet-stream",
      headers: { "Content-Disposition": `attachment; filename="${fileName}"`, "Cache-Control": "no-store" } });
    if (url.pathname.endsWith(`/backup/${id}`)) return success(state());
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
  return { setStatus: (value: string) => { status = value; }, starts: () => starts,
    failStart: () => { failStart = true; }, expire: () => { expiresAt = new Date(Date.now() - 1000).toISOString(); } };
}

test("gera, verifica e baixa arquivo sem bloquear o painel; retoma na mesma aba", async ({ page, baseURL }) => {
  const server = await setup(page, baseURL!);
  await page.getByRole("button", { name: "Fazer backup completo", exact: true }).click();
  await expect(page.getByRole("button", { name: "Preparando backup…", exact: true })).toBeDisabled();
  await page.reload();
  await expect(page.getByRole("button", { name: "Preparando backup…", exact: true })).toBeDisabled();
  expect(server.starts()).toBe(1);
  server.setStatus("verificando");
  await expect(page.getByText("Verificando a integridade do backup…", { exact: true })).toBeVisible();
  server.setStatus("concluido");
  await expect(page.getByRole("button", { name: "Baixar backup", exact: true })).toBeVisible();
  const request = page.waitForEvent("download");
  await page.getByRole("button", { name: "Baixar backup", exact: true }).click();
  const download = await request;
  expect(download.suggestedFilename()).toBe(fileName);
  expect(await readFile((await download.path())!)).toEqual(bytes);
  await expect(page.locator("main")).toHaveAttribute("data-active-page", "gerenciamento-geral");
});

test("erro de configuração é visível e não dispara novas gerações", async ({ page, baseURL }) => {
  const server = await setup(page, baseURL!);
  server.failStart();
  await page.getByRole("button", { name: "Fazer backup completo", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("Configure a pasta compartilhada");
  await expect(page.getByRole("button", { name: "Fazer backup completo", exact: true })).toBeEnabled();
  expect(server.starts()).toBe(1);
});

test("backup que falhou ou expirou não fica disponível para download", async ({ page, baseURL }) => {
  const server = await setup(page, baseURL!);
  server.setStatus("falhou");
  await page.getByRole("button", { name: "Fazer backup completo", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("Pasta do SQL indisponível");
  await expect(page.getByRole("button", { name: "Baixar backup", exact: true })).toHaveCount(0);
  server.setStatus("concluido");
  server.expire();
  await page.reload();
  await expect(page.getByRole("alert")).toContainText("backup expirou");
  await expect(page.getByRole("button", { name: "Baixar backup", exact: true })).toHaveCount(0);
});

test("administrador de loja não acessa o botão de backup da plataforma", async ({ page, baseURL }) => {
  await setup(page, baseURL!, "loja-a");
  await expect(page.getByRole("button", { name: "Fazer backup completo", exact: true })).toHaveCount(0);
  await expect(page.locator("main")).not.toHaveAttribute("data-active-page", "gerenciamento-geral");
});
