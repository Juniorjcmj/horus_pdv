import { defineConfig } from "@playwright/test";
import architectureConfig from "./playwright.arch.config";

const appUrl = "http://127.0.0.1:5216";
export default defineConfig(architectureConfig, {
  testMatch: "admin-ui.spec.ts",
  workers: 1,
  outputDir: "./node_modules/.cache/playwright-admin-ui",
  use: { baseURL: appUrl, serviceWorkers: "block" },
  webServer: {
    command: "npx vite --host 127.0.0.1 --port 5216 --strictPort",
    url: appUrl,
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
