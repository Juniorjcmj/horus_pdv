import { useEffect, useState } from "react";
import { DatabaseBackup, Download, LoaderCircle } from "lucide-react";
import { ApiError } from "@/services/api/apiClient";
import { superAdminService, type DatabaseBackupStatus } from "@/services/api/superAdminService";
import { getStoredAuthUser } from "@/utils/authStorage";

export default function DatabaseBackupPanel() {
  const storageKey = `horuspdv.database-backup.${getStoredAuthUser()?.id ?? ""}`;
  const [jobId, setJobId] = useState<string | null>(() => sessionStorage.getItem(storageKey));
  const [job, setJob] = useState<DatabaseBackupStatus | null>(null);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState("");
  const [downloadRequested, setDownloadRequested] = useState(false);
  const running = Boolean(jobId && (!job || job.status === "gerando" || job.status === "verificando"));

  useEffect(() => {
    if (!jobId) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        const state = await superAdminService.getDatabaseBackup(jobId);
        if (cancelled) return;
        setJob(state);
        setError(state.status === "falhou" ? state.message || "Não foi possível gerar o backup. Tente novamente." : "");
        if (state.status === "concluido" || state.status === "falhou") return;
      } catch (failure) {
        if (cancelled) return;
        setError(failure instanceof Error ? failure.message : "Não foi possível acompanhar o backup.");
        if (failure instanceof ApiError && [401, 403, 404].includes(failure.status)) {
          sessionStorage.removeItem(storageKey);
          setJobId(null);
          setJob(null);
          return;
        }
      }
      if (!cancelled) timer = setTimeout(() => void poll(), 3000);
    };
    void poll();
    return () => { cancelled = true; clearTimeout(timer); };
  }, [jobId, storageKey]);

  useEffect(() => {
    if (job?.status !== "concluido") return;
    const timer = setTimeout(() => {
      sessionStorage.removeItem(storageKey);
      setJobId(null);
      setJob(null);
      setDownloadRequested(false);
      setError("O backup expirou. Gere uma nova cópia para baixar.");
    }, Math.max(0, new Date(job.expiresAt).getTime() - Date.now()));
    return () => clearTimeout(timer);
  }, [job, storageKey]);

  const start = async () => {
    setStarting(true);
    setError("");
    setDownloadRequested(false);
    try {
      const state = await superAdminService.startDatabaseBackup();
      sessionStorage.setItem(storageKey, state.id);
      setJob(state);
      setJobId(state.id);
    } catch (failure) {
      setError(failure instanceof ApiError && failure.status === 404
        ? "O servidor precisa ser atualizado para habilitar o backup completo."
        : failure instanceof Error ? failure.message : "Não foi possível iniciar o backup.");
    } finally { setStarting(false); }
  };

  const ready = job?.status === "concluido";
  const buttonClass = "inline-flex min-h-11 items-center justify-center gap-2 rounded-xl bg-accent px-4 py-2 text-sm font-semibold text-white transition hover:brightness-110 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent disabled:cursor-wait disabled:opacity-60";

  return (
    <section aria-labelledby="database-backup-title" className="mb-6 rounded-xl border border-border-primary bg-bg-light p-4 sm:p-5">
      <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
        <div className="min-w-0 max-w-2xl">
          <h2 id="database-backup-title" className="flex items-center gap-2 text-base font-semibold text-text-primary">
            <DatabaseBackup size={19} className="shrink-0 text-accent" />
            Backup completo do banco de dados
          </h2>
          <p className="mt-1 text-sm text-text-secondary">
            Inclui todas as empresas, cadastros, estoque, vendas, caixas e documentos fiscais do banco central.
          </p>
          <p className="mt-2 text-sm text-text-secondary" role="status" aria-live="polite">
            {running || starting ? job?.status === "verificando" ? "Verificando a integridade do backup…" : "Gerando a cópia completa. Você pode continuar usando o sistema."
              : ready ? `Backup verificado (${((job.sizeBytes ?? 0) / 1024 / 1024).toLocaleString("pt-BR", { maximumFractionDigits: 1 })} MB). Disponível até ${new Date(job.expiresAt).toLocaleString("pt-BR")}.`
              : "A cópia será verificada antes de ficar disponível para download."}
          </p>
          {downloadRequested && <p className="mt-2 text-sm text-text-primary" role="status">Download solicitado. Confira os downloads do aplicativo ou navegador.</p>}
          {error && <p className="mt-2 text-sm text-red-700 dark:text-red-300" role="alert">{error}</p>}
        </div>
        <div className="flex shrink-0 flex-wrap items-center gap-2">
          {ready && <button type="button" className={buttonClass} onClick={() => {
            superAdminService.downloadDatabaseBackup(job.id);
            setDownloadRequested(true);
          }}><Download size={17} />Baixar backup</button>}
          <button type="button" disabled={starting || running} onClick={() => void start()}
            className={ready ? "inline-flex min-h-11 items-center justify-center rounded-xl border border-border-primary px-4 py-2 text-sm font-semibold text-text-primary transition hover:bg-hover-light focus-visible:outline-2 focus-visible:outline-accent" : buttonClass}>
            {(starting || running) && <LoaderCircle size={17} className="animate-spin" />}
            {starting || running ? "Preparando backup…" : ready ? "Gerar outro backup" : "Fazer backup completo"}
          </button>
        </div>
      </div>
    </section>
  );
}
