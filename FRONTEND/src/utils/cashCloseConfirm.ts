/**
 * Arquivo: src/utils/cashCloseConfirm.ts
 * Objetivo: texto da confirmação de fechamento de caixa. Com eventos ainda não enviados à nuvem (vendas feitas
 *           sem internet, por exemplo), avisa a quantidade antes de confirmar.
 */
import { getNotInCloudCount } from "@/infrastructure/database/repositories/OutboxRepository";

/** Mensagem pura (testável): acrescenta o aviso de pendências à pergunta base. */
export function buildCloseConfirmMessage(base: string, pendingCount: number): string {
  if (pendingCount <= 0) return base;
  const plural = pendingCount > 1;
  return (
    `${base} Há ${pendingCount} ${plural ? "pendências" : "pendência"} ainda não ${plural ? "enviadas" : "enviada"} ` +
    `à nuvem — ${plural ? "serão enviadas" : "será enviada"} quando a internet voltar. ` +
    "Não limpe os dados do navegador até a sincronização terminar."
  );
}

/** Pergunta de confirmação do fechamento já com a contagem atual de pendências (falha de leitura = sem aviso). */
export async function closeCashConfirmMessage(base: string): Promise<string> {
  try {
    return buildCloseConfirmMessage(base, await getNotInCloudCount());
  } catch {
    return base;
  }
}
