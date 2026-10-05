/**
 * Arquivo: src/components/Admin/ImportPendingBackupButton.tsx
 * Objetivo: botão "Importar backup" — escolhe um arquivo de pendências (Documentos\Quack PDV\Backups),
 *           mostra o resumo, pede confirmação, recoloca os eventos na fila e dispara o envio.
 */
import { Upload } from "lucide-react";
import { type ChangeEvent, useRef, useState } from "react";
import { Toast, useStatusDialog } from "@/hooks/Dialog";
import { importPendingBackup, parsePendingBackup } from "@/infrastructure/desktop/pendingBackup";
import { syncEngine } from "@/infrastructure/synchronization/SyncEngine";

const EVENT_LABELS: Record<string, [string, string]> = {
  SALE_CREATED: ["venda", "vendas"],
  CASH_OPEN: ["abertura de caixa", "aberturas de caixa"],
  CASH_CLOSE: ["fechamento de caixa", "fechamentos de caixa"],
  CASH_MOVEMENT: ["sangria/reforço", "sangrias/reforços"],
};

function describe(byType: Record<string, number>): string {
  return Object.entries(byType)
    .map(([type, count]) => {
      const [one, many] = EVENT_LABELS[type] ?? [type, type];
      return `${count} ${count === 1 ? one : many}`;
    })
    .join(", ");
}

type ImportPendingBackupButtonProps = {
  className?: string;
  onImported?: () => void;
};

export default function ImportPendingBackupButton({ className, onImported }: ImportPendingBackupButtonProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const statusDialog = useStatusDialog();
  const [busy, setBusy] = useState(false);

  const handleFile = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = ""; // permite escolher o mesmo arquivo de novo
    if (!file) return;

    setBusy(true);
    try {
      const preview = parsePendingBackup(await file.text());
      if (preview.backup.count === 0 || preview.backup.events.length === 0) {
        Toast.info("Este backup não tem pendências: estava tudo enviado quando ele foi gravado.");
        return;
      }
      const createdAt = new Date(preview.backup.createdAt).toLocaleString("pt-BR");
      const confirmed = await statusDialog.confirm(
        `Importar backup de ${createdAt}? Ele tem ${describe(preview.byType)}. ` +
          "O que já estiver no servidor não será duplicado.",
        { confirmLabel: "Importar", cancelLabel: "Cancelar", confirmIntent: "success" },
      );
      if (!confirmed) return;

      const { imported, skipped } = await importPendingBackup(preview.backup);
      if (imported === 0) {
        Toast.info("Nada novo para importar: esses eventos já estão na fila deste caixa.");
      } else {
        Toast.success(
          `${imported} evento(s) colocado(s) na fila para envio` + (skipped > 0 ? ` (${skipped} já estavam na fila).` : "."),
        );
        void syncEngine.syncNow();
      }
      onImported?.();
    } catch (err) {
      Toast.error(err instanceof Error ? err.message : "Não foi possível importar o backup.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <input ref={inputRef} type="file" accept=".json,application/json" className="hidden" onChange={handleFile} />
      <button
        type="button"
        onClick={() => inputRef.current?.click()}
        disabled={busy}
        className={
          className ??
          "inline-flex shrink-0 items-center gap-2 whitespace-nowrap rounded-xl border border-border-secondary px-4 py-2 text-sm font-semibold text-text-secondary transition hover:bg-hover-light hover:text-text-primary disabled:opacity-50"
        }
      >
        <Upload size={15} />
        {busy ? "Importando..." : "Importar backup"}
      </button>
      {statusDialog.Dialog}
    </>
  );
}
