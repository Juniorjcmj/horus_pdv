import { defineConfig } from "@playwright/test";
import architectureConfig from "./playwright.arch.config";

const appUrl = "http://127.0.0.1:5220";
export default defineConfig(architectureConfig, {
  testMatch: ["cash-closing-refresh.spec.ts", "caixa-offline.spec.ts", "bloco4-cash-atomicity.spec.ts"],
  workers: 1,
  outputDir: "./node_modules/.cache/playwright-cash-closing",
  use: { baseURL: appUrl, serviceWorkers: "block" },
  webServer: {
    command: "npx vite --host 127.0.0.1 --port 5220 --strictPort",
    url: appUrl, reuseExistingServer: false, timeout: 60_000,
  },
});
