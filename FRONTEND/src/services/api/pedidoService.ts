/**
 * Arquivo: src/services/api/pedidoService.ts
 * Objetivo: encapsula chamadas HTTP de criação, consulta, finalização e cancelamento de pedidos.
 * Entradas esperadas: recebe payloads já validados pelas telas e retorna respostas tipadas da API.
 */
import { apiRequest } from "./apiClient";

const PEDIDO_API_URL = import.meta.env.VITE_PEDIDO_API_URL ?? "http://localhost:5260/api/Pedido";

export type PedidoItemDto = {
  productCode: string;
  productName: string;
  quantity: number;
  unitPrice: string;
  itemTotal: string;
};

export type PedidoStatus = "aberto" | "finalizado" | "cancelado";

export type PedidoDto = {
  orderNumber: string;
  customerName: string;
  customerCpf: string;
  sellerName: string;
  status: PedidoStatus;
  createdAt: string;
  totalAmount: string;
  itens: PedidoItemDto[];
};

export type CriarPedidoPayload = {
  customerName: string;
  customerCpf: string;
  items: Array<{ productCode: string; productName: string; quantity: number }>;
};

export const pedidoService = {
  async create(payload: CriarPedidoPayload) {
    const response = await apiRequest<PedidoDto>(PEDIDO_API_URL, {
      method: "POST",
      body: JSON.stringify(payload),
    });
    return response.data ?? null;
  },
  async listOpen() {
    const response = await apiRequest<PedidoDto[]>(PEDIDO_API_URL);
    return response.data ?? [];
  },
  async getByOrderNumber(orderNumber: string) {
    try {
      const response = await apiRequest<PedidoDto>(`${PEDIDO_API_URL}/${orderNumber}`);
      return response.data ?? null;
    } catch {
      return null;
    }
  },
  async finalize(orderNumber: string, paymentType: string) {
    const response = await apiRequest<{ saleNumber: string; fiscalQueued: boolean }>(
      `${PEDIDO_API_URL}/${orderNumber}/finalizar`,
      { method: "POST", body: JSON.stringify({ paymentType }) },
    );
    return response.data ?? null;
  },
  async cancel(orderNumber: string) {
    await apiRequest<object>(`${PEDIDO_API_URL}/${orderNumber}/cancelar`, { method: "POST" });
  },
};
