import { defineConfig } from "vite";
import { mkdirSync } from "node:fs";
import { fileURLToPath, URL } from "node:url";
import { join } from "node:path";
import { tmpdir } from "node:os";

const artifacts = process.env.FISCAL_TEST_ARTIFACTS ?? join(tmpdir(), "pdv-fiscal-front-tests");
mkdirSync(artifacts, { recursive: true });

// No application bootstrap or real API configuration is needed for these browser-level utilities.
export default defineConfig({
  root: fileURLToPath(new URL("../..", import.meta.url)),
  envDir: artifacts,
  cacheDir: join(artifacts, "vite-cache"),
  resolve: { alias: { "@": fileURLToPath(new URL("../../src", import.meta.url)) } },
  define: {
    "import.meta.env.VITE_NFCE_API_URL": JSON.stringify("http://127.0.0.1:5189/mock/nfce"),
    "import.meta.env.VITE_NFE_API_URL": JSON.stringify("http://127.0.0.1:5189/mock/nfe"),
  },
});
