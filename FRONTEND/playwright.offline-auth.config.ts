import { defineConfig } from "@playwright/test";
import architectureConfig from "./playwright.arch.config";

const appUrl = "http://127.0.0.1:5209";

export default defineConfig(architectureConfig, {
  testMatch: ["offline-login.spec.ts", "bloco7-offline-auth.spec.ts", "desktop-gateway.spec.ts", "caixa-offline.spec.ts"],
  workers: 1,
  outputDir: "./node_modules/.cache/playwright-offline-auth",
  use: { baseURL: appUrl, serviceWorkers: "block" },
  webServer: {
    command: "npx vite --host 127.0.0.1 --port 5209 --strictPort",
    url: appUrl,
    reuseExistingServer: false,
    timeout: 60_000,
    env: { VITE_RECAPTCHA_SITE_KEY: "offline-auth-test-key" },
  },
});
