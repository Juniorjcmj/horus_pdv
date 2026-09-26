import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { fileURLToPath, URL } from "node:url";
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

const chunkGroups: Record<string, string> = {
  react: "vendor-react",
  "react-dom": "vendor-react",
  scheduler: "vendor-react",
  "react-router": "vendor-router",
  "react-router-dom": "vendor-router",
  "date-fns": "vendor-date",
  "@date-fns/tz": "vendor-date",
  "react-day-picker": "vendor-ui",
  "lucide-react": "vendor-ui",
  recharts: "vendor-charts",
  "d3-array": "vendor-charts",
  "d3-color": "vendor-charts",
  "d3-format": "vendor-charts",
  "d3-interpolate": "vendor-charts",
  "d3-path": "vendor-charts",
  "d3-scale": "vendor-charts",
  "d3-shape": "vendor-charts",
  eventemitter3: "vendor-charts",
};

function getPackageName(id: string) {
  const [, pathAfterNodeModules] = id.split("node_modules/");
  if (!pathAfterNodeModules) return null;

  const parts = pathAfterNodeModules.split("/");
  if (parts[0].startsWith("@") && parts[1]) {
    return `${parts[0]}/${parts[1]}`;
  }
  return parts[0];
}

/**
 * Plugin que injeta a lista de assets com hash e versao do cache no Service Worker
 * apos o build. Substitui os placeholders __CACHE_VERSION__ e PRECACHE_URLS = []
 * no dist/sw.js gerado a partir de public/sw.js.
 */
function horusPwaPlugin(): Plugin {
  return {
    name: "horus-pwa-precache",
    apply: "build",
    closeBundle() {
      const distDir = join(__dirname, "dist");
      const swPath = join(distDir, "sw.js");

      let swSource: string;
      try {
        swSource = readFileSync(swPath, "utf-8");
      } catch {
        return; // sw.js nao existe no dist — nada a fazer
      }

      // Coleta todos os assets de dist/ que devem ser pre-cacheados
      const precacheUrls: string[] = ["/", "/index.html", "/manifest.webmanifest", "/favicon.svg"];

      const assetsDir = join(distDir, "assets");
      try {
        const files = readdirSync(assetsDir);
        for (const file of files) {
          const full = join(assetsDir, file);
          if (statSync(full).isFile()) {
            precacheUrls.push(`/assets/${file}`);
          }
        }
      } catch {
        // pasta assets pode nao existir em builds minimos
      }

      // Gera versao baseada no hash do conteudo dos assets
      const hash = createHash("md5");
      for (const url of precacheUrls.sort()) {
        hash.update(url);
      }
      const cacheVersion = hash.digest("hex").slice(0, 10);

      // Substitui os placeholders
      let output = swSource.replace('"__CACHE_VERSION__"', JSON.stringify(cacheVersion));
      output = output.replace("const PRECACHE_URLS = [];", `const PRECACHE_URLS = ${JSON.stringify(precacheUrls, null, 2)};`);

      writeFileSync(swPath, output, "utf-8");
    },
  };
}

// https://vite.dev/config/
export default defineConfig({
  build: {
    target: "esnext",
    sourcemap: false,
    outDir: "dist",
    assetsInlineLimit: 4096,
    cssCodeSplit: true,
    modulePreload: false,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes("node_modules")) return;

          const pkgName = getPackageName(id);
          if (!pkgName) return "vendor";

          return chunkGroups[pkgName] ?? "vendor";
        },
      },
    },
  },
  optimizeDeps: {
    include: ["react", "react-dom"],
  },
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  plugins: [react(), tailwindcss(), horusPwaPlugin()],
});
