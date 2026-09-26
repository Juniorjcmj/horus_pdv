import { defineConfig, devices } from "@playwright/test";

const appUrl = process.env.SMOKE_APP_URL ?? "http://localhost:5173";

export default defineConfig({
  testDir: "./tests/homologation",
  timeout: 180_000,
  expect: {
    timeout: 15_000,
  },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: appUrl,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  webServer: [
    {
      command:
        `bash -lc 'Recaptcha__Enabled=false Email__Enabled=false HORUSPDV_CONNECTION_STRING="${process.env.HORUSPDV_CONNECTION_STRING ?? "Server=localhost,1433;Database=HorusPdv;User Id=sa;Password=Senha@12345;TrustServerCertificate=True;MultipleActiveResultSets=True;"}" dotnet run --project ../API/NETCORE/HORUSPDV-API.csproj --urls http://localhost:5260'`,
      url: "http://localhost:5260/swagger/index.html",
      reuseExistingServer: true,
      timeout: 120_000,
    },
    {
      command: "vite --mode development --host localhost --port 5173",
      url: appUrl,
      reuseExistingServer: true,
      timeout: 120_000,
    },
  ],
});
