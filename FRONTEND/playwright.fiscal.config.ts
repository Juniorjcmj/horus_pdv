import { defineConfig, devices } from "@playwright/test";
import { join } from "node:path";
import { tmpdir } from "node:os";

const artifacts = process.env.FISCAL_TEST_ARTIFACTS ?? join(tmpdir(), "pdv-fiscal-front-tests");
process.env.FISCAL_TEST_ARTIFACTS = artifacts;
process.env.TEMP = artifacts;
process.env.TMP = artifacts;
const appUrl = "http://127.0.0.1:5189";

export default defineConfig({
  testDir: "./tests/fiscal",
  outputDir: join(artifacts, "results"),
  timeout: 30_000,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["list"]],
  use: { baseURL: appUrl, trace: "off", screenshot: "off", video: "off", serviceWorkers: "block" },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: "node node_modules/vite/bin/vite.js --config tests/fiscal/vite.config.ts --host 127.0.0.1 --port 5189 --strictPort",
    url: appUrl,
    reuseExistingServer: false,
    timeout: 30_000,
  },
});
