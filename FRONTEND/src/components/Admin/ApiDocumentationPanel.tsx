import { useRef, useState } from "react";
import { Download, FileJson, LoaderCircle } from "lucide-react";
import { ApiError } from "@/services/api/apiClient";
import { superAdminService } from "@/services/api/superAdminService";

export default function ApiDocumentationPanel() {
  const running = useRef(false);
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState("");
  const [requested, setRequested] = useState(false);

  const download = async () => {
    if (running.current) return;
    running.current = true;
    setDownloading(true);
    setError("");
    setRequested(false);
    try {
      await superAdminService.downloadApiDocumentation();
      setRequested(true);
    } catch (failure) {
      setError(failure instanceof ApiError && failure.status === 404
        ? "Atualize a API do servidor para habilitar o download da documentação."
        : failure instanceof Error ? failure.message : "Não foi possível baixar a documentação. Tente novamente.");
    } finally {
      running.current = false;
      setDownloading(false);
    }
  };

  return (
    <section aria-labelledby="api-documentation-title" className="mb-6 rounded-xl border border-border-primary bg-bg-light p-4 sm:p-5">
      <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
        <div className="min-w-0 max-w-2xl">
          <h2 id="api-documentation-title" className="flex items-center gap-2 text-base font-semibold text-text-primary">
            <FileJson size={19} className="shrink-0 text-accent" />Documentação da API
          </h2>
          <p className="mt-1 text-sm text-text-secondary">Baixe um JSON com as rotas, parâmetros e estruturas de envio e resposta da versão atual da API, no formato OpenAPI.</p>
          <p className="mt-2 text-sm text-text-secondary">Gerado a cada download, sem dados das empresas ou credenciais.</p>
          <p role="status" aria-live="polite" className="mt-2 text-sm text-text-primary">
            {downloading ? "Preparando a documentação…" : requested ? "Download solicitado. Confira os downloads do aplicativo ou navegador." : ""}
          </p>
          {error && <p role="alert" className="mt-2 text-sm text-red-700 dark:text-red-300">{error}</p>}
        </div>
        <button type="button" disabled={downloading} onClick={() => void download()}
          className="inline-flex min-h-11 shrink-0 items-center justify-center gap-2 rounded-xl border border-border-primary px-4 py-2 text-sm font-semibold text-text-primary transition hover:bg-hover-light focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent disabled:cursor-wait disabled:opacity-60">
          {downloading ? <LoaderCircle size={17} className="animate-spin" /> : <Download size={17} />}
          {downloading ? "Preparando JSON…" : "Baixar documentação JSON"}
        </button>
      </div>
    </section>
  );
}
