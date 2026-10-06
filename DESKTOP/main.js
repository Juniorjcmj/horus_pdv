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
 *     "autoZoom": true, "zoomAdjust": 1, "backupDir": "<opcional>" }
 * Variável de ambiente QUACK_PDV_URL sobrescreve a URL (útil para apontar para o ambiente de dev).
 *
 * Zoom automático: o PDV foi desenhado para 1366x768. A janela aplica um zoom proporcional ao tamanho
 * dela (como o Ctrl+ do navegador) para a tela inteira — letras, ícones e campos — ficar do mesmo jeito
 * em qualquer monitor. "zoomAdjust" é o ajuste fino do operador (Ctrl+ / Ctrl- / Ctrl+0), salvo no config.
 *
 * Gateway embutido (ver gateway.js): ativado pelo PDV em Configurações → Gateway deste computador; fica em
 *   config.json → "gateway": { "enabled", "companyId", "storeId", "apiUrl", "port", "tokenEnc" }.
 *
 * Atalhos: F11 tela cheia | F5 / Ctrl+R recarregar | Ctrl+Shift+R recarregar sem cache | Ctrl+Shift+I DevTools (suporte)
 *          Ctrl+= / Ctrl+- aumentar/diminuir | Ctrl+0 voltar ao tamanho automático
 */
const { app, BrowserWindow, dialog, ipcMain, safeStorage, screen, shell } = require("electron");
const fs = require("node:fs");
const path = require("node:path");
const { EmbeddedGateway, validateConfigureInput } = require("./gateway");

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

// ---------------------------------------------------------------------------
// Backup automático das pendências (vendas/movimentos ainda não enviados ao servidor)
// ---------------------------------------------------------------------------

/** Fora do perfil do programa: se %APPDATA%\Quack PDV for apagado/corromper, o backup continua. */
function backupDir(config) {
  return config.backupDir || path.join(app.getPath("documents"), "Quack PDV", "Backups");
}

const MAX_BACKUP_BYTES = 50 * 1024 * 1024;
const BACKUP_RETENTION_DAYS = 30;

/** Grava em arquivo temporário e renomeia: um desligamento no meio não deixa o backup pela metade. */
function writeAtomic(file, content) {
  const tmp = `${file}.tmp`;
  fs.writeFileSync(tmp, content, "utf8");
  fs.renameSync(tmp, file);
}

function pruneOldBackups(dir) {
  const limit = Date.now() - BACKUP_RETENTION_DAYS * 24 * 60 * 60 * 1000;
  for (const name of fs.readdirSync(dir)) {
    if (!/^pendencias-\d{4}-\d{2}-\d{2}\.json$/.test(name)) continue;
    const full = path.join(dir, name);
    try {
      if (fs.statSync(full).mtimeMs < limit) fs.unlinkSync(full);
    } catch {
      // arquivo em uso/sumiu: tenta de novo no próximo backup
    }
  }
}

/**
 * pendencias-atual.json: sempre o estado mais recente (com 0 itens quando está tudo enviado).
 * pendencias-AAAA-MM-DD.json: último estado com pendências de cada dia (guardado por 30 dias).
 */
function savePendingBackup(config, json) {
  const parsed = JSON.parse(json);
  const dir = backupDir(config);
  fs.mkdirSync(dir, { recursive: true });
  const content = JSON.stringify(parsed, null, 2);
  const current = path.join(dir, "pendencias-atual.json");
  writeAtomic(current, content);
  if (Number(parsed?.count) > 0) {
    // Data local (não UTC): à noite no Brasil o UTC já é o dia seguinte.
    const now = new Date();
    const day = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`;
    writeAtomic(path.join(dir, `pendencias-${day}.json`), content);
  }
  pruneOldBackups(dir);
  return current;
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

// ---------------------------------------------------------------------------
// Gateway embutido: configuração (token cifrado pelo Windows) e caminho do executável
// ---------------------------------------------------------------------------

function readFileConfig() {
  try {
    const file = configPath();
    return fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, "utf8")) : {};
  } catch {
    return {};
  }
}

function readGatewaySettings() {
  const gateway = { ...(readFileConfig().gateway || {}) };
  let token = "";
  if (gateway.tokenEnc) {
    try {
      token = safeStorage.decryptString(Buffer.from(gateway.tokenEnc, "base64"));
    } catch (err) {
      console.error("Não foi possível decifrar o token do Gateway:", err);
    }
  } else if (gateway.token) {
    token = gateway.token;
  }
  delete gateway.tokenEnc;
  return { ...gateway, token };
}

function writeGatewaySettings(patch) {
  const current = readFileConfig().gateway || {};
  const next = { ...current, ...patch };
  delete next.token;
  if ("token" in patch) {
    delete next.tokenEnc;
    if (patch.token) {
      if (safeStorage.isEncryptionAvailable()) {
        next.tokenEnc = safeStorage.encryptString(patch.token).toString("base64");
      } else {
        next.token = patch.token;
      }
    }
  }
  saveConfig({ gateway: next });
}

/** Instalado: resources\gateway (extraResources). Desenvolvimento: DESKTOP\gateway-bin (scripts/prepare-gateway.js). */
function gatewayExePath() {
  if (process.env.QUACK_GATEWAY_EXE) return process.env.QUACK_GATEWAY_EXE;
  return app.isPackaged
    ? path.join(process.resourcesPath, "gateway", "HorusGateway.exe")
    : path.join(__dirname, "gateway-bin", "HorusGateway.exe");
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
        preload: path.join(__dirname, "preload.js"),
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

  let gateway = null;
  let quitting = false;

  app.whenReady().then(() => {
    config = loadConfig();

    // Só a página do PDV (mesma origem da URL configurada) fala com o programa.
    const assertAppOrigin = (event) => {
      const appOrigin = new URL(config.url).origin;
      let senderOrigin = "";
      try {
        senderOrigin = new URL(event.senderFrame?.url || "").origin;
      } catch {
        // URL inválida: cai na recusa abaixo
      }
      if (senderOrigin !== appOrigin) throw new Error("Origem não autorizada.");
    };

    // Backup: conteúdo limitado e validado.
    ipcMain.handle("quack:save-pending-backup", (event, json) => {
      assertAppOrigin(event);
      if (typeof json !== "string" || json.length > MAX_BACKUP_BYTES) throw new Error("Backup inválido.");
      return savePendingBackup(config, json);
    });

    gateway = new EmbeddedGateway({
      exePath: gatewayExePath(),
      dataDir: path.join(app.getPath("userData"), "gateway"),
      readSettings: readGatewaySettings,
      writeSettings: writeGatewaySettings,
    });
    ipcMain.handle("quack:gateway-status", (event) => {
      assertAppOrigin(event);
      return gateway.status();
    });
    ipcMain.handle("quack:gateway-configure", (event, input) => {
      assertAppOrigin(event);
      return gateway.configure(validateConfigureInput(input));
    });
    ipcMain.handle("quack:gateway-disable", (event) => {
      assertAppOrigin(event);
      return gateway.disable();
    });
    // Sobe junto com o programa (sem travar a abertura da janela).
    void gateway.start();

    createWindow();
  });

  // Fecha o Gateway junto com o programa (espera o processo sair antes de encerrar).
  app.on("will-quit", (event) => {
    if (quitting || !gateway?.child) return;
    event.preventDefault();
    quitting = true;
    void gateway.stop().finally(() => app.quit());
  });

  app.on("window-all-closed", () => app.quit());
}
