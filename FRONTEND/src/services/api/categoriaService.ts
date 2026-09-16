/**
 * Arquivo: src/services/api/categoriaService.ts
 * Objetivo: encapsula chamadas HTTP de categorias e departamentos de produtos.
 */
import { apiRequest } from "./apiClient";

const CATEGORIA_API_URL =
  import.meta.env.VITE_CATEGORIA_API_URL ?? "http://localhost:5260/api/Categoria";

export type Categoria = {
  id: string;
  nome: string;
  categoriaPaiId: string | null;
  categoriaPaiNome?: string | null;
  ordem: number;
  ativa: boolean;
  quantidadeProdutos: number;
};

export type CategoriaArvore = Categoria & {
  subcategorias: CategoriaArvore[];
};

export type CategoriaPayload = {
  id?: string;
  nome: string;
  categoriaPaiId?: string | null;
  ordem?: number;
  ativa?: boolean;
};

export const categoriaService = {
  async list(plana = false, apenasAtivas = false) {
    const params = new URLSearchParams();
    if (plana) params.append("plana", "true");
    if (apenasAtivas) params.append("apenasAtivas", "true");
    const qs = params.toString() ? `?${params.toString()}` : "";
    const response = await apiRequest<CategoriaArvore[]>(`${CATEGORIA_API_URL}${qs}`);
    return response.data ?? [];
  },

  async listTodas(apenasAtivas = false) {
    const params = new URLSearchParams({ plana: "true" });
    if (apenasAtivas) params.append("apenasAtivas", "true");
    const response = await apiRequest<Categoria[]>(`${CATEGORIA_API_URL}?${params.toString()}`);
    return response.data ?? [];
  },

  async getById(id: string) {
    const response = await apiRequest<Categoria>(`${CATEGORIA_API_URL}/${id}`);
    return response.data;
  },

  async create(payload: CategoriaPayload) {
    const response = await apiRequest<Categoria>(CATEGORIA_API_URL, {
      method: "POST",
      body: JSON.stringify(payload),
    });
    return response.data;
  },

  async update(id: string, payload: CategoriaPayload) {
    const response = await apiRequest<Categoria>(`${CATEGORIA_API_URL}/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
    return response.data;
  },

  async toggleStatus(id: string, ativa: boolean) {
    const response = await apiRequest<{ success: boolean }>(`${CATEGORIA_API_URL}/${id}/status`, {
      method: "PATCH",
      body: JSON.stringify({ ativa }),
    });
    return response.success;
  },

  async remove(id: string) {
    const response = await apiRequest<{ success: boolean }>(`${CATEGORIA_API_URL}/${id}`, {
      method: "DELETE",
    });
    return response.success;
  },
};
