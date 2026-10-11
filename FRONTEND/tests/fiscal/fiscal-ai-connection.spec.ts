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
  expect(result.message).toContain("conexão com o servidor da análise fiscal");
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

test("análise bem-sucedida mantém autenticação, prazo e identificação do produto", async ({ page }) => {
  const result = await page.evaluate(async () => {
    const { fiscalAiService } = await import("/src/services/api/fiscalAiService.ts" as string);
    let request: { url: string; method: string; credentials: string; signal: boolean };
    window.fetch = async (url, options) => {
      request = { url: String(url), method: options.method, credentials: options.credentials, signal: options.signal instanceof AbortSignal };
      return Response.json({ success: true, data: { id: "analise-4442", produtoId: "produto/4442" } });
    };
    const report = await fiscalAiService.analyze("produto/4442");
    return { request, report };
  });
  expect(result.request).toEqual({ url: "http://127.0.0.1:5189/mock/produto/fiscal-ia/produto%2F4442/analisar", method: "POST", credentials: "include", signal: true });
  expect(result.report.id).toBe("analise-4442");
});
