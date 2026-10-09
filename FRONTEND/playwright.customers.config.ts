import { defineConfig } from "@playwright/test";
import architectureConfig from "./playwright.arch.config";

const appUrl = "http://127.0.0.1:5217";
export default defineConfig(architectureConfig, {
  testMatch: "customer-registration.spec.ts",
  workers: 1,
  outputDir: "./node_modules/.cache/playwright-customers",
  use: { baseURL: appUrl, serviceWorkers: "block" },
  webServer: {
    command: "npx vite --host 127.0.0.1 --port 5217 --strictPort",
    url: appUrl,
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
