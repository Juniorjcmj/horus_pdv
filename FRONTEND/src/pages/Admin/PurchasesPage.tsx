/**
 * Arquivo: src/pages/Admin/PurchasesPage.tsx
 * Objetivo: gestão de ordens de compra — criação, edição de ordens pendentes, listagem,
 *           recebimento com entrada de estoque e validade automática, espelho de impressão/PDF,
 *           cancelamento com justificativa, exportação CSV e sugestões de reposição.
 */
import {
  AlertCircle,
  AlertTriangle,
  Check,
  ClipboardList,
  Download,
  Edit3,
  FileText,
  Loader2,
  Package,
  Plus,
  Printer,
  RefreshCw,
  Search,
  ShoppingCart,
  Trash2,
  Truck,
  X,
} from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";
import PageHeader from "@/components/Admin/PageHeader";
import PurchaseOrderPrintModal from "@/components/Admin/PurchaseOrderPrintModal";
import RowActionsMenu from "@/components/Admin/RowActionsMenu";
import { SearchableSelectField } from "@/components/Form";
import TablePagination from "@/components/Pagination/TablePagination";
import { Toast } from "@/hooks/Dialog";
import PageLayout from "@/layout/PageLayout";
import { productService, type ProductDto } from "@/services/api/productService";
import {
  OC_STATUS,
  ocStatusBadgeClass,
  ocStatusLabel,
  purchaseOrderService,
  type OcStatus,
  type PurchaseOrderDetailDto,
  type PurchaseOrderDto,
  type PurchaseOrderItemDto,
  type ReplenishmentSuggestionDto,
} from "@/services/api/purchaseOrderService";
import { supplierService, type SupplierDto } from "@/services/api/supplierService";

// ---------------------------------------------------------------------------
// Types & Helpers
// ---------------------------------------------------------------------------

type PurchasesTab = "ordens" | "nova" | "sugestoes";
type StatusFilter = "todos" | "pendente" | "recebido" | "cancelado" | "parcial";

type CartItem = {
  productCode: string;
  productName: string;
  quantity: number;
  unitCost: number;
};

type ReceiveItemState = PurchaseOrderItemDto & {
  qtyToReceive: string;
  dataValidade: string;
  controlaValidade: boolean;
};

function formatDate(value: string | null | undefined) {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleDateString("pt-BR");
}

