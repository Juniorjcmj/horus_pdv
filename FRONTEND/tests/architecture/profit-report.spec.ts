import { test, expect, type Page } from "@playwright/test";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { mkdirSync, readFileSync } from "node:fs";

const user = { id: "report-ui", companyId: "empresa-principal", name: "Administrador", email: "report@teste.invalid",
  role: "administrador", status: "ativo", mustChangePassword: false };
const columns = [
  ["codigo", "Código"], ["descricao", "Descrição do produto"], ["compra", "Compra média (un.)"], ["venda", "Venda média (un.)"],
  ["lucro", "Lucro bruto"], ["markup", "Markup (%)"], ["quantidade", "Quantidade vendida"], ["custo", "Custo total"],
  ["faturamento", "Total vendido"], ["baseCusto", "Base do custo"],
].map(([key, label]) => ({ key, label }));
const notes = ["Valores unitários são médias ponderadas pela quantidade vendida.",
  "Markup do período = lucro bruto ÷ custo total × 100; os percentuais dos produtos não são somados.",
  "1 produto(s) incluem vendas sem custo histórico salvo. Nessas vendas, o custo é estimado pelo cadastro atual."];
function result(count = 12) {
  const money = (value: number) => value.toLocaleString("pt-BR", { style: "currency", currency: "BRL" });
  return { columns, notes,
    rows: Array.from({ length: count }, (_, i) => ({ codigo: `P${i + 1}`, descricao: `Produto teste ${i + 1}`,
      quantidade: "3", compra: money(10), venda: money(15), custo: money(30), faturamento: money(45),
      lucro: money(15), markup: "50,00%", baseCusto: i === 0 ? "Inclui estimativa do cadastro" : "Registrado na venda" })),
    totalRow: { codigo: "TOTAL", descricao: "Todos os produtos filtrados", quantidade: "—", compra: "—", venda: "—",
      custo: money(30 * count), faturamento: money(45 * count), lucro: money(15 * count), markup: count ? "50,00%" : "—", baseCusto: "—" },
    summary: [{ label: "Custo dos produtos vendidos", value: money(30 * count) }, { label: "Total vendido", value: money(45 * count) },
      { label: "Lucro bruto do período", value: money(15 * count) }, { label: "Markup do período", value: count ? "50,00%" : "—" }],
  };
}
async function setup(page: Page, baseURL: string, theme = "light") {
  const state = { fail: false, requests: [] as { reportId: string; filters: Record<string, string> }[] };
  await page.route("**/*", async route => {
    const url = new URL(route.request().url());
    if (url.origin === new URL(baseURL).origin) return route.continue();
    if (url.hostname.includes("google")) return route.fulfill({ body: "", contentType: "text/css" });
    let data: unknown = [];
    if (/\/api\/Auth\/me$/i.test(url.pathname)) data = user;
    else if (/\/api\/Relatorio\/Gerar$/i.test(url.pathname)) {
      const request = route.request().postDataJSON(); state.requests.push(request);
      if (state.fail) return route.fulfill({ status: 400, json: { success: false, message: "A data inicial deve ser anterior ou igual à data final." } });
      data = result(request.filters.productSearch === "VAZIO" ? 0 : request.filters.productSearch ? 1 : 12);
    }
    return route.fulfill({ json: { success: true, data } });
  });
  await page.addInitScript(({ user, theme }) => {
    localStorage.setItem("horuspdv.auth.user", JSON.stringify(user));
    localStorage.setItem("horuspdv.activePage", "relatorios"); localStorage.setItem("horuspdv.theme", theme);
  }, { user, theme });
  await page.goto("/");
  await page.getByRole("button", { name: /Lucro e markup por produto Compare/ }).click();
  return state;
}
test("totais abrangem todas as páginas e são recalculados pelo filtro de produto", async ({ page, baseURL }) => {
  const state = await setup(page, baseURL!);
  await page.getByRole("button", { name: "Gerar relatório", exact: true }).click();
  const totals = page.getByRole("region", { name: "Totais do período filtrado" });
  await expect(totals).toContainText("540,00"); await expect(totals).toContainText("180,00");
  await expect(totals).toContainText("50,00%");
  await expect(page.locator("tbody tr")).toHaveCount(10); await expect(page.locator("tfoot")).toContainText("540,00");
  await page.getByRole("button", { name: "Próxima", exact: true }).click();
  await expect(page.locator("tbody tr")).toHaveCount(2); await expect(totals).toContainText("180,00");
  await expect(page.getByText(notes[2], { exact: true })).toBeVisible();
  await page.getByRole("searchbox", { name: "Código ou descrição do produto" }).fill("P1");
  await page.getByRole("button", { name: "Gerar relatório", exact: true }).click();
  await expect(totals).toContainText("45,00"); await expect(totals).toContainText("15,00");
  expect(state.requests[1].reportId).toBe("lucro-markup-produtos");
  expect(state.requests[1].filters.productSearch).toBe("P1");
  expect(state.requests[1].filters.startDate).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  await expect(page.locator("tbody tr")).toHaveCount(1);
});
test("exporta todas as linhas, totais e avisos; falha não mantém resultados antigos", async ({ page, baseURL }) => {
  const state = await setup(page, baseURL!);
  await page.getByRole("button", { name: "Gerar relatório", exact: true }).click();
  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("button", { name: "Exportar Excel" }).click();
  const download = await downloadPromise;
  const path = await download.path(); const exported = readFileSync(path!, "utf8");
  expect(exported).toContain("Produto teste 12"); expect(exported).toContain("TOTAL");
  expect(exported).toContain("540,00"); expect(exported).toContain("estimado"); expect(exported).toContain("Data inicial:");
  const popupPromise = page.waitForEvent("popup");
  await page.getByRole("button", { name: "Exportar PDF" }).click();
  const popup = await popupPromise;
  await expect(popup.locator("body")).toContainText("Produto teste 12");
  await expect(popup.locator("body")).toContainText("TOTAL");
  await expect(popup.locator("body")).toContainText("estimado"); await popup.close();
  state.fail = true;
  await page.getByRole("button", { name: "Gerar relatório", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("A data inicial");
  await expect(page.getByRole("region", { name: "Totais do período filtrado" })).toHaveCount(0);
  state.fail = false;
  await page.getByRole("searchbox", { name: "Código ou descrição do produto" }).fill("VAZIO");
  await page.getByRole("button", { name: "Gerar relatório", exact: true }).click();
  await expect(page.getByText("Nenhum dado encontrado para os filtros aplicados.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Exportar Excel" })).toBeDisabled();
  await expect(page.getByRole("region", { name: "Totais do período filtrado" })).toContainText("0,00");
});
test("visual do relatório em desktop, celular e tema escuro", async ({ page, baseURL }) => {
  const folder = join(tmpdir(), "pdv-profit-report"); mkdirSync(folder, { recursive: true });
  await setup(page, baseURL!);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.getByRole("button", { name: "Gerar relatório", exact: true }).click();
  await expect(page.getByRole("region", { name: "Totais do período filtrado" })).toBeVisible();
  await page.screenshot({ path: join(folder, "desktop.png"), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("region", { name: "Totais do período filtrado" }).scrollIntoViewIfNeeded();
  await page.screenshot({ path: join(folder, "mobile.png"), fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.evaluate(() => { document.documentElement.classList.add("dark"); document.documentElement.setAttribute("data-theme", "dark"); });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.screenshot({ path: join(folder, "dark.png"), fullPage: true });
});
