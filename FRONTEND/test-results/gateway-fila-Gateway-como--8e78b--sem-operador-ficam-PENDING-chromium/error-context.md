# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: gateway-fila.spec.ts >> Gateway como fila: sem internet a fila vai ao Gateway da loja >> eventos com operador vão ao Gateway (mesmo eventId) e ficam FORWARDED; sem operador ficam PENDING
- Location: tests\architecture\gateway-fila.spec.ts:52:3

# Error details

```
Error: expect(received).toMatchObject(expected)

- Expected  - 1
+ Received  + 1

  Object {
    "companyId": "emp-1",
    "eventType": "SALE_CREATED",
-   "payloadHash": "hash-ev-venda-1",
+   "payloadHash": "b5c9571888706f1104ff6481aed9691c049f79356f65d85918ccf9e6d1803cb3",
    "terminalId": "PDV-01",
  }
```

# Test source

```ts
  1   | import { test, expect, type Page, type Route } from "@playwright/test";
  2   | 
  3   | const GW = "http://gateway.test:5080";
  4   | 
  5   | type Captured = { body: any; headers: Record<string, string> };
  6   | 
  7   | async function setupGateway(page: Page, mode: { up: boolean }, captured: Captured[]) {
  8   |   await page.route(`${GW}/**`, async (route: Route) => {
  9   |     if (!mode.up) return route.abort();
  10  |     const url = route.request().url();
  11  |     if (url.endsWith("/api/gateway/status")) {
  12  |       return route.fulfill({ json: { service: "HorusGateway", bound: true, companyId: "emp-1", storeId: "loja-01", gatewayId: "gw-1", terminalAuthRequired: true } });
  13  |     }
  14  |     if (url.endsWith("/api/gateway/events") && route.request().method() === "POST") {
  15  |       captured.push({ body: route.request().postDataJSON(), headers: route.request().headers() });
  16  |       return route.fulfill({ json: { status: "accepted", ack: true } });
  17  |     }
  18  |     return route.fulfill({ status: 404, json: {} });
  19  |   });
  20  | }
  21  | 
  22  | async function prepare(page: Page) {
  23  |   await page.goto("/");
  24  |   await page.waitForFunction(() => (window as any).__horus_test__ !== undefined);
  25  |   await page.evaluate(async (gw) => {
  26  |     const { resetDatabase, CashSessionRepository } = (window as any).__horus_test__;
  27  |     await resetDatabase();
  28  |     localStorage.setItem(
  29  |       "horus-gateway-config",
  30  |       JSON.stringify({ enabled: true, url: gw, companyId: "emp-1", storeId: "loja-01", terminalId: "PDV-01", terminalType: "CASH", apiKey: "chave-terminal" }),
  31  |     );
  32  |     await CashSessionRepository.saveCashStatus({
  33  |       state: "aberto", canSell: true, blockReason: "", serverNow: new Date().toISOString(), history: [], lastSession: null,
  34  |       currentSession: {
  35  |         id: "s-1", status: "Aberto", openedAt: new Date().toISOString(), closedAt: null, openingAmount: "100,00", closingAmount: "0,00",
  36  |         operatorId: "usr-1", operatorName: "Maria", closedById: "", closedByName: "", note: "", elapsedMinutes: 1,
  37  |         expectedCashAmount: "100,00", movimentos: [], paymentBreakdown: [],
  38  |       },
  39  |     });
  40  |   }, GW);
  41  | }
  42  | 
  43  | const sale = (operatorId: string | undefined, eventId: string, paymentType = "dinheiro") => ({
  44  |   eventId, clientSaleId: `cs-${eventId}`, operatorId,
  45  |   customerName: "Maria Fiado", customerCpf: "111.222.333-44", paymentType, totalAmount: "30,00", operatorName: "Maria",
  46  |   items: [{ productCode: "P001", productName: "Arroz", quantity: 1, unitPrice: 30, desconto: 0, itemTotal: 30 }],
  47  |   payments: [{ paymentType, amount: 30, cashGiven: null, changeAmount: null }],
  48  |   payloadHash: `hash-${eventId}`,
  49  | });
  50  | 
  51  | test.describe("Gateway como fila: sem internet a fila vai ao Gateway da loja", () => {
  52  |   test("eventos com operador vão ao Gateway (mesmo eventId) e ficam FORWARDED; sem operador ficam PENDING", async ({ page }) => {
  53  |     const captured: Captured[] = [];
  54  |     await setupGateway(page, { up: true }, captured);
  55  |     await prepare(page);
  56  | 
  57  |     const r = await page.evaluate(async ([s1, s2]) => {
  58  |       const { SaleOutboxAdapter, CashSessionRepository, db } = (window as any).__horus_test__;
  59  |       await SaleOutboxAdapter.queueSaleToOutbox(s1);
  60  |       await SaleOutboxAdapter.queueSaleToOutbox(s2);
  61  |       await CashSessionRepository.registerMovementLocal("Reforco", "15,00", "Troco", "usr-1", "Maria", "ev-mov-1");
  62  |       const mod = await import("/src/infrastructure/gateway/outboxGatewayForwarder.ts" as string);
  63  |       const result = await mod.forwardPendingToGateway();
  64  |       const events = await db.outbox.orderBy("sequence").toArray();
  65  |       return { result, statuses: events.map((e: any) => `${e.id}:${e.status}`) };
  66  |     }, [sale("usr-1", "ev-venda-1"), sale(undefined, "ev-venda-antiga")]);
  67  | 
  68  |     expect(r.result).toMatchObject({ forwarded: 2, skipped: 1 });
  69  |     expect(r.statuses).toEqual(["ev-venda-1:FORWARDED", "ev-venda-antiga:PENDING", "ev-mov-1:FORWARDED"]);
  70  | 
  71  |     expect(captured.map((c) => c.body.eventId)).toEqual(["ev-venda-1", "ev-mov-1"]);
  72  |     const venda = captured[0];
> 73  |     expect(venda.body).toMatchObject({ eventType: "SALE_CREATED", companyId: "emp-1", terminalId: "PDV-01", payloadHash: "hash-ev-venda-1" });
      |                        ^ Error: expect(received).toMatchObject(expected)
  74  |     expect(venda.body.payload.operatorId).toBe("usr-1");
  75  |     expect(venda.headers["x-terminal-id"]).toBe("PDV-01");
  76  |     expect(venda.headers["x-terminal-key"]).toBe("chave-terminal");
  77  |     expect(captured[1].body).toMatchObject({ eventType: "CASH_MOVEMENT" });
  78  |     expect(captured[1].body.payload).toMatchObject({ tipo: "Reforco", valor: "15,00", operatorId: "usr-1" });
  79  |   });
  80  | 
  81  |   test("FORWARDED continua na fila de envio à nuvem e protege o saldo do fiado", async ({ page }) => {
  82  |     const captured: Captured[] = [];
  83  |     await setupGateway(page, { up: true }, captured);
  84  |     await prepare(page);
  85  | 
  86  |     const r = await page.evaluate(async (s) => {
  87  |       const { SaleOutboxAdapter, CustomerSyncAdapter, customerService, OutboxRepository, db } = (window as any).__horus_test__;
  88  |       await db.customers.put({
  89  |         id: "cli-1", tenantId: "", name: "Maria Fiado", cpfCnpj: "111.222.333-44", phone: "", email: "",
  90  |         limiteCredito: 100, saldoDevedor: 30, updatedAt: new Date().toISOString(), version: 1,
  91  |       });
  92  |       await SaleOutboxAdapter.queueSaleToOutbox(s);
  93  |       const mod = await import("/src/infrastructure/gateway/outboxGatewayForwarder.ts" as string);
  94  |       await mod.forwardPendingToGateway();
  95  | 
  96  |       const paraNuvem = (await OutboxRepository.getPendingEvents()).map((e: any) => `${e.id}:${e.status}`);
  97  |       // Servidor ainda sem a venda (só o Gateway tem): saldo do servidor 30 + fiado 30 pendente = 60
  98  |       const original = customerService.list;
  99  |       customerService.list = async () => [{ id: "cli-1", customerName: "Maria Fiado", document: "111.222.333-44", limiteCredito: 100, saldoDevedor: 30 }];
  100 |       try {
  101 |         await CustomerSyncAdapter.syncCustomersFromApi();
  102 |       } finally {
  103 |         customerService.list = original;
  104 |       }
  105 |       return { paraNuvem, saldo: (await db.customers.get("cli-1"))?.saldoDevedor };
  106 |     }, sale("usr-1", "ev-fiado-1", "fiado"));
  107 | 
  108 |     expect(r.paraNuvem).toEqual(["ev-fiado-1:FORWARDED"]);
  109 |     expect(r.saldo).toBe(60);
  110 |   });
  111 | 
  112 |   test("Gateway fora do ar: nada é marcado, eventos seguem PENDING", async ({ page }) => {
  113 |     const captured: Captured[] = [];
  114 |     await setupGateway(page, { up: false }, captured);
  115 |     await prepare(page);
  116 | 
  117 |     const r = await page.evaluate(async (s) => {
  118 |       const { SaleOutboxAdapter, db } = (window as any).__horus_test__;
  119 |       await SaleOutboxAdapter.queueSaleToOutbox(s);
  120 |       const mod = await import("/src/infrastructure/gateway/outboxGatewayForwarder.ts" as string);
  121 |       const result = await mod.forwardPendingToGateway();
  122 |       return { result, status: (await db.outbox.get("ev-venda-2"))?.status };
  123 |     }, sale("usr-1", "ev-venda-2"));
  124 | 
  125 |     expect(r.result.forwarded).toBe(0);
  126 |     expect(r.status).toBe("PENDING");
  127 |     expect(captured).toHaveLength(0);
  128 |   });
  129 | });
  130 | 
```