import { defineConfig, devices } from "@playwright/test";

const appUrl = process.env.APP_URL ?? "http://localhost:5173";

export default defineConfig({
  testDir: "./tests/architecture",
  timeout: 60_000,
  expect: {
    timeout: 10_000,
  },
  fullyParallel: false,
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: appUrl,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  webServer: {
    command: "npx vite --port 5173",
    url: appUrl,
    reuseExistingServer: true,
    timeout: 30_000,
  },
});
