/**
 * Arquivo: src/application/customers/CustomerSyncAdapter.ts
 * Objetivo: puxa clientes da API e sincroniza com IndexedDB.
 */
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
    limiteCredito: dto.limiteCredito ?? 0,
    saldoDevedor: dto.saldoDevedor ?? 0,
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
    saldoDevedor: r.saldoDevedor,
  };
}

/**
 * Soma, por CPF/CNPJ (só dígitos), o fiado das vendas offline que ainda não chegaram ao servidor
 * (SALE_CREATED em PENDING/PROCESSING no outbox). Esse valor ainda não está no saldo do servidor.
 */
async function sumPendingFiadoByDocument(): Promise<Map<string, number>> {
  // Inclui FORWARDED: entregue ao Gateway da loja, mas a nuvem ainda não somou esse fiado.
  // Só da empresa logada: fiado offline de outra empresa (mesmo computador) não entra no saldo daqui.
  const tenant = currentTenantId();
  const pendingEvents = await db.outbox
    .where("status")
    .anyOf(NOT_IN_CLOUD_STATUSES)
    .filter((event) => isCurrentTenant(event, tenant))
    .toArray();
  const totals = new Map<string, number>();

  for (const evt of pendingEvents) {
    if (evt.eventType !== "SALE_CREATED") continue;
    try {
      const payload = typeof evt.payload === "string" ? JSON.parse(evt.payload) : evt.payload;
      const digits = String(payload?.customerCpf ?? "").replace(/\D/g, "");
      if (!digits) continue;

      const fiado = Array.isArray(payload?.payments) && payload.payments.length > 0
        ? payload.payments
            .filter((p: { paymentType?: string }) => String(p.paymentType).trim().toLowerCase() === "fiado")
            .reduce((sum: number, p: { amount?: number }) => sum + (Number(p.amount) || 0), 0)
        : String(payload?.paymentType).trim().toLowerCase() === "fiado"
          ? parseFloat(String(payload?.totalAmount ?? "0").replace(/\./g, "").replace(",", ".")) || 0
          : 0;

      if (fiado > 0) totals.set(digits, (totals.get(digits) ?? 0) + fiado);
    } catch {
      // Ignora payload malformado
    }
  }
  return totals;
}

/**
 * Puxa todos os clientes da API e salva no IndexedDB.
 * Protege o saldo devedor: soma o fiado de vendas offline ainda pendentes no outbox, senão o
 * cliente "recuperaria" limite até a fila sincronizar e poderia comprar sem saldo.
 */
export async function syncCustomersFromApi(): Promise<LocalCustomerRecord[]> {
  const dtos = await customerService.list();
  const records = dtos.map(mapDtoToLocal);

  await db.transaction("rw", [db.customers, db.outbox], async () => {
    const pendingFiado = await sumPendingFiadoByDocument();
    if (pendingFiado.size > 0) {
      for (const record of records) {
        const extra = pendingFiado.get(record.cpfCnpj.replace(/\D/g, "")) ?? 0;
        if (extra > 0) record.saldoDevedor = (record.saldoDevedor ?? 0) + extra;
      }
    }
    await db.customers.clear();
    await db.customers.bulkPut(records);
  });
  return records;
}

/** Saldo devedor atual do cliente no IndexedDB (inclui fiado offline pendente). Null se não estiver no cache. */
export async function getLocalCustomerDebt(customerIdOrDoc: string): Promise<number | null> {
  const digits = customerIdOrDoc.replace(/\D/g, "");
  const all = await customerRepository.getAll();
  const target = all.find(
    (c) => c.id === customerIdOrDoc || (digits.length > 0 && c.cpfCnpj.replace(/\D/g, "") === digits),
  );
  return target ? target.saldoDevedor ?? 0 : null;
}

/** Carrega clientes do IndexedDB. Se vazio, tenta sync da API. */
export async function loadCustomersLocal(): Promise<LocalCustomerRecord[]> {
  const count = await customerRepository.count();
  if (count > 0) return customerRepository.getAll();
  return [];
}

/** Salva um único cliente no IndexedDB (após criação/edição online). */
export async function upsertCustomerLocal(dto: CustomerDto): Promise<void> {
  await customerRepository.upsert(mapDtoToLocal(dto));
}

/** Atualiza o saldo devedor do cliente localmente no IndexedDB e notifica componentes via evento. */
export async function updateCustomerDebtLocal(
  customerIdOrDoc: string,
  newSaldoDevedor: number,
): Promise<void> {
  try {
    const all = await customerRepository.getAll();
    const digits = customerIdOrDoc.replace(/\D/g, "");
    const target = all.find(
      (c) =>
        c.id === customerIdOrDoc ||
        (digits.length > 0 && c.cpfCnpj.replace(/\D/g, "") === digits),
    );
    if (target) {
      target.saldoDevedor = newSaldoDevedor;
      target.updatedAt = new Date().toISOString();
      await customerRepository.upsert(target);
      window.dispatchEvent(
        new CustomEvent("customer-balance-updated", {
          detail: { customerId: target.id, document: target.cpfCnpj, saldoDevedor: newSaldoDevedor },
        }),
      );
    }
  } catch (err) {
    console.warn("Falha ao atualizar saldo local do cliente no IndexedDB:", err);
  }
}
