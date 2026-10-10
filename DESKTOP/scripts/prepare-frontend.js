/** Build and copy the current production UI into the installer; native Gateway has its own resource. */
const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const desktop = path.resolve(__dirname, "..");
const frontend = path.resolve(desktop, "..", "FRONTEND");
const output = path.join(desktop, "frontend-bin");
if (!process.env.npm_execpath) throw new Error("Execute pelo npm: npm run prepare-frontend.");
execFileSync(process.execPath, [process.env.npm_execpath, "run", "build"], { cwd: frontend, stdio: "inherit" });
const dist = path.join(frontend, "dist");
for (const file of ["index.html", "sw.js", "build-info.json"]) {
  if (!fs.existsSync(path.join(dist, file))) throw new Error(`Frontend incompleto: falta ${file}.`);
}
// Fixed generated directory within DESKTOP; never touches the installed app's profile.
if (path.dirname(output) !== desktop || path.basename(output) !== "frontend-bin") throw new Error("Destino de build inválido.");
fs.rmSync(output, { recursive: true, force: true });
fs.cpSync(dist, output, { recursive: true, filter: file => file !== path.join(dist, "gateway") });
console.log(`[prepare-frontend] Telas atuais incluídas em ${output}`);
