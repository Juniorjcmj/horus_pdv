/**
 * Arquivo: DESKTOP/gateway.js
 * Objetivo: Gateway embutido — o programa traz o Quack Gateway (HorusGateway.exe, publicado
 *           self-contained) e o executa como processo filho enquanto o PDV está aberto. Pensado para a
 *           loja de um caixa só: tudo num instalador, sem Administrador, sem Serviço do Windows e sem
 *           firewall (escuta só em 127.0.0.1).
 *
 * A ativação vem do PDV (Configurações → Gateway deste computador): a página gera o token da loja
 * (qgw_...) e entrega aqui com o CompanyId. O token fica no config.json cifrado pelo Windows (DPAPI,
 * via safeStorage). O banco do Gateway fica em %APPDATA%\Quack PDV\gateway\horus-gateway.db.
 *
 * Se já houver um Gateway respondendo na porta (ex.: Serviço do Windows numa loja com vários caixas),
 * o programa não sobe outro: só informa "external".
 */
const { spawn, execFile } = require("node:child_process");
const { randomUUID } = require("node:crypto");
const fs = require("node:fs");
const http = require("node:http");
const path = require("node:path");

const DEFAULT_PORT = 5080;
const DEFAULT_STORE_ID = "loja-01";
const RESTART_DELAYS_MS = [2_000, 5_000, 10_000, 30_000];
const STARTUP_TIMEOUT_MS = 20_000;

