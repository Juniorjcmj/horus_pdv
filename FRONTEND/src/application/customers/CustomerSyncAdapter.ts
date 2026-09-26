/**
 * Arquivo: src/application/customers/CustomerSyncAdapter.ts
 * Objetivo: puxa clientes da API e sincroniza com IndexedDB.
 */
import { customerService, type CustomerDto } from "@/services/api/customerService";
import { customerRepository } from "@/infrastructure/database/repositories/CustomerRepository";
import type { LocalCustomerRecord } from "@/infrastructure/database/dexie";

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

/** Puxa todos os clientes da API e salva no IndexedDB. */
export async function syncCustomersFromApi(): Promise<LocalCustomerRecord[]> {
  const dtos = await customerService.list();
  const records = dtos.map(mapDtoToLocal);
  await customerRepository.clear();
  await customerRepository.bulkUpsert(records);
  return records;
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
