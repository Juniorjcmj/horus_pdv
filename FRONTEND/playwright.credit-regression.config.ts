import { defineConfig } from "@playwright/test";
import architecture from "./playwright.arch.config";

// Isolated local server; unmocked external traffic cannot reach production services.
const baseURL = "http://127.0.0.1:5229";
process.env.APP_URL = baseURL;
export default defineConfig(architecture, {
  workers: 2,
  reporter: [["list"], ["json", { outputFile: "./node_modules/.cache/credit-regression-results.json" }]],
  outputDir: "./node_modules/.cache/playwright-credit-regression",
  use: { baseURL, serviceWorkers: "allow", proxy: { server: "http://127.0.0.1:9", bypass: "127.0.0.1,localhost" } },
  webServer: { command: "npx vite --host 127.0.0.1 --port 5229 --strictPort", url: baseURL, reuseExistingServer: false, timeout: 60000, stderr: "ignore" },
});
