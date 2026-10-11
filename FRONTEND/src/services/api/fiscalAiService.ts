import { ApiError, apiRequest, requireEnvUrl } from "./apiClient";

const base = `${requireEnvUrl("VITE_PRODUTO_API_URL")}/fiscal-ia`;
export type FiscalAiConfig = { configurada: boolean; modelo: string; usarJev: boolean };
export type FiscalAiSuggestion = { campo: string; atual: string | null; sugerido: string | null; justificativa: string; fontes: string[]; podeAplicar: boolean; bloqueio: string | null; probabilidadeJev: number | null };
export type FiscalAiReport = {
  id: string; produtoId: string; produtoNome: string; analisadoEm: string; crt: number; uf: string; modelo: string;
  resumo: string; pendencias: string[]; cadastroOriginal: Record<string, string | null>;
  fontes: { id: string; titulo: string; url: string; conteudo: string }[];
  sugestoes: FiscalAiSuggestion[]; situacaoJev: string; custoUsd: number | null;
};
export const fiscalAiService = {
  async config() { return (await apiRequest<FiscalAiConfig>(`${base}/config`)).data; },
  async saveConfig(chave: string, usarJev: boolean, removerChave = false) {
    return (await apiRequest<FiscalAiConfig>(`${base}/config`, { method: "PUT", body: JSON.stringify({ chave, usarJev, removerChave }) })).data;
  },
  async analyze(id: string) {
    let response;
    try {
      response = await apiRequest<FiscalAiReport>(`${base}/${encodeURIComponent(id)}/analisar`, { method: "POST", timeoutMs: 110000 });
    } catch (error) {
      // Não reenviar automaticamente: a primeira chamada pode ter sido processada e cobrada.
      if (error instanceof ApiError && error.status === 0 && error.data instanceof TypeError) {
        throw new ApiError("A conexão com o servidor da análise fiscal falhou. Verifique a internet e tente novamente. Se continuar, informe ao suporte em qual momento o erro aparece.", 0, error.data);
      }
      throw error;
    }
    if (!response.data) throw new Error("A análise não retornou dados. Tente novamente.");
    return response.data;
  },
  async apply(id: string, analiseId: string, campos: string[]) {
    await apiRequest(`${base}/${encodeURIComponent(id)}/aplicar`, { method: "POST", body: JSON.stringify({ analiseId, campos, revisado: true }), timeoutMs: 35000 });
  },
};
