/**
 * Arquivo: src/utils/mergePendingSales.ts
 * Objetivo: o histórico de vendas mostra, junto das vendas da nuvem, as feitas neste terminal que a nuvem
 *           ainda não aceitou (marcadas "pendente de envio"), sem duplicar uma venda já presente na nuvem.
 */
import type { SaleHistoryDto } from "@/services/api/salesHistoryService";

export type PendingSyncMarked<T> = T & { pendingSync?: boolean };

/**
 * Junta as linhas da nuvem com as vendas locais ainda não sincronizadas. A comparação é por `clientSaleId`
 * (identidade da venda, igual no terminal e na nuvem): venda já presente na nuvem não é repetida.
 * As locais entram primeiro (mais recentes) e vêm marcadas com `pendingSync`.
 */
export function mergePendingLocalSales(
  cloudRows: SaleHistoryDto[],
  pendingLocalRows: SaleHistoryDto[],
): PendingSyncMarked<SaleHistoryDto>[] {
  const cloudClientIds = new Set(cloudRows.map((row) => row.clientSaleId).filter((id): id is string => Boolean(id)));
  const cloudNumbers = new Set(cloudRows.map((row) => row.saleNumber));
  const onlyPending = pendingLocalRows.filter(
    (row) => !(row.clientSaleId && cloudClientIds.has(row.clientSaleId)) && !cloudNumbers.has(row.saleNumber),
  );
  return [...onlyPending.map((row) => ({ ...row, pendingSync: true })), ...cloudRows];
}
