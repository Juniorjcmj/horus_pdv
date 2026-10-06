/**
 * Arquivo: src/infrastructure/desktop/pendingBackup.ts
 * Objetivo: backup automático das pendências de sincronização quando o PDV roda no programa desktop
 *           (Electron). Tudo o que ainda não chegou ao servidor — eventos do outbox (vendas, abertura,
 *           fechamento, sangria, reforço) com o payload completo — é gravado em arquivo JSON fora do
 *           perfil do programa (Documentos\Quack PDV\Backups). No navegador comum não faz nada.
 *           Também importa um backup de volta para a fila (funciona no programa e no navegador).
 */
import { db } from "@/infrastructure/database/dexie";
import { getCachedDeviceId } from "@/infrastructure/database/deviceId";
import { OUTBOX_CHANGED_EVENT } from "@/infrastructure/database/repositories/OutboxRepository";
import { syncEngine } from "@/infrastructure/synchronization/SyncEngine";
import type { OutboxEvent } from "@/shared/types/sync";

type QuackDesktopBridge = {
  savePendingBackup: (json: string) => Promise<string>;
};

declare global {
  interface Window {
    quackDesktop?: QuackDesktopBridge;
  }
}

const PERIODIC_MS = 5 * 60_000;
const DEBOUNCE_MS = 2_000;

export type PendingBackup = {
  format: "quack-pdv-pending-backup";
  version: 1;
  createdAt: string;
  deviceId: string;
  origin: string;
  count: number;
  events: unknown[];
};

/** Monta o backup com todos os eventos ainda não sincronizados (PENDING, PROCESSING ou FAILED). */
export async function buildPendingBackup(): Promise<PendingBackup> {
  // Inclui FORWARDED: já está no Gateway da loja, mas se aquela máquina falhar o backup ainda cobre.
  const events = await db.outbox.where("status").anyOf(["PENDING", "PROCESSING", "FAILED", "FORWARDED"]).sortBy("sequence");
  return {
    format: "quack-pdv-pending-backup",
    version: 1,
    createdAt: new Date().toISOString(),
    deviceId: getCachedDeviceId() || "unknown",
    origin: typeof window !== "undefined" ? window.location.origin : "",
    count: events.length,
    events: events.map((event) => ({
      ...event,
      // payload é guardado como string no outbox; no backup vai como objeto para ficar legível
      payload: (() => {
        try {
          return typeof event.payload === "string" ? JSON.parse(event.payload) : event.payload;
        } catch {
          return event.payload;
        }
      })(),
    })),
  };
}

// ---------------------------------------------------------------------------
// Importação (restaurar um backup na fila)
// ---------------------------------------------------------------------------

/** Tipos que o SyncEngine sabe enviar; qualquer outro no arquivo é recusado. */
const IMPORTABLE_EVENT_TYPES = ["SALE_CREATED", "CASH_OPEN", "CASH_CLOSE", "CASH_MOVEMENT"] as const;

export type ImportPreview = {
  backup: PendingBackup;
  byType: Record<string, number>;
};

export type ImportResult = { imported: number; skipped: number };

/** Lê e valida o conteúdo do arquivo de backup. Lança Error com mensagem para o operador. */
export function parsePendingBackup(text: string): ImportPreview {
  let data: unknown;
  try {
    data = JSON.parse(text);
  } catch {
    throw new Error("O arquivo não é um JSON válido.");
  }
  const backup = data as Partial<PendingBackup>;
  if (backup?.format !== "quack-pdv-pending-backup" || backup.version !== 1 || !Array.isArray(backup.events)) {
    throw new Error("Este arquivo não é um backup de pendências do Quack PDV.");
  }

  const byType: Record<string, number> = {};
  for (const raw of backup.events) {
    const event = raw as Partial<OutboxEvent> & { payload?: unknown };
    if (typeof event?.id !== "string" || !event.id) throw new Error("Backup corrompido: evento sem identificador.");
    if (!IMPORTABLE_EVENT_TYPES.includes(event.eventType as (typeof IMPORTABLE_EVENT_TYPES)[number])) {
      throw new Error(`Backup contém um tipo de evento desconhecido: ${String(event.eventType)}.`);
    }
    if (event.payload === undefined || event.payload === null) throw new Error("Backup corrompido: evento sem dados.");
    byType[event.eventType as string] = (byType[event.eventType as string] ?? 0) + 1;
  }
  return { backup: backup as PendingBackup, byType };
}

/**
 * Recoloca na fila os eventos do backup que ainda não estão neste aparelho (mesmo id = pula).
 * Mantém id/hash originais: o que já chegou ao servidor vira replay idempotente, não duplica.
 */
export async function importPendingBackup(backup: PendingBackup): Promise<ImportResult> {
  const result = await db.transaction("rw", db.outbox, async () => {
    let imported = 0;
    let skipped = 0;
    const last = await db.outbox.orderBy("sequence").last();
    let sequence = last ? last.sequence : 0;

    for (const raw of backup.events) {
      const event = raw as OutboxEvent & { payload: unknown };
      if (await db.outbox.get(event.id)) {
        skipped++;
        continue;
      }
      sequence++;
      await db.outbox.put({
        ...event,
        payload: typeof event.payload === "string" ? event.payload : JSON.stringify(event.payload),
        sequence,
        status: "PENDING",
        retryCount: 0,
        lastAttemptAt: null,
        lastError: null,
      });
      imported++;
    }
    return { imported, skipped };
  });
  if (result.imported > 0 && typeof window !== "undefined") {
    window.dispatchEvent(new CustomEvent(OUTBOX_CHANGED_EVENT));
  }
  return result;
}

let lastWritten = "";

/** Grava o backup se o conteúdo mudou desde o último (ignora createdAt na comparação). */
async function writeBackup(bridge: QuackDesktopBridge): Promise<void> {
  try {
    const backup = await buildPendingBackup();
    const signature = JSON.stringify(backup.events);
    if (signature === lastWritten) return;
    await bridge.savePendingBackup(JSON.stringify(backup));
    lastWritten = signature;
  } catch (err) {
    console.warn("[PDV] Falha no backup automático das pendências:", err);
  }
}

/** Inicia o backup automático (só no programa desktop). Retorna a função que para. */
export function startPendingBackup(): () => void {
  const bridge = typeof window !== "undefined" ? window.quackDesktop : undefined;
  if (!bridge?.savePendingBackup) return () => {};

  let timer: ReturnType<typeof setTimeout> | null = null;
  const schedule = () => {
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => void writeBackup(bridge), DEBOUNCE_MS);
  };

  // A cada mudança na fila: evento novo (venda/movimento offline) ou ciclo de envio (enviado, falha)
  // + rede de segurança periódica.
  const unsubscribe = syncEngine.subscribe(() => schedule());
  window.addEventListener(OUTBOX_CHANGED_EVENT, schedule);
  const interval = setInterval(() => void writeBackup(bridge), PERIODIC_MS);
  schedule();

  return () => {
    unsubscribe();
    window.removeEventListener(OUTBOX_CHANGED_EVENT, schedule);
    clearInterval(interval);
    if (timer) clearTimeout(timer);
  };
}
