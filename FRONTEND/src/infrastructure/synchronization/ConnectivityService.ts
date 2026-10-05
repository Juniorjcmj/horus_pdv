/**
 * Arquivo: src/infrastructure/synchronization/ConnectivityService.ts
 * Objetivo: detecta conectividade real (não apenas navigator.onLine) verificando
 *           o health da API periodicamente. Expõe estado reativo para o React.
 */
import type { ConnectionStatus } from "@/shared/types/sync";
import { requireEnvUrl } from "@/services/api/apiClient";

type Listener = (status: ConnectionStatus) => void;

const HEALTH_CHECK_INTERVAL_MS = 30_000;
/**
 * A API de produção ocasionalmente leva 6–7s para responder (medido). Com 3s e uma falha só, o PDV
 * mostrava "API indisponível" a cada poucos minutos mesmo com internet.
 */
const HEALTH_CHECK_TIMEOUT_MS = 10_000;
/** Falhas seguidas antes de declarar a API indisponível (uma resposta lenta isolada não conta). */
const FAILURES_BEFORE_UNAVAILABLE = 2;
/** Depois de uma falha, confere de novo mais cedo em vez de esperar o intervalo inteiro. */
const RECHECK_AFTER_FAILURE_MS = 5_000;
/** Resposta real da API há menos que isso também prova que ela está acessível. */
const RECENT_RESPONSE_GRACE_MS = 60_000;

/** Disparado pelo apiClient a cada resposta HTTP recebida da API (qualquer status). */
export const API_RESPONDED_EVENT = "horus-api-responded";

class ConnectivityService {
  private _status: ConnectionStatus = navigator.onLine ? "ONLINE" : "OFFLINE";
  private _listeners = new Set<Listener>();
  private _intervalId: ReturnType<typeof setInterval> | null = null;
  private _healthUrl: string;
  private _started = false;
  private _consecutiveFailures = 0;
  private _lastApiResponseAt = 0;
  private _recheckTimer: ReturnType<typeof setTimeout> | null = null;
  private _checking = false;

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
    window.addEventListener(API_RESPONDED_EVENT, this._handleApiResponded);

    // Health check periódico
    this._intervalId = setInterval(() => void this._checkHealth(), HEALTH_CHECK_INTERVAL_MS);

    // Verificar imediatamente
    void this._checkHealth();
  }

  stop(): void {
    this._started = false;
    window.removeEventListener("online", this._handleOnline);
    window.removeEventListener("offline", this._handleOffline);
    window.removeEventListener(API_RESPONDED_EVENT, this._handleApiResponded);
    if (this._intervalId) {
      clearInterval(this._intervalId);
      this._intervalId = null;
    }
    if (this._recheckTimer) {
      clearTimeout(this._recheckTimer);
      this._recheckTimer = null;
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
    this._consecutiveFailures = 0;
    this._setStatus("OFFLINE");
  };

  /** Qualquer resposta HTTP da API (venda, consulta…) prova que ela está acessível. */
  private _handleApiResponded = (): void => {
    this._lastApiResponseAt = Date.now();
    this._markReachable();
  };

  private _markReachable(): void {
    this._consecutiveFailures = 0;
    if (this._status !== "ONLINE" && this._status !== "SYNCING") this._setStatus("ONLINE");
  }

  private _markFailure(): void {
    if (!navigator.onLine) {
      this._consecutiveFailures = 0;
      this._setStatus("OFFLINE");
      return;
    }
    // Uma resposta real recente vale mais que um health check lento isolado.
    if (Date.now() - this._lastApiResponseAt < RECENT_RESPONSE_GRACE_MS) return;

    this._consecutiveFailures++;
    if (this._consecutiveFailures >= FAILURES_BEFORE_UNAVAILABLE) {
      this._setStatus("API_UNAVAILABLE");
    } else if (this._started && !this._recheckTimer) {
      // Confirma logo: se foi só lentidão, a próxima resposta zera a contagem sem alarme.
      this._recheckTimer = setTimeout(() => {
        this._recheckTimer = null;
        void this._checkHealth();
      }, RECHECK_AFTER_FAILURE_MS);
    }
  }

  private async _checkHealth(): Promise<void> {
    if (!navigator.onLine) {
      this._consecutiveFailures = 0;
      this._setStatus("OFFLINE");
      return;
    }
    if (this._checking) return;
    this._checking = true;

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), HEALTH_CHECK_TIMEOUT_MS);

    try {
      const response = await fetch(this._healthUrl, {
        method: "GET",
        credentials: "include",
        signal: controller.signal,
      });

      // 401 = API respondeu, mas usuário não autenticado — API está acessível
      if (response.ok || response.status === 401) this._markReachable();
      else this._markFailure();
    } catch {
      this._markFailure();
    } finally {
      clearTimeout(timeoutId);
      this._checking = false;
    }
  }
}

/** Singleton global */
export const connectivityService = new ConnectivityService();
