/**
 * Arquivo: src/components/SettingsPage/SystemLogCard.tsx
 * Objetivo: exibir log do sistema persistido em IndexedDB para diagnóstico.
 */
import { useCallback, useEffect, useState } from "react";
import { systemLog, type LogEntry } from "@/infrastructure/logging/systemLog";
import { ScrollText, Trash2, RefreshCw } from "lucide-react";

const LEVEL_COLORS: Record<string, string> = {
  error: "text-red-600 dark:text-red-400",
  warn: "text-amber-600 dark:text-amber-400",
  info: "text-sky-600 dark:text-sky-400",
};

const LEVEL_BG: Record<string, string> = {
  error: "bg-red-50 dark:bg-red-950/30",
  warn: "bg-amber-50 dark:bg-amber-950/30",
  info: "",
};

export default function SystemLogCard() {
  const [entries, setEntries] = useState<LogEntry[]>([]);
  const [filter, setFilter] = useState<"all" | "error" | "warn">("all");
  const [expanded, setExpanded] = useState<Set<number>>(new Set());

  const refresh = useCallback(async () => {
    const all = await systemLog.getAll();
    setEntries(all);
  }, []);

  useEffect(() => { void refresh(); }, [refresh]);

  const handleClear = async () => {
    await systemLog.clear();
    setEntries([]);
  };

  const toggle = (id: number) => setExpanded(prev => {
    const next = new Set(prev);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });

  const filtered = filter === "all" ? entries : entries.filter(e => e.level === filter);

  const formatTime = (iso: string) => {
    try { return new Date(iso).toLocaleString("pt-BR", { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit" }); }
    catch { return iso; }
  };

  return (
    <div className="rounded-xl border border-border-primary bg-bg-light p-4 shadow-sm">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <ScrollText size={18} className="text-text-secondary" />
          <h3 className="text-sm font-semibold text-text-primary">Log do Sistema</h3>
          <span className="text-xs text-text-secondary">({filtered.length})</span>
        </div>
        <div className="flex items-center gap-2">
          <select
            value={filter}
            onChange={e => setFilter(e.target.value as "all" | "error" | "warn")}
            className="rounded border border-border-primary bg-bg-primary px-2 py-1 text-xs text-text-primary"
          >
            <option value="all">Todos</option>
            <option value="error">Erros</option>
            <option value="warn">Avisos</option>
          </select>
          <button type="button" onClick={refresh} className="p-1 rounded hover:bg-bg-primary" title="Atualizar">
            <RefreshCw size={14} className="text-text-secondary" />
          </button>
          <button type="button" onClick={handleClear} className="p-1 rounded hover:bg-red-50 dark:hover:bg-red-950/30" title="Limpar log">
            <Trash2 size={14} className="text-red-500" />
          </button>
        </div>
      </div>

      {filtered.length === 0 ? (
        <p className="text-xs text-text-secondary py-4 text-center">Nenhum registro.</p>
      ) : (
        <div className="max-h-80 overflow-y-auto space-y-0.5 text-xs font-mono">
          {filtered.slice(0, 100).map(entry => (
            <div key={entry.id} className={`rounded px-2 py-1 ${LEVEL_BG[entry.level] ?? ""}`}>
              <div
                className="flex items-start gap-2 cursor-pointer select-none"
                onClick={() => entry.detail && toggle(entry.id)}
              >
                <span className="text-text-secondary whitespace-nowrap">{formatTime(entry.timestamp)}</span>
                <span className={`font-bold uppercase w-10 shrink-0 ${LEVEL_COLORS[entry.level] ?? ""}`}>{entry.level}</span>
                <span className="text-text-secondary shrink-0">[{entry.source}]</span>
                <span className="text-text-primary break-all">{entry.message}</span>
                {!entry.online && <span className="ml-auto text-amber-500 shrink-0" title="Offline">OFFLINE</span>}
              </div>
              {entry.detail && expanded.has(entry.id) && (
                <pre className="mt-1 ml-12 text-[10px] text-text-secondary whitespace-pre-wrap break-all max-h-24 overflow-auto">{entry.detail}</pre>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
