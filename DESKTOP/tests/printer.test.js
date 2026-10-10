const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { test } = require("node:test");

function printerHarness({ success = true, loadError, printError } = {}) {
  const windows = [];
  class BrowserWindow {
    constructor(options) {
      this.options = options;
      this.destroyed = false;
      this.loaded = false;
      this.jobs = [];
      this.webContents = {
        setWindowOpenHandler: handler => { this.openHandler = handler; },
        print: (options, callback) => {
          assert.ok(this.loaded, "Não pode imprimir antes de carregar o cupom.");
          if (printError) throw new Error(printError);
          this.jobs.push(options);
          callback(success, success ? undefined : "Print job failed");
        },
      };
      windows.push(this);
    }
    async loadURL(url) {
      if (loadError) throw new Error(loadError);
      this.url = url;
      this.loaded = true;
    }
    isDestroyed() { return this.destroyed; }
    destroy() { this.destroyed = true; }
  }
  const context = { require: name => {
    assert.equal(name, "electron");
    return { BrowserWindow };
  }, module: { exports: {} }, Buffer };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, "..", "printer.js"), "utf8"), context);
  return { printer: context.module.exports, windows };
}

test("cupom vai uma única vez à impressora padrão, oculto e sem scripts", async () => {
  const { printer, windows } = printerHarness();
  const html = '<html><body>Açúcar R$ 10,00<script>window.print()</script></body></html>';
  assert.equal((await printer.printReceipt(html, 2)).printed, true);
  const win = windows[0];
  assert.equal(win.options.show, false);
  assert.equal(win.options.webPreferences.javascript, false);
  assert.equal(win.options.webPreferences.nodeIntegration, false);
  assert.equal(win.options.webPreferences.sandbox, true);
  assert.equal(win.openHandler().action, "deny");
  assert.equal(decodeURIComponent(win.url.split(",")[1]), html);
  assert.equal(win.jobs.length, 1);
  assert.equal(win.jobs[0].silent, true);
  assert.equal(win.jobs[0].deviceName, undefined);
  assert.equal(win.jobs[0].copies, 2);
  assert.equal(win.jobs[0].usePrinterDefaultPageSize, true);
  assert.equal(win.destroyed, true);
});

test("falha do driver é devolvida e a janela é fechada sem nova tentativa", async () => {
  const { printer, windows } = printerHarness({ success: false });
  const result = await printer.printReceipt("<html>Cupom</html>");
  assert.equal(result.printed, false);
  assert.equal(result.error, "Print job failed");
  assert.equal(windows[0].jobs.length, 1);
  assert.equal(windows[0].destroyed, true);
});

for (const failure of [{ loadError: "Falha ao carregar" }, { printError: "Driver indisponível" }]) {
  test(`limpa a janela após exceção: ${Object.values(failure)[0]}`, async () => {
    const { printer, windows } = printerHarness(failure);
    assert.equal((await printer.printReceipt("<html>Cupom</html>")).printed, false);
    assert.equal(windows[0].destroyed, true);
  });
}

test("rejeita cupom inválido antes de abrir uma janela", async () => {
  const { printer, windows } = printerHarness();
  for (const html of [null, "", " ", "x".repeat(5 * 1024 * 1024 + 1)]) {
    assert.equal((await printer.printReceipt(html)).printed, false);
  }
  assert.equal(windows.length, 0);
});

test("limita o número de cópias do cupom", async () => {
  const { printer, windows } = printerHarness();
  await printer.printReceipt("Cupom", 99);
  await printer.printReceipt("Cupom", -1);
  assert.equal(windows[0].jobs[0].copies, 5);
  assert.equal(windows[1].jobs[0].copies, 1);
});
