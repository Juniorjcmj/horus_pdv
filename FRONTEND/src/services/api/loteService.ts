/**
 * Arquivo: src/services/api/loteService.ts
 * Objetivo: encapsula chamadas HTTP do controle de validade por lote (alertas, registro e prazos por categoria).
 * Entradas esperadas: recebe payloads já validados pelas telas e retorna respostas tipadas da API.
 */
import { apiRequest, requireEnvUrl } from "./apiClient";

const LOTES_API_URL = `${requireEnvUrl("VITE_PRODUTO_API_URL")}/lotes`;

/** "ok" = fora da janela de alerta; "esgotado" = sem saldo estimado. Só a consulta devolve esses dois. */
export type LoteFaixa = "vencido" | "critico" | "atencao" | "ok" | "esgotado";

export type LoteAlertaDto = {
  id: string;
  produtoId: string;
  numeroLote: string;
  /** AAAA-MM-DD */
  dataValidade: string;
  diasParaVencer: number;
  qtdInicial: number;
  /** Saldo estimado no consumo FEFO (a venda ainda não baixa lote). */
  qtdEstimada: number;
  origem: string;
  validadePadrao: boolean;
  criadoEm: string;
  productCode: string;
  productName: string;
  categoriaNome: string;
  categoriaId: string;
  categoriaPaiId: string;
  janelaAlertaDias: number;
  faixa: LoteFaixa;
  custoUnitario: number;
  precoVenda: number;
  valorEmRisco: number;
};

export type LoteAlertasResumoDto = {
  vencidos: number;
  criticos: number;
  atencao: number;
  valorEmRisco: number;
  produtosSemLote: number;
  itens: LoteAlertaDto[];
};

export type LoteConsultaFiltro = {
  busca?: string;
  /** AAAA-MM-DD, inclusive */
  de?: string;
  /** AAAA-MM-DD, inclusive */
  ate?: string;
  categoriaId?: string;
  faixa?: LoteFaixa;
  comSaldo?: boolean;
  origem?: string;
  pagina?: number;
  tamanhoPagina?: number;
};

export type LoteConsultaDto = {
  total: number;
  pagina: number;
  tamanhoPagina: number;
  valorEmRisco: number;
  itens: LoteAlertaDto[];
};

export type CategoriaValidadeDto = {
  categoriaId: string;
  nome: string;
  categoriaPaiId: string | null;
  prazoPadraoDias: number | null;
  diasAlerta: number | null;
};

export type RegistrarLotePayload = {
  produtoId: string;
  /** AAAA-MM-DD */
  dataValidade: string;
  quantidade: number;
  numeroLote?: string;
};

export const loteService = {
  async alertas() {
    const response = await apiRequest<LoteAlertasResumoDto>(`${LOTES_API_URL}/alertas`);
    return response.data;
  },
  /** Consulta qualquer lote cadastrado, com filtros no servidor e paginação. */
  async consulta(filtro: LoteConsultaFiltro) {
    const query = new URLSearchParams();
    if (filtro.busca?.trim()) query.set("busca", filtro.busca.trim());
    if (filtro.de) query.set("de", filtro.de);
    if (filtro.ate) query.set("ate", filtro.ate);
    if (filtro.categoriaId) query.set("categoriaId", filtro.categoriaId);
    if (filtro.faixa) query.set("faixa", filtro.faixa);
    if (filtro.comSaldo) query.set("comSaldo", "true");
    if (filtro.origem) query.set("origem", filtro.origem);
    query.set("pagina", String(filtro.pagina ?? 1));
    query.set("tamanhoPagina", String(filtro.tamanhoPagina ?? 25));
    const response = await apiRequest<LoteConsultaDto>(`${LOTES_API_URL}/consulta?${query.toString()}`);
    return response.data;
  },
  async registrar(payload: RegistrarLotePayload) {
    return apiRequest<object>(`${LOTES_API_URL}/registrar`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  async categorias() {
    const response = await apiRequest<CategoriaValidadeDto[]>(`${LOTES_API_URL}/categorias`);
    return response.data ?? [];
  },
  async salvarCategoria(categoriaId: string, payload: { prazoPadraoDias: number | null; diasAlerta: number | null }) {
    return apiRequest<object>(`${LOTES_API_URL}/categorias/${encodeURIComponent(categoriaId)}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
};
