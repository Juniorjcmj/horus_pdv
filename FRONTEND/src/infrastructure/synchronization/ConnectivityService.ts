/**
 * Arquivo: src/infrastructure/synchronization/ConnectivityService.ts
 * Objetivo: detecta conectividade real (não apenas navigator.onLine) verificando
 *           o health da API periodicamente. Expõe estado reativo para o React.
 */
import type { ConnectionStatus } from "@/shared/types/sync";
import { requireEnvUrl } from "@/services/api/apiClient";

type Listener = (status: ConnectionStatus) => void;

const HEALTH_CHECK_INTERVAL_MS = 30_000;
const HEALTH_CHECK_TIMEOUT_MS = 3_000;

class ConnectivityService {
  private _status: ConnectionStatus = navigator.onLine ? "ONLINE" : "OFFLINE";
  private _listeners = new Set<Listener>();
  private _intervalId: ReturnType<typeof setInterval> | null = null;
  private _healthUrl: string;
  private _started = false;

  constructor() {
    this._healthUrl = `${requireEnvUrl("VITE_AUTH_API_URL")}/me`;
  }

  get status(): ConnectionStatus {
    return this._status;
  }

  isOnline(): boolean {
    return this._status === "ONLINE" || this._status === "SYNCING";
  }

  isApiReachable(): boolean {
    return this._status === "ONLINE" || this._status === "SYNCING";
  }

  /** Chamado pelo SyncCoordinator para indicar sincronização ativa. */
  setSyncing(active: boolean): void {
    if (active && this._status === "ONLINE") {
      this._setStatus("SYNCING");
    } else if (!active && this._status === "SYNCING") {
      this._setStatus("ONLINE");
    }
  }

  subscribe(listener: Listener): () => void {
    this._listeners.add(listener);
    return () => {
      this._listeners.delete(listener);
    };
  }

  start(): void {
    if (this._started) return;
    this._started = true;

    window.addEventListener("online", this._handleOnline);
    window.addEventListener("offline", this._handleOffline);

    // Health check periódico
    this._intervalId = setInterval(() => void this._checkHealth(), HEALTH_CHECK_INTERVAL_MS);

    // Verificar imediatamente
    void this._checkHealth();
  }

  stop(): void {
    this._started = false;
    window.removeEventListener("online", this._handleOnline);
    window.removeEventListener("offline", this._handleOffline);
    if (this._intervalId) {
      clearInterval(this._intervalId);
      this._intervalId = null;
    }
  }

  private _setStatus(next: ConnectionStatus): void {
    if (next === this._status) return;
    this._status = next;
    for (const listener of this._listeners) {
      try {
        listener(next);
      } catch {
        // listener não deve quebrar o serviço
      }
    }
  }

  private _handleOnline = (): void => {
    // Browser diz que está online — verificar se a API responde
    void this._checkHealth();
  };

  private _handleOffline = (): void => {
    this._setStatus("OFFLINE");
  };

  private async _checkHealth(): Promise<void> {
    if (!navigator.onLine) {
      this._setStatus("OFFLINE");
      return;
    }

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), HEALTH_CHECK_TIMEOUT_MS);

    try {
      const response = await fetch(this._healthUrl, {
        method: "GET",
        credentials: "include",
        signal: controller.signal,
      });
      clearTimeout(timeoutId);

      if (response.ok || response.status === 401) {
        // 401 = API respondeu, mas usuário não autenticado — API está acessível
        this._setStatus("ONLINE");
      } else {
        this._setStatus("API_UNAVAILABLE");
      }
    } catch {
      clearTimeout(timeoutId);
      if (!navigator.onLine) {
        this._setStatus("OFFLINE");
      } else {
        this._setStatus("API_UNAVAILABLE");
      }
    }
  }
}

/** Singleton global */
export const connectivityService = new ConnectivityService();
