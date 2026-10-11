import { test as base, expect } from "@playwright/test";

export const test = base.extend<{ unexpectedRequests: string[] }>({
  unexpectedRequests: [async ({ page }, use) => {
    const requests: string[] = [];
    await page.context().route("**/*", async route => {
      const url = new URL(route.request().url());
      if (url.origin !== "http://127.0.0.1:5189") {
        requests.push(url.toString());
        await route.abort();
      } else if (url.pathname === "/__fiscal_test__") {
        await route.fulfill({ contentType: "text/html", body: "<!doctype html><html><body></body></html>" });
      } else if (url.pathname.startsWith("/mock/")) {
        requests.push(url.toString());
        await route.abort();
      } else {
        await route.continue();
      }
    });
    await page.goto("/__fiscal_test__");
    await use(requests);
    expect(requests, "The fiscal utility suite must not contact any API or external service").toEqual([]);
  }, { auto: true }],
});

export { expect };
