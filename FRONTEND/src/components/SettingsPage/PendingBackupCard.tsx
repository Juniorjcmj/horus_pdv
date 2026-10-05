/**
 * Arquivo: src/components/SettingsPage/PendingBackupCard.tsx
 * Objetivo: cartão de Configurações para restaurar um backup de pendências (vendas/movimentos que não
 *           chegaram ao servidor). Fica sempre acessível — a janela da fila só abre quando há pendências,
 *           e quem restaura um backup normalmente está com a fila vazia (computador novo, perfil apagado).
 */
import { DatabaseBackup } from "lucide-react";
import ImportPendingBackupButton from "@/components/Admin/ImportPendingBackupButton";

export default function PendingBackupCard() {
  const isDesktop = typeof window !== "undefined" && Boolean(window.quackDesktop);
  return (
    <div className="rounded-xl border border-border-primary bg-bg-primary p-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between sm:gap-4">
        <div className="flex gap-3">
          <span className="mt-0.5 inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-accent/10 text-accent">
            <DatabaseBackup size={18} />
          </span>
          <div>
            <p className="text-base font-semibold text-text-primary">Backup das pendências</p>
            <p className="mt-1 text-sm text-text-secondary">
              {isDesktop
                ? "O programa grava automaticamente em Documentos\\Quack PDV\\Backups tudo o que ainda não foi enviado ao servidor."
                : "O backup automático é feito pelo programa Quack PDV (desktop)."}{" "}
              Para recuperar, importe o arquivo <span className="font-mono text-xs">pendencias-…json</span>: os
              itens voltam para a fila de envio, sem duplicar o que já chegou ao servidor.
            </p>
          </div>
        </div>
        <ImportPendingBackupButton />
      </div>
    </div>
  );
}
