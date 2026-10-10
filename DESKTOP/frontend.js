/** Serves the bundled PDV at its existing HTTPS origin, preserving the store's browser profile. */
const fs = require("node:fs");
const path = require("node:path");
const { pathToFileURL } = require("node:url");

const PDV_ORIGIN = "https://pdv.quacksistemas.com.br";

function readBuildInfo(root) {
  try {
    const info = JSON.parse(fs.readFileSync(path.join(root, "build-info.json"), "utf8"));
    return typeof info.version === "string" && Number.isFinite(Date.parse(info.builtAt)) ? info : null;
  } catch { return null; }
}

async function shouldUseRemote(local, origin, fetchRemote) {
  try {
    const response = await fetchRemote(`${origin}/build-info.json`, {
      cache: "no-store", signal: AbortSignal.timeout(4000), bypassCustomProtocolHandlers: true,
    });
    if (!response.ok) return false;
    const remote = await response.json();
    return typeof remote.version === "string" && remote.version !== local.version
      && Date.parse(remote.builtAt) > Date.parse(local.builtAt);
  } catch { return false; }
}

function resolveBundledFile(root, pathname) {
  let decoded;
  try { decoded = decodeURIComponent(pathname); } catch { return null; }
  if (decoded.includes("\\") || decoded.includes("\0")) return null;
  const relative = decoded === "/" ? "index.html" : decoded.replace(/^\/+/, "");
  const file = path.resolve(root, relative);
  const inside = path.relative(root, file);
  if (!inside || inside.startsWith("..") || path.isAbsolute(inside)) return null;
  return fs.existsSync(file) && fs.statSync(file).isFile() ? file : null;
}

async function configureFrontend({ app, net, protocol, url, source = "auto" }) {
  const origin = new URL(url).origin;
  const root = app.isPackaged ? path.join(process.resourcesPath, "frontend") : path.join(__dirname, "frontend-bin");
  const local = readBuildInfo(root);
  // Custom environments keep their configured URL. Only the production PDV has a bundled copy.
  if (origin !== PDV_ORIGIN || !local || source === "remote"
    || (!app.isPackaged && source !== "bundled")) return "remote";
  if (source !== "bundled" && await shouldUseRemote(local, origin, (...args) => net.fetch(...args))) return "remote";

  protocol.handle("https", async request => {
    const target = new URL(request.url);
    const file = target.origin === origin && (request.method === "GET" || request.method === "HEAD")
      ? resolveBundledFile(root, target.pathname) : null;
    if (!file) return net.fetch(request, { bypassCustomProtocolHandlers: true });
    const response = await net.fetch(pathToFileURL(file).href);
    const headers = new Headers(response.headers);
    headers.set("Cache-Control", "no-cache");
    return new Response(request.method === "HEAD" ? null : response.body, { status: response.status, headers });
  });
  console.info(`[Quack PDV] Telas do instalador: ${local.version}`);
  return "bundled";
}

module.exports = { configureFrontend, readBuildInfo, resolveBundledFile, shouldUseRemote };
