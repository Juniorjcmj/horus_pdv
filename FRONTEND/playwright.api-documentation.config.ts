import { defineConfig } from "@playwright/test";
import architectureConfig from "./playwright.arch.config";

const appUrl = "http://127.0.0.1:5218";

export default defineConfig(architectureConfig, {
  testMatch: "api-documentation.spec.ts",
  workers: 1,
  outputDir: "./node_modules/.cache/playwright-api-documentation",
  use: { baseURL: appUrl, serviceWorkers: "block" },
  webServer: {
    command: "npx vite --host 127.0.0.1 --port 5218 --strictPort",
    url: appUrl,
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
