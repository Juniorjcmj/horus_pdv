/**
 * Arquivo: src/application/customers/CustomerSyncAdapter.ts
 * Objetivo: puxa clientes da API e sincroniza com IndexedDB.
 */
import { sumMoney, money } from "@/utils/saleMoney";
import { sharedCreditRequired } from "@/infrastructure/gateway/gatewayCreditClient";
import { customerService, type CustomerDto } from "@/services/api/customerService";
import { customerRepository } from "@/infrastructure/database/repositories/CustomerRepository";
import { db, type LocalCustomerRecord } from "@/infrastructure/database/dexie";
import { NOT_IN_CLOUD_STATUSES, currentTenantId, isCurrentTenant } from "@/infrastructure/database/repositories/OutboxRepository";

function mapDtoToLocal(dto: CustomerDto): LocalCustomerRecord {
  return {
    id: dto.id,
    tenantId: "",
    name: dto.customerName,
    cpfCnpj: dto.document || "",
    phone: dto.cellphone || dto.telephone || "",
    email: dto.email || "",
    limiteCredito: dto.limiteCredito ?? Number.NaN,
    saldoDevedor: dto.saldoDevedor ?? Number.NaN,
    updatedAt: new Date().toISOString(),
    version: 1,
  };
}

/** Converte LocalCustomerRecord de volta para CustomerDto (para uso na UI). */
export function localToDto(r: LocalCustomerRecord): CustomerDto {
  return {
    id: r.id,
    customerName: r.name,
    document: r.cpfCnpj,
    birthDate: "",
    age: "",
    cep: "",
    city: "",
    state: "",
    address: "",
    neighborhood: "",
    streetComplement: "",
    number: "",
    referencePoint: "",
    telephone: "",
    cellphone: r.phone,
    email: r.email,
    limiteCredito: r.limiteCredito,
    saldoDevedor: r.creditUnavailable ? Number.NaN : r.saldoDevedor,
  };
}

/**
 * Soma, por CPF/CNPJ (só dígitos), o fiado das vendas offline que ainda não chegaram ao servidor
 * (SALE_CREATED em PENDING/PROCESSING no outbox). Esse valor ainda não está no saldo do servidor.
 */
async function sumPendingFiadoByDocument(acceptedIds: Set<string> = new Set(), capturedIds: Set<string> = new Set()): Promise<Map<string, number>> {
  // Inclui FORWARDED: entregue ao Gateway da loja, mas a nuvem ainda não somou esse fiado.
  // Só da empresa logada: fiado offline de outra empresa (mesmo computador) não entra no saldo daqui.
  const tenant = currentTenantId();
  const pendingEvents = await db.outbox
    .filter((event) => isCurrentTenant(event, tenant) && (NOT_IN_CLOUD_STATUSES.includes(event.status) || capturedIds.has(event.aggregateId)))
    .toArray();
  const totals = new Map<string, number>();

  for (const evt of pendingEvents) {
    if (evt.eventType !== "SALE_CREATED" || acceptedIds.has(evt.clientSaleId || evt.aggregateId)) continue;
    try {
      const payload = typeof evt.payload === "string" ? JSON.parse(evt.payload) : evt.payload;
      const digits = String(payload?.customerCpf ?? "").replace(/\D/g, "");
      if (!payload || typeof payload !== "object") throw new Error("Pendência sem dados financeiros.");

      const fiado = Array.isArray(payload?.payments) && payload.payments.length > 0
        ? payload.payments
            .filter((p: { paymentType?: string }) => String(p.paymentType).trim().toLowerCase() === "fiado")
            .reduce((sum: number, p: { amount?: number }) => sumMoney([sum, Number(p.amount)]), 0)
        : String(payload?.paymentType).trim().toLowerCase() === "fiado"
          ? money(payload?.totalAmount ?? "0")
          : 0;

      if (fiado > 0 && !digits) throw new Error("Pendência fiado sem cliente.");
      if (fiado < 0) throw new Error("Pendência fiado negativa.");
      if (fiado > 0) totals.set(digits, sumMoney([totals.get(digits) ?? 0, fiado]));
    } catch {
      throw new Error("Pendência fiado inválida. Crédito indisponível até conferir a fila deste caixa.");
    }
  }
  return totals;
}

/**
 * Puxa todos os clientes da API e salva no IndexedDB.
 * Protege o saldo devedor: soma o fiado de vendas offline ainda pendentes no outbox, senão o
 * cliente "recuperaria" limite até a fila sincronizar e poderia comprar sem saldo.
 */
