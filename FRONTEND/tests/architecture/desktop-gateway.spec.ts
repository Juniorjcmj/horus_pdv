import { test, expect, type Page, type Route } from "@playwright/test";

/** Gateway embutido do programa desktop (DESKTOP/gateway.js), simulado: a ponte e o Gateway em 127.0.0.1. */
const GW = "http://127.0.0.1:5080";
const TOKEN_API = "http://localhost:5260/api/GatewayToken";

type Calls = { registers: any[]; tokenPosts: any[]; revoked: string[]; published: any[] };

async function setup(page: Page, opts: { configured: boolean; companyId?: string; tokenId?: string; publishStatus?: number }) {
  const calls: Calls = { registers: [], tokenPosts: [], revoked: [], published: [] };

  await page.addInitScript((initial) => {
    const state = { ...initial };
    const status = () => ({
      bundled: true,
      configured: state.configured,
      running: state.configured,
      managed: state.configured,
      external: false,
      url: "http://127.0.0.1:5080",
      companyId: state.configured ? state.companyId : "",
      storeId: state.configured ? "loja-01" : "",
      gatewayId: "gw-embutido",
      tokenId: state.tokenId,
      lastError: "",
    });
    (window as any).__bridgeCalls = [];
    (window as any).quackDesktop = {
      savePendingBackup: async () => "",
      gateway: {
        status: async () => status(),
        configure: async (input: any) => {
          (window as any).__bridgeCalls.push({ configure: input });
          Object.assign(state, { configured: true, companyId: input.companyId, tokenId: input.tokenId });
          return status();
        },
        disable: async () => {
          (window as any).__bridgeCalls.push({ disable: true });
          Object.assign(state, { configured: false, tokenId: "" });
          return status();
        },
      },
    };
  }, { configured: opts.configured, companyId: opts.companyId ?? "emp-1", tokenId: opts.tokenId ?? "" });

  await page.route(`${GW}/**`, async (route: Route) => {
    const url = route.request().url();
    if (url.endsWith("/api/gateway/register")) {
      calls.registers.push(route.request().postDataJSON());
      return route.fulfill({ json: { apiKey: `chave-${calls.registers.length}`, terminalId: "x" } });
    }
    if (url.endsWith("/api/gateway/status")) {
      return route.fulfill({ json: { service: "HorusGateway", bound: true, companyId: "emp-1", storeId: "loja-01", gatewayId: "gw-embutido", terminalAuthRequired: true } });
    }
    if (url.endsWith("/api/gateway/identify")) return route.fulfill({ status: 404, json: {} });
    if (url.endsWith("/api/gateway/events")) {
      calls.published.push({ body: route.request().postDataJSON(), key: route.request().headers()["x-terminal-key"] });
      const status = opts.publishStatus ?? 200;
      return status === 200 ? route.fulfill({ json: { status: "accepted" } }) : route.fulfill({ status, json: {} });
    }
    return route.fulfill({ status: 404, json: {} });
  });

  await page.route(`${TOKEN_API}**`, async (route: Route) => {
    const url = route.request().url();
    const revoke = url.match(/GatewayToken\/([^/]+)\/revogar$/);
    if (revoke) {
      calls.revoked.push(decodeURIComponent(revoke[1]));
      return route.fulfill({ json: { success: true, data: {} } });
    }
    if (route.request().method() === "POST") {
      calls.tokenPosts.push(route.request().postDataJSON());
      return route.fulfill({
        json: { success: true, data: { token: "qgw_TOKENNOVO1234567890abcdefghijklmnopqrstu", info: { id: "tok-novo", nome: "x", tokenPrefix: "qgw_TOKE", createdAt: "" } } },
      });
    }
    return route.fulfill({ json: { success: true, data: [] } });
  });

  await page.goto("/");
  await page.waitForFunction(() => (window as any).__horus_test__ !== undefined);
  await page.evaluate(async () => {
    await (window as any).__horus_test__.resetDatabase();
    localStorage.setItem("horuspdv.auth.user", JSON.stringify({ id: "usr-1", name: "Gerente", role: "gerente", companyId: "emp-1" }));
  });
  return calls;
}

const mod = (page: Page) => "/src/infrastructure/desktop/desktopGateway.ts";
const gatewayConfig = (page: Page) => page.evaluate(() => JSON.parse(localStorage.getItem("horus-gateway-config") || "{}"));

