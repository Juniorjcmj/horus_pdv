import { test, expect } from "./browser";

test("falha imediata de conexão da IA orienta em português e não repete a chamada paga", async ({ page }) => {
  const result = await page.evaluate(async () => {
    const { fiscalAiService } = await import("/src/services/api/fiscalAiService.ts" as string);
    const { ApiError } = await import("/src/services/api/apiClient.ts" as string);
    let calls = 0;
    window.fetch = async () => { calls++; throw new TypeError("Failed to fetch"); };
    try { await fiscalAiService.analyze("produto/4442"); }
    catch (error) {
      return { calls, apiError: error instanceof ApiError, status: error.status, message: error.message, cause: error.data?.message };
    }
    throw new Error("A análise deveria falhar");
  });
  expect(result.calls).toBe(1);
  expect(result.apiError).toBe(true);
  expect(result.status).toBe(0);
  expect(result.message).toContain("conexão com a análise fiscal");
  expect(result.message).not.toContain("Failed to fetch");
  expect(result.cause).toBe("Failed to fetch");
});

test("análise preserva a referência de diagnóstico devolvida pelo servidor", async ({ page }) => {
  const result = await page.evaluate(async () => {
    const { fiscalAiService } = await import("/src/services/api/fiscalAiService.ts" as string);
    window.fetch = async () => Response.json({ success: false, message: "Não foi possível consultar os dados. Referência TESTE-4442." }, { status: 503 });
    try { await fiscalAiService.analyze("4442"); }
    catch (error) { return { status: error.status, message: error.message }; }
    throw new Error("A análise deveria falhar");
  });
  expect(result.status).toBe(503);
  expect(result.message).toContain("TESTE-4442");
});

test("timeout e falta de internet não são apresentados como erro imediato", async ({ page }) => {
  const result = await page.evaluate(async () => {
    const { fiscalAiService } = await import("/src/services/api/fiscalAiService.ts" as string);
    window.fetch = async () => { throw new DOMException("Aborted", "AbortError"); };
    let timeout = "";
    try { await fiscalAiService.analyze("4442"); } catch (error) { timeout = error.message; }
    Object.defineProperty(navigator, "onLine", { value: false, configurable: true });
    let offline = "";
    try { await fiscalAiService.analyze("4442"); } catch (error) { offline = error.message; }
    return { timeout, offline };
  });
  expect(result.timeout).toContain("demorou demais");
  expect(result.offline).toBe("Sem conexão com a internet.");
});

test("análise em segundo plano usa requisições curtas autenticadas e acompanha a fila", async ({ page }) => {
  const result = await page.evaluate(async () => {
    const { fiscalAiService } = await import("/src/services/api/fiscalAiService.ts" as string);
    const requests: { url: string; method: string; credentials: string; signal: boolean }[] = [];
    const progress: string[] = [];
    const jobId = "14444444-4444-4444-4444-444444444444";
    let polls = 0;
    window.fetch = async (url, options) => {
      requests.push({ url: String(url), method: options.method ?? "GET", credentials: options.credentials, signal: options.signal instanceof AbortSignal });
      if (options.method === "POST") return Response.json({ success: true, data: { id: jobId } }, { status: 202 });
      polls++;
      return Response.json({ success: true, data: { id: jobId, produtoId: "produto/4442", status: polls === 1 ? "aguardando" : polls === 2 ? "analisando" : "concluida", relatorio: polls >= 3 ? { id: "analise-4442", produtoId: "produto/4442" } : null } });
    };
    const report = await fiscalAiService.analyze("produto/4442", message => progress.push(message));
    return { requests, report, progress };
  });
  expect(result.requests[0]).toEqual({ url: "http://127.0.0.1:5189/mock/produto/fiscal-ia/produto%2F4442/analises", method: "POST", credentials: "include", signal: true });
  expect(result.requests).toHaveLength(4);
  expect(result.requests.every(r => r.credentials === "include" && r.signal)).toBe(true);
  expect(result.requests.slice(1).every(r => r.method === "GET" && r.url.endsWith("/analises/14444444-4444-4444-4444-444444444444"))).toBe(true);
  expect(result.progress).toHaveLength(2);
  expect(result.report.id).toBe("analise-4442");
});

test("resposta perdida na criação e queda na consulta recuperam a mesma solicitação", async ({ page }) => {
  const result = await page.evaluate(async () => {
    const { fiscalAiService } = await import("/src/services/api/fiscalAiService.ts" as string);
    const submitted: string[] = [];
    let failPost = true, failPoll = true;
    window.fetch = async (_url, options) => {
      if (options.method === "POST") {
        submitted.push(JSON.parse(options.body as string).solicitacaoId);
        if (failPost) { failPost = false; throw new TypeError("Failed to fetch"); }
        return Response.json({ success: true, data: { id: submitted[0] } }, { status: 202 });
      }
      if (failPoll) { failPoll = false; throw new TypeError("Failed to fetch"); }
      return Response.json({ success: true, data: { id: submitted[0], produtoId: "4442", status: "concluida", relatorio: { id: "resultado", produtoId: "4442" } } });
    };
    for (let i = 0; i < 2; i++) try { await fiscalAiService.analyze("4442"); } catch { /* Falhas deliberadas */ }
    const report = await fiscalAiService.analyze("4442");
    return { submitted, report, pending: Object.keys(sessionStorage).filter(k => k.startsWith("fiscal-ai:solicitacao:")) };
  });
  expect(new Set(result.submitted).size).toBe(1);
  expect(result.submitted).toHaveLength(3);
  expect(result.report.id).toBe("resultado");
  expect(result.pending).toEqual([]);
});

test("falha do provedor termina a consulta com a orientação recebida", async ({ page }) => {
  const result = await page.evaluate(async () => {
    const { fiscalAiService } = await import("/src/services/api/fiscalAiService.ts" as string);
    const jobId = crypto.randomUUID();
    window.fetch = async (_url, options) => Response.json({ success: true, data: options.method === "POST" ? { id: jobId }
      : { id: jobId, produtoId: "4442", status: "falha", mensagem: "Saldo insuficiente no OpenRouter.", relatorio: null } });
    try { await fiscalAiService.analyze("4442"); } catch (error) { return error.message; }
    throw new Error("A análise deveria falhar");
  });
  expect(result).toBe("Saldo insuficiente no OpenRouter.");
});
