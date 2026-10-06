/**
 * Arquivo: src/infrastructure/database/localTenant.ts
 * Objetivo: mesmo computador usado por mais de uma empresa. Ao entrar numa empresa diferente da última
 *           que usou este aparelho, apaga os dados locais que vieram do servidor da outra (catálogo,
 *           clientes/saldo de fiado, situação do caixa) para não vender com o cadastro errado enquanto a
 *           sincronização não termina. As PENDÊNCIAS (vendas/caixa offline ainda não enviados) NÃO são
 *           apagadas: ficam guardadas e só são enviadas quando alguém da empresa dona entrar
 *           (ver OutboxRepository).
 */
import { db } from "./dexie";

const LAST_TENANT_KEY = "horus-local-tenant";

function readLastTenant(): string {
  try {
    return window.localStorage.getItem(LAST_TENANT_KEY) ?? "";
  } catch {
    return "";
  }
}

function writeLastTenant(companyId: string) {
  try {
    window.localStorage.setItem(LAST_TENANT_KEY, companyId);
  } catch {
    // sem storage: na próxima entrada a comparação simplesmente não acontece
  }
}

/**
 * Chamar ao entrar (antes de iniciar a sincronização). Retorna true se limpou dados de outra empresa.
 * Primeira vez neste aparelho (nenhuma empresa registrada): só registra, não apaga nada.
 */
export async function ensureLocalTenant(companyId: string | undefined | null): Promise<boolean> {
  const tenant = companyId?.trim() ?? "";
  if (!tenant) return false;
  const last = readLastTenant();
  if (last === tenant) return false;

  if (last) {
    await db.transaction("rw", [db.products, db.customers, db.cashSessions], async () => {
      await db.products.clear();
      await db.customers.clear();
      await db.cashSessions.clear();
    });
  }
  writeLastTenant(tenant);
  return Boolean(last);
}