test.describe("Gateway embutido no programa desktop", () => {
  test("ativar: gera token, entrega ao programa, revoga o anterior e liga o caixa ao Gateway local", async ({ page }) => {
    const calls = await setup(page, { configured: true, tokenId: "tok-antigo" });

    const status = await page.evaluate(async (path) => {
      const m = await import(path as string);
      return m.activateEmbeddedGateway();
    }, mod(page));

    expect(status.running).toBe(true);
    const bridgeCalls = await page.evaluate(() => (window as any).__bridgeCalls);
    expect(bridgeCalls[0].configure).toMatchObject({
      token: "qgw_TOKENNOVO1234567890abcdefghijklmnopqrstu",
      tokenId: "tok-novo",
      companyId: "emp-1",
      storeId: "loja-01",
      apiUrl: "http://localhost:5260",
    });
    expect(calls.tokenPosts).toHaveLength(1);
    expect(calls.revoked).toEqual(["tok-antigo"]);

    const config = await gatewayConfig(page);
    expect(config).toMatchObject({ enabled: true, url: GW, companyId: "emp-1", storeId: "loja-01", terminalType: "CASH", apiKey: "chave-1" });
    expect(config.terminalId).toMatch(/^CAIXA-/);
    expect(calls.registers[0]).toMatchObject({ companyId: "emp-1", terminalId: config.terminalId, terminalType: "CASH" });
  });

  test("abrir o programa já ativado liga sozinho; de outra empresa não liga", async ({ page }) => {
    const calls = await setup(page, { configured: true, companyId: "outra-empresa" });
    const r1 = await page.evaluate(async (path) => {
      const m = await import(path as string);
      await m.linkEmbeddedGateway();
      return localStorage.getItem("horus-gateway-config");
    }, mod(page));
    expect(r1).toBeNull();
    expect(calls.registers).toHaveLength(0);
  });

  test("ligado ao Gateway local, a fila sem internet vai a ele; credencial recusada (401) é renovada", async ({ page }) => {
    const calls = await setup(page, { configured: true, publishStatus: 401 });
    const result = await page.evaluate(async (path) => {
      const m = await import(path as string);
      await m.linkEmbeddedGateway();
      const { SaleOutboxAdapter } = (window as any).__horus_test__;
      await SaleOutboxAdapter.queueSaleToOutbox({
        eventId: "ev-1", clientSaleId: "cs-1", operatorId: "usr-1", customerName: "", customerCpf: "", paymentType: "dinheiro",
        totalAmount: "10,00", operatorName: "Gerente", payloadHash: "h-1",
        items: [{ productCode: "P1", productName: "Arroz", quantity: 1, unitPrice: 10, desconto: 0, itemTotal: 10 }],
        payments: [{ paymentType: "dinheiro", amount: 10, cashGiven: null, changeAmount: null }],
      });
      const fwd = await import("/src/infrastructure/gateway/outboxGatewayForwarder.ts" as string);
      const first = await fwd.forwardPendingToGateway();
      const afterFirst = JSON.parse(localStorage.getItem("horus-gateway-config") || "{}").apiKey;
      await fwd.forwardPendingToGateway();
      return { first, afterFirst };
    }, mod(page));

    expect(calls.published[0].key).toBe("chave-1");
    expect(result.first.forwarded).toBe(0);
    expect(result.afterFirst).toBe("");
    // Segundo ciclo: registrou de novo (registro aberto) e tentou com a chave nova.
    expect(calls.registers).toHaveLength(2);
    expect(calls.published[1].key).toBe("chave-2");
  });

  test("desativar para o Gateway, revoga o token e desliga o uso no PDV", async ({ page }) => {
    const calls = await setup(page, { configured: true, tokenId: "tok-atual" });
    const config = await page.evaluate(async (path) => {
      const m = await import(path as string);
      await m.linkEmbeddedGateway();
      await m.deactivateEmbeddedGateway();
      return JSON.parse(localStorage.getItem("horus-gateway-config") || "{}");
    }, mod(page));

    expect(calls.revoked).toEqual(["tok-atual"]);
    expect(config).toMatchObject({ enabled: false, apiKey: "" });
    const bridgeCalls = await page.evaluate(() => (window as any).__bridgeCalls);
    expect(bridgeCalls).toEqual([{ disable: true }]);
  });

  test("descoberta pela nuvem não troca o Gateway embutido por outro endereço", async ({ page }) => {
    await setup(page, { configured: true });
    let cloudAsked = 0;
    await page.route("http://localhost:5260/api/gateway-config**", (route) => {
      cloudAsked++;
      return route.fulfill({
        json: { success: true, data: { gatewayUrl: "http://192.168.0.50:5080", enabled: true, storeId: "loja-01", updatedAt: "2026-10-06T00:00:00Z" } },
      });
    });
    const urls = await page.evaluate(async (path) => {
      const read = () => JSON.parse(localStorage.getItem("horus-gateway-config") || "{}").url;
      const p = await import("/src/infrastructure/gateway/cloudGatewayProvisioning.ts" as string);
      // Sem o Gateway embutido ligado, a nuvem define o endereço (controle do teste)...
      await p.learnGatewayFromCloud();
      const semEmbutido = read();
      // ...com ele ligado, a nuvem não sobrescreve.
      const m = await import(path as string);
      await m.linkEmbeddedGateway();
      await p.learnGatewayFromCloud();
      return { semEmbutido, comEmbutido: read() };
    }, mod(page));
    expect(urls.semEmbutido).toBe("http://192.168.0.50:5080");
    expect(urls.comEmbutido).toBe(GW);
    expect(cloudAsked).toBe(1);
  });
});
