/**
 * Arquivo: DESKTOP/main.js
 * Objetivo: casca desktop (Electron) do Quack PDV — etapa 1.
 *           Abre o PDV publicado (https://pdv.quacksistemas.com.br) numa janela própria em tela cheia,
 *           sem barra de endereço/abas. O código do PDV continua vindo do servidor (atualiza a cada deploy);
 *           service worker e IndexedDB funcionam como no navegador, mas os dados ficam no perfil do
 *           programa (%APPDATA%\Quack PDV) e não somem ao limpar o histórico do Chrome/Edge.
 *
 * Configuração opcional: %APPDATA%\Quack PDV\config.json
 *   { "url": "https://pdv.quacksistemas.com.br", "fullscreen": true, "confirmClose": true,
 *     "autoZoom": true, "zoomAdjust": 1 }
 * Variável de ambiente QUACK_PDV_URL sobrescreve a URL (útil para apontar para o ambiente de dev).
 *
 * Zoom automático: o PDV foi desenhado para 1366x768. A janela aplica um zoom proporcional ao tamanho
 * dela (como o Ctrl+ do navegador) para a tela inteira — letras, ícones e campos — ficar do mesmo jeito
 * em qualquer monitor. "zoomAdjust" é o ajuste fino do operador (Ctrl+ / Ctrl- / Ctrl+0), salvo no config.
 *
 * Atalhos: F11 tela cheia | F5 / Ctrl+R recarregar | Ctrl+Shift+R recarregar sem cache | Ctrl+Shift+I DevTools (suporte)
 *          Ctrl+= / Ctrl+- aumentar/diminuir | Ctrl+0 voltar ao tamanho automático
 */
const { app, BrowserWindow, dialog, screen, shell } = require("electron");
const fs = require("node:fs");
const path = require("node:path");

const DEFAULT_CONFIG = {
  url: "https://pdv.quacksistemas.com.br",
  fullscreen: true,
  confirmClose: true,
  autoZoom: true,
  zoomAdjust: 1,
};

/** Resolução para a qual o PDV foi desenhado: nela o zoom automático é 1. */
const BASE_WIDTH = 1366;
const BASE_HEIGHT = 768;
const MIN_ZOOM = 0.6;
/** Piso do zoom automático: abaixo disso as letras pequenas do PDV (10–11px) ficam ilegíveis. */
const MIN_AUTO_ZOOM = 0.85;
const MAX_ZOOM = 3;
const ADJUST_STEP = 0.1;

const clamp = (value, min, max) => Math.min(max, Math.max(min, value));

function configPath() {
  return path.join(app.getPath("userData"), "config.json");
}

/** Grava só as chaves alteradas, preservando o resto do config.json do cliente. */
function saveConfig(patch) {
  try {
    const file = configPath();
    const current = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, "utf8")) : {};
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, JSON.stringify({ ...current, ...patch }, null, 2), "utf8");
  } catch (err) {
    console.error("Não foi possível salvar config.json:", err);
  }
}

function loadConfig() {
  const file = configPath();
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

// Perfil separado (testes/desenvolvimento): não mistura dados nem trava a instalação do caixa.
// Precisa vir antes do requestSingleInstanceLock, que usa a pasta do perfil.
if (process.env.QUACK_PDV_USER_DATA) {
  app.setPath("userData", process.env.QUACK_PDV_USER_DATA);
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

    // Zoom automático proporcional ao tamanho da janela (em DIPs: já respeita a escala do Windows).
    const applyZoom = () => {
      if (!mainWindow || mainWindow.isDestroyed()) return;
      const adjust = clamp(Number(config.zoomAdjust) || 1, 0.5, 2);
      let factor = adjust;
      if (config.autoZoom) {
        const [width, height] = mainWindow.getContentSize();
        factor = Math.max(MIN_AUTO_ZOOM, Math.min(width / BASE_WIDTH, height / BASE_HEIGHT)) * adjust;
      }
      mainWindow.webContents.setZoomFactor(clamp(Math.round(factor * 100) / 100, MIN_ZOOM, MAX_ZOOM));
    };
    let zoomTimer = null;
    const scheduleZoom = () => {
      clearTimeout(zoomTimer);
      zoomTimer = setTimeout(applyZoom, 80);
    };
    const changeAdjust = (next) => {
      config.zoomAdjust = clamp(Math.round(next * 100) / 100, 0.5, 2);
      saveConfig({ zoomAdjust: config.zoomAdjust });
      applyZoom();
    };
    mainWindow.on("resize", scheduleZoom);
    mainWindow.on("enter-full-screen", scheduleZoom);
    mainWindow.on("leave-full-screen", scheduleZoom);
    // Trocar de monitor (ex.: Win+Shift+seta em tela cheia) nem sempre dispara "resize": sem isto o zoom
    // calculado para o monitor de 1920 ficava no de 1366 e a página caía no layout de celular.
    mainWindow.on("move", scheduleZoom);
    mainWindow.on("moved", scheduleZoom);
    const onDisplayChange = () => scheduleZoom();
    screen.on("display-metrics-changed", onDisplayChange);
    screen.on("display-added", onDisplayChange);
    screen.on("display-removed", onDisplayChange);
    // Rede de segurança: confere o tamanho da janela a cada 1s e reaplica se mudou.
    let lastSize = "";
    const sizeWatch = setInterval(() => {
      if (!mainWindow || mainWindow.isDestroyed()) return;
      const size = mainWindow.getContentSize().join("x");
      if (size !== lastSize) {
        lastSize = size;
        applyZoom();
      }
    }, 1000);
    mainWindow.on("closed", () => {
      clearInterval(sizeWatch);
      screen.removeListener("display-metrics-changed", onDisplayChange);
      screen.removeListener("display-added", onDisplayChange);
      screen.removeListener("display-removed", onDisplayChange);
    });
    // O Chromium guarda zoom por origem: reaplica a cada carregamento (inclui a tela offline).
    mainWindow.webContents.on("did-finish-load", applyZoom);
    // Ctrl + roda do mouse vira ajuste fino (em vez do zoom solto do Chromium).
    mainWindow.webContents.on("zoom-changed", (_event, direction) => {
      changeAdjust((Number(config.zoomAdjust) || 1) + (direction === "in" ? ADJUST_STEP : -ADJUST_STEP));
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
      } else if (input.control && input.shift && key === "i") {
        // F12 NÃO: é o atalho de PAGAMENTO do PDV.
        mainWindow.webContents.toggleDevTools();
        event.preventDefault();
      } else if (input.control && (input.key === "=" || input.key === "+")) {
        changeAdjust((Number(config.zoomAdjust) || 1) + ADJUST_STEP);
        event.preventDefault();
      } else if (input.control && (input.key === "-" || input.key === "_")) {
        changeAdjust((Number(config.zoomAdjust) || 1) - ADJUST_STEP);
        event.preventDefault();
      } else if (input.control && input.key === "0") {
        changeAdjust(1);
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
