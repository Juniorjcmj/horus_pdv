/** Exercises the packaged Windows application in an isolated profile, with no store/API writes. */
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { createHash } = require("node:crypto");
const { _electron } = require("../../FRONTEND/node_modules/playwright");
const { expect } = require("../../FRONTEND/node_modules/@playwright/test");

const desktop = path.resolve(__dirname, "..");
const version = require("../package.json").version;
const executablePath = path.join(desktop, "release", version, "win-unpacked", "Quack PDV.exe");
const cache = path.join(desktop, "node_modules", ".cache");
fs.mkdirSync(cache, { recursive: true });
const profile = fs.mkdtempSync(path.join(cache, "installer-smoke-"));
fs.writeFileSync(path.join(profile, "config.json"), JSON.stringify({ fullscreen: false, confirmClose: false,
  autoZoom: false, backupDir: path.join(profile, "backups"), configMarker: "preservar",
  gateway: { enabled: false }, printer: { mode: "dialog", copies: 1 } }));
const origin = "https://pdv.quacksistemas.com.br";
const user = { id: "teste-instalador", companyId: "loja-teste", name: "Gerente", email: "teste@teste.invalid",
  role: "gerente", status: "ativo", createdAt: new Date().toISOString(), lastLoginAt: new Date().toISOString(), mustChangePassword: false };
const milkCode = "7890000000001";
const coffeeCode = "7890000000002";
const products = [
  { id: "leite", productName: "Leite instalador", productCode: milkCode, productSalePrice: "10,00" },
  { id: "cafe", productName: "Café instalador", productCode: coffeeCode, productSalePrice: "20,00" },
].map(item => ({ productUnitPrice: "5,00", productQnt: "9999", productSupplier: "", unidadeComercial: "UN", ...item }));
let application;
let originalBackup;
let originalBackupHash;
const fileHash = file => fs.existsSync(file) ? createHash("sha256").update(fs.readFileSync(file)).digest("hex") : null;

async function launch() {
  return _electron.launch({ executablePath, timeout: 45_000,
    args: ["--host-resolver-rules=MAP * 127.0.0.1, EXCLUDE localhost"],
    env: { ...process.env, QUACK_PDV_USER_DATA: profile, QUACK_PDV_FRONTEND_SOURCE: "bundled", QUACK_PDV_TEST_MODE: "1" } });
}

