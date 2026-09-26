/**
 * Arquivo: src/services/api/purchaseOrderService.ts
 * Objetivo: encapsula chamadas HTTP de ordens de compra e sugestões de reposição.
 */
import { apiRequest, requireEnvUrl } from "./apiClient";

const OC_API_URL = requireEnvUrl("VITE_ORDEM_COMPRA_API_URL");

export type PurchaseOrderDto = {
  id: string;
  orderNumber: string;
  supplierId: string | null;
  supplierName: string;
  supplierCnpj: string;
  status: number; // 0=pendente, 1=recebido, 2=cancelado, 3=parcial
  createdBy: string | null;
  createdByName: string;
  createdAt: string;
  receivedAt: string | null;
  canceledAt: string | null;
  previsaoEntrega?: string | null;
  condicaoPagamento?: string | null;
  formaPagamento?: string | null;
  valorFrete: number;
  valorDesconto: number;
  motivoCancelamento?: string | null;
  receivedBy?: string | null;
  receivedByName?: string | null;
  note: string;
  totalEstimado: number;
};

export type PurchaseOrderItemDto = {
  productCode: string;
  productName: string;
  quantity: number;
  unitCost: number;
  itemTotal: number;
  quantityReceived: number;
  dataValidade?: string | null;
};

export type PurchaseOrderDetailDto = PurchaseOrderDto & {
  itens: PurchaseOrderItemDto[];
};

export type ReplenishmentSuggestionDto = {
  productCode: string;
  productName: string;
  currentStock: number;
  minStock: number;
  maxStock: number;
  supplierId: string | null;
  supplierName: string;
  unitCost: number;
};

export const OC_STATUS = {
  Pendente: 0,
  Recebido: 1,
  Cancelado: 2,
  RecebidoParcial: 3,
} as const;

export type OcStatus = (typeof OC_STATUS)[keyof typeof OC_STATUS];

export function ocStatusLabel(status: OcStatus): string {
  switch (status) {
    case OC_STATUS.Pendente:
      return "Pendente";
    case OC_STATUS.Recebido:
      return "Recebido";
    case OC_STATUS.Cancelado:
      return "Cancelado";
    case OC_STATUS.RecebidoParcial:
      return "Parcial";
    default:
      return "—";
  }
}

export function ocStatusBadgeClass(status: OcStatus): string {
  switch (status) {
    case OC_STATUS.Recebido:
      return "border border-success/30 bg-success/15 text-success";
    case OC_STATUS.Cancelado:
      return "border border-border-secondary bg-bg-primary text-text-secondary";
    case OC_STATUS.RecebidoParcial:
      return "border border-accent/30 bg-accent/15 text-accent";
    default:
      return "border border-warning/30 bg-warning/15 text-warning";
  }
}

export const purchaseOrderService = {
  async list(status?: number) {
    const query = status !== undefined ? `?status=${status}` : "";
    const response = await apiRequest<PurchaseOrderDto[]>(`${OC_API_URL}${query}`);
    return response.data ?? [];
  },

  async getByNumber(orderNumber: string) {
    const response = await apiRequest<PurchaseOrderDetailDto>(
      `${OC_API_URL}/${encodeURIComponent(orderNumber)}`,
    );
    return response.data ?? null;
  },

  async create(payload: {
    supplierId: string;
    note?: string;
    previsaoEntrega?: string;
    condicaoPagamento?: string;
    formaPagamento?: string;
    valorFrete?: number;
    valorDesconto?: number;
    items: Array<{ productCode: string; quantity: number; unitCost: number }>;
  }) {
    const response = await apiRequest<{ orderNumber: string }>(`${OC_API_URL}`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
    return response;
  },

  async update(
    orderNumber: string,
    payload: {
      supplierId: string;
      note?: string;
      previsaoEntrega?: string;
      condicaoPagamento?: string;
      formaPagamento?: string;
      valorFrete?: number;
      valorDesconto?: number;
      items: Array<{ productCode: string; quantity: number; unitCost: number }>;
    },
  ) {
    const response = await apiRequest<PurchaseOrderDto>(
      `${OC_API_URL}/${encodeURIComponent(orderNumber)}`,
      {
        method: "PUT",
        body: JSON.stringify(payload),
      },
    );
    return response;
  },

  async receive(
    orderNumber: string,
    itens: Array<{
      productCode: string;
      quantityReceived: number;
      dataValidade?: string;
    }>,
  ) {
    const response = await apiRequest<{ status: number }>(
      `${OC_API_URL}/${encodeURIComponent(orderNumber)}/receber`,
      {
        method: "POST",
        body: JSON.stringify({ itens }),
      },
    );
    return response;
  },

  async cancel(orderNumber: string, motivoCancelamento?: string) {
    const response = await apiRequest<object>(
      `${OC_API_URL}/${encodeURIComponent(orderNumber)}/cancelar`,
      {
        method: "POST",
        body: JSON.stringify({ motivoCancelamento }),
      },
    );
    return response.message;
  },

  async replenishmentSuggestions() {
    const response = await apiRequest<ReplenishmentSuggestionDto[]>(
      `${OC_API_URL}/sugestoes-reposicao`,
    );
    return response.data ?? [];
  },
};
