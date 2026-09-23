/**
 * Arquivo: src/pages/Admin/PurchasesPage.tsx
 * Objetivo: gestão de ordens de compra — criação, listagem, recebimento com entrada de
 *           estoque automática e sugestões de reposição baseadas em estoque mínimo.
 */
import {
  AlertTriangle,
  Check,
  ClipboardList,
  Download,
  FileText,
  Loader2,
  Package,
  Plus,
  RefreshCw,
  Search,
  ShoppingCart,
  Trash2,
  Truck,
  X,
} from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";
import PageHeader from "@/components/Admin/PageHeader";
import RowActionsMenu from "@/components/Admin/RowActionsMenu";
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
  type PurchaseOrderDto,
  type PurchaseOrderItemDto,
  type ReplenishmentSuggestionDto,
} from "@/services/api/purchaseOrderService";
import { supplierService, type SupplierDto } from "@/services/api/supplierService";

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

type PurchasesTab = "ordens" | "nova" | "sugestoes";
type StatusFilter = "todos" | "pendente" | "recebido" | "cancelado" | "parcial";

type CartItem = {
  productCode: string;
  productName: string;
  quantity: number;
  unitCost: number;
};

function formatDate(value: string | null | undefined) {
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

  // --- New order form ---
  const [suppliers, setSuppliers] = useState<SupplierDto[]>([]);
  const [products, setProducts] = useState<ProductDto[]>([]);
  const [selectedSupplierId, setSelectedSupplierId] = useState("");
  const [cart, setCart] = useState<CartItem[]>([]);
  const [productSearch, setProductSearch] = useState("");
  const [showProductOptions, setShowProductOptions] = useState(false);
  const [qtyInput, setQtyInput] = useState("1");
  const [costInput, setCostInput] = useState("");
  const [selectedProductCode, setSelectedProductCode] = useState("");
  const [orderNote, setOrderNote] = useState("");
  const [submitting, setSubmitting] = useState(false);

  // --- Replenishment suggestions ---
  const [suggestions, setSuggestions] = useState<ReplenishmentSuggestionDto[]>([]);
  const [suggestionsLoading, setSuggestionsLoading] = useState(false);

  // --- Receive modal ---
  const [receiveOrder, setReceiveOrder] = useState<PurchaseOrderDto | null>(null);
  const [receiveItems, setReceiveItems] = useState<
    (PurchaseOrderItemDto & { qtyToReceive: string })[]
  >([]);
  const [receiving, setReceiving] = useState(false);

  // --- Detail modal ---
  const [detailOrder, setDetailOrder] = useState<
    (PurchaseOrderDto & { itens: PurchaseOrderItemDto[] }) | null
  >(null);

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
  }, [loadOrders]);

  useEffect(() => {
    if (activeTab === "nova") {
      loadSuppliers();
      loadProducts();
    }
    if (activeTab === "sugestoes") loadSuggestions();
  }, [activeTab, loadSuppliers, loadProducts, loadSuggestions]);

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
  // New order helpers
  // ---------------------------------------------------------------------------

  const filteredProducts = useMemo(() => {
    if (!productSearch.trim()) return [];
    const q = productSearch.trim().toLowerCase();
    let list = products;
    if (selectedSupplierId) {
      list = list.filter((p) => p.supplierId === selectedSupplierId || !p.supplierId);
    }
    return list
      .filter(
        (p) =>
          p.productName.toLowerCase().includes(q) ||
          p.productCode.toLowerCase().includes(q) ||
          (p.gtin ?? "").includes(q),
      )
      .slice(0, 10);
  }, [products, productSearch, selectedSupplierId]);

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

  function handleRemoveFromCart(code: string) {
    setCart((prev) => prev.filter((c) => c.productCode !== code));
  }

  const cartTotal = cart.reduce((sum, c) => sum + c.quantity * c.unitCost, 0);

  async function handleCreateOrder() {
    if (!selectedSupplierId) {
      Toast.error("Selecione um fornecedor.");
      return;
    }
    if (cart.length === 0) {
      Toast.error("Adicione ao menos um item.");
      return;
    }
    setSubmitting(true);
    try {
      const response = await purchaseOrderService.create({
        supplierId: selectedSupplierId,
        note: orderNote || undefined,
        items: cart.map((c) => ({
          productCode: c.productCode,
          quantity: c.quantity,
          unitCost: c.unitCost,
        })),
      });
      if (response.success) {
        Toast.success(response.message || "Ordem criada!");
        setCart([]);
        setSelectedSupplierId("");
        setOrderNote("");
        setActiveTab("ordens");
        loadOrders();
      } else {
        Toast.error(response.message || "Erro ao criar ordem.");
      }
    } catch {
      Toast.error("Erro ao criar ordem de compra.");
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
        detail.itens.map((i) => ({
          ...i,
          qtyToReceive: String(Math.max(0, i.quantity - i.quantityReceived)),
        })),
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
        }))
        .filter((i) => i.quantityReceived > 0);

      if (itens.length === 0) {
        Toast.error("Informe ao menos um item para receber.");
        setReceiving(false);
        return;
      }

      const response = await purchaseOrderService.receive(receiveOrder.orderNumber, itens);
      if (response.success) {
        Toast.success(response.message || "Recebimento registrado!");
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
  // Detail modal
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

  // ---------------------------------------------------------------------------
  // Cancel
  // ---------------------------------------------------------------------------

  async function handleCancel(order: PurchaseOrderDto) {
    try {
      await purchaseOrderService.cancel(order.orderNumber);
      Toast.success("Ordem cancelada.");
      loadOrders();
    } catch {
      Toast.error("Erro ao cancelar ordem.");
    }
  }

  // ---------------------------------------------------------------------------
  // Replenishment → create order
  // ---------------------------------------------------------------------------

  function handleGenerateFromSuggestions(supplierId: string | null) {
    const items = suggestions.filter((s) => s.supplierId === supplierId);
    if (items.length === 0) return;

    setSelectedSupplierId(supplierId ?? "");
    setCart(
      items.map((s) => ({
        productCode: s.productCode,
        productName: s.productName,
        quantity: Math.max(1, Math.round(s.maxStock > 0 ? s.maxStock - s.currentStock : s.minStock - s.currentStock)),
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
    const recebidas = orders.filter((o) => o.status === OC_STATUS.Recebido).length;
    const totalGasto = orders
      .filter((o) => o.status === OC_STATUS.Recebido)
      .reduce((s, o) => s + o.totalEstimado, 0);
    return { pendentes, recebidas, totalGasto };
  }, [orders]);

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------

  return (
    <PageLayout className="space-y-4 py-4 md:space-y-6 md:py-6">
      <PageHeader
        title="Compras e Reposição"
        description="Gerencie ordens de compra, recebimento de mercadorias e reposição de estoque."
      />

      {/* KPIs */}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
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
          <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-success/15">
            <Check size={20} className="text-success" />
          </div>
          <div>
            <p className="text-xs font-medium text-text-secondary">Recebidas</p>
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
            { key: "nova", label: "Nova Ordem", icon: Plus },
            { key: "sugestoes", label: "Sugestões de Reposição", icon: AlertTriangle },
          ] as const
        ).map(({ key, label, icon: Icon }) => (
          <button
            key={key}
            type="button"
            onClick={() => setActiveTab(key)}
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
            <div className="relative flex-1">
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
              <option value="cancelado">Cancelado</option>
              <option value="parcial">Parcial</option>
            </select>
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
                      <th className="px-4 py-2">Criado por</th>
                      <th className="px-4 py-2">Data</th>
                      <th className="px-4 py-2 text-center">Ações</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border-primary">
                    {paginatedOrders.map((o) => (
                      <tr key={o.id} className="transition-colors hover:bg-bg-light/60">
                        <td className="whitespace-nowrap px-4 py-2.5 font-mono text-xs">
                          #{o.orderNumber}
                        </td>
                        <td className="px-4 py-2.5">
                          <div className="text-sm">{o.supplierName}</div>
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
                        <td className="px-4 py-2.5 text-xs text-text-secondary">
                          {o.createdByName || "—"}
                        </td>
                        <td className="whitespace-nowrap px-4 py-2.5 text-xs text-text-secondary">
                          {formatDate(o.createdAt)}
                        </td>
                        <td className="px-4 py-2.5 text-center">
                          <RowActionsMenu
                            actions={[
                              {
                                label: "Ver detalhes",
                                onClick: () => openDetailModal(o),
                              },
                              ...(o.status === OC_STATUS.Pendente || o.status === OC_STATUS.RecebidoParcial
                                ? [
                                    {
                                      label: "Receber",
                                      onClick: () => openReceiveModal(o),
                                    },
                                  ]
                                : []),
                              ...(o.status === OC_STATUS.Pendente
                                ? [
                                    {
                                      label: "Cancelar",
                                      onClick: () => handleCancel(o),
                                      variant: "danger" as const,
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
      {/* Tab: Nova Ordem                                                  */}
      {/* ================================================================ */}
      {activeTab === "nova" && (
        <>
          {/* Supplier select */}
          <section className="card p-4">
            <h2 className="mb-3 text-sm font-bold text-text-primary">1. Selecione o Fornecedor</h2>
            <select
              value={selectedSupplierId}
              onChange={(e) => setSelectedSupplierId(e.target.value)}
              className="select-field w-full text-sm md:w-1/2"
            >
              <option value="">— Selecione —</option>
              {suppliers.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.fantasyName || s.companyName} {s.cnpj ? `(${s.cnpj})` : ""}
                </option>
              ))}
            </select>
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
                  placeholder="Buscar produto..."
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
              <div className="w-24">
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
                  Custo Unit.
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

            {cart.length > 0 && (
              <div className="border-t border-border-primary">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-border-primary bg-bg-light text-left text-xs font-semibold uppercase tracking-wider text-text-secondary">
                      <th className="px-4 py-2">Produto</th>
                      <th className="px-4 py-2 text-right">Qtd</th>
                      <th className="px-4 py-2 text-right">Custo Unit.</th>
                      <th className="px-4 py-2 text-right">Subtotal</th>
                      <th className="px-4 py-2 text-center">Remover</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border-primary">
                    {cart.map((c) => (
                      <tr key={c.productCode}>
                        <td className="px-4 py-2">
                          <span className="font-mono text-xs text-text-tertiary">
                            {c.productCode}
                          </span>{" "}
                          — {c.productName}
                        </td>
                        <td className="px-4 py-2 text-right">{c.quantity}</td>
                        <td className="px-4 py-2 text-right">{formatMoney(c.unitCost)}</td>
                        <td className="px-4 py-2 text-right font-semibold">
                          {formatMoney(c.quantity * c.unitCost)}
                        </td>
                        <td className="px-4 py-2 text-center">
                          <button
                            type="button"
                            onClick={() => handleRemoveFromCart(c.productCode)}
                            className="text-primary hover:text-primary/70"
                          >
                            <Trash2 size={14} />
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                  <tfoot>
                    <tr className="border-t border-border-primary bg-bg-light">
                      <td colSpan={3} className="px-4 py-2 text-right text-sm font-bold">
                        Total Estimado:
                      </td>
                      <td className="px-4 py-2 text-right text-sm font-bold text-secondary">
                        {formatMoney(cartTotal)}
                      </td>
                      <td />
                    </tr>
                  </tfoot>
                </table>
              </div>
            )}
          </section>

          {/* Note + Submit */}
          <section className="card p-4">
            <div className="mb-4">
              <label className="mb-1 block text-xs font-semibold text-text-secondary">
                Observação (opcional)
              </label>
              <textarea
                value={orderNote}
                onChange={(e) => setOrderNote(e.target.value)}
                rows={2}
                maxLength={500}
                className="input-field w-full resize-none text-sm"
                placeholder="Observações sobre a ordem de compra..."
              />
            </div>
            <div className="flex justify-end">
              <button
                type="button"
                onClick={handleCreateOrder}
                disabled={submitting || cart.length === 0}
                className="btn-primary inline-flex items-center gap-2 px-6 py-2.5 text-sm"
              >
                {submitting ? (
                  <Loader2 size={16} className="animate-spin" />
                ) : (
                  <FileText size={16} />
                )}
                Criar Ordem de Compra
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
              <span>Carregando...</span>
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
                      const sugerido = Math.max(1, Math.round(
                        s.maxStock > 0 ? s.maxStock - s.currentStock : s.minStock - s.currentStock,
                      ));
                      return (
                        <tr key={s.productCode} className="transition-colors hover:bg-bg-light/60">
                          <td className="px-4 py-2.5">
                            <div className="text-sm">{s.productName}</div>
                            <div className="font-mono text-xs text-text-tertiary">
                              {s.productCode}
                            </div>
                          </td>
                          <td className="px-4 py-2.5 text-right">
                            <span className="font-semibold text-primary">
                              {s.currentStock.toFixed(0)}
                            </span>
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
                  Gerar ordem de compra por fornecedor:
                </p>
                <div className="flex flex-wrap gap-2">
                  {[
                    ...new Map(
                      suggestions.map((s) => [s.supplierId ?? "__none__", s.supplierName || "Sem fornecedor"]),
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
      {/* Modal: Recebimento                                               */}
      {/* ================================================================ */}
      {receiveOrder && (
        <div className="dept-modal-overlay" onClick={() => setReceiveOrder(null)}>
          <div
            className="relative w-full max-w-2xl rounded-2xl border border-border-primary bg-bg-primary p-6 shadow-xl"
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
              Receber Ordem #{receiveOrder.orderNumber}
            </h2>
            <p className="mb-4 text-sm text-text-secondary">
              {receiveOrder.supplierName}
            </p>

            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border-primary text-left text-xs font-semibold uppercase tracking-wider text-text-secondary">
                    <th className="px-3 py-2">Produto</th>
                    <th className="px-3 py-2 text-right">Esperado</th>
                    <th className="px-3 py-2 text-right">Já Recebido</th>
                    <th className="px-3 py-2 text-right">Receber Agora</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border-primary">
                  {receiveItems.map((item, idx) => (
                    <tr key={item.productCode}>
                      <td className="px-3 py-2">
                        <div className="text-sm">{item.productName}</div>
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
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="mt-4 flex justify-end gap-3">
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
                Confirmar Recebimento
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ================================================================ */}
      {/* Modal: Detalhes                                                  */}
      {/* ================================================================ */}
      {detailOrder && (
        <div className="dept-modal-overlay" onClick={() => setDetailOrder(null)}>
          <div
            className="relative w-full max-w-2xl rounded-2xl border border-border-primary bg-bg-primary p-6 shadow-xl"
            onClick={(e) => e.stopPropagation()}
          >
            <button
              type="button"
              onClick={() => setDetailOrder(null)}
              className="absolute right-4 top-4 text-text-secondary hover:text-text-primary"
            >
              <X size={18} />
            </button>

            <h2 className="mb-1 text-lg font-bold text-text-primary">
              Ordem de Compra #{detailOrder.orderNumber}
            </h2>
            <div className="mb-4 flex flex-wrap gap-3 text-sm text-text-secondary">
              <span>{detailOrder.supplierName}</span>
              <span
                className={`inline-block rounded-full px-2.5 py-0.5 text-xs font-semibold ${ocStatusBadgeClass(detailOrder.status as OcStatus)}`}
              >
                {ocStatusLabel(detailOrder.status as OcStatus)}
              </span>
              <span>{formatDate(detailOrder.createdAt)}</span>
            </div>

            {detailOrder.note && (
              <p className="mb-3 rounded-lg bg-bg-light p-3 text-sm text-text-secondary">
                {detailOrder.note}
              </p>
            )}

            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border-primary text-left text-xs font-semibold uppercase tracking-wider text-text-secondary">
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
                        <div className="text-sm">{item.productName}</div>
                        <div className="font-mono text-xs text-text-tertiary">
                          {item.productCode}
                        </div>
                      </td>
                      <td className="px-3 py-2 text-right">{item.quantity}</td>
                      <td className="px-3 py-2 text-right">{formatMoney(item.unitCost)}</td>
                      <td className="px-3 py-2 text-right font-semibold">
                        {formatMoney(item.itemTotal)}
                      </td>
                      <td className="px-3 py-2 text-right">
                        {item.quantityReceived >= item.quantity ? (
                          <span className="text-success">{item.quantityReceived}</span>
                        ) : (
                          <span className="text-warning">{item.quantityReceived}</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
                <tfoot>
                  <tr className="border-t border-border-primary bg-bg-light">
                    <td colSpan={3} className="px-3 py-2 text-right text-sm font-bold">
                      Total:
                    </td>
                    <td className="px-3 py-2 text-right text-sm font-bold text-secondary">
                      {formatMoney(detailOrder.totalEstimado)}
                    </td>
                    <td />
                  </tr>
                </tfoot>
              </table>
            </div>
          </div>
        </div>
      )}
    </PageLayout>
  );
}
