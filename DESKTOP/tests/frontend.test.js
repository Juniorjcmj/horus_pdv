const { test, after } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { readBuildInfo, resolveBundledFile, shouldUseRemote } = require("../frontend");

const cache = path.resolve(__dirname, "../node_modules/.cache");
fs.mkdirSync(cache, { recursive: true });
const root = fs.mkdtempSync(path.join(cache, "frontend-test-"));
fs.mkdirSync(path.join(root, "assets"));
fs.writeFileSync(path.join(root, "index.html"), "<html>PDV</html>");
fs.writeFileSync(path.join(root, "assets", "app.js"), "console.log('PDV');");
const local = { version: "local", builtAt: "2026-10-09T12:00:00Z" };
fs.writeFileSync(path.join(root, "build-info.json"), JSON.stringify(local));
after(() => {
  assert.equal(path.dirname(root), cache);
  fs.rmSync(root, { recursive: true, force: true });
});

test("resolves the bundled index and hashed assets", () => {
  assert.equal(resolveBundledFile(root, "/"), path.join(root, "index.html"));
  assert.equal(resolveBundledFile(root, "/assets/app.js"), path.join(root, "assets/app.js"));
  assert.equal(resolveBundledFile(root, "/missing.js"), null);
});

test("rejects traversal, Windows paths, malformed encoding and directories", () => {
  for (const value of ["/../package.json", "/%2e%2e/package.json", "/%2e%2e%2fpackage.json", "/%5c..%5cpackage.json",
    "/C:/Windows/system.ini", "/%00", "/%ZZ", "/assets"]) assert.equal(resolveBundledFile(root, value), null);
});

test("requires valid build metadata", () => {
  assert.deepEqual(readBuildInfo(root), local);
  assert.equal(readBuildInfo(path.join(root, "missing")), null);
});

test("a newer published frontend can replace the bundled one", async () => {
  const result = await shouldUseRemote(local, "https://pdv.quacksistemas.com.br", async (url, options) => {
    assert.equal(url, "https://pdv.quacksistemas.com.br/build-info.json");
    assert.equal(options.bypassCustomProtocolHandlers, true);
    assert.equal(options.cache, "no-store");
    return Response.json({ version: "new", builtAt: "2026-10-09T13:00:00Z" });
  });
  assert.equal(result, true);
});

test("an older, identical or invalid server build keeps the bundled version", async () => {
  for (const remote of [{ version: "old", builtAt: "2026-10-08T12:00:00Z" },
    { version: "local", builtAt: "2026-10-10T12:00:00Z" }, { version: "bad", builtAt: "invalid" }, {}]) {
    assert.equal(await shouldUseRemote(local, "https://pdv.quacksistemas.com.br", async () => Response.json(remote)), false);
  }
});

test("offline, timeout, HTTP error and a server without metadata use the bundle", async () => {
  for (const request of [async () => { throw new Error("offline"); }, async () => { throw new DOMException("Timeout", "TimeoutError"); },
    async () => new Response("missing", { status: 404 }), async () => new Response("<html>PDV</html>")]) {
    assert.equal(await shouldUseRemote(local, "https://pdv.quacksistemas.com.br", request), false);
  }
});
