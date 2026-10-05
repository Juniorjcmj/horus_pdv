/**
 * Arquivo: src/infrastructure/database/persistentStorage.ts
 * Objetivo: pede ao navegador armazenamento PERSISTENTE para o IndexedDB do PDV.
 *           Sem isso o navegador pode apagar o banco local sozinho (disco cheio / limpeza automática)
 *           e levar junto vendas offline e fiado ainda não sincronizados.
 *           Chrome/Edge concedem sem perguntar para sites usados com frequência ou PWA instalado;
 *           Firefox pode exibir um aviso ao operador.
 */

export type PersistentStorageStatus = "granted" | "denied" | "unsupported";

let cachedStatus: PersistentStorageStatus | null = null;

/** Pede persistência (idempotente) e devolve o resultado. Nunca lança erro. */
export async function requestPersistentStorage(): Promise<PersistentStorageStatus> {
  if (cachedStatus) return cachedStatus;
  try {
    if (typeof navigator === "undefined" || !navigator.storage?.persist) {
      cachedStatus = "unsupported";
      return cachedStatus;
    }
    const already = await navigator.storage.persisted();
    const granted = already || (await navigator.storage.persist());
    cachedStatus = granted ? "granted" : "denied";
    if (!granted) {
      console.warn(
        "[PDV] O navegador NÃO concedeu armazenamento persistente: o IndexedDB pode ser apagado " +
          "automaticamente. Instale o PDV como aplicativo (PWA) para garantir.",
      );
    }
  } catch (err) {
    console.warn("[PDV] Falha ao pedir armazenamento persistente:", err);
    cachedStatus = "unsupported";
  }
  return cachedStatus;
}

/** Último resultado conhecido (null se ainda não foi pedido). */
export function getPersistentStorageStatus(): PersistentStorageStatus | null {
  return cachedStatus;
}
