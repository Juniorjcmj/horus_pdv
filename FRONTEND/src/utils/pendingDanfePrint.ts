import type { SaleReceipt } from "@/components/Admin/ReceiptPreviewModal";
import { fiscalService } from "@/services/api/fiscalService";
import { currentTenantId } from "@/infrastructure/database/repositories/OutboxRepository";
import { db } from "@/infrastructure/database/dexie";
import { buildDanfePrintHtml } from "./danfePrint";
import { printSaleReceipt } from "./printSaleReceipt";
import { gatewayDetailFromXml } from "./gatewayFiscalAttempt";

const KEY = "horus-pdv-pending-danfe-prints";
type Pending = { tenant: string; receipt: SaleReceipt; retryAt: number; attempts: number };
function read(): Pending[] {
  try { const data: unknown = JSON.parse(localStorage.getItem(KEY) || "[]"); return Array.isArray(data) ? data.filter(p => p && typeof p.tenant === "string" && p.receipt?.saleNumber) : []; }
  catch { return []; }
}
function save(queue: Pending[]) { localStorage.setItem(KEY, JSON.stringify(queue)); }
export function enqueueDanfePrint(receipt: SaleReceipt) {
  const queue = read(), tenant = currentTenantId();
  if (!queue.some(p => p.tenant === tenant && p.receipt.saleNumber === receipt.saleNumber))
    save([...queue, { tenant, receipt, retryAt: 0, attempts: 0 }]);
}
export function finishDanfePrint(saleNumber: string) {
  save(read().filter(p => p.tenant !== currentTenantId() || p.receipt.saleNumber !== saleNumber));
}
export async function printReviewedReceipt(html: string, saleNumber: string, fiscal: boolean) {
  const print = async () => { await printSaleReceipt(html); if (fiscal) finishDanfePrint(saleNumber); };
  if (navigator.locks) await navigator.locks.request("horus-pdv-danfe-print", print);
  else await print();
}
let flushing = false;
/** A fila continua depois de recarregar a tela e nunca imprime um recibo no lugar da nota. */
export async function flushPendingDanfePrints(formatMoney: (value: number) => string, onPrinted?: (receipt: SaleReceipt) => void) {
  if (flushing) return;
  const run = async () => {
    flushing = true;
    try {
      const tenant = currentTenantId();
      for (const pending of read().filter(p => p.tenant === tenant && p.retryAt <= Date.now())) {
        try {
          if (tenant !== currentTenantId()) break;
          if (!read().some(p => p.tenant === tenant && p.receipt.saleNumber === pending.receipt.saleNumber)) continue;
          const localSale = pending.receipt.clientSaleId ? await db.sales.get(pending.receipt.clientSaleId) : null;
          const queryNumber = localSale?.tenantId === tenant ? localSale.saleNumber : pending.receipt.saleNumber;
          const event = pending.receipt.eventId ? await db.outbox.get(pending.receipt.eventId) : null;
          if (event?.tenantId === tenant && event.status === "FISCAL_PENDING") throw new Error("Comprovante em preparação.");
          const eventPayload = event?.tenantId === tenant ? JSON.parse(event.payload) as { xmlNfceContingencia?: string } : null;
          const localDetail = eventPayload?.xmlNfceContingencia ? gatewayDetailFromXml(eventPayload.xmlNfceContingencia, queryNumber) : null;
          const detail = localDetail || (pending.receipt.fiscalDetail?.xmlDocumento && pending.receipt.fiscalDetail.status === 8 ? pending.receipt.fiscalDetail : await fiscalService.getBySaleNumber(queryNumber));
          if (!detail || (detail.status !== 3 && detail.status !== 8)) throw new Error("Comprovante ainda não disponível.");
          if (tenant !== currentTenantId()) break;
          await printSaleReceipt(buildDanfePrintHtml(pending.receipt, detail, formatMoney));
          finishDanfePrint(pending.receipt.saleNumber);
          onPrinted?.({ ...pending.receipt, fiscalDetail: detail });
        } catch {
          const queue = read();
          const entry = queue.find(p => p.tenant === tenant && p.receipt.saleNumber === pending.receipt.saleNumber);
          if (entry) { entry.attempts++; entry.retryAt = Date.now() + Math.min(60000, 3000 * 2 ** Math.min(entry.attempts, 5)); save(queue); }
        }
      }
    } finally { flushing = false; }
  };
  if (navigator.locks) await navigator.locks.request("horus-pdv-danfe-print", { ifAvailable: true }, lock => lock ? run() : undefined);
  else await run();
}
