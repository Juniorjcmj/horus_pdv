import { test, expect, type Page } from "@playwright/test";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { mkdirSync } from "node:fs";
import type { FiscalAiProductStatus, FiscalAiReport } from "../../src/services/api/fiscalAiService";

const user = { id: "fiscal-ui", companyId: "empresa-principal", name: "Administrador", email: "admin@teste.invalid", role: "administrador", status: "ativo", mustChangePassword: false };
const product = (index: number, ncm = "22030000") => ({ id: `p${index}`, productCode: `${1000 + index}`, productName: `CERVEJA TESTE ${index}`, ncm, cest: "0302103", cfop: "5405", origemMercadoria: 0, csosnIcms: "500", cstIcms: null, aliquotaIcms: 0, cstPis: "04", cstCofins: "04", cstIbsCbs: null, cClassTrib: null });
type Product = ReturnType<typeof product>;
function report(p: Product, situation = "ok"): FiscalAiReport {
  return { id: `resultado-${p.id}`, produtoId: p.id, produtoNome: p.productName, analisadoEm: "2026-10-11T13:00:00Z", crt: 1, uf: "RJ", modelo: "google/gemini-2.5-flash-lite", resumo: `Conferência de ${p.productName}`, pendencias: situation === "revisao" ? ["Confirmar o enquadramento de ST com a contabilidade"] : [], cadastroOriginal: {}, fontes: [], sugestoes: situation === "corrigir" ? [{ campo: "cest", atual: "0302100", sugerido: "0302103", justificativa: "Conferir embalagem", fontes: [], podeAplicar: true, bloqueio: null, probabilidadeJev: .97 }] : [], situacaoJev: "Conferido", custoUsd: .001, errosCadastro: [] };
}
function summary(p: Product, situation: FiscalAiProductStatus["situacao"] = "ok"): FiscalAiProductStatus {
  return { produtoId: p.id, situacao: situation, analiseId: `resultado-${p.id}`, analisadoEm: "2026-10-11T13:00:00Z", solicitacaoId: null, mensagem: "Conferência salva", aplicada: false };
}
async function setup(page: Page, baseURL: string, products = [product(1), product(2), product(3, "19012010")], theme = "light") {
  const state = {
    summaries: {} as Record<string, FiscalAiProductStatus>, reports: {} as Record<string, FiscalAiReport>,
    starts: [] as string[], polls: [] as string[], reads: 0, applications: 0, hold: false, fail: false, overviewFail: false,
    reportFail: false, active: 0, maximumActive: 0,
    jobs: {} as Record<string, { id: string; done: boolean }>,
  };
  await page.route("**/*", async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname;
    if (url.origin === new URL(baseURL).origin) return route.continue();
    if (url.hostname.includes("google")) return route.fulfill({ body: "", contentType: "text/css" });
    let data: unknown = [];
    if (/\/api\/Auth\/me$/i.test(path)) data = user;
    else if (/\/api\/Produto$/i.test(path)) data = products;
    else if (path.endsWith("/fiscal-ia/config")) data = { configurada: true, modelo: "google/gemini-2.5-flash-lite", usarJev: true };
    else if (path.endsWith("/fiscal-ia/resumos")) {
      if (state.overviewFail) return route.fulfill({ status: 503, json: { success: false, message: "Falha simulada de consulta" } });
      data = Object.values(state.summaries);
    } else if (/\/fiscal-ia\/resumos\//.test(path)) {
      data = state.summaries[decodeURIComponent(path.split("/").at(-1)!)];
    } else if (/\/fiscal-ia\/relatorios\//.test(path)) {
      state.reads++;
      if (state.reportFail) return route.fulfill({ status: 503, json: { success: false, message: "Relatório indisponível" } });
      data = { relatorio: state.reports[decodeURIComponent(path.split("/").at(-1)!)], aplicada: false, criadaEm: "2026-10-11T13:00:00Z" };
    } else if (request.method() === "POST" && /\/fiscal-ia\/[^/]+\/analises$/.test(path)) {
      const id = decodeURIComponent(path.split("/").at(-2)!);
      state.starts.push(id); state.active++; state.maximumActive = Math.max(state.maximumActive, state.active);
      const jobId = request.postDataJSON().solicitacaoId;
      state.jobs[jobId] = { id, done: false };
      data = { id: jobId };
    } else if (/\/fiscal-ia\/analises\//.test(path)) {
      const jobId = path.split("/").at(-1)!, job = state.jobs[jobId];
      state.polls.push(jobId);
      if (!job) throw new Error("Consulta de solicitação inexistente");
      const p = products.find(p => p.id === job.id)!;
      if (state.hold) data = { id: jobId, produtoId: p.id, status: "analisando", relatorio: null };
      else {
        if (!job.done) { state.active--; job.done = true; }
        if (state.fail) data = { id: jobId, produtoId: p.id, status: "falha", mensagem: "Saldo insuficiente no provedor.", relatorio: null };
        else {
          const situation = p.id === "p2" ? "revisao" : p.id === "p3" ? "corrigir" : "ok";
          const saved = report(p, situation);
          state.reports[saved.id] = saved; state.summaries[p.id] = summary(p, situation);
          data = { id: jobId, produtoId: p.id, status: "concluida", relatorio: saved };
        }
      }
    } else if (path.endsWith("/aplicar")) {
      state.applications++;
      const p = products.find(p => p.id === path.split("/").at(-2))!;
      state.summaries[p.id] = { ...summary(p, "desatualizada"), aplicada: true };
    }
    return route.fulfill({ json: { success: true, data } });
  });
  await page.addInitScript(({ user, theme }) => {
    localStorage.setItem("horuspdv.auth.user", JSON.stringify(user)); localStorage.setItem("horuspdv.activePage", "tributacao-produtos"); localStorage.setItem("horuspdv.theme", theme);
  }, { user, theme });
  return state;
}
const row = (page: Page, index: number) => page.getByRole("row").filter({ has: page.getByRole("rowheader", { name: `CERVEJA TESTE ${index}`, exact: true }) });

