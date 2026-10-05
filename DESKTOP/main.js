/**
 * Arquivo: DESKTOP/main.js
 * Objetivo: casca desktop (Electron) do Quack PDV — etapa 1.
 *           Abre o PDV publicado (https://pdv.quacksistemas.com.br) numa janela própria em tela cheia,
 *           sem barra de endereço/abas. O código do PDV continua vindo do servidor (atualiza a cada deploy);
 *           service worker e IndexedDB funcionam como no navegador, mas os dados ficam no perfil do
 *           programa (%APPDATA%\Quack PDV) e não somem ao limpar o histórico do Chrome/Edge.
 *
 * Configuração opcional: %APPDATA%\Quack PDV\config.json
 *   { "url": "https://pdv.quacksistemas.com.br", "fullscreen": true, "confirmClose": true }
 * Variável de ambiente QUACK_PDV_URL sobrescreve a URL (útil para apontar para o ambiente de dev).
 *
 * Atalhos: F11 tela cheia | F5 / Ctrl+R recarregar | Ctrl+Shift+R recarregar sem cache | F12 DevTools (suporte)
 */
const { app, BrowserWindow, dialog, shell } = require("electron");
const fs = require("node:fs");
const path = require("node:path");

const DEFAULT_CONFIG = {
  url: "https://pdv.quacksistemas.com.br",
  fullscreen: true,
  confirmClose: true,
};

function loadConfig() {
  const file = path.join(app.getPath("userData"), "config.json");
  let fromFile = {};
  try {
    if (fs.existsSync(file)) fromFile = JSON.parse(fs.readFileSync(file, "utf8"));
  } catch (err) {
    console.error("config.json inválido, usando padrões:", err);
  }
  const config = { ...DEFAULT_CONFIG, ...fromFile };
  if (process.env.QUACK_PDV_URL) config.url = process.env.QUACK_PDV_URL;
  return config;
}

/** Mesma origem do PDV (ou páginas locais blob:/about: usadas na impressão) abre dentro do programa. */
function isInternalUrl(target, appOrigin) {
  if (target.startsWith("blob:") || target.startsWith("about:")) return true;
  try {
    return new URL(target).origin === appOrigin;
  } catch {
    return false;
  }
}

// Uma única instância: abrir o atalho de novo só traz a janela existente para frente.
if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  let mainWindow = null;
  let config = DEFAULT_CONFIG;
  let allowClose = false;

  app.on("second-instance", () => {
    if (!mainWindow) return;
    if (mainWindow.isMinimized()) mainWindow.restore();
    mainWindow.focus();
  });

  const createWindow = () => {
    const appOrigin = new URL(config.url).origin;

    mainWindow = new BrowserWindow({
      width: 1366,
      height: 768,
      show: false,
      fullscreen: Boolean(config.fullscreen),
      autoHideMenuBar: true,
      backgroundColor: "#0f172a",
      title: "Quack PDV",
      icon: path.join(__dirname, "build", "icon.png"),
      webPreferences: {
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true,
      },
    });
    mainWindow.removeMenu();
    mainWindow.once("ready-to-show", () => {
      if (!config.fullscreen) mainWindow.maximize();
      mainWindow.show();
    });

    // Janelas abertas pelo PDV (impressão do cupom, frente de caixa em nova aba) ficam no programa;
    // links externos (WhatsApp, SEFAZ etc.) vão para o navegador padrão.
    mainWindow.webContents.setWindowOpenHandler(({ url }) => {
      if (isInternalUrl(url, appOrigin)) {
        return {
          action: "allow",
          overrideBrowserWindowOptions: { autoHideMenuBar: true, icon: path.join(__dirname, "build", "icon.png") },
        };
      }
      void shell.openExternal(url);
      return { action: "deny" };
    });
    mainWindow.webContents.on("did-create-window", (child) => child.removeMenu());

    mainWindow.webContents.on("will-navigate", (event, url) => {
      if (isInternalUrl(url, appOrigin) || url.startsWith("file:")) return;
      event.preventDefault();
      void shell.openExternal(url);
    });

    // Sem internet E sem o PDV em cache (primeira abertura): mostra tela local com "tentar de novo".
    mainWindow.webContents.on("did-fail-load", (_event, errorCode, errorDescription, validatedUrl, isMainFrame) => {
      if (!isMainFrame || errorCode === -3 /* ERR_ABORTED */) return;
      console.error(`Falha ao carregar ${validatedUrl}: ${errorCode} ${errorDescription}`);
      void mainWindow.loadFile(path.join(__dirname, "offline.html"), {
        query: { url: config.url, erro: errorDescription },
      });
    });

    mainWindow.webContents.on("before-input-event", (event, input) => {
      if (input.type !== "keyDown") return;
      const key = input.key.toLowerCase();
      if (input.key === "F11") {
        mainWindow.setFullScreen(!mainWindow.isFullScreen());
        event.preventDefault();
      } else if (input.key === "F5" || (input.control && key === "r")) {
        if (input.shift) mainWindow.webContents.reloadIgnoringCache();
        else mainWindow.webContents.reload();
        event.preventDefault();
      } else if (input.key === "F12") {
        mainWindow.webContents.toggleDevTools();
        event.preventDefault();
      }
    });

    // Evita fechar o caixa sem querer (Alt+F4 / clique no X).
    mainWindow.on("close", (event) => {
      if (allowClose || !config.confirmClose) return;
      const choice = dialog.showMessageBoxSync(mainWindow, {
        type: "question",
        buttons: ["Fechar o PDV", "Cancelar"],
        defaultId: 1,
        cancelId: 1,
        title: "Quack PDV",
        message: "Deseja fechar o PDV?",
        detail: "Vendas feitas sem internet continuam guardadas e serão enviadas na próxima abertura.",
      });
      if (choice === 0) allowClose = true;
      else event.preventDefault();
    });

    mainWindow.on("closed", () => {
      mainWindow = null;
    });

    void mainWindow.loadURL(config.url);
  };

  app.whenReady().then(() => {
    config = loadConfig();
    createWindow();
  });

  app.on("window-all-closed", () => app.quit());
}
