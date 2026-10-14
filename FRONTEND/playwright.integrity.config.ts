import { defineConfig } from "@playwright/test";
export default defineConfig({
  outputDir: "./test-results-integrity", testDir: "./tests/integrity", fullyParallel: false, workers: 1, retries: 0,
  timeout: 60_000, reporter: "list", use: { baseURL: "http://127.0.0.1:5175" },
  webServer: { command: "npx vite --host 127.0.0.1 --port 5175", url: "http://127.0.0.1:5175", reuseExistingServer: false, timeout: 40_000 },
});
