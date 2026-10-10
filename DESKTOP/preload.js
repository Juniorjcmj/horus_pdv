/**
 * Arquivo: DESKTOP/preload.js
 * Objetivo: ponte mínima e segura entre a página do PDV e o programa desktop (contextIsolation).
 *           Expõe só o que o PDV precisa — nada de acesso livre a disco ou Node.
 *           Roda também nas janelas abertas pelo PDV (cupom, DANFE, relatórios), que herdam o preload.
 */
const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("quackDesktop", {
  /** Grava o backup das pendências (JSON) em Documentos\Quack PDV\Backups. Resolve com o caminho salvo. */
  savePendingBackup: (json) => ipcRenderer.invoke("quack:save-pending-backup", json),

  /** Gateway embutido (roda junto com o programa em 127.0.0.1). Nunca devolve o token. */
  gateway: {
    status: () => ipcRenderer.invoke("quack:gateway-status"),
    /** { token: "qgw_...", companyId, storeId?, apiUrl } — ativa/troca a configuração e reinicia o Gateway. */
    configure: (input) => ipcRenderer.invoke("quack:gateway-configure", input),
    disable: () => ipcRenderer.invoke("quack:gateway-disable"),
  },

  /** Impressora do caixa: { mode: "direct" | "dialog", deviceName, copies }. */
  printer: {
    /** Envia o cupom à impressora padrão do Windows, sem diálogo nem janela auxiliar visível. */
    printReceipt: (html) => ipcRenderer.invoke("quack:print-receipt", html),
    list: () => ipcRenderer.invoke("quack:printer-list"),
    getSettings: () => ipcRenderer.invoke("quack:printer-settings"),
    saveSettings: (settings) => ipcRenderer.invoke("quack:printer-save", settings),
    /** Imprime uma página de teste direto na impressora (usa as configurações informadas, sem salvar). */
    test: (settings) => ipcRenderer.invoke("quack:printer-test", settings),
  },
});

// ---------------------------------------------------------------------------
// window.print() → impressão direta
// ---------------------------------------------------------------------------
// Aqui (mundo isolado do preload) window.print continua sendo o original, com a janela do Windows.
const nativePrint = () => window.print();
let printing = false;

function printViaDesktop() {
  if (printing) return;
  printing = true;
  ipcRenderer
    .invoke("quack:print-page")
    .then((result) => {
      printing = false;
      if (result?.printed) {
        // As telas fecham a janela do cupom no "afterprint".
        window.dispatchEvent(new Event("afterprint"));
      } else {
        // Modo "dialog" ou a impressora falhou: abre a janela de impressão para o operador escolher.
        if (result?.error) console.warn("[Quack PDV] Impressão direta falhou:", result.error);
        nativePrint();
      }
    })
    .catch((err) => {
      printing = false;
      console.warn("[Quack PDV] Impressão direta indisponível:", err);
      nativePrint();
    });
}

// Troca o window.print da página (mundo principal) antes de qualquer script dela rodar.
contextBridge.executeInMainWorld({
  func: (print) => {
    window.print = function quackPrint() {
      print();
    };
  },
  args: [printViaDesktop],
});