test("analisa na linha, mantém filtros e tabela; OK e relatório persistem ao recarregar", async ({ page, baseURL }) => {
  const state = await setup(page, baseURL!); state.hold = true;
  await page.goto("/"); await expect(row(page, 1)).toContainText("Não analisado");
  await page.getByRole("searchbox", { name: "Código ou descrição" }).fill("TESTE 1");
  await page.getByRole("textbox", { name: "Filtrar por NCM" }).fill("2203.00.00");
  await row(page, 1).getByRole("button", { name: /Analisar com IA/ }).click();
  await expect(row(page, 1).getByRole("status")).toContainText("IA está analisando");
  await expect(page.getByRole("heading", { name: "Tributação dos produtos", exact: true })).toBeVisible();
  await expect(page.getByRole("region", { name: "Tabela de tributação dos produtos" })).toBeVisible();
  await expect(page.getByRole("searchbox")).toHaveValue("TESTE 1");
  expect(state.starts).toEqual(["p1"]);
  state.hold = false;
  await expect(row(page, 1).getByText("OK", { exact: true })).toBeVisible();
  await row(page, 1).getByRole("button", { name: "Ver análise de CERVEJA TESTE 1" }).click();
  await expect(page.getByRole("heading", { name: "Análise fiscal com IA", exact: true })).toBeVisible();
  await expect(page.getByRole("table").first()).toBeVisible(); expect(state.reads).toBe(1); expect(state.starts).toHaveLength(1);
  await page.getByRole("button", { name: "Fechar análise" }).click(); await expect(page.getByRole("searchbox")).toHaveValue("TESTE 1");
  await page.reload(); await expect(row(page, 1).getByText("OK", { exact: true })).toBeVisible(); expect(state.starts).toHaveLength(1);
});