class CreditSnapshotChanged extends Error {}
export async function syncCustomersFromApi(): Promise<LocalCustomerRecord[]> {
  for (let attempt = 0; attempt < 3; attempt++) {
    try { return await readCreditSnapshotOnce(); }
    catch (error) {
      if (!(error instanceof CreditSnapshotChanged)) throw error;
      // Cache preserved — offline operation continues with last validated snapshot.
    }
  }
  throw new Error("Crédito mudou durante a conferência. Atualize novamente.");
}
async function readCreditSnapshotOnce(): Promise<LocalCustomerRecord[]> {
  const tenant = currentTenantId();
  const captured = await db.transaction("r", db.outbox, async () => {
    const events = await db.outbox.filter(e => isCurrentTenant(e, tenant) && e.eventType === "SALE_CREATED").toArray();
    return { sequence: Math.max(0, ...events.map(e => e.sequence)), ids: events.filter(e => NOT_IN_CLOUD_STATUSES.includes(e.status)).map(e => e.clientSaleId || e.aggregateId) };
  });
  const capturedIds = new Set(captured.ids);
  // Network failure preserves the validated cache for offline operation — never poison it.
  const snapshot = await customerService.creditSnapshot([...capturedIds]);
  if (tenant !== currentTenantId()) throw new Error("Empresa alterada durante conferência de crédito.");
  const records = snapshot.customers.map(dto => ({ ...mapDtoToLocal(dto), tenantId: tenant }));
  await db.transaction("rw", [db.customers, db.outbox], async () => {
    const newEvent = await db.outbox.filter(e => isCurrentTenant(e, tenant) && e.eventType === "SALE_CREATED" && e.sequence > captured.sequence).first();
    if (newEvent) throw new CreditSnapshotChanged("Crédito mudou durante a conferência. Novo snapshot necessário.");
    const pendingFiado = await sumPendingFiadoByDocument(new Set(snapshot.acceptedClientSaleIds), capturedIds);
    for (const record of records) {
      const extra = pendingFiado.get(record.cpfCnpj.replace(/\D/g, "")) ?? 0;
      if (extra > 0) record.saldoDevedor = sumMoney([record.saldoDevedor, extra]);
    }
    await db.customers.filter(c => c.tenantId === tenant).delete();
    await db.customers.bulkPut(records);
  });
  return records;
}

/** Informational debt only. Shared-credit authority always comes from the Gateway ledger. */
export async function getLocalCustomerDebt(customerIdOrDoc: string): Promise<number | null> {
  const digits = customerIdOrDoc.replace(/\D/g, "");
  const all = await customerRepository.getAll();
  const target = all.find(
    (c) => c.tenantId === currentTenantId() && (c.id === customerIdOrDoc || (digits.length > 0 && c.cpfCnpj.replace(/\D/g, "") === digits)),
  );
  return target ? target.creditUnavailable && !sharedCreditRequired() ? Number.NaN : target.saldoDevedor ?? null : null;
}

/** Carrega clientes do IndexedDB. Se vazio, tenta sync da API. */
export async function loadCustomersLocal(): Promise<LocalCustomerRecord[]> {
  const count = await customerRepository.count();
  if (count > 0) return customerRepository.getAll();
  return [];
}

/** Salva um único cliente no IndexedDB (após criação/edição online). */
export async function upsertCustomerLocal(dto: CustomerDto): Promise<void> {
  // Always use the same snapshot mechanism; a pending-count check followed by upsert races an offline sale.
  try {
    await syncCustomersFromApi();
    if (!(await db.customers.get(dto.id))) throw new Error("Cliente indisponível no snapshot de crédito.");
  } catch {
    // Network failure: upsert with available data. Do NOT mark creditUnavailable.
    const record: LocalCustomerRecord = { ...mapDtoToLocal(dto), tenantId: currentTenantId() };
    await db.customers.put(record);
  }
}

/** A cloud balance alone cannot distinguish pending debt. Always reconcile identities. */
export async function updateCustomerDebtLocal(customerIdOrDoc: string, newSaldoDevedor: number): Promise<void> {
  const tenant = currentTenantId();
  const digits = customerIdOrDoc.replace(/\D/g, "");
  try {
    await syncCustomersFromApi();
    const target = (await customerRepository.getAll()).find(c => c.tenantId === tenant && (c.id === customerIdOrDoc || (digits.length > 0 && c.cpfCnpj.replace(/\D/g, "") === digits)));
    if (target) window.dispatchEvent(new CustomEvent("customer-balance-updated", { detail: { customerId: target.id, document: target.cpfCnpj, saldoDevedor: target.saldoDevedor } }));
  } catch {
    // Network failure: update the local saldo with the value we already know from the sale.
    // Do NOT mark creditUnavailable — the cache stays valid for offline operation.
    await db.customers.filter(c => c.tenantId === tenant && (c.id === customerIdOrDoc || (digits.length > 0 && c.cpfCnpj.replace(/\D/g, "") === digits))).modify({ saldoDevedor: newSaldoDevedor });
    console.warn("Sync falhou; saldo local atualizado com valor da venda.");
  }
}