async function mockApi() {
  // Protocol requests and service workers run outside renderer routing: fake at the native boundary.
  await application.evaluate(({ net }, { origin, user, products }) => {
    const original = net.fetch.bind(net);
    globalThis.installerSmokeWrites = [];
    const openedAt = new Date(Date.now() - 60_000).toISOString();
    net.fetch = async (input, options) => {
      const url = new URL(typeof input === "string" ? input : input.url);
      if (url.origin === origin || url.protocol === "file:") return original(input, options);
      if (globalThis.installerSmokeOffline) throw new Error("Offline during test.");
      if (url.hostname !== "api-pdv.quacksistemas.com.br") throw new Error("External requests blocked in smoke test.");
      const method = input.method || options?.method || "GET";
      const headers = { "Access-Control-Allow-Origin": origin, "Access-Control-Allow-Credentials": "true",
        "Access-Control-Allow-Headers": "Content-Type, Authorization", "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS" };
      if (method === "OPTIONS") return new Response(null, { status: 204, headers });
      let data = [];
      if (method !== "GET") {
        const payload = typeof input.json === "function" ? await input.json() : JSON.parse(options.body);
        globalThis.installerSmokeWrites.push({ path: url.pathname, payload });
        data = url.pathname.endsWith("/HistoricoVendas")
          ? { saleNumber: `TESTE-${globalThis.installerSmokeWrites.length}`, emitirFiscal: false, fiscalQueued: false }
          : url.pathname.endsWith("/NfeImport/confirmar") ? { produtosCriados: 1, produtosAtualizados: 0, fornecedorCriado: true }
          : { ...payload, id: "cadastro-instalador" };
      } else if (url.pathname.endsWith("/Auth/me")) data = user;
      else if (url.pathname.endsWith("/Produto")) data = products;
      else if (url.pathname.endsWith("/Empresa")) data = null;
      else if (url.pathname.endsWith("/Caixa/status")) {
        const totals = {};
        for (const write of globalThis.installerSmokeWrites.filter(write => write.path.endsWith("/HistoricoVendas"))) {
          for (const payment of write.payload.payments) totals[payment.paymentType] = (totals[payment.paymentType] || 0) + payment.amount;
        }
        const money = amount => amount.toLocaleString("pt-BR", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
        data = { state: "aberto", canSell: true, blockReason: "", serverNow: new Date().toISOString(),
        currentSession: { id: "sessao-teste", status: "Aberto", openedAt, openingAmount: "100,00",
          closingAmount: "0,00", operatorId: user.id, operatorName: user.name, closedById: "", closedByName: "", note: "",
          elapsedMinutes: 1, expectedCashAmount: money(100 + (totals.dinheiro || 0)), movimentos: [],
          paymentBreakdown: Object.entries(totals).map(([paymentType, total]) => ({ paymentType, total: money(total) })) }, lastSession: null, history: [] };
      }
      return Response.json({ success: true, data }, { headers });
    };
  }, { origin, user, products });
}

const getWrites = () => application.evaluate(() => globalThis.installerSmokeWrites);

async function mockPrinting() {
  // Intercepta somente a saída nativa: exercita HTML, IPC e Electron sem imprimir papel.
  await application.evaluate(({ app, BrowserWindow }) => {
    globalThis.installerSmokePrints = [];
    globalThis.installerSmokePrintSuccess = true;
    app.on("web-contents-created", (_event, contents) => {
      contents.print = (options, callback) => {
        globalThis.installerSmokePrints.push({ options, url: contents.getURL(),
          visible: BrowserWindow.fromWebContents(contents).isVisible(), preferences: contents.getLastWebPreferences() });
        callback(globalThis.installerSmokePrintSuccess, "Print job failed");
      };
    });
  });
}

async function finishSale(page, success = true, offline = false, preview = false) {
  await application.evaluate((_electron, success) => { globalThis.installerSmokePrintSuccess = success; }, success);
  const writesBefore = (await getWrites()).filter(write => write.path.endsWith("/HistoricoVendas")).length;
  const printsBefore = await application.evaluate(() => globalThis.installerSmokePrints.length);
  const totalWindows = (await application.windows()).length;
  await page.evaluate(preview => {
    localStorage.setItem("horus-pdv-print-preview-enabled", String(preview));
    window.dispatchEvent(new CustomEvent("horus-pdv-print-preview-change", { detail: { enabled: preview } }));
  }, preview);
  await page.getByText("Lista de itens:", { exact: true }).click();
  await scan(page, milkCode);
  await page.keyboard.press("F12");
  await page.getByRole("textbox", { name: "Valor entregue em dinheiro", exact: true }).fill("10000");
  if (offline) {
    await application.context().setOffline(true);
    await application.evaluate(() => { globalThis.installerSmokeOffline = true; });
  }
  await page.getByRole("button", { name: "Confirmar Venda", exact: true }).click();
  await expect.poll(() => application.evaluate(() => globalThis.installerSmokePrints.length)).toBe(printsBefore + 1);
  await expect(page.getByRole("heading", { name: "Pagamento", exact: true })).toHaveCount(0);
  await expect(page.getByRole("row").filter({ hasText: "Leite instalador" })).toHaveCount(0);
  const job = (await application.evaluate(() => globalThis.installerSmokePrints)).at(-1);
  assert.equal(job.options.silent, true);
  assert.ok(!job.options.deviceName, "O cupom deve usar a impressora padrão.");
  assert.equal(job.options.usePrinterDefaultPageSize, true);
  assert.equal(job.visible, false);
  assert.equal(job.preferences.javascript, false);
  assert.ok(decodeURIComponent(job.url).includes("Leite instalador"));
  assert.ok(decodeURIComponent(job.url).includes("<svg"));
  await expect.poll(async () => (await application.windows()).length).toBe(totalWindows);
  if (preview) {
    await expect(page.getByRole("heading", { name: "Prévia de impressão", exact: true })).toBeVisible();
    await page.getByRole("button", { name: "Fechar", exact: true }).click();
    assert.equal(await application.evaluate(() => globalThis.installerSmokePrints.length), printsBefore + 1);
  }
  if (!success) {
    await expect(page.getByText('Venda salva, mas não foi possível imprimir. Confira a impressora e use "Imprimir última venda".', { exact: true })).toBeVisible();
    assert.equal((await getWrites()).filter(write => write.path.endsWith("/HistoricoVendas")).length, writesBefore + 1);
    await application.evaluate(() => { globalThis.installerSmokePrintSuccess = true; });
    await page.getByRole("button", { name: "Imprimir última venda", exact: true }).click();
    await page.getByRole("button", { name: "Imprimir Cupom Não Fiscal", exact: true }).click();
    await expect.poll(() => application.evaluate(() => globalThis.installerSmokePrints.length)).toBe(printsBefore + 2);
    assert.equal((await getWrites()).filter(write => write.path.endsWith("/HistoricoVendas")).length, writesBefore + 1);
    await page.getByRole("button", { name: "Fechar", exact: true }).click();
  }
  const cashInSales = (await getWrites()).filter(write => write.path.endsWith("/HistoricoVendas"))
    .flatMap(write => write.payload.payments).filter(payment => payment.paymentType === "dinheiro")
    .reduce((sum, payment) => sum + payment.amount, 0);
  const expectedCash = (100 + cashInSales + (offline ? 10 : 0)).toLocaleString("pt-BR", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  await page.keyboard.press("F6");
  const panel = page.getByRole("dialog", { name: "Caixa", exact: true });
  await expect(panel.getByText("Dinheiro esperado na gaveta").locator("..")).toContainText(`R$ ${expectedCash}`);
  await panel.getByRole("button", { name: "Fechar caixa", exact: true }).click();
  await expect(panel.getByRole("textbox", { name: "Valor contado na gaveta (dinheiro)" })).toHaveValue(expectedCash);
  await expect(panel.getByText("Confere com o esperado.", { exact: true })).toBeVisible();
  await panel.getByRole("button", { name: "Fechar painel de caixa" }).click();
  if (offline) {
    await application.context().setOffline(false);
    await application.evaluate(() => { globalThis.installerSmokeOffline = false; });
  }
}

async function scan(page, code) {
  await page.keyboard.type(code, { delay: 1 });
  await page.keyboard.press("Enter");
}

async function run() {
  assert.ok(fs.existsSync(executablePath), "Gere o instalador com npm run dist antes do teste.");
  application = await launch();
  const page = await application.firstWindow();
  await page.waitForURL(url => url.origin === origin, { waitUntil: "domcontentloaded" });
  assert.equal(new URL(page.url()).origin, origin);
  assert.equal(await page.evaluate(() => typeof window.quackDesktop?.savePendingBackup), "function");
  originalBackup = path.join(await application.evaluate(({ app }) => app.getPath("documents")), "Quack PDV", "Backups", "pendencias-atual.json");
  originalBackupHash = fileHash(originalBackup);
  const savedBackup = await page.evaluate(() => window.quackDesktop.savePendingBackup(JSON.stringify({ count: 0, events: [] })));
  assert.equal(savedBackup, path.join(profile, "backups", "pendencias-atual.json"));
  assert.equal(fileHash(originalBackup), originalBackupHash);
  console.log("OK: backups do teste ficam apenas no perfil isolado.");
  await mockApi();
  const build = await page.evaluate(async () => (await fetch("/build-info.json")).json());
  assert.deepEqual(build, JSON.parse(fs.readFileSync(path.join(desktop, "frontend-bin", "build-info.json"), "utf8")));
  console.log(`OK 1: executável ${version} carrega o frontend incluído na origem original.`);

  await page.evaluate(async user => {
    localStorage.setItem("horuspdv.auth.user", JSON.stringify(user));
    localStorage.setItem("horuspdv.activePage", "vendas");
    localStorage.setItem("installer-preservation", "preservado");
    await new Promise((resolve, reject) => {
      const request = indexedDB.open("installer-preservation", 1);
      request.onupgradeneeded = () => request.result.createObjectStore("markers");
      request.onerror = () => reject(request.error);
      request.onsuccess = () => {
        const db = request.result;
        const tx = db.transaction("markers", "readwrite");
        tx.objectStore("markers").put("preservado", "perfil");
        tx.oncomplete = () => { db.close(); resolve(true); };
        tx.onerror = () => reject(tx.error);
      };
    });
  }, user);
  await page.reload();
  const product = page.getByRole("textbox", { name: /^Produto:/ });
  await product.fill(milkCode);
  await expect(page.getByText("Leite instalador", { exact: true }).first()).toBeVisible();
  await product.fill("");
  await page.getByText("Lista de itens:", { exact: true }).click();
  await scan(page, milkCode);
  const row = page.getByRole("row").filter({ hasText: "Leite instalador" });
  await expect(row.getByRole("cell").nth(3)).toHaveText("1");
  const quantity = page.getByRole("textbox", { name: "Quantidade (volume):", exact: true });
  await quantity.fill("3");
  await scan(page, milkCode);
  await expect(row.getByRole("cell").nth(3)).toHaveText("4");
  console.log("OK 2: leitor funciona fora da busca e preserva a quantidade no aplicativo real.");

  await page.keyboard.press("F12");
  const amount = page.getByRole("textbox", { name: "Valor entregue em dinheiro", exact: true });
  await amount.fill("5000");
  await scan(page, coffeeCode);
  await expect(amount).toHaveValue("50,00");
  assert.equal((await getWrites()).filter(write => /Venda/i.test(write.path)).length, 0);
  await page.getByRole("button", { name: "Voltar", exact: true }).click();
  await expect(page.getByRole("row").filter({ hasText: "Café instalador" })).toHaveCount(1);
  await application.context().setOffline(true);
  await page.getByText("Lista de itens:", { exact: true }).click();
  await scan(page, milkCode);
  await expect(row.getByRole("cell").nth(3)).toHaveText("5");
  await application.context().setOffline(false);
  console.log("OK 3: pagamento protegido e leitura sem internet.");

  await mockPrinting();
  await finishSale(page, true, false, true);
  await finishSale(page, false);
  await finishSale(page, true, true);
  console.log("OK: cupom automático, reimpressão e totais no fechamento após vendas online e offline.");

  await page.evaluate(() => localStorage.setItem("horuspdv.activePage", "cadastro-produto"));
  await page.reload();
  await page.getByRole("button", { name: "Novo produto", exact: true }).click();
  await page.getByRole("textbox", { name: "Descrição do Produto *", exact: true }).fill("Produto sem fornecedor");
  await page.getByRole("textbox", { name: "Valor de Custo *", exact: true }).fill("1000");
  await page.getByRole("textbox", { name: "Valor de Venda *", exact: true }).fill("1500");
  await page.getByRole("button", { name: "Criar produto", exact: true }).click();
  await expect(page.getByText("Produto cadastrado com sucesso.", { exact: true })).toBeVisible();
  assert.equal((await getWrites()).find(write => write.path.endsWith("/Produto")).payload.productSupplier, "");

  await page.getByRole("button", { name: "Importar / Cargas" }).click();
  await page.getByRole("button", { name: "Entrada de NF-e / NFC-e" }).click();
  await page.getByRole("textbox", { name: "Chave de acesso da nota" }).fill("33261036716865000104651160000104891000214277");
  await page.getByRole("button", { name: "Digitar itens do cupom" }).click();
  await expect(page.getByRole("textbox", { name: "CNPJ", exact: true })).toHaveValue("36716865000104");
  await page.getByRole("textbox", { name: "Razão social", exact: true }).fill("FORNECEDOR DO TESTE");
  await page.getByRole("textbox", { name: "Descrição item 1", exact: true }).fill("Sal grosso do teste");
  await page.getByRole("textbox", { name: "Quantidade item 1", exact: true }).fill("4");
  await page.getByRole("textbox", { name: "Custo item 1", exact: true }).fill("745");
  await page.getByRole("textbox", { name: "Venda item 1", exact: true }).fill("1000");
  await page.screenshot({ path: path.join(profile, "entrada-nfce.png") });
  await page.getByRole("button", { name: /Confirmar entrada/ }).click();
  await expect.poll(async () => (await getWrites()).filter(write => write.path.endsWith("/NfeImport/confirmar")).length).toBe(1);
  await expect(page.getByRole("button", { name: /Confirmar entrada/ })).toHaveCount(0);
  const cupomEntry = (await getWrites()).find(write => write.path.endsWith("/NfeImport/confirmar"));
  assert.equal(cupomEntry.payload.itens[0].precoCusto, "7,45");
  assert.equal(cupomEntry.payload.itens[0].quantidade, "4");
  assert.equal(cupomEntry.payload.itens[0].gtin, "SEM GTIN");
  assert.equal((await getWrites()).filter(write => write.path.endsWith("/NfeImport/buscar-sefaz")).length, 0);
  console.log("OK: chave NFC-e abre entrada de cupom revisável no executável, sem consulta de NF-e.");

  await page.evaluate(() => localStorage.setItem("horuspdv.activePage", "cadastro-cliente"));
  await page.reload();
  await page.getByRole("button", { name: "Novo cliente", exact: true }).click();
  await page.getByRole("textbox", { name: "Nome *", exact: true }).fill("Cliente mínimo");
  await page.getByRole("textbox", { name: "Telefone *", exact: true }).fill("1133334444");
  await page.getByRole("button", { name: "Criar cliente", exact: true }).click();
  await expect(page.getByText("Cliente cadastrado com sucesso.", { exact: true })).toBeVisible();
  assert.equal((await getWrites()).find(write => write.path.endsWith("/Cliente")).payload.document, "");
  console.log("OK 4: cadastro mínimo de produtos e clientes está no executável.");

  await application.close();
  application = await launch();
  await application.context().setOffline(true);
  const reopened = await application.firstWindow();
  await reopened.waitForURL(url => url.origin === origin, { waitUntil: "domcontentloaded" });
  assert.equal(new URL(reopened.url()).origin, origin);
  assert.equal(await reopened.evaluate(() => localStorage.getItem("installer-preservation")), "preservado");
  const stored = await reopened.evaluate(() => new Promise((resolve, reject) => {
    const request = indexedDB.open("installer-preservation", 1);
    request.onerror = () => reject(request.error);
    request.onsuccess = () => {
      const db = request.result;
      const value = db.transaction("markers").objectStore("markers").get("perfil");
      value.onsuccess = () => { db.close(); resolve(value.result); };
      value.onerror = () => reject(value.error);
    };
  }));
  assert.equal(stored, "preservado");
  assert.equal(JSON.parse(fs.readFileSync(path.join(profile, "config.json"), "utf8")).configMarker, "preservar");
  console.log("OK 5: perfil, IndexedDB e configurações preservados após reabrir.");
}

run().catch(async error => {
  if (application) {
    const pages = await application.windows();
    for (const page of pages) {
      try { await page.screenshot({ path: path.join(profile, "failure.png") }); } catch { /* Keep the original failure. */ }
    }
  }
  console.error(error);
  process.exitCode = 1;
}).finally(async () => {
  if (application) await application.close();
  if (originalBackup) assert.equal(fileHash(originalBackup), originalBackupHash, "O teste deve preservar o backup da pasta padrão.");
  console.log(`Perfil isolado do teste: ${profile}`);
});