function formatDateTime(value: string | null | undefined) {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("pt-BR", {
    timeZone: "America/Sao_Paulo",
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function formatMoney(value: number) {
  return value.toLocaleString("pt-BR", { style: "currency", currency: "BRL" });
}

function parseBrNumber(value: string | null | undefined): number {
  if (!value) return 0;
  const normalized = value.replace(/\./g, "").replace(",", ".");
  const n = Number(normalized);
  return Number.isFinite(n) ? n : 0;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function PurchasesPage() {
  const [activeTab, setActiveTab] = useState<PurchasesTab>("ordens");

  // --- Orders list ---
  const [orders, setOrders] = useState<PurchaseOrderDto[]>([]);
  const [ordersLoading, setOrdersLoading] = useState(true);
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("todos");
  const [ordersSearch, setOrdersSearch] = useState("");
  const [ordersPage, setOrdersPage] = useState(1);
  const [ordersPerPage, setOrdersPerPage] = useState(10);

  // --- New / Edit order form ---
  const [suppliers, setSuppliers] = useState<SupplierDto[]>([]);
  const [products, setProducts] = useState<ProductDto[]>([]);
  const [editingOrderNumber, setEditingOrderNumber] = useState<string | null>(null);
  const [selectedSupplierId, setSelectedSupplierId] = useState("");
  const [cart, setCart] = useState<CartItem[]>([]);
  const [productSearch, setProductSearch] = useState("");
  const [showProductOptions, setShowProductOptions] = useState(false);
  const [qtyInput, setQtyInput] = useState("1");
  const [costInput, setCostInput] = useState("");
  const [selectedProductCode, setSelectedProductCode] = useState("");
  const [orderNote, setOrderNote] = useState("");
  const [previsaoEntrega, setPrevisaoEntrega] = useState("");
  const [condicaoPagamento, setCondicaoPagamento] = useState("");
  const [formaPagamento, setFormaPagamento] = useState("");
  const [freteInput, setFreteInput] = useState("");
  const [descontoInput, setDescontoInput] = useState("");
  const [submitting, setSubmitting] = useState(false);

  // --- Replenishment suggestions ---
  const [suggestions, setSuggestions] = useState<ReplenishmentSuggestionDto[]>([]);
  const [suggestionsLoading, setSuggestionsLoading] = useState(false);

  // --- Receive modal ---
  const [receiveOrder, setReceiveOrder] = useState<PurchaseOrderDto | null>(null);
  const [receiveItems, setReceiveItems] = useState<ReceiveItemState[]>([]);
  const [receiving, setReceiving] = useState(false);

  // --- Detail modal ---
  const [detailOrder, setDetailOrder] = useState<PurchaseOrderDetailDto | null>(null);

  // --- Print modal ---
  const [printOrder, setPrintOrder] = useState<PurchaseOrderDetailDto | null>(null);

  // --- Cancel modal ---
  const [cancelOrderTarget, setCancelOrderTarget] = useState<PurchaseOrderDto | null>(null);
  const [cancelMotivo, setCancelMotivo] = useState("");
  const [canceling, setCanceling] = useState(false);

  // ---------------------------------------------------------------------------
  // Data loading
  // ---------------------------------------------------------------------------

  const loadOrders = useCallback(async () => {
    setOrdersLoading(true);
    try {
      const data = await purchaseOrderService.list();
      setOrders(data);
    } catch {
      Toast.error("Erro ao carregar ordens de compra.");
    } finally {
      setOrdersLoading(false);
    }
  }, []);

  const loadSuppliers = useCallback(async () => {
    try {
      const data = await supplierService.list();
      setSuppliers(data);
    } catch {
      /* silent */
    }
  }, []);

  const loadProducts = useCallback(async () => {
    try {
      const data = await productService.list();
      setProducts(data);
    } catch {
      /* silent */
    }
  }, []);

  const loadSuggestions = useCallback(async () => {
    setSuggestionsLoading(true);
    try {
      const data = await purchaseOrderService.replenishmentSuggestions();
      setSuggestions(data);
    } catch {
      Toast.error("Erro ao carregar sugestões.");
    } finally {
      setSuggestionsLoading(false);
    }
  }, []);

  useEffect(() => {
    loadOrders();
    loadSuppliers();
    loadProducts();
  }, [loadOrders, loadSuppliers, loadProducts]);

  useEffect(() => {
    if (activeTab === "sugestoes") loadSuggestions();
  }, [activeTab, loadSuggestions]);

  // ---------------------------------------------------------------------------
  // Filtered orders
  // ---------------------------------------------------------------------------

  const filteredOrders = useMemo(() => {
    let list = orders;
    if (statusFilter !== "todos") {
      const statusMap: Record<StatusFilter, number | undefined> = {
        todos: undefined,
        pendente: OC_STATUS.Pendente,
        recebido: OC_STATUS.Recebido,
        cancelado: OC_STATUS.Cancelado,
        parcial: OC_STATUS.RecebidoParcial,
      };
      const s = statusMap[statusFilter];
      if (s !== undefined) list = list.filter((o) => o.status === s);
    }
    if (ordersSearch.trim()) {
      const q = ordersSearch.trim().toLowerCase();
      list = list.filter(
        (o) =>
          o.orderNumber.toLowerCase().includes(q) ||
          o.supplierName.toLowerCase().includes(q) ||
          (o.supplierCnpj ?? "").includes(q),
      );
    }
    return list;
  }, [orders, statusFilter, ordersSearch]);

  const totalOrdersPages = Math.max(1, Math.ceil(filteredOrders.length / ordersPerPage));
  const safeOrdersPage = Math.min(ordersPage, totalOrdersPages);
  const paginatedOrders = filteredOrders.slice(
    (safeOrdersPage - 1) * ordersPerPage,
    safeOrdersPage * ordersPerPage,
  );

  useEffect(() => {
    setOrdersPage(1);
  }, [ordersSearch, statusFilter]);

  // ---------------------------------------------------------------------------
  // Export CSV
  // ---------------------------------------------------------------------------

  const handleExportCsv = () => {
    if (filteredOrders.length === 0) {
      Toast.error("Nenhuma ordem para exportar.");
      return;
    }

    const header = [
      "Número",
      "Fornecedor",
      "CNPJ Fornecedor",
      "Status",
      "Total Estimado (R$)",
      "Frete (R$)",
      "Desconto (R$)",
      "Previsão Entrega",
      "Condição Pagamento",
      "Criado Por",
      "Data Emissão",
    ];

    const rows = filteredOrders.map((o) => [
      `#${o.orderNumber}`,
      `"${(o.supplierName || "").replace(/"/g, '""')}"`,
      `"${o.supplierCnpj || ""}"`,
      ocStatusLabel(o.status as OcStatus),
      (o.totalEstimado ?? 0).toFixed(2),
      (o.valorFrete ?? 0).toFixed(2),
      (o.valorDesconto ?? 0).toFixed(2),
      formatDate(o.previsaoEntrega),
      `"${(o.condicaoPagamento || "").replace(/"/g, '""')}"`,
      `"${(o.createdByName || "").replace(/"/g, '""')}"`,
      formatDate(o.createdAt),
    ]);

    const csvContent =
      "\uFEFF" + [header.join(";"), ...rows.map((r) => r.join(";"))].join("\r\n");
    const blob = new Blob([csvContent], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.setAttribute("download", `ordens_de_compra_${new Date().toISOString().slice(0, 10)}.csv`);
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
    Toast.success("Exportação concluída com sucesso!");
  };

  // ---------------------------------------------------------------------------
  // New / Edit order helpers
  // ---------------------------------------------------------------------------


  const filteredProducts = useMemo(() => {
    if (!productSearch.trim()) return [];
    const q = productSearch.trim().toLowerCase();
    let list = products;
    if (selectedSupplierId) {
      const selectedSup = suppliers.find((s) => s.id === selectedSupplierId);
      const supName = (selectedSup?.fantasyName || selectedSup?.companyName || "").toLowerCase();
      if (supName) {
        list = list.filter(
          (p) => !p.productSupplier || p.productSupplier.toLowerCase() === supName,
        );
      }
    }
    return list
      .filter(
        (p) =>
          p.productName.toLowerCase().includes(q) ||
          p.productCode.toLowerCase().includes(q) ||
          (p.gtin ?? "").includes(q),
      )
      .slice(0, 10);
  }, [products, productSearch, selectedSupplierId, suppliers]);

  function handleSelectProduct(p: ProductDto) {
    setSelectedProductCode(p.productCode);
    setProductSearch(p.productName);
    setCostInput(String(parseBrNumber(p.productUnitPrice) || ""));
    setShowProductOptions(false);
  }

  function handleAddToCart() {
    if (!selectedProductCode) {
      Toast.error("Selecione um produto.");
      return;
    }
    const qty = Number(qtyInput);
    const cost = Number(costInput);
    if (!qty || qty <= 0) {
      Toast.error("Quantidade deve ser maior que zero.");
      return;
    }
    if (cost < 0) {
      Toast.error("Custo unitário inválido.");
      return;
    }

    const prod = products.find((p) => p.productCode === selectedProductCode);
    setCart((prev) => {
      const existing = prev.findIndex((c) => c.productCode === selectedProductCode);
      if (existing >= 0) {
        const updated = [...prev];
        updated[existing] = {
          ...updated[existing],
          quantity: updated[existing].quantity + qty,
          unitCost: cost,
        };
        return updated;
      }
      return [
        ...prev,
        {
          productCode: selectedProductCode,
          productName: prod?.productName ?? selectedProductCode,
          quantity: qty,
          unitCost: cost,
        },
      ];
    });

    setSelectedProductCode("");
    setProductSearch("");
    setQtyInput("1");
    setCostInput("");
  }

  function handleUpdateCartItem(code: string, field: "quantity" | "unitCost", value: number) {
    setCart((prev) =>
      prev.map((c) => (c.productCode === code ? { ...c, [field]: Math.max(0, value) } : c)),
    );
  }

  function handleRemoveFromCart(code: string) {
    setCart((prev) => prev.filter((c) => c.productCode !== code));
  }

  const subtotalCart = cart.reduce((sum, c) => sum + c.quantity * c.unitCost, 0);
  const numFrete = Number(freteInput) || 0;
  const numDesconto = Number(descontoInput) || 0;
  const cartTotal = Math.max(0, subtotalCart + numFrete - numDesconto);

  function resetForm() {
    setEditingOrderNumber(null);
    setSelectedSupplierId("");
    setCart([]);
    setOrderNote("");
    setPrevisaoEntrega("");
    setCondicaoPagamento("");
    setFormaPagamento("");
    setFreteInput("");
    setDescontoInput("");
    setSelectedProductCode("");
    setProductSearch("");
  }

  async function handleStartEdit(order: PurchaseOrderDto) {
    try {
      const detail = await purchaseOrderService.getByNumber(order.orderNumber);
      if (!detail) {
        Toast.error("Erro ao carregar detalhes da ordem para edição.");
        return;
      }
      setEditingOrderNumber(order.orderNumber);
      setSelectedSupplierId(detail.supplierId || "");
      setCart(
        detail.itens.map((i) => ({
          productCode: i.productCode,
          productName: i.productName,
          quantity: i.quantity,
          unitCost: i.unitCost,
        })),
      );
      setOrderNote(detail.note || "");
      setPrevisaoEntrega(detail.previsaoEntrega ? detail.previsaoEntrega.substring(0, 10) : "");
      setCondicaoPagamento(detail.condicaoPagamento || "");
      setFormaPagamento(detail.formaPagamento || "");
      setFreteInput(detail.valorFrete > 0 ? String(detail.valorFrete) : "");
      setDescontoInput(detail.valorDesconto > 0 ? String(detail.valorDesconto) : "");
      setActiveTab("nova");
    } catch {
      Toast.error("Não foi possível abrir a edição da ordem.");
    }
  }

  async function handleSaveOrder() {
    if (!selectedSupplierId) {
      Toast.error("Selecione um fornecedor.");
      return;
    }
    if (cart.length === 0) {
      Toast.error("Adicione ao menos um item ao pedido.");
      return;
    }
    setSubmitting(true);
    try {
      const payload = {
        supplierId: selectedSupplierId,
        note: orderNote || undefined,
        previsaoEntrega: previsaoEntrega ? new Date(previsaoEntrega).toISOString() : undefined,
        condicaoPagamento: condicaoPagamento || undefined,
        formaPagamento: formaPagamento || undefined,
        valorFrete: numFrete,
        valorDesconto: numDesconto,
        items: cart.map((c) => ({
          productCode: c.productCode,
          quantity: c.quantity,
          unitCost: c.unitCost,
        })),
      };

      if (editingOrderNumber) {
        const response = await purchaseOrderService.update(editingOrderNumber, payload);
        if (response.success) {
          Toast.success("Ordem de compra atualizada com sucesso!");
          resetForm();
          setActiveTab("ordens");
          loadOrders();
        } else {
          Toast.error(response.message || "Erro ao atualizar ordem.");
        }
      } else {
        const response = await purchaseOrderService.create(payload);
        if (response.success) {
          Toast.success(response.message || "Ordem de compra criada com sucesso!");
          resetForm();
          setActiveTab("ordens");
          loadOrders();
        } else {
          Toast.error(response.message || "Erro ao criar ordem.");
        }
      }
    } catch {
      Toast.error("Falha ao salvar ordem de compra.");
    } finally {
      setSubmitting(false);
    }
  }

  // ---------------------------------------------------------------------------
  // Receive order
  // ---------------------------------------------------------------------------

  async function openReceiveModal(order: PurchaseOrderDto) {
    try {
      const detail = await purchaseOrderService.getByNumber(order.orderNumber);
      if (!detail) {
        Toast.error("Não foi possível carregar os itens da ordem.");
        return;
      }
      setReceiveOrder(order);
      setReceiveItems(
        detail.itens.map((i) => {
          const prod = products.find((p) => p.productCode === i.productCode);
          return {
            ...i,
            qtyToReceive: String(Math.max(0, i.quantity - i.quantityReceived)),
            dataValidade: "",
            controlaValidade: Boolean(prod?.controlaValidade),
          };
        }),
      );
    } catch {
      Toast.error("Erro ao carregar ordem.");
    }
  }

  async function handleReceive() {
    if (!receiveOrder) return;
    setReceiving(true);
    try {
      const itens = receiveItems
        .map((i) => ({
          productCode: i.productCode,
          quantityReceived: Number(i.qtyToReceive) || 0,
          dataValidade: i.dataValidade ? new Date(i.dataValidade).toISOString() : undefined,
        }))
        .filter((i) => i.quantityReceived > 0);

      if (itens.length === 0) {
        Toast.error("Informe ao menos um item com quantidade maior que zero para receber.");
        setReceiving(false);
        return;
      }

      const response = await purchaseOrderService.receive(receiveOrder.orderNumber, itens);
      if (response.success) {
        Toast.success(response.message || "Recebimento registrado e estoque atualizado!");
        setReceiveOrder(null);
        loadOrders();
      } else {
        Toast.error(response.message || "Erro no recebimento.");
      }
    } catch {
      Toast.error("Erro ao processar recebimento.");
    } finally {
      setReceiving(false);
    }
  }

  // ---------------------------------------------------------------------------
  // Detail & Print modal
  // ---------------------------------------------------------------------------

  async function openDetailModal(order: PurchaseOrderDto) {
    try {
      const detail = await purchaseOrderService.getByNumber(order.orderNumber);
      if (detail) setDetailOrder(detail);
      else Toast.error("Não foi possível carregar os detalhes.");
    } catch {
      Toast.error("Erro ao carregar detalhes.");
    }
  }

  async function openPrintModal(order: PurchaseOrderDto | PurchaseOrderDetailDto) {
    if ("itens" in order && order.itens.length > 0) {
      setPrintOrder(order);
      return;
    }
    try {
      const detail = await purchaseOrderService.getByNumber(order.orderNumber);
      if (detail) setPrintOrder(detail);
      else Toast.error("Erro ao carregar ordem para impressão.");
    } catch {
      Toast.error("Erro ao abrir espelho de impressão.");
    }
  }

  // ---------------------------------------------------------------------------
  // Cancel with reason
  // ---------------------------------------------------------------------------

  function handleOpenCancelModal(order: PurchaseOrderDto) {
    setCancelOrderTarget(order);
    setCancelMotivo("");
  }

  async function handleConfirmCancel() {
    if (!cancelOrderTarget) return;
    setCanceling(true);
    try {
      await purchaseOrderService.cancel(cancelOrderTarget.orderNumber, cancelMotivo || undefined);
      Toast.success("Ordem de compra cancelada.");
      setCancelOrderTarget(null);
      loadOrders();
    } catch {
      Toast.error("Erro ao cancelar ordem de compra.");
    } finally {
      setCanceling(false);
    }
  }

  // ---------------------------------------------------------------------------
  // Replenishment → create order
  // ---------------------------------------------------------------------------

  function handleGenerateFromSuggestions(supplierId: string | null) {
    const items = suggestions.filter((s) => s.supplierId === supplierId);
    if (items.length === 0) return;

    resetForm();
    setSelectedSupplierId(supplierId ?? "");
    setCart(
      items.map((s) => ({
        productCode: s.productCode,
        productName: s.productName,
        quantity: Math.max(
          1,
          Math.round(s.maxStock > 0 ? s.maxStock - s.currentStock : s.minStock - s.currentStock),
        ),
        unitCost: s.unitCost,
      })),
    );
    setActiveTab("nova");
  }

  // ---------------------------------------------------------------------------
  // KPIs
  // ---------------------------------------------------------------------------

  const kpis = useMemo(() => {
    const pendentes = orders.filter((o) => o.status === OC_STATUS.Pendente).length;
    const parciais = orders.filter((o) => o.status === OC_STATUS.RecebidoParcial).length;
    const recebidas = orders.filter((o) => o.status === OC_STATUS.Recebido).length;
    const totalGasto = orders
      .filter((o) => o.status === OC_STATUS.Recebido)
      .reduce((s, o) => s + (o.totalEstimado ?? 0), 0);
    return { pendentes, parciais, recebidas, totalGasto };
  }, [orders]);

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------

  return (
    <PageLayout className="space-y-4 py-4 md:space-y-6 md:py-6">
      <PageHeader
        title="Compras e Reposição"
        description="Gestão completa de ordens de compra, recebimento de mercadorias com conferência de validade e espelho do pedido."
      />

      {/* KPIs */}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-4">
        <div className="card flex items-center gap-3 p-4">
          <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-warning/15">
            <ClipboardList size={20} className="text-warning" />
          </div>
          <div>
            <p className="text-xs font-medium text-text-secondary">Pendentes</p>
            <p className="text-lg font-bold text-text-primary">{kpis.pendentes}</p>
          </div>
        </div>
        <div className="card flex items-center gap-3 p-4">
          <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-accent/15">
            <Truck size={20} className="text-accent" />
          </div>
          <div>
            <p className="text-xs font-medium text-text-secondary">Recebidas Parciais</p>
            <p className="text-lg font-bold text-text-primary">{kpis.parciais}</p>
          </div>
        </div>
        <div className="card flex items-center gap-3 p-4">
          <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-success/15">
            <Check size={20} className="text-success" />
          </div>
          <div>
            <p className="text-xs font-medium text-text-secondary">Recebidas Total</p>
            <p className="text-lg font-bold text-text-primary">{kpis.recebidas}</p>
          </div>
        </div>
        <div className="card flex items-center gap-3 p-4">
          <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-secondary/15">
            <ShoppingCart size={20} className="text-secondary" />
          </div>
          <div>
            <p className="text-xs font-medium text-text-secondary">Total Recebido</p>
            <p className="text-lg font-bold text-text-primary">{formatMoney(kpis.totalGasto)}</p>
          </div>
        </div>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 rounded-lg border border-border-primary bg-bg-light p-1">
        {(
          [
            { key: "ordens", label: "Ordens de Compra", icon: ClipboardList },
            {
              key: "nova",
              label: editingOrderNumber ? `Editar Ordem #${editingOrderNumber}` : "Nova Ordem",
              icon: editingOrderNumber ? Edit3 : Plus,
            },
            { key: "sugestoes", label: "Sugestões de Reposição", icon: AlertTriangle },
          ] as const
        ).map(({ key, label, icon: Icon }) => (
          <button
            key={key}
            type="button"
            onClick={() => {
              if (key !== "nova" && editingOrderNumber) {
                resetForm();
              }
              setActiveTab(key);
            }}
            className={`flex-1 rounded-md px-4 py-2 text-sm font-semibold transition ${
              activeTab === key
                ? "bg-white text-secondary shadow-sm"
                : "text-text-secondary hover:text-text-primary"
            }`}
          >
            <Icon size={14} className="mr-1.5 inline" />
            {label}
          </button>
        ))}
      </div>

      {/* ================================================================ */}
      {/* Tab: Ordens de Compra                                            */}
      {/* ================================================================ */}
      {activeTab === "ordens" && (
        <section className="card overflow-hidden">
          <div className="flex flex-wrap items-center gap-3 border-b border-border-primary bg-bg-light px-4 py-3">
            <div className="relative flex-1 min-w-[240px]">
              <Search
                size={16}
                className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-text-tertiary"
              />
              <input
                type="text"
                placeholder="Buscar por número, fornecedor ou CNPJ..."
                value={ordersSearch}
                onChange={(e) => setOrdersSearch(e.target.value)}
                className="input-field w-full pl-9 text-sm"
              />
            </div>
            <select
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value as StatusFilter)}
              className="select-field text-sm"
            >
              <option value="todos">Todos os status</option>
              <option value="pendente">Pendente</option>
              <option value="recebido">Recebido</option>
              <option value="parcial">Recebido Parcial</option>
              <option value="cancelado">Cancelado</option>
            </select>
            <button
              type="button"
              onClick={handleExportCsv}
              className="inline-flex items-center gap-1.5 rounded-lg border border-border-primary bg-bg-primary px-3 py-2 text-xs font-semibold text-text-secondary hover:text-text-primary"
              title="Exportar para CSV"
            >
              <Download size={14} />
              Exportar CSV
            </button>
            <button
              type="button"
              onClick={loadOrders}
              disabled={ordersLoading}
              className="inline-flex items-center gap-1.5 text-xs font-semibold text-secondary hover:text-secondary/80"
            >
              <RefreshCw size={14} className={ordersLoading ? "animate-spin" : ""} />
              Atualizar
            </button>
          </div>

          {ordersLoading ? (
            <div className="flex items-center justify-center gap-2 py-12 text-text-secondary">
              <Loader2 size={18} className="animate-spin" />
              <span>Carregando...</span>
            </div>
          ) : filteredOrders.length === 0 ? (
            <p className="py-12 text-center text-sm text-text-secondary">
              Nenhuma ordem de compra encontrada.
            </p>
          ) : (
            <>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-border-primary bg-bg-light text-left text-xs font-semibold uppercase tracking-wider text-text-secondary">
                      <th className="px-4 py-2">Nº</th>
                      <th className="px-4 py-2">Fornecedor</th>
                      <th className="px-4 py-2">Status</th>
                      <th className="px-4 py-2 text-right">Total Est.</th>
                      <th className="px-4 py-2">Previsão</th>
                      <th className="px-4 py-2">Emissão</th>
                      <th className="px-4 py-2 text-center">Ações</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border-primary">
                    {paginatedOrders.map((o) => (
                      <tr key={o.id} className="transition-colors hover:bg-bg-light/60">
                        <td className="whitespace-nowrap px-4 py-2.5 font-mono text-xs font-semibold text-text-primary">
                          #{o.orderNumber}
                        </td>
                        <td className="px-4 py-2.5">
                          <div className="text-sm font-medium">{o.supplierName}</div>
                          {o.supplierCnpj && (
                            <div className="text-xs text-text-tertiary">{o.supplierCnpj}</div>
                          )}
                        </td>
                        <td className="px-4 py-2.5">
                          <span
                            className={`inline-block rounded-full px-2.5 py-0.5 text-xs font-semibold ${ocStatusBadgeClass(o.status as OcStatus)}`}
                          >
                            {ocStatusLabel(o.status as OcStatus)}
                          </span>
                        </td>
                        <td className="whitespace-nowrap px-4 py-2.5 text-right font-semibold">
                          {formatMoney(o.totalEstimado)}
                        </td>
                        <td className="whitespace-nowrap px-4 py-2.5 text-xs text-text-secondary">
                          {formatDate(o.previsaoEntrega)}
                        </td>
                        <td className="whitespace-nowrap px-4 py-2.5 text-xs text-text-secondary">
                          {formatDate(o.createdAt)}
                        </td>
                        <td className="px-4 py-2.5 text-center">
                          <RowActionsMenu
                            items={[
                              {
                                key: "detalhes",
                                label: "Ver detalhes",
                                onClick: () => openDetailModal(o),
                              },
                              {
                                key: "imprimir",
                                label: "Imprimir Espelho / PDF",
                                onClick: () => void openPrintModal(o),
                              },
                              ...(o.status === OC_STATUS.Pendente
                                ? [
                                    {
                                      key: "editar",
                                      label: "Editar Ordem",
                                      onClick: () => void handleStartEdit(o),
                                    },
                                  ]
                                : []),
                              ...(o.status === OC_STATUS.Pendente ||
                              o.status === OC_STATUS.RecebidoParcial
                                ? [
                                    {
                                      key: "receber",
                                      label: "Receber Mercadoria",
                                      onClick: () => void openReceiveModal(o),
                                    },
                                  ]
                                : []),
                              ...(o.status === OC_STATUS.Pendente
                                ? [
                                    {
                                      key: "cancelar",
                                      label: "Cancelar Ordem",
                                      onClick: () => handleOpenCancelModal(o),
                                      danger: true,
                                    },
                                  ]
                                : []),
                            ]}
                          />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="px-4 py-3">
                <TablePagination
                  totalItems={filteredOrders.length}
                  currentPage={safeOrdersPage}
                  itemsPerPage={ordersPerPage}
                  onPageChange={setOrdersPage}
                  onItemsPerPageChange={(v) => {
                    setOrdersPerPage(v);
                    setOrdersPage(1);
                  }}
                />
              </div>
            </>
          )}
        </section>
      )}

      {/* ================================================================ */}
      {/* Tab: Nova / Editar Ordem                                         */}
      {/* ================================================================ */}
      {activeTab === "nova" && (
        <>
          {/* Supplier and Conditions */}
          <section className="card p-4 space-y-4">
            <div className="flex items-center justify-between border-b border-border-primary pb-3">
              <h2 className="text-sm font-bold text-text-primary">
                {editingOrderNumber
                  ? `Editar Ordem de Compra #${editingOrderNumber}`
                  : "1. Fornecedor e Condições Comerciais"}
              </h2>
              {editingOrderNumber && (
                <button
                  type="button"
                  onClick={resetForm}
                  className="btn-cancel px-3 py-1 text-xs"
                >
                  Cancelar Edição
                </button>
              )}
            </div>

            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
              <div className="sm:col-span-2">
                <label className="mb-1 block text-xs font-semibold text-text-secondary">
                  Fornecedor *
                </label>
                <SearchableSelectField
                  label=""
                  options={suppliers}
                  value={selectedSupplierId}
                  onChange={(val) => setSelectedSupplierId(val)}
                  getOptionValue={(s) => s.id}
                  getOptionLabel={(s) => `${s.fantasyName || s.companyName} ${s.cnpj ? `(${s.cnpj})` : ""}`}
                  getOptionSearchText={(s) => `${s.fantasyName || ""} ${s.companyName || ""} ${s.cnpj || ""}`}
                  placeholder="Selecione ou busque o fornecedor..."
                />
              </div>
              <div>
                <label className="mb-1 block text-xs font-semibold text-text-secondary">
                  Previsão de Entrega
                </label>
                <div className="relative">
                  <input
                    type="date"
                    value={previsaoEntrega}
                    onChange={(e) => setPrevisaoEntrega(e.target.value)}
                    className="input-field w-full text-sm"
                  />
                </div>
              </div>
              <div>
                <label className="mb-1 block text-xs font-semibold text-text-secondary">
                  Condição de Pagamento
                </label>
                <input
                  type="text"
                  placeholder="Ex.: À vista, 30 dias, 30/60 dias"
                  value={condicaoPagamento}
                  onChange={(e) => setCondicaoPagamento(e.target.value)}
                  className="input-field w-full text-sm"
                />
              </div>
            </div>

            <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
              <div>
                <label className="mb-1 block text-xs font-semibold text-text-secondary">
                  Forma de Pagamento Prevista
                </label>
                <select
                  value={formaPagamento}
                  onChange={(e) => setFormaPagamento(e.target.value)}
                  className="select-field w-full text-sm"
                >
                  <option value="">— Selecione —</option>
                  <option value="Boleto">Boleto Bancário</option>
                  <option value="PIX">PIX</option>
                  <option value="Transferência">Transferência Bancária</option>
                  <option value="Cartão de Crédito">Cartão de Crédito</option>
                  <option value="Dinheiro">Dinheiro</option>
                </select>
              </div>
              <div>
                <label className="mb-1 block text-xs font-semibold text-text-secondary">
                  Valor do Frete (R$)
                </label>
                <input
                  type="number"
                  min="0"
                  step="0.01"
                  placeholder="0,00"
                  value={freteInput}
                  onChange={(e) => setFreteInput(e.target.value)}
                  className="input-field w-full text-sm"
                />
              </div>
              <div>
                <label className="mb-1 block text-xs font-semibold text-text-secondary">
                  Desconto Geral (R$)
                </label>
                <input
                  type="number"
                  min="0"
                  step="0.01"
                  placeholder="0,00"
                  value={descontoInput}
                  onChange={(e) => setDescontoInput(e.target.value)}
                  className="input-field w-full text-sm"
                />
              </div>
            </div>
          </section>

          {/* Add products */}
          <section className="card overflow-hidden">
            <div className="border-b border-border-primary bg-bg-light px-4 py-3">
              <h2 className="text-sm font-bold text-text-primary">2. Adicionar Produtos</h2>
            </div>
            <div className="flex flex-wrap items-end gap-3 p-4">
              <div className="relative min-w-[200px] flex-1">
                <label className="mb-1 block text-xs font-semibold text-text-secondary">
                  Produto
                </label>
                <input
                  type="text"
                  placeholder="Buscar produto por código, nome ou GTIN..."
                  value={productSearch}
                  onChange={(e) => {
                    setProductSearch(e.target.value);
                    setSelectedProductCode("");
                    setShowProductOptions(true);
                  }}
                  onFocus={() => setShowProductOptions(true)}
                  onBlur={() => setTimeout(() => setShowProductOptions(false), 200)}
                  className="input-field w-full text-sm"
                />
                {showProductOptions && filteredProducts.length > 0 && (
                  <ul className="absolute z-20 mt-1 max-h-48 w-full overflow-y-auto rounded-lg border border-border-primary bg-bg-primary shadow-lg">
                    {filteredProducts.map((p) => (
                      <li key={p.productCode}>
                        <button
                          type="button"
                          onMouseDown={() => handleSelectProduct(p)}
                          className="w-full px-3 py-2 text-left text-sm hover:bg-bg-light"
                        >
                          <span className="font-mono text-xs text-text-tertiary">
                            {p.productCode}
                          </span>{" "}
                          — {p.productName}
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
              <div className="w-28">
                <label className="mb-1 block text-xs font-semibold text-text-secondary">Qtd</label>
                <input
                  type="number"
                  min="0.01"
                  step="any"
                  value={qtyInput}
                  onChange={(e) => setQtyInput(e.target.value)}
                  className="input-field w-full text-sm"
                />
              </div>
              <div className="w-32">
                <label className="mb-1 block text-xs font-semibold text-text-secondary">
                  Custo Unit. (R$)
                </label>
                <input
                  type="number"
                  min="0"
                  step="any"
                  value={costInput}
                  onChange={(e) => setCostInput(e.target.value)}
                  className="input-field w-full text-sm"
                />
              </div>
              <button
                type="button"
                onClick={handleAddToCart}
                className="btn-primary inline-flex items-center gap-1.5 px-4 py-2 text-sm"
              >
                <Plus size={14} />
                Adicionar
              </button>
            </div>

            {cart.length > 0 ? (
              <div className="border-t border-border-primary overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-border-primary bg-bg-light text-left text-xs font-semibold uppercase tracking-wider text-text-secondary">
                      <th className="px-4 py-2">Produto</th>
                      <th className="px-4 py-2 text-right w-28">Qtd</th>
                      <th className="px-4 py-2 text-right w-36">Custo Unit.</th>
                      <th className="px-4 py-2 text-right">Subtotal</th>
                      <th className="px-4 py-2 text-center w-16">Ação</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border-primary">
                    {cart.map((c) => (
                      <tr key={c.productCode} className="hover:bg-bg-light/50">
                        <td className="px-4 py-2">
                          <span className="font-mono text-xs text-text-tertiary">
                            {c.productCode}
                          </span>{" "}
                          — {c.productName}
                        </td>
                        <td className="px-4 py-2 text-right">
                          <input
                            type="number"
                            min="0.01"
                            step="any"
                            value={c.quantity}
                            onChange={(e) =>
                              handleUpdateCartItem(c.productCode, "quantity", Number(e.target.value))
                            }
                            className="input-field w-20 text-right text-xs py-1"
                          />
                        </td>
                        <td className="px-4 py-2 text-right">
                          <input
                            type="number"
                            min="0"
                            step="0.01"
                            value={c.unitCost}
                            onChange={(e) =>
                              handleUpdateCartItem(c.productCode, "unitCost", Number(e.target.value))
                            }
                            className="input-field w-28 text-right text-xs py-1"
                          />
                        </td>
                        <td className="px-4 py-2 text-right font-semibold">
                          {formatMoney(c.quantity * c.unitCost)}
                        </td>
                        <td className="px-4 py-2 text-center">
                          <button
                            type="button"
                            onClick={() => handleRemoveFromCart(c.productCode)}
                            className="text-danger hover:text-danger/80"
                            title="Remover item"
                          >
                            <Trash2 size={14} />
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                  <tfoot>
                    <tr className="border-t border-border-primary bg-bg-light">
                      <td colSpan={3} className="px-4 py-2 text-right text-xs font-semibold text-text-secondary">
                        Subtotal dos Itens:
                      </td>
                      <td className="px-4 py-2 text-right text-xs font-semibold">
                        {formatMoney(subtotalCart)}
                      </td>
                      <td />
                    </tr>
                    {numFrete > 0 && (
                      <tr className="bg-bg-light">
                        <td colSpan={3} className="px-4 py-1 text-right text-xs text-text-secondary">
                          Frete (+):
                        </td>
                        <td className="px-4 py-1 text-right text-xs font-medium">
                          {formatMoney(numFrete)}
                        </td>
                        <td />
                      </tr>
                    )}
                    {numDesconto > 0 && (
                      <tr className="bg-bg-light">
                        <td colSpan={3} className="px-4 py-1 text-right text-xs text-danger">
                          Desconto (-):
                        </td>
                        <td className="px-4 py-1 text-right text-xs font-medium text-danger">
                          -{formatMoney(numDesconto)}
                        </td>
                        <td />
                      </tr>
                    )}
                    <tr className="border-t-2 border-border-primary bg-bg-light">
                      <td colSpan={3} className="px-4 py-2 text-right text-sm font-bold text-text-primary">
                        Total Estimado da Ordem:
                      </td>
                      <td className="px-4 py-2 text-right text-sm font-bold text-secondary">
                        {formatMoney(cartTotal)}
                      </td>
                      <td />
                    </tr>
                  </tfoot>
                </table>
              </div>
            ) : (
              <p className="p-4 text-center text-xs text-text-tertiary">
                Nenhum produto adicionado ao pedido ainda.
              </p>
            )}
          </section>

          {/* Note + Submit */}
          <section className="card p-4">
            <div className="mb-4">
              <label className="mb-1 block text-xs font-semibold text-text-secondary">
                Observações / Instruções de Entrega (opcional)
              </label>
              <textarea
                value={orderNote}
                onChange={(e) => setOrderNote(e.target.value)}
                rows={2}
                maxLength={500}
                className="input-field w-full resize-none text-sm"
                placeholder="Ex.: Entregar em horário comercial, nota fiscal com chave de acesso..."
              />
            </div>
            <div className="flex justify-end gap-3">
              {editingOrderNumber && (
                <button
                  type="button"
                  onClick={resetForm}
                  className="btn-cancel px-5 py-2.5 text-sm"
                >
                  Cancelar Edição
                </button>
              )}
              <button
                type="button"
                onClick={handleSaveOrder}
                disabled={submitting || cart.length === 0}
                className="btn-primary inline-flex items-center gap-2 px-6 py-2.5 text-sm"
              >
                {submitting ? (
                  <Loader2 size={16} className="animate-spin" />
                ) : (
                  <FileText size={16} />
                )}
                {editingOrderNumber ? "Salvar Alterações" : "Criar Ordem de Compra"}
              </button>
            </div>
          </section>
        </>
      )}

      {/* ================================================================ */}
      {/* Tab: Sugestões de Reposição                                      */}
      {/* ================================================================ */}
      {activeTab === "sugestoes" && (
        <section className="card overflow-hidden">
          <div className="flex items-center justify-between border-b border-border-primary bg-bg-light px-4 py-3">
            <h2 className="text-sm font-bold text-text-primary">
              Produtos abaixo do estoque mínimo
            </h2>
            <button
              type="button"
              onClick={loadSuggestions}
              disabled={suggestionsLoading}
              className="inline-flex items-center gap-1.5 text-xs font-semibold text-secondary hover:text-secondary/80"
            >
              <RefreshCw size={14} className={suggestionsLoading ? "animate-spin" : ""} />
              Atualizar
            </button>
          </div>

          {suggestionsLoading ? (
            <div className="flex items-center justify-center gap-2 py-12 text-text-secondary">
              <Loader2 size={18} className="animate-spin" />
              <span>Carregando sugestões...</span>
            </div>
          ) : suggestions.length === 0 ? (
            <div className="flex flex-col items-center gap-2 py-12 text-text-secondary">
              <Package size={32} className="text-success/60" />
              <p className="text-sm">Todos os produtos estão com estoque adequado.</p>
            </div>
          ) : (
            <>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-border-primary bg-bg-light text-left text-xs font-semibold uppercase tracking-wider text-text-secondary">
                      <th className="px-4 py-2">Produto</th>
                      <th className="px-4 py-2 text-right">Estoque Atual</th>
                      <th className="px-4 py-2 text-right">Mínimo</th>
                      <th className="px-4 py-2 text-right">Sugerido</th>
                      <th className="px-4 py-2">Fornecedor</th>
                      <th className="px-4 py-2 text-right">Custo Unit.</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border-primary">
                    {suggestions.map((s) => {
                      const sugerido = Math.max(
                        1,
                        Math.round(
                          s.maxStock > 0 ? s.maxStock - s.currentStock : s.minStock - s.currentStock,
                        ),
                      );
                      return (
                        <tr key={s.productCode} className="transition-colors hover:bg-bg-light/60">
                          <td className="px-4 py-2.5">
                            <div className="text-sm">{s.productName}</div>
                            <div className="font-mono text-xs text-text-tertiary">
                              {s.productCode}
                            </div>
                          </td>
                          <td className="px-4 py-2.5 text-right font-semibold text-danger">
                            {s.currentStock.toFixed(0)}
                          </td>
                          <td className="px-4 py-2.5 text-right">{s.minStock.toFixed(0)}</td>
                          <td className="px-4 py-2.5 text-right font-semibold text-secondary">
                            +{sugerido}
                          </td>
                          <td className="px-4 py-2.5 text-xs text-text-secondary">
                            {s.supplierName || "—"}
                          </td>
                          <td className="px-4 py-2.5 text-right text-xs">
                            {formatMoney(s.unitCost)}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>

              {/* Group by supplier and offer "Generate Order" buttons */}
              <div className="border-t border-border-primary px-4 py-3">
                <p className="mb-2 text-xs font-semibold text-text-secondary">
                  Gerar ordem de compra rápida por fornecedor:
                </p>
                <div className="flex flex-wrap gap-2">
                  {[
                    ...new Map(
                      suggestions.map((s) => [
                        s.supplierId ?? "__none__",
                        s.supplierName || "Sem fornecedor vinculado",
                      ]),
                    ).entries(),
                  ].map(([sid, name]) => (
                    <button
                      key={sid}
                      type="button"
                      onClick={() =>
                        handleGenerateFromSuggestions(sid === "__none__" ? null : sid)
                      }
                      className="inline-flex items-center gap-1.5 rounded-lg border border-secondary/30 bg-secondary/10 px-3 py-1.5 text-xs font-semibold text-secondary transition hover:bg-secondary/20"
                    >
                      <Truck size={13} />
                      {name}
                    </button>
                  ))}
                </div>
              </div>
            </>
          )}
        </section>
      )}

      {/* ================================================================ */}
      {/* Modal: Recebimento com Validade                                  */}
      {/* ================================================================ */}
      {receiveOrder && (
        <div className="dept-modal-overlay" onClick={() => setReceiveOrder(null)}>
          <div
            className="relative w-full max-w-3xl rounded-2xl border border-border-primary bg-bg-primary p-6 shadow-xl"
            onClick={(e) => e.stopPropagation()}
          >
            <button
              type="button"
              onClick={() => setReceiveOrder(null)}
              className="absolute right-4 top-4 text-text-secondary hover:text-text-primary"
            >
              <X size={18} />
            </button>

            <h2 className="mb-1 text-lg font-bold text-text-primary">
              Receber Mercadorias — Ordem #{receiveOrder.orderNumber}
            </h2>
            <p className="mb-4 text-xs text-text-secondary">
              {receiveOrder.supplierName} • Informe a quantidade conferida e a data de validade dos perecíveis.
            </p>

            <div className="overflow-x-auto max-h-[60vh]">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border-primary text-left text-xs font-semibold uppercase tracking-wider text-text-secondary">
                    <th className="px-3 py-2">Produto</th>
                    <th className="px-3 py-2 text-right">Esperado</th>
                    <th className="px-3 py-2 text-right">Já Recebido</th>
                    <th className="px-3 py-2 text-right w-28">Receber Agora</th>
                    <th className="px-3 py-2 text-center w-36">Validade da Remessa</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border-primary">
                  {receiveItems.map((item, idx) => (
                    <tr key={item.productCode} className="hover:bg-bg-light/40">
                      <td className="px-3 py-2">
                        <div className="text-sm font-medium">{item.productName}</div>
                        <div className="font-mono text-xs text-text-tertiary">
                          {item.productCode}
                        </div>
                      </td>
                      <td className="px-3 py-2 text-right">{item.quantity}</td>
                      <td className="px-3 py-2 text-right">{item.quantityReceived}</td>
                      <td className="px-3 py-2 text-right">
                        <input
                          type="number"
                          min="0"
                          step="any"
                          value={item.qtyToReceive}
                          onChange={(e) => {
                            const updated = [...receiveItems];
                            updated[idx] = { ...updated[idx], qtyToReceive: e.target.value };
                            setReceiveItems(updated);
                          }}
                          className="input-field w-24 text-right text-sm"
                        />
                      </td>
                      <td className="px-3 py-2 text-center">
                        {item.controlaValidade ? (
                          <input
                            type="date"
                            value={item.dataValidade}
                            onChange={(e) => {
                              const updated = [...receiveItems];
                              updated[idx] = { ...updated[idx], dataValidade: e.target.value };
                              setReceiveItems(updated);
                            }}
                            className="input-field w-36 text-xs"
                            title="Produto controla validade. Preencha a data de validade da carga recebida."
                          />
                        ) : (
                          <span className="text-xs text-text-tertiary">Não controlada</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="mt-6 flex justify-end gap-3 border-t border-border-primary pt-4">
              <button
                type="button"
                onClick={() => setReceiveOrder(null)}
                className="btn-cancel px-4 py-2 text-sm"
              >
                Cancelar
              </button>
              <button
                type="button"
                onClick={handleReceive}
                disabled={receiving}
                className="btn-primary inline-flex items-center gap-2 px-5 py-2 text-sm"
              >
                {receiving ? (
                  <Loader2 size={16} className="animate-spin" />
                ) : (
                  <Download size={16} />
                )}
                Confirmar Recebimento e Atualizar Estoque
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ================================================================ */}
      {/* Modal: Detalhes da Ordem                                         */}
      {/* ================================================================ */}
      {detailOrder && (
        <div className="dept-modal-overlay" onClick={() => setDetailOrder(null)}>
          <div
            className="relative w-full max-w-3xl rounded-2xl border border-border-primary bg-bg-primary p-6 shadow-xl"
            onClick={(e) => e.stopPropagation()}
          >
            <button
              type="button"
              onClick={() => setDetailOrder(null)}
              className="absolute right-4 top-4 text-text-secondary hover:text-text-primary"
            >
              <X size={18} />
            </button>

            <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border-primary pb-3 mb-4">
              <div>
                <h2 className="text-lg font-bold text-text-primary">
                  Ordem de Compra #{detailOrder.orderNumber}
                </h2>
                <div className="flex flex-wrap gap-2 text-xs text-text-secondary">
                  <span>{detailOrder.supplierName}</span>
                  <span>•</span>
                  <span>Emissão: {formatDateTime(detailOrder.createdAt)}</span>
                </div>
              </div>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={() => openPrintModal(detailOrder)}
                  className="btn-secondary inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold"
                >
                  <Printer size={14} /> Espelho / Imprimir
                </button>
                <span
                  className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ${ocStatusBadgeClass(detailOrder.status as OcStatus)}`}
                >
                  {ocStatusLabel(detailOrder.status as OcStatus)}
                </span>
              </div>
            </div>

            <div className="grid grid-cols-2 gap-3 sm:grid-cols-4 mb-4 text-xs">
              <div className="rounded-lg bg-bg-light p-2.5">
                <span className="block text-text-tertiary">Previsão de Entrega:</span>
                <span className="font-semibold text-text-primary">
                  {formatDate(detailOrder.previsaoEntrega)}
                </span>
              </div>
              <div className="rounded-lg bg-bg-light p-2.5">
                <span className="block text-text-tertiary">Condição de Pagto:</span>
                <span className="font-semibold text-text-primary">
                  {detailOrder.condicaoPagamento || "—"}
                </span>
              </div>
              <div className="rounded-lg bg-bg-light p-2.5">
                <span className="block text-text-tertiary">Forma de Pagto:</span>
                <span className="font-semibold text-text-primary">
                  {detailOrder.formaPagamento || "—"}
                </span>
              </div>
              <div className="rounded-lg bg-bg-light p-2.5">
                <span className="block text-text-tertiary">Criado por:</span>
                <span className="font-semibold text-text-primary">
                  {detailOrder.createdByName || "—"}
                </span>
              </div>
            </div>

            {detailOrder.motivoCancelamento && (
              <div className="mb-4 rounded-lg bg-danger/10 p-3 text-xs text-danger">
                <strong>Motivo do Cancelamento:</strong> {detailOrder.motivoCancelamento}
              </div>
            )}

            <div className="overflow-x-auto max-h-[45vh]">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border-primary bg-bg-light text-left text-xs font-semibold uppercase tracking-wider text-text-secondary">
                    <th className="px-3 py-2">Produto</th>
                    <th className="px-3 py-2 text-right">Qtd</th>
                    <th className="px-3 py-2 text-right">Custo Unit.</th>
                    <th className="px-3 py-2 text-right">Subtotal</th>
                    <th className="px-3 py-2 text-right">Recebido</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border-primary">
                  {detailOrder.itens.map((item) => (
                    <tr key={item.productCode}>
                      <td className="px-3 py-2">
                        <div className="text-sm font-medium">{item.productName}</div>
                        <div className="font-mono text-xs text-text-tertiary">
                          {item.productCode}
                        </div>
                      </td>
                      <td className="px-3 py-2 text-right">{item.quantity}</td>
                      <td className="px-3 py-2 text-right">{formatMoney(item.unitCost)}</td>
                      <td className="px-3 py-2 text-right font-semibold">
                        {formatMoney(item.itemTotal)}
                      </td>
                      <td className="px-3 py-2 text-right font-medium">
                        {item.quantityReceived >= item.quantity ? (
                          <span className="text-success">{item.quantityReceived}</span>
                        ) : item.quantityReceived > 0 ? (
                          <span className="text-accent">{item.quantityReceived}</span>
                        ) : (
                          <span className="text-warning">0</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
                <tfoot>
                  <tr className="border-t border-border-primary bg-bg-light">
                    <td colSpan={3} className="px-3 py-2 text-right text-xs font-semibold">
                      Total Estimado:
                    </td>
                    <td className="px-3 py-2 text-right text-sm font-bold text-secondary">
                      {formatMoney(detailOrder.totalEstimado)}
                    </td>
                    <td />
                  </tr>
                </tfoot>
              </table>
            </div>

            {detailOrder.note && (
              <p className="mt-3 rounded-lg bg-bg-light p-3 text-xs text-text-secondary">
                <strong>Observações:</strong> {detailOrder.note}
              </p>
            )}
          </div>
        </div>
      )}

      {/* ================================================================ */}
      {/* Modal: Confirmação de Cancelamento com Motivo                    */}
      {/* ================================================================ */}
      {cancelOrderTarget && (
        <div className="dept-modal-overlay" onClick={() => setCancelOrderTarget(null)}>
          <div
            className="relative w-full max-w-md rounded-2xl border border-border-primary bg-bg-primary p-6 shadow-xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center gap-2 text-danger mb-2">
              <AlertCircle size={20} />
              <h2 className="text-base font-bold">Cancelar Ordem #{cancelOrderTarget.orderNumber}</h2>
            </div>
            <p className="text-xs text-text-secondary mb-4">
              Tem certeza que deseja cancelar esta ordem de compra? Esta ação não pode ser desfeita.
            </p>

            <div className="mb-4">
              <label className="block text-xs font-semibold text-text-secondary mb-1">
                Motivo do cancelamento (opcional):
              </label>
              <textarea
                value={cancelMotivo}
                onChange={(e) => setCancelMotivo(e.target.value)}
                placeholder="Ex.: Fornecedor sem estoque, preço divergente..."
                rows={2}
                maxLength={300}
                className="input-field w-full text-xs"
              />
            </div>

            <div className="flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setCancelOrderTarget(null)}
                className="btn-cancel px-3 py-1.5 text-xs"
              >
                Voltar
              </button>
              <button
                type="button"
                onClick={handleConfirmCancel}
                disabled={canceling}
                className="btn-danger inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold"
              >
                {canceling ? <Loader2 size={13} className="animate-spin" /> : <Trash2 size={13} />}
                Confirmar Cancelamento
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ================================================================ */}
      {/* Modal: Espelho de Impressão                                      */}
      {/* ================================================================ */}
      {printOrder && (
        <PurchaseOrderPrintModal
          order={printOrder}
          onClose={() => setPrintOrder(null)}
        />
      )}
    </PageLayout>
  );
}
