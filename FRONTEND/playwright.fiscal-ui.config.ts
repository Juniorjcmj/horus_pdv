import { defineConfig } from "@playwright/test";
import { join } from "node:path";
import { tmpdir } from "node:os";
import adminConfig from "./playwright.admin-ui.config";

export default defineConfig(adminConfig, {
  testMatch: "fiscal-ai-overview.spec.ts",
  outputDir: join(tmpdir(), "pdv-fiscal-status", "ui-results"),
  use: { trace: "off", screenshot: "off", video: "off" },
});
