/**
 * Arquivo: src/components/ErrorBoundary.tsx
 * Objetivo: captura erros de renderizacao do React (ex: falha ao carregar chunk lazy)
 *           e exibe fallback amigavel sem destruir dados locais (IndexedDB, localStorage, outbox).
 */
import { Component, type ErrorInfo, type ReactNode } from "react";
import { RefreshCw, AlertTriangle } from "lucide-react";

interface Props {
  children: ReactNode;
}

interface State {
  hasError: boolean;
  error: Error | null;
}

export default class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error("[ErrorBoundary] Erro capturado:", error, info.componentStack);
  }

  private handleReload = () => {
    window.location.reload();
  };

  render() {
    if (!this.state.hasError) {
      return this.props.children;
    }

    const isChunkError =
      this.state.error?.message?.includes("Failed to fetch dynamically imported module") ||
      this.state.error?.message?.includes("Loading chunk") ||
      this.state.error?.message?.includes("Loading CSS chunk") ||
      this.state.error?.name === "ChunkLoadError";

    return (
      <div
        data-testid="error-boundary-fallback"
        className="flex min-h-screen items-center justify-center bg-bg-primary p-6"
      >
        <div className="w-full max-w-md rounded-xl border border-border-primary bg-bg-light p-8 shadow-lg text-center">
          <AlertTriangle size={48} className="mx-auto mb-4 text-amber-500" />

          <h1 className="text-xl font-semibold text-text-primary mb-2">
            Ocorreu um erro inesperado
          </h1>

          <p className="text-sm text-text-secondary mb-6">
            {isChunkError
              ? "Uma atualizacao do sistema foi detectada. Recarregue a pagina para continuar."
              : "Algo deu errado, mas seus dados estao seguros. Tente recarregar a pagina."}
          </p>

          <button
            type="button"
            data-testid="error-boundary-reload"
            onClick={this.handleReload}
            className="inline-flex items-center gap-2 rounded-lg bg-accent px-5 py-2.5 text-sm font-medium text-white shadow transition hover:bg-accent/90 focus:outline-none focus:ring-2 focus:ring-accent"
          >
            <RefreshCw size={16} />
            Tentar novamente
          </button>

          {import.meta.env.DEV && this.state.error && (
            <pre className="mt-6 max-h-40 overflow-auto rounded-lg bg-red-50 p-3 text-left text-xs text-red-800 dark:bg-red-950 dark:text-red-200">
              {this.state.error.message}
            </pre>
          )}
        </div>
      </div>
    );
  }
}
