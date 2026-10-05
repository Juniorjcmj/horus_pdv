/**
 * Arquivo: src/services/api/treinamentoService.ts
 * Objetivo: chamadas HTTP da página de aprendizado (seções e vídeos de treinamento).
 * Entradas esperadas: payloads já validados pela tela; o servidor decide quem pode editar.
 */
import { apiRequest, requireEnvUrl } from "./apiClient";

// A API de treinamento fica em api/treinamento; a base vem da URL de Home já configurada (…/api/Home).
const TREINAMENTO_API_URL = `${requireEnvUrl("VITE_HOME_API_URL").replace(/\/Home\/?$/i, "")}/treinamento`;

export type TreinamentoVideoDto = {
  id: string;
  secaoId: string;
  titulo: string;
  descricao: string;
  /** Passos em texto, um por linha. */
  instrucoes: string;
  youtubeId: string;
  ordem: number;
};

export type TreinamentoSecaoDto = {
  id: string;
  nome: string;
  descricao: string;
  ordem: number;
  videos: TreinamentoVideoDto[];
};

export type TreinamentoDto = {
  podeGerenciar: boolean;
  secoes: TreinamentoSecaoDto[];
};

export type SalvarSecaoPayload = { nome: string; descricao: string; ordem: number };

export type SalvarVideoPayload = {
  secaoId: string;
  titulo: string;
  descricao: string;
  instrucoes: string;
  /** Link do YouTube (ou o ID do vídeo). */
  url: string;
  ordem: number;
};

export const treinamentoService = {
  async listar() {
    const response = await apiRequest<TreinamentoDto>(TREINAMENTO_API_URL);
    return response.data ?? { podeGerenciar: false, secoes: [] };
  },
  salvarSecao(payload: SalvarSecaoPayload, id?: string) {
    return apiRequest<{ id: string }>(id ? `${TREINAMENTO_API_URL}/secoes/${id}` : `${TREINAMENTO_API_URL}/secoes`, {
      method: id ? "PUT" : "POST",
      body: JSON.stringify(payload),
    });
  },
  excluirSecao(id: string) {
    return apiRequest<object>(`${TREINAMENTO_API_URL}/secoes/${id}`, { method: "DELETE" });
  },
  salvarVideo(payload: SalvarVideoPayload, id?: string) {
    return apiRequest<{ id: string }>(id ? `${TREINAMENTO_API_URL}/videos/${id}` : `${TREINAMENTO_API_URL}/videos`, {
      method: id ? "PUT" : "POST",
      body: JSON.stringify(payload),
    });
  },
  excluirVideo(id: string) {
    return apiRequest<object>(`${TREINAMENTO_API_URL}/videos/${id}`, { method: "DELETE" });
  },
};
