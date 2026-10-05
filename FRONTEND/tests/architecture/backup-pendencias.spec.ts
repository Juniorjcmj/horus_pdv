import { test, expect } from "@playwright/test";

test.describe("Backup das pendências: gerar e importar", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/");
    await page.waitForFunction(() => (window as any).__horus_test__ !== undefined);
    await page.evaluate(async () => (window as any).__horus_test__.resetDatabase());
  });

  test("backup inclui só o que não foi enviado; importar recoloca na fila sem duplicar", async ({ page }) => {
    const r = await page.evaluate(async () => {
      const { OutboxRepository, db } = (window as any).__horus_test__;
      const mod = await import("/src/infrastructure/desktop/pendingBackup.ts" as string);

      await OutboxRepository.enqueueEvent({
        id: "ev-venda-1", eventType: "SALE_CREATED", aggregateType: "Sale", aggregateId: "cs-1",
        payloadHash: "hash-venda-1", payload: { totalAmount: "25,90", items: [] },
      });
      await OutboxRepository.enqueueEvent({
        id: "ev-cxmov-1", eventType: "CASH_MOVEMENT", aggregateType: "CashSession", aggregateId: "cx-1",
        payloadHash: "hash-mov-1", payload: { tipo: "Reforco", valor: "35,00", motivo: "Troco" },
      });
      await OutboxRepository.enqueueEvent({
        id: "ev-ja-enviado", eventType: "SALE_CREATED", aggregateType: "Sale", aggregateId: "cs-2", payload: {},
      });
      await db.outbox.update("ev-ja-enviado", { status: "PROCESSED" });

      const backup = await mod.buildPendingBackup();
      const text = JSON.stringify(backup);
      const preview = mod.parsePendingBackup(text);

      // Computador "novo": fila vazia → importa tudo
      await db.outbox.clear();
      const first = await mod.importPendingBackup(preview.backup);
      const afterFirst = await db.outbox.orderBy("sequence").toArray();
      // Importar de novo o mesmo arquivo: não duplica
      const second = await mod.importPendingBackup(preview.backup);

      return {
        count: backup.count,
        ids: backup.events.map((e: any) => e.id),
        byType: preview.byType,
        first,
        second,
        restored: afterFirst.map((e: any) => ({ id: e.id, status: e.status, hash: e.payloadHash, payloadIsString: typeof e.payload === "string" })),
        total: await db.outbox.count(),
      };
    });

    expect(r.count).toBe(2);
    expect(r.ids).toEqual(["ev-venda-1", "ev-cxmov-1"]);
    expect(r.byType).toEqual({ SALE_CREATED: 1, CASH_MOVEMENT: 1 });
    expect(r.first).toEqual({ imported: 2, skipped: 0 });
    expect(r.second).toEqual({ imported: 0, skipped: 2 });
    expect(r.total).toBe(2);
    expect(r.restored).toEqual([
      { id: "ev-venda-1", status: "PENDING", hash: "hash-venda-1", payloadIsString: true },
      { id: "ev-cxmov-1", status: "PENDING", hash: "hash-mov-1", payloadIsString: true },
    ]);
  });

  test("recusa arquivo que não é backup do Quack PDV ou tem evento desconhecido", async ({ page }) => {
    const errors = await page.evaluate(async () => {
      const mod = await import("/src/infrastructure/desktop/pendingBackup.ts" as string);
      const tryParse = (text: string) => {
        try {
          mod.parsePendingBackup(text);
          return "aceitou";
        } catch (e) {
          return (e as Error).message;
        }
      };
      return [
        tryParse("não é json"),
        tryParse(JSON.stringify({ hello: 1 })),
        tryParse(JSON.stringify({ format: "quack-pdv-pending-backup", version: 1, count: 1, events: [{ id: "x", eventType: "DROP_TABLE", payload: {} }] })),
      ];
    });
    expect(errors[0]).toContain("JSON válido");
    expect(errors[1]).toContain("não é um backup");
    expect(errors[2]).toContain("tipo de evento desconhecido");
  });
});
