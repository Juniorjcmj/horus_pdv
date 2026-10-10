import { defineConfig } from "@playwright/test";
import architectureConfig from "./playwright.arch.config";

const appUrl = "http://127.0.0.1:5222";
export default defineConfig(architectureConfig, {
  testMatch: "database-backup.spec.ts",
  workers: 1,
  outputDir: "./node_modules/.cache/playwright-database-backup",
  use: { baseURL: appUrl, serviceWorkers: "block" },
  webServer: { command: "npx vite --host 127.0.0.1 --port 5222 --strictPort", url: appUrl, reuseExistingServer: false, timeout: 60_000 },
});