test("R, ajustes e ainda não analisados podem ser filtrados sem chamar o provedor", async ({ page, baseURL }) => {
  const products = [product(1), product(2), product(3), product(4)];
  const state = await setup(page, baseURL!, products);
  for (const [index, situation] of [[1, "ok"], [2, "revisao"], [3, "corrigir"]] as const) {
    state.summaries[`p${index}`] = summary(products[index - 1], situation); const r = report(products[index - 1], situation); state.reports[r.id] = r;
  }
  await page.goto("/");
  const filter = page.getByRole("combobox", { name: "Situação da análise" });
  for (const [value, index] of [["ok", 1], ["revisao", 2], ["corrigir", 3], ["nao_analisado", 4]] as const) {
    await filter.selectOption(value); await expect(row(page, index)).toBeVisible(); await expect(page.locator("tbody tr")).toHaveCount(1);
  }
  await filter.selectOption("analisados"); await expect(page.locator("tbody tr")).toHaveCount(3); expect(state.starts).toEqual([]);
});

test("grupo por NCM percorre produtos de todas as páginas, um por vez, sem agrupar descrições diferentes", async ({ page, baseURL }) => {
  const products = [...Array.from({ length: 51 }, (_, i) => product(i + 1)), product(52, "19012010")];
  const state = await setup(page, baseURL!, products);
  await page.goto("/");
  const ncm = page.getByRole("textbox", { name: "Filtrar por NCM" });
  await ncm.fill("2203"); await expect(page.getByRole("button", { name: "Analisar NCM com IA", exact: true })).toBeDisabled();
  await ncm.fill("22030000"); await page.getByRole("searchbox").fill("TESTE 1");
  await page.getByRole("button", { name: "Analisar com IA 51 produtos do NCM 22030000", exact: true }).click();
  await expect(page.getByText("51 de 51 análises concluídas", { exact: false })).toBeVisible({ timeout: 50_000 });
  expect(state.starts).toEqual(products.slice(0, 51).map(p => p.id)); expect(state.maximumActive).toBe(1);
  await page.getByRole("searchbox").fill("");
  await expect(row(page, 1)).toContainText("OK"); await expect(row(page, 2)).toContainText("R — Revisão contábil"); await expect(row(page, 3)).toContainText("Ajustes");
  await page.getByRole("button", { name: "Próxima", exact: true }).click(); await expect(row(page, 51)).toContainText("OK");
  await page.reload(); await expect(row(page, 52)).not.toBeVisible();
  await page.getByRole("button", { name: "Próxima", exact: true }).click(); await expect(row(page, 52)).toContainText("Não analisado");
  expect(state.starts).toHaveLength(51);
});

test("parar grupo conclui apenas produto atual e preserva resultados", async ({ page, baseURL }) => {
  const state = await setup(page, baseURL!); state.hold = true;
  await page.goto("/"); await page.getByRole("textbox", { name: "Filtrar por NCM" }).fill("22030000");
  await page.getByRole("button", { name: "Analisar com IA 2 produtos do NCM 22030000" }).click();
  await expect(row(page, 1).getByRole("status")).toContainText("IA está analisando");
  await page.getByRole("button", { name: "Parar após este produto" }).click(); state.hold = false;
  await expect(page.getByText("Grupo interrompido. Os resultados concluídos foram salvos.")).toBeVisible();
  await expect(row(page, 1)).toContainText("OK"); await expect(row(page, 2)).toContainText("Não analisado"); expect(state.starts).toEqual(["p1"]);
});

test("falha de consulta não é revisão contábil; grupo para sem iniciar produtos seguintes", async ({ page, baseURL }) => {
  const state = await setup(page, baseURL!); state.fail = true;
  await page.goto("/"); await page.getByRole("textbox", { name: "Filtrar por NCM" }).fill("22030000");
  await page.getByRole("button", { name: "Analisar com IA 2 produtos do NCM 22030000" }).click();
  await expect(row(page, 1)).toContainText("Falha na consulta"); await expect(row(page, 1)).toContainText("Saldo insuficiente");
  await expect(page.getByText(/Grupo interrompido após uma falha/)).toBeVisible();
  expect(state.starts).toEqual(["p1"]); await expect(row(page, 1)).not.toContainText("Revisão contábil");
});

