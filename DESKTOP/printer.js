/**
 * Arquivo: DESKTOP/printer.js
 * Objetivo: impressão direta na impressora do caixa (cupom, DANFE NFC-e, fechamento, sangria, fiado...).
 *           As telas do PDV abrem o documento numa janela e chamam window.print(); no programa o preload
 *           troca essa chamada por um pedido ao processo principal, que imprime a página na impressora
 *           escolhida SEM a caixa de diálogo do Windows (modo "direct"). No modo "dialog" (padrão) nada
 *           muda: abre a janela de impressão de sempre.
 *
 * Configuração em config.json → "printer": { "mode": "direct" | "dialog", "deviceName": "", "copies": 1 }
 * deviceName vazio = impressora padrão do Windows.
 */
const { BrowserWindow } = require("electron");

const DEFAULT_PRINTER = { mode: "dialog", deviceName: "", copies: 1 };

function normalizePrinterSettings(raw) {
  const value = { ...DEFAULT_PRINTER, ...(raw || {}) };
  return {
    mode: value.mode === "direct" ? "direct" : "dialog",
    deviceName: typeof value.deviceName === "string" ? value.deviceName.slice(0, 256) : "",
    copies: Math.min(5, Math.max(1, Math.round(Number(value.copies) || 1))),
  };
}

async function listPrinters(webContents) {
  const printers = await webContents.getPrintersAsync();
  return printers.map((p) => ({
    name: p.name,
    displayName: p.displayName || p.name,
    isDefault: Boolean(p.isDefault ?? p.options?.isDefault),
  }));
}

/** Imprime o conteúdo de um webContents na impressora configurada, sem diálogo. */
async function printSilently(webContents, settings) {
  if (settings.deviceName) {
    const names = (await webContents.getPrintersAsync()).map((p) => p.name);
    if (!names.includes(settings.deviceName)) {
      return { printed: false, error: `Impressora "${settings.deviceName}" não encontrada neste computador.` };
    }
  }
  return new Promise((resolve) => {
    webContents.print(
      {
        silent: true,
        printBackground: true,
        deviceName: settings.deviceName || undefined,
        copies: settings.copies,
        ...(settings.usePrinterDefaultPageSize ? { usePrinterDefaultPageSize: true } : {}),
      },
      (success, failureReason) => resolve(success ? { printed: true } : { printed: false, error: failureReason || "falha ao imprimir" }),
    );
  });
}

/** Cupom de venda: sempre automático na impressora padrão, em uma janela invisível. */
async function printReceipt(html, copies = 1) {
  if (typeof html !== "string" || !html.trim() || Buffer.byteLength(html, "utf8") > 5 * 1024 * 1024) {
    return { printed: false, error: "Cupom inválido para impressão." };
  }
  let win;
  try {
    win = new BrowserWindow({
      show: false,
      webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false, javascript: false },
    });
    win.webContents.setWindowOpenHandler(() => ({ action: "deny" }));
    // O HTML já contém estilos e QR Code. Scripts desativados impedem uma segunda impressão.
    await win.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(html)}`);
    return await printSilently(win.webContents, {
      deviceName: "", copies: normalizePrinterSettings({ copies }).copies, usePrinterDefaultPageSize: true,
    });
  } catch (error) {
    return { printed: false, error: error instanceof Error ? error.message : "Não foi possível imprimir o cupom." };
  } finally {
    if (win && !win.isDestroyed()) win.destroy();
  }
}

const TEST_PAGE = `<!doctype html><html><head><meta charset="utf-8"><style>
  @page { size: 80mm auto; margin: 4mm; }
  body { font-family: "Courier New", monospace; font-size: 12px; margin: 0; width: 72mm; }
  h1 { font-size: 15px; text-align: center; margin: 0 0 6px; }
  p { margin: 2px 0; } .c { text-align: center; } hr { border: 0; border-top: 1px dashed #000; }
</style></head><body>
  <h1>QUACK PDV</h1><p class="c">Teste de impressão</p><hr>
  <p>Impressora: __PRINTER__</p><p>Data: __DATE__</p><hr>
  <p>Acentuação: ÁÉÍÓÚ ÂÊÔ ÃÕ Ç áéíóú ç</p><p>Valor: R$ 1.234,56</p><hr>
  <p class="c">Se você está lendo isto, está tudo certo.</p>
</body></html>`;

/** Página de teste numa janela invisível, impressa direto (sem diálogo) na impressora informada. */
async function printTestPage(settings) {
  const win = new BrowserWindow({ show: false, webPreferences: { sandbox: true, contextIsolation: true } });
  try {
    const html = TEST_PAGE.replace("__PRINTER__", settings.deviceName || "padrão do Windows").replace(
      "__DATE__",
      new Date().toLocaleString("pt-BR"),
    );
    await win.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(html)}`);
    return await printSilently(win.webContents, { ...settings, copies: 1 });
  } finally {
    win.destroy();
  }
}

module.exports = { normalizePrinterSettings, listPrinters, printSilently, printReceipt, printTestPage };
