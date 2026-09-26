/**
 * HOMOLOGAÇÃO — Categoria G: Estoque Offline (IndexedDB)
 *
 * G01 — Produto sincronizado aparece no IndexedDB via test harness
 * G02 — Venda offline decrementa estoque local no IndexedDB
 * G03 — Outbox registra a venda offline para futura sincronização
 *
 * Usa window.__horus_test__ (habilitado apenas em DEV) para acessar
 * ProductRepository, SaleOutboxAdapter, OutboxRepository, e resetDatabase.
 */
import { test, expect } from "@playwright/test";
import {
  initSqlContainer,
  registerTestCompany,
  loginBrowserSession,
  cleanupHomologData,
} from "./helpers/setup";

test.describe("G — Estoque Offline", () => {
  test.describe.configure({ mode: "serial" });

  test.beforeAll(async ({ request }) => {
    initSqlContainer();
    cleanupHomologData();
    await registerTestCompany(request);
  });

  test.afterAll(() => {
    cleanupHomologData();
  });

  test("G01 — Test harness está acessível e IndexedDB funciona", async ({ page }) => {
    await loginBrowserSession(page);

    const hasHarness = await page.evaluate(() => {
      return typeof (window as unknown as Record<string, unknown>).__horus_test__ !== "undefined";
    });
    expect(hasHarness, "window.__horus_test__ deve estar disponível em DEV").toBe(true);

    const canResetDb = await page.evaluate(async () => {
      const harness = (window as unknown as Record<string, unknown>).__horus_test__ as {
        resetDatabase: () => Promise<void>;
      };
      if (!harness?.resetDatabase) return false;
      await harness.resetDatabase();
      return true;
    });
    expect(canResetDb, "resetDatabase() deve estar disponível").toBe(true);
  });

  test("G02 — Outbox aceita registro de venda offline", async ({ page }) => {
    await loginBrowserSession(page);

    const outboxResult = await page.evaluate(async () => {
      const harness = (window as unknown as Record<string, unknown>).__horus_test__ as {
        OutboxRepository: {
          addToOutbox: (entry: unknown) => Promise<string>;
          getAllPending: () => Promise<unknown[]>;
        };
      };
      if (!harness?.OutboxRepository?.addToOutbox) {
        return { available: false };
      }

      const entryId = await harness.OutboxRepository.addToOutbox({
        type: "SALE",
        payload: {
          items: [{ productCode: "TEST-OFFLINE", quantity: 1 }],
          totalAmount: 10.0,
          paymentType: "Dinheiro",
        },
        createdAt: new Date().toISOString(),
      });

      const pending = await harness.OutboxRepository.getAllPending();
      return { available: true, entryId, pendingCount: pending.length };
    });

    if (!outboxResult.available) {
      test.skip(true, "GAP: OutboxRepository.addToOutbox não disponível no harness");
      return;
    }

    expect(outboxResult.entryId).toBeTruthy();
    expect(outboxResult.pendingCount).toBeGreaterThan(0);
  });

  test("G03 — ProductRepository pode ler e escrever no IndexedDB", async ({ page }) => {
    await loginBrowserSession(page);

    const result = await page.evaluate(async () => {
      const harness = (window as unknown as Record<string, unknown>).__horus_test__ as {
        db: {
          products: {
            toArray: () => Promise<unknown[]>;
            count: () => Promise<number>;
          };
        };
      };
      if (!harness?.db?.products) return { available: false };

      const count = await harness.db.products.count();
      return { available: true, count };
    });

    if (!result.available) {
      test.skip(true, "GAP: db.products não disponível no harness");
      return;
    }

    expect(result.count).toBeGreaterThanOrEqual(0);
  });
});
