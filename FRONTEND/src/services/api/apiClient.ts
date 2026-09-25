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

type ApiRequestOptions = RequestInit & {
  skipAuth?: boolean;
  /** Timeout em milissegundos (padrão: 5 000 ms). Operações longas como consulta SEFAZ devem usar valor maior. */
  timeoutMs?: number;
};

const API_TIMEOUT_MS = 5_000;

export async function apiRequest<T>(
  endpointUrl: string,
  options: ApiRequestOptions = {},
): Promise<ApiResponse<T>> {
  if (!navigator.onLine) {
    throw new Error("Sem conexão com a internet.");
  }

  const { skipAuth: _skipAuth, headers, timeoutMs, ...requestOptions } = options;
  void _skipAuth;

  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), timeoutMs ?? API_TIMEOUT_MS);

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
      throw new Error("A requisição demorou demais e foi cancelada. Verifique sua conexão ou tente novamente.");
    }
    throw err;
  } finally {
    window.clearTimeout(timeoutId);
  }

  const contentType = response.headers.get("content-type") || "";
  const payload = contentType.includes("application/json")
    ? ((await response.json()) as ApiResponse<T>)
    : ({
        success: response.ok,
        message: response.ok ? "Operação concluída." : "Erro ao comunicar com a API.",
      } as ApiResponse<T>);

  if (response.status === 401) {
    clearAuthSession();
  }

  if (!response.ok || !payload.success) {
    throw new Error(payload.message || "Erro ao comunicar com a API.");
  }

  return payload;
}
