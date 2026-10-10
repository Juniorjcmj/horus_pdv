import { defineConfig } from "@playwright/test";
import architectureConfig from "./playwright.arch.config";

const appUrl = "http://127.0.0.1:5218";

export default defineConfig(architectureConfig, {
  testMatch: "barcode-scanner.spec.ts",
  workers: 1,
  outputDir: "./node_modules/.cache/playwright-scanner",
  use: { baseURL: appUrl, serviceWorkers: "block" },
  webServer: {
    command: "npm run build && npx vite preview --host 127.0.0.1 --port 5218 --strictPort",
    url: appUrl,
    reuseExistingServer: false,
    timeout: 180_000,
  },
});
