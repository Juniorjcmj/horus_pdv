/**
 * Arquivo: src/infrastructure/logging/systemLog.ts
 * Objetivo: log local persistente em IndexedDB para diagnóstico offline e erros do PDV.
 * Entradas circulares (máx 500) — sem dependência de rede.
 */

const DB_NAME = "HorusPdvSystemLog";
const STORE_NAME = "entries";
const MAX_ENTRIES = 500;

export type LogLevel = "info" | "warn" | "error";

export type LogEntry = {
  id: number;
  timestamp: string;
  level: LogLevel;
  source: string;
  message: string;
  detail?: string;
  online: boolean;
};

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        db.createObjectStore(STORE_NAME, { keyPath: "id", autoIncrement: true });
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function trimOldEntries(database: IDBDatabase): Promise<void> {
  return new Promise((resolve) => {
    const tx = database.transaction(STORE_NAME, "readwrite");
    const store = tx.objectStore(STORE_NAME);
    const countReq = store.count();
    countReq.onsuccess = () => {
      const excess = countReq.result - MAX_ENTRIES;
      if (excess <= 0) { resolve(); return; }
      const cursor = store.openCursor();
      let deleted = 0;
      cursor.onsuccess = () => {
        const c = cursor.result;
        if (c && deleted < excess) { c.delete(); deleted++; c.continue(); }
        else resolve();
      };
      cursor.onerror = () => resolve();
    };
    countReq.onerror = () => resolve();
  });
}

async function write(level: LogLevel, source: string, message: string, detail?: string): Promise<void> {
  try {
    const database = await openDb();
    const entry: Omit<LogEntry, "id"> = {
      timestamp: new Date().toISOString(),
      level,
      source,
      message: message.slice(0, 500),
      detail: detail?.slice(0, 2000),
      online: typeof navigator !== "undefined" ? navigator.onLine : true,
    };
    await new Promise<void>((resolve, reject) => {
      const tx = database.transaction(STORE_NAME, "readwrite");
      tx.objectStore(STORE_NAME).add(entry);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
    await trimOldEntries(database);
    database.close();
  } catch {
    // Log system must never crash the app.
  }
}

export const systemLog = {
  info: (source: string, message: string, detail?: string) => write("info", source, message, detail),
  warn: (source: string, message: string, detail?: string) => write("warn", source, message, detail),
  error: (source: string, message: string, detail?: string) => write("error", source, message, detail),

  async getAll(): Promise<LogEntry[]> {
    try {
      const database = await openDb();
      return await new Promise<LogEntry[]>((resolve, reject) => {
        const tx = database.transaction(STORE_NAME, "readonly");
        const req = tx.objectStore(STORE_NAME).getAll();
        req.onsuccess = () => { database.close(); resolve((req.result as LogEntry[]).reverse()); };
        req.onerror = () => { database.close(); reject(req.error); };
      });
    } catch { return []; }
  },

  async clear(): Promise<void> {
    try {
      const database = await openDb();
      await new Promise<void>((resolve, reject) => {
        const tx = database.transaction(STORE_NAME, "readwrite");
        tx.objectStore(STORE_NAME).clear();
        tx.oncomplete = () => resolve();
        tx.onerror = () => reject(tx.error);
      });
      database.close();
    } catch { /* */ }
  },
};
