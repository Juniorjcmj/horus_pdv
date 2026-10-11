import { ApiError, apiRequest, requireEnvUrl } from "./apiClient";
import { getStoredAuthUser } from "@/utils/authStorage";

const base = `${requireEnvUrl("VITE_PRODUTO_API_URL")}/fiscal-ia`;
export type FiscalAiConfig = { configurada: boolean; modelo: string; usarJev: boolean };
export type FiscalAiSuggestion = { campo: string; atual: string | null; sugerido: string | null; justificativa: string; fontes: string[]; podeAplicar: boolean; bloqueio: string | null; probabilidadeJev: number | null };
export type FiscalAiReport = {
  id: string; produtoId: string; produtoNome: string; analisadoEm: string; crt: number; uf: string; modelo: string;
  resumo: string; pendencias: string[]; cadastroOriginal: Record<string, string | null>;
  fontes: { id: string; titulo: string; url: string; conteudo: string }[];
  sugestoes: FiscalAiSuggestion[]; situacaoJev: string; custoUsd: number | null;
};
type FiscalAiJob = { id: string; produtoId: string; status: "aguardando" | "analisando" | "concluida" | "falha"; mensagem: string | null; relatorio: FiscalAiReport | null };
const pending = new Map<string, string>();
function requestKey(id: string) { return `fiscal-ai:solicitacao:${getStoredAuthUser()?.companyId ?? ""}:${id}`; }
function remember(key: string, id: string | null) {
  if (id) pending.set(key, id); else pending.delete(key);
  try { if (id) sessionStorage.setItem(key, id); else sessionStorage.removeItem(key); } catch { /* Perfil sem armazenamento: mantém em memória. */ }
}
function requestId(key: string) {
  let saved = pending.get(key);
  try { saved ??= sessionStorage.getItem(key) ?? undefined; } catch { /* Usa memória. */ }
  const id = saved && /^[a-f\d]{8}(-[a-f\d]{4}){3}-[a-f\d]{12}$/i.test(saved) ? saved : crypto.randomUUID();
  remember(key, id);
  return id;
}
export const fiscalAiService = {
  async config() { return (await apiRequest<FiscalAiConfig>(`${base}/config`)).data; },
  async saveConfig(chave: string, usarJev: boolean, removerChave = false) {
    return (await apiRequest<FiscalAiConfig>(`${base}/config`, { method: "PUT", body: JSON.stringify({ chave, usarJev, removerChave }) })).data;
  },
  async analyze(id: string, onProgress?: (message: string) => void) {
    const key = requestKey(id);
    try {
      const started = await apiRequest<{ id: string }>(`${base}/${encodeURIComponent(id)}/analises`, {
        method: "POST", body: JSON.stringify({ solicitacaoId: requestId(key) }), timeoutMs: 15000,
      });
      if (!started.data?.id) throw new Error("O servidor não retornou a identificação da análise.");
      remember(key, started.data.id);
      const deadline = Date.now() + 5 * 60 * 1000;
      while (Date.now() < deadline) {
        const response = await apiRequest<FiscalAiJob>(`${base}/analises/${encodeURIComponent(started.data.id)}`, { timeoutMs: 10000 });
        const job = response.data;
        if (!job || job.produtoId !== id) throw new Error("O servidor retornou uma análise inválida para este produto.");
        if (job.status === "concluida") {
          if (!job.relatorio || job.relatorio.produtoId !== id) throw new Error("A análise não retornou dados válidos para este produto.");
          remember(key, null);
          return job.relatorio;
        }
        if (job.status === "falha") { remember(key, null); throw new Error(job.mensagem || "Não foi possível concluir a análise fiscal."); }
        if (job.status !== "aguardando" && job.status !== "analisando") throw new Error("O servidor retornou uma situação de análise desconhecida.");
        onProgress?.(job.status === "aguardando" ? "Aguardando a vez da análise…" : "A IA está analisando o produto e as referências fiscais…");
        await new Promise(resolve => window.setTimeout(resolve, 1500));
      }
      throw new Error("A análise continua no servidor. Use Tentar análise novamente para consultar a mesma solicitação; ela não será repetida.");
    } catch (error) {
      // Não reenviar automaticamente: a primeira chamada pode ter sido processada e cobrada.
      if (error instanceof ApiError && error.status === 0 && error.data instanceof TypeError) {
        throw new ApiError("A conexão com a análise fiscal foi interrompida. Tente novamente para recuperar a mesma solicitação, sem repetir a análise.", 0, error.data);
      }
      if (error instanceof ApiError && error.status === 404) throw new ApiError("O servidor não disponibilizou a solicitação de análise. Confira se a API foi atualizada junto com o aplicativo.", 404, error.data);
      throw error;
    }
  },
  async apply(id: string, analiseId: string, campos: string[]) {
    await apiRequest(`${base}/${encodeURIComponent(id)}/aplicar`, { method: "POST", body: JSON.stringify({ analiseId, campos, revisado: true }), timeoutMs: 35000 });
  },
};
