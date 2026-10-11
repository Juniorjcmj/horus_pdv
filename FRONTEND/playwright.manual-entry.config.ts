import { defineConfig } from "@playwright/test";
import { join } from "node:path";
import { tmpdir } from "node:os";
import adminConfig from "./playwright.admin-ui.config";

export default defineConfig(adminConfig, {
  testMatch: ["manual-entry.spec.ts", "invoice-history.spec.ts"],
  outputDir: join(tmpdir(), "pdv-manual-entry", "ui-results"),
  use: { trace: "off", screenshot: "off", video: "off" },
});