test("retoma análise em andamento ao voltar, somente por GET", async ({ page, baseURL }) => {
  const state = await setup(page, baseURL!);
  const jobId = "11111111-1111-4111-8111-111111111111";
  state.jobs[jobId] = { id: "p1", done: false }; state.active = 1; state.hold = true;
  state.summaries.p1 = { ...summary(product(1), "analisando"), analiseId: null, analisadoEm: null, solicitacaoId: jobId };
  await page.goto("/"); await expect(row(page, 1).getByRole("status")).toContainText("IA está analisando");
  await page.reload(); await expect(row(page, 1).getByRole("status")).toContainText("IA está analisando");
  state.hold = false; await expect(row(page, 1).getByText("OK", { exact: true })).toBeVisible();
  expect(state.starts).toEqual([]); expect(state.polls.every(id => id === jobId)).toBe(true);
});

test("falha ao recuperar situações não finge que produtos nunca foram analisados", async ({ page, baseURL }) => {
  const state = await setup(page, baseURL!); state.overviewFail = true;
  await page.goto("/"); await expect(row(page, 1)).toContainText("Situação indisponível"); await expect(row(page, 1)).not.toContainText("Não analisado");
  state.overviewFail = false; state.summaries.p1 = summary(product(1));
  await page.getByRole("button", { name: "Consultar situações novamente" }).click(); await expect(row(page, 1).getByText("OK", { exact: true })).toBeVisible();
});

test("aplicar correção deixa reanalisar; abrir relatório nunca inicia análise paga", async ({ page, baseURL }) => {
  const state = await setup(page, baseURL!, [product(3)]);
  state.summaries.p3 = summary(product(3), "corrigir"); const r = report(product(3), "corrigir"); state.reports[r.id] = r;
  await page.goto("/"); await row(page, 3).getByRole("button", { name: "Ver análise de CERVEJA TESTE 3" }).click();
  await page.getByRole("checkbox", { name: "Selecionar correção de CEST" }).check();
  await page.getByRole("checkbox", { name: /Revisei as sugestões/ }).check();
  await page.getByRole("button", { name: "Aplicar 1 correção selecionada", exact: true }).click();
  await expect(row(page, 3)).toContainText("Reanalisar"); expect(state.applications).toBe(1); expect(state.starts).toEqual([]);
});

test("verificação visual em desktop, tablet, celular e tema escuro", async ({ browser, baseURL }) => {
  const target = join(tmpdir(), "pdv-fiscal-status", "screenshots"); mkdirSync(target, { recursive: true });
  for (const [name, width, height, theme] of [["desktop", 1920, 900, "light"], ["tablet", 1024, 900, "light"], ["mobile", 375, 812, "light"], ["dark", 1366, 900, "dark"]] as const) {
    const context = await browser.newContext({ viewport: { width, height }, serviceWorkers: "block" }); const page = await context.newPage();
    const state = await setup(page, baseURL!, [product(1), product(2), product(3)], theme);
    state.summaries.p1 = summary(product(1)); state.summaries.p2 = summary(product(2), "revisao"); state.summaries.p3 = summary(product(3), "corrigir");
    await page.goto("/"); await expect(row(page, 1).getByText("OK", { exact: true })).toBeAttached();
    await page.getByRole("textbox", { name: "Filtrar por NCM" }).fill("22030000");
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: join(target, `${name}.png`), fullPage: true });
    if (name === "mobile") {
      await page.getByRole("region", { name: "Tabela de tributação dos produtos" }).evaluate(el => el.scrollLeft = 448);
      await page.getByRole("region", { name: "Tabela de tributação dos produtos" }).scrollIntoViewIfNeeded();
      await expect(row(page, 2).getByRole("button", { name: "Ver análise de CERVEJA TESTE 2" })).toBeVisible();
      await page.screenshot({ path: join(target, "mobile-status.png"), fullPage: true });
    }
    await context.close();
  }
});