/** GET http://127.0.0.1:<port><path> com timeout curto; resolve com o JSON ou null. */
function getJson(port, urlPath, timeoutMs = 1500) {
  return new Promise((resolve) => {
    const req = http.get({ host: "127.0.0.1", port, path: urlPath, timeout: timeoutMs }, (res) => {
      let body = "";
      res.setEncoding("utf8");
      res.on("data", (chunk) => {
        body += chunk;
      });
      res.on("end", () => {
        if (res.statusCode !== 200) return resolve(null);
        try {
          resolve(JSON.parse(body));
        } catch {
          resolve(null);
        }
      });
    });
    req.on("timeout", () => req.destroy());
    req.on("error", () => resolve(null));
  });
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Confirma pelo nome da imagem que o PID ainda é um HorusGateway (o Windows reaproveita PIDs). */
function isGatewayPid(pid) {
  return new Promise((resolve) => {
    execFile("tasklist", ["/FI", `PID eq ${pid}`, "/FO", "CSV", "/NH"], { windowsHide: true }, (err, stdout) => {
      resolve(!err && /"HorusGateway\.exe"/i.test(stdout));
    });
  });
}

class EmbeddedGateway {
  /**
   * @param {object} deps
   * @param {string} deps.exePath  caminho do HorusGateway.exe
   * @param {string} deps.dataDir  pasta do banco/pid (%APPDATA%\Quack PDV\gateway)
   * @param {() => object} deps.readSettings   lê config.gateway (já com o token decifrado)
   * @param {(patch: object) => void} deps.writeSettings  grava config.gateway (cifra o token)
   */
  constructor({ exePath, dataDir, readSettings, writeSettings }) {
    this.exePath = exePath;
    this.dataDir = dataDir;
    this.readSettings = readSettings;
    this.writeSettings = writeSettings;
    this.child = null;
    this.stopping = false;
    this.external = false;
    this.restartIndex = 0;
    this.restartTimer = null;
    this.lastError = "";
    this.startedAt = null;
  }

  get pidFile() {
    return path.join(this.dataDir, "gateway.pid");
  }

  get port() {
    return Number(this.readSettings().port) || DEFAULT_PORT;
  }

  get url() {
    return `http://127.0.0.1:${this.port}`;
  }

  isBundled() {
    return fs.existsSync(this.exePath);
  }

  isConfigured(settings = this.readSettings()) {
    return Boolean(settings.enabled && settings.companyId && settings.token);
  }

  /** Estado para a página do PDV (nunca devolve o token). */
  async status() {
    const settings = this.readSettings();
    const live = await getJson(this.port, "/api/gateway/status");
    if (live && this.child) this.lastError = "";
    return {
      bundled: this.isBundled(),
      configured: this.isConfigured(settings),
      running: Boolean(live),
      managed: Boolean(this.child),
      external: this.external,
      url: this.url,
      companyId: live?.companyId || settings.companyId || "",
      storeId: live?.storeId || settings.storeId || "",
      gatewayId: live?.gatewayId || "",
      /** Id do token na nuvem (não é segredo): o PDV revoga o anterior ao reativar/desativar. */
      tokenId: settings.tokenId || "",
      lastError: this.lastError,
    };
  }

  /** Mata um Gateway que sobrou de uma execução anterior do programa (queda/encerramento forçado). */
  async killOrphan() {
    let pid = 0;
    try {
      pid = Number(fs.readFileSync(this.pidFile, "utf8").trim());
    } catch {
      return;
    }
    if (pid > 0 && (await isGatewayPid(pid))) {
      try {
        process.kill(pid);
        await sleep(1000);
      } catch {
        // já tinha saído
      }
    }
    try {
      fs.unlinkSync(this.pidFile);
    } catch {
      // ignore
    }
  }

  /** Sobe o Gateway se estiver ativado. Nunca lança: falhas ficam em lastError. */
  async start() {
    this.stopping = false;
    this.external = false;
    const settings = this.readSettings();
    if (!this.isConfigured(settings)) return this.status();
    if (!this.isBundled()) {
      this.lastError = "Gateway não incluído nesta instalação do programa.";
      return this.status();
    }
    if (this.child) return this.status();

    await this.killOrphan();
    if (await getJson(this.port, "/api/gateway/status")) {
      // Porta ocupada por outro Gateway (Serviço do Windows/instalação manual): usa o que já existe.
      this.external = true;
      this.lastError = "";
      return this.status();
    }

    this.spawnChild(settings);
    const deadline = Date.now() + STARTUP_TIMEOUT_MS;
    while (Date.now() < deadline && this.child) {
      if (await getJson(this.port, "/api/gateway/status")) {
        this.lastError = "";
        break;
      }
      await sleep(500);
    }
    return this.status();
  }

  spawnChild(settings) {
    fs.mkdirSync(this.dataDir, { recursive: true });
    // Identidade fixa deste Gateway (sem ela o Gateway inventa outra a cada início).
    let gatewayId = settings.gatewayId;
    if (!gatewayId) {
      gatewayId = `gw_${randomUUID().replace(/-/g, "")}`;
      this.writeSettings({ gatewayId });
    }
    const env = {
      ...process.env,
      ASPNETCORE_ENVIRONMENT: "Production",
      ASPNETCORE_URLS: `http://127.0.0.1:${this.port}`,
      Gateway__GatewayId: gatewayId,
      Gateway__CompanyId: settings.companyId,
      Gateway__StoreId: settings.storeId || DEFAULT_STORE_ID,
      Gateway__DatabasePath: path.join(this.dataDir, "horus-gateway.db"),
      Gateway__CloudSyncToken: settings.token,
      Gateway__CloudApiBaseUrl: settings.apiUrl || "",
    };
    // cwd = pasta do exe: o Gateway acha appsettings.json e o painel (wwwroot) e grava os logs ali.
    const child = spawn(this.exePath, [], {
      cwd: path.dirname(this.exePath),
      env,
      windowsHide: true,
      stdio: "ignore",
    });
    this.child = child;
    this.startedAt = Date.now();
    try {
      fs.writeFileSync(this.pidFile, String(child.pid ?? ""), "utf8");
    } catch {
      // sem pid file só perde a limpeza de órfão
    }

    child.on("error", (err) => {
      this.lastError = `Não foi possível iniciar o Gateway: ${err.message}`;
    });
    child.on("exit", () => {
      if (this.child !== child) return;
      this.child = null;
      try {
        fs.unlinkSync(this.pidFile);
      } catch {
        // ignore
      }
      if (this.stopping) return;
      this.lastError = "O Gateway parou inesperadamente. Reiniciando...";
      // Rodou bem por um tempo: volta ao menor intervalo de reinício.
      if (Date.now() - (this.startedAt ?? 0) > 60_000) this.restartIndex = 0;
      const delay = RESTART_DELAYS_MS[Math.min(this.restartIndex, RESTART_DELAYS_MS.length - 1)];
      this.restartIndex++;
      clearTimeout(this.restartTimer);
      this.restartTimer = setTimeout(() => {
        if (!this.stopping && !this.child && this.isConfigured()) this.spawnChild(this.readSettings());
      }, delay);
    });
  }

  /** Para o Gateway e espera o processo sair (o SQLite já gravou cada evento ao recebê-lo). */
  async stop() {
    this.stopping = true;
    clearTimeout(this.restartTimer);
    const child = this.child;
    if (!child) return;
    await new Promise((resolve) => {
      const timer = setTimeout(resolve, 5_000);
      child.once("exit", () => {
        clearTimeout(timer);
        resolve();
      });
      try {
        child.kill();
      } catch {
        clearTimeout(timer);
        resolve();
      }
    });
    this.child = null;
  }

  /** Ativa (ou troca o token/empresa) e reinicia o Gateway com a configuração nova. */
  async configure({ token, tokenId, companyId, storeId, apiUrl }) {
    this.writeSettings({
      enabled: true,
      token,
      tokenId,
      companyId,
      storeId: storeId || DEFAULT_STORE_ID,
      apiUrl,
    });
    await this.stop();
    this.restartIndex = 0;
    return this.start();
  }

  /** Desativa: para o Gateway e apaga o token. O banco fica (eventos ainda não enviados não se perdem). */
  async disable() {
    await this.stop();
    this.writeSettings({ enabled: false, token: "", tokenId: "" });
    this.lastError = "";
    return this.status();
  }
}

/** Valida o que a página manda para configure(). Lança Error com mensagem para o operador. */
function validateConfigureInput(input) {
  const token = typeof input?.token === "string" ? input.token.trim() : "";
  const companyId = typeof input?.companyId === "string" ? input.companyId.trim() : "";
  const storeId = typeof input?.storeId === "string" ? input.storeId.trim() : "";
  const tokenId = typeof input?.tokenId === "string" ? input.tokenId.trim() : "";
  const apiUrl = typeof input?.apiUrl === "string" ? input.apiUrl.trim().replace(/\/+$/, "") : "";
  if (!/^qgw_[A-Za-z0-9_-]{16,200}$/.test(token)) throw new Error("Token do Gateway inválido.");
  if (tokenId.length > 64) throw new Error("Id do token inválido.");
  if (!companyId || companyId.length > 100) throw new Error("Empresa inválida.");
  if (storeId.length > 100) throw new Error("Loja inválida.");
  let parsed;
  try {
    parsed = new URL(apiUrl);
  } catch {
    throw new Error("Endereço da API inválido.");
  }
  const isLocal = ["localhost", "127.0.0.1", "[::1]"].includes(parsed.hostname);
  if (parsed.protocol !== "https:" && !(isLocal && parsed.protocol === "http:")) {
    throw new Error("O endereço da API precisa ser https.");
  }
  return { token, tokenId, companyId, storeId, apiUrl };
}

module.exports = { EmbeddedGateway, validateConfigureInput, DEFAULT_PORT };
