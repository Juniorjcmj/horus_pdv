/**
 * Arquivo: src/services/api/apiClient.ts
 * Objetivo: centralizar chamadas HTTP para a API .NET do Hórus PDV.
  * Entradas esperadas: recebe caminho, método e payload opcional para executar requisições autenticadas.
*/
import { clearAuthSession } from "@/utils/authStorage";

export type ApiResponse<T> = {
  success: boolean;
  message: string;
  details?: string;
  data?: T;
};

export class ApiError extends Error {
  status: number;
  data?: unknown;
  constructor(message: string, status: number, data?: unknown) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.data = data;
  }
}

type ApiRequestOptions = RequestInit & {
  skipAuth?: boolean;
  /** Timeout em milissegundos (padrão: 5 000 ms). Operações longas como consulta SEFAZ devem usar valor maior. */
  timeoutMs?: number;
};

const API_TIMEOUT_MS = 5_000;

/**
 * Retorna o valor de uma variável de ambiente VITE_*.
 * Em produção, falha explicitamente se a variável não estiver configurada.
 */
export function requireEnvUrl(envVar: string): string {
  const value = import.meta.env[envVar] as string | undefined;
  if (!value) {
    throw new Error(`Variável de ambiente ${envVar} não configurada.`);
  }
  return value;
}

export async function apiRequest<T>(
  endpointUrl: string,
  options: ApiRequestOptions = {},
): Promise<ApiResponse<T>> {
  if (!navigator.onLine) {
    throw new ApiError("Sem conexão com a internet.", 0);
  }

  const { skipAuth: _skipAuth, headers, timeoutMs, ...requestOptions } = options;
  void _skipAuth;

  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), timeoutMs ?? API_TIMEOUT_MS);

  try {
    let response: Response;
    try {
      response = await fetch(endpointUrl, {
        credentials: "include",
        headers: {
          "Content-Type": "application/json",
          ...headers,
        },
        signal: controller.signal,
        ...requestOptions,
      });
    } catch (err) {
      if (err instanceof DOMException && err.name === "AbortError") {
        throw new ApiError("A requisição demorou demais e foi cancelada. Verifique sua conexão ou tente novamente.", 0);
      }
      // Falhas do fetch não têm resposta HTTP. O status 0 permite tratar conectividade sem
      // depender da mensagem (que varia entre Chromium, Electron e outros navegadores).
      throw new ApiError(err instanceof Error ? err.message : "Não foi possível conectar à API.", 0);
    }

    // Resposta real da API (não 5xx: 502/503 do proxy = API fora) prova conectividade para o
    // ConnectivityService, evitando "API indisponível" por um health check lento isolado.
    if (response.status < 500) {
      window.dispatchEvent(new CustomEvent("horus-api-responded"));
    }

    if (response.status === 401) {
      clearAuthSession();
    }

    const contentType = response.headers.get("content-type") || "";
    let payload: ApiResponse<T>;
    try {
      payload = contentType.includes("application/json")
        ? ((await response.json()) as ApiResponse<T>)
        : {
            success: response.ok,
            message: response.ok ? "Operação concluída." : "Erro ao comunicar com a API.",
          };
    } catch (error) {
      // Rede também pode cair depois dos cabeçalhos, enquanto o corpo ainda é recebido.
      // Uma recusa HTTP já recebida continua valendo; só uma resposta de sucesso interrompida
      // é tratada como falta de conexão. JSON inválido preserva o status recebido.
      const interrupted = error instanceof TypeError || (error instanceof DOMException && error.name === "AbortError");
      throw new ApiError("Erro ao ler a resposta da API.", response.ok && interrupted ? 0 : response.status, error);
    }

    if (!response.ok || !payload?.success) {
      throw new ApiError(payload?.message || "Erro ao comunicar com a API.", response.status, payload);
    }

    return payload;
  } finally {
    // O prazo cobre a leitura do corpo, além da conexão inicial.
    window.clearTimeout(timeoutId);
  }
}
