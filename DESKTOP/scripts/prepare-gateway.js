/**
 * Arquivo: DESKTOP/scripts/prepare-gateway.js
 * Objetivo: copia o Quack Gateway publicado (self-contained win-x64) para DESKTOP/gateway-bin, de onde o
 *           electron-builder o empacota em resources\gateway (ver "extraResources" no package.json).
 *
 * Fonte: o mesmo kit que o sistema oferece para download, FRONTEND/public/gateway/quack-gateway-completo.zip
 * (regerado por GATEWAY/build-installer.sh sempre que GATEWAY/src muda). Assim o Gateway do programa é
 * sempre o mesmo do kit. Roda sozinho antes do "npm run dist"; também serve para testar com "npm start".
 */
const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const root = path.resolve(__dirname, "..");
const zip = path.resolve(root, "..", "FRONTEND", "public", "gateway", "quack-gateway-completo.zip");
const dest = path.join(root, "gateway-bin");

if (!fs.existsSync(zip)) {
  console.error(`[prepare-gateway] Não achei ${zip}. Rode GATEWAY/build-installer.sh antes.`);
  process.exit(1);
}

fs.rmSync(dest, { recursive: true, force: true });
fs.mkdirSync(dest, { recursive: true });

// tar do Windows (bsdtar, nativo desde o Windows 10) abre .zip. Logs/banco de testes não entram.
execFileSync(
  "tar",
  [
    "-xf", zip,
    "-C", dest,
    "--strip-components=2",
    "--exclude=QuackGateway/publish-service/logs",
    "--exclude=QuackGateway/publish-service/gateway-data",
    "QuackGateway/publish-service",
  ],
  { stdio: "inherit" },
);

const exe = path.join(dest, "HorusGateway.exe");
if (!fs.existsSync(exe)) {
  console.error("[prepare-gateway] HorusGateway.exe não veio do zip.");
  process.exit(1);
}
const files = fs.readdirSync(dest, { recursive: true }).length;
console.log(`[prepare-gateway] OK: ${files} arquivos em ${dest}`);
