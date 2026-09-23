/**
 * Arquivo: src/pages/Admin/StockPage.tsx
 * Objetivo: painel de gestão de estoque e inventário com KPIs, tabela de produtos,
 * indicadores visuais de nível e ajuste manual de quantidades.
 */
import {
  AlertTriangle,
  ArrowDownCircle,
  ArrowUpCircle,
  Box,
  DollarSign,
  Filter,
  Loader2,
  MapPin,
  Package,
  PackageX,
  RefreshCw,
  Search,
  TrendingDown,
  X,
} from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";
import PageHeader from "@/components/Admin/PageHeader";
import TablePagination from "@/components/Pagination/TablePagination";
import { Toast } from "@/hooks/Dialog";
import PageLayout from "@/layout/PageLayout";
import { productService, type ProductDto } from "@/services/api/productService";
import { formatMoneyBr } from "@/utils/inputMasks";

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function parseBrNumber(value: string | null | undefined): number {
  if (!value) return 0;
  const normalized = value.replace(/\./g, "").replace(",", ".");
  const n = Number(normalized);
  return Number.isFinite(n) ? n : 0;
}

type StockStatus = "sem-estoque" | "estoque-baixo" | "normal" | "excesso";

function getStockStatus(qty: number, min: number, max: number): StockStatus {
  if (qty <= 0) return "sem-estoque";
  if (min > 0 && qty < min) return "estoque-baixo";
  if (max > 0 && qty > max) return "excesso";
  return "normal";
}

const STATUS_CONFIG: Record<
  StockStatus,
  { label: string; color: string; bg: string; border: string }
> = {
  "sem-estoque": {
    label: "Sem estoque",
    color: "text-red-700",
    bg: "bg-red-50",
    border: "border-red-200",
  },
  "estoque-baixo": {
    label: "Estoque baixo",
    color: "text-amber-700",
    bg: "bg-amber-50",
    border: "border-amber-200",
  },
  normal: {
    label: "Normal",
    color: "text-emerald-700",
    bg: "bg-emerald-50",
    border: "border-emerald-200",
  },
  excesso: {
    label: "Acima do máx.",
    color: "text-blue-700",
    bg: "bg-blue-50",
    border: "border-blue-200",
  },
};

type StockFilter = "todos" | StockStatus;

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function StockPage() {
  const [products, setProducts] = useState<ProductDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState("");
  const [filter, setFilter] = useState<StockFilter>("todos");
  const [currentPage, setCurrentPage] = useState(1);
  const [itemsPerPage, setItemsPerPage] = useState(20);

  // Adjustment modal state
  const [adjustProduct, setAdjustProduct] = useState<ProductDto | null>(null);
  const [adjustType, setAdjustType] = useState<"entrada" | "saida">("entrada");
  const [adjustQty, setAdjustQty] = useState("");
  const [adjustReason, setAdjustReason] = useState("");
  const [adjusting, setAdjusting] = useState(false);

  // ---------------------------------------------------------------------------
  // Data fetching
  // ---------------------------------------------------------------------------

  const fetchProducts = useCallback(async () => {
    setLoading(true);
    try {
      const data = await productService.list();
      setProducts(data);
    } catch {
      Toast.error("Erro ao carregar produtos.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchProducts();
  }, [fetchProducts]);

  // ---------------------------------------------------------------------------
  // Computed data
  // ---------------------------------------------------------------------------

  const enriched = useMemo(
    () =>
      products.map((p) => {
        const qty = parseBrNumber(p.productQnt);
        const min = parseBrNumber(p.estoqueMinimo);
        const max = parseBrNumber(p.estoqueMaximo);
        const cost = parseBrNumber(p.custoMedio);
        const salePrice = parseBrNumber(p.productSalePrice);
        const status = getStockStatus(qty, min, max);
        return { ...p, _qty: qty, _min: min, _max: max, _cost: cost, _salePrice: salePrice, _status: status };
      }),
    [products],
  );

  // KPIs
  const kpis = useMemo(() => {
    const totalProducts = enriched.length;
    const outOfStock = enriched.filter((p) => p._status === "sem-estoque").length;
    const lowStock = enriched.filter((p) => p._status === "estoque-baixo").length;
    const totalValue = enriched.reduce((sum, p) => sum + p._qty * p._cost, 0);
    const totalSaleValue = enriched.reduce((sum, p) => sum + p._qty * p._salePrice, 0);
    return { totalProducts, outOfStock, lowStock, totalValue, totalSaleValue };
  }, [enriched]);

  // Filtered + searched
  const filtered = useMemo(() => {
    let list = enriched;
    if (filter !== "todos") {
      list = list.filter((p) => p._status === filter);
    }
    if (search.trim()) {
      const q = search.trim().toLowerCase();
      list = list.filter(
        (p) =>
          p.productName.toLowerCase().includes(q) ||
          p.productCode.toLowerCase().includes(q) ||
          (p.localizacaoEstoque ?? "").toLowerCase().includes(q) ||
          (p.categoriaNome ?? "").toLowerCase().includes(q),
      );
    }
    return list;
  }, [enriched, filter, search]);

  // Pagination
  const totalPages = Math.max(1, Math.ceil(filtered.length / itemsPerPage));
  const safePage = Math.min(currentPage, totalPages);
  const paginated = filtered.slice((safePage - 1) * itemsPerPage, safePage * itemsPerPage);

  // Reset page on filter/search change
  useEffect(() => {
    setCurrentPage(1);
  }, [search, filter]);

  // ---------------------------------------------------------------------------
  // Stock adjustment
  // ---------------------------------------------------------------------------

  function openAdjust(product: ProductDto, type: "entrada" | "saida") {
    setAdjustProduct(product);
    setAdjustType(type);
    setAdjustQty("");
    setAdjustReason("");
  }

  async function handleAdjust() {
    if (!adjustProduct) return;
    const qty = parseBrNumber(adjustQty);
    if (qty <= 0) {
      Toast.error("Informe uma quantidade válida maior que zero.");
      return;
    }
    if (!adjustReason.trim()) {
      Toast.error("Informe o motivo do ajuste.");
      return;
    }

    setAdjusting(true);
    try {
      await productService.adjustStock(adjustProduct.id, {
        tipo: adjustType,
        quantidade: qty,
        motivo: adjustReason.trim(),
      });
      Toast.success(
        adjustType === "entrada"
          ? `Entrada de ${qty} unidade(s) registrada com sucesso.`
          : `Saída de ${qty} unidade(s) registrada com sucesso.`,
      );
      setAdjustProduct(null);
      await fetchProducts();
    } catch {
      Toast.error("Erro ao registrar ajuste de estoque.");
    } finally {
      setAdjusting(false);
    }
  }

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------

  return (
    <PageLayout className="space-y-4 py-4 md:space-y-6 md:py-6">
      <PageHeader
        title="Estoque e Inventário"
        description="Acompanhe níveis de estoque, identifique produtos críticos e registre ajustes manuais."
        action={
          <button
            type="button"
            onClick={fetchProducts}
            disabled={loading}
            className="btn-primary inline-flex items-center gap-2 text-sm"
          >
            <RefreshCw size={16} className={loading ? "animate-spin" : ""} />
            Atualizar
          </button>
        }
      />

      {/* KPI Cards */}
      <section className="grid grid-cols-2 gap-3 md:grid-cols-4 lg:gap-4">
        <KpiCard
          icon={<Package size={22} className="text-secondary" />}
          label="Total de Produtos"
          value={kpis.totalProducts.toLocaleString("pt-BR")}
          bg="bg-indigo-50"
        />
        <KpiCard
          icon={<PackageX size={22} className="text-red-600" />}
          label="Sem Estoque"
          value={kpis.outOfStock.toLocaleString("pt-BR")}
          bg="bg-red-50"
          alert={kpis.outOfStock > 0}
        />
        <KpiCard
          icon={<TrendingDown size={22} className="text-amber-600" />}
          label="Estoque Baixo"
          value={kpis.lowStock.toLocaleString("pt-BR")}
          bg="bg-amber-50"
          alert={kpis.lowStock > 0}
        />
        <KpiCard
          icon={<DollarSign size={22} className="text-emerald-600" />}
          label="Valor em Estoque (custo)"
          value={`R$ ${formatMoneyBr(kpis.totalValue)}`}
          bg="bg-emerald-50"
        />
      </section>

      {/* Search + Filter */}
      <section className="card p-4 md:p-5">
        <div className="flex flex-col gap-3 md:flex-row md:items-center">
          <div className="relative flex-1">
            <Search
              size={16}
              className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-text-tertiary"
            />
            <input
              type="text"
              placeholder="Buscar por nome, código, localização ou categoria..."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="input-field w-full pl-9 pr-8 text-sm"
            />
            {search && (
              <button
                type="button"
                onClick={() => setSearch("")}
                className="absolute right-2.5 top-1/2 -translate-y-1/2 text-text-tertiary hover:text-text-primary"
              >
                <X size={14} />
              </button>
            )}
          </div>

          <div className="flex items-center gap-2">
            <Filter size={16} className="shrink-0 text-text-tertiary" />
            <select
              value={filter}
              onChange={(e) => setFilter(e.target.value as StockFilter)}
              className="select-field py-2 text-sm"
            >
              <option value="todos">Todos os produtos</option>
              <option value="sem-estoque">Sem estoque</option>
              <option value="estoque-baixo">Estoque baixo</option>
              <option value="normal">Normal</option>
              <option value="excesso">Acima do máximo</option>
            </select>
          </div>
        </div>

        {(search || filter !== "todos") && (
          <p className="mt-2 text-xs text-text-secondary">
            {filtered.length} produto(s) encontrado(s)
          </p>
        )}
      </section>

      {/* Product Stock Table */}
      <section className="card overflow-hidden">
        {loading ? (
          <div className="flex items-center justify-center gap-2 py-16 text-text-secondary">
            <Loader2 size={20} className="animate-spin" />
            <span>Carregando produtos...</span>
          </div>
        ) : filtered.length === 0 ? (
          <div className="flex flex-col items-center justify-center gap-2 py-16 text-text-secondary">
            <Box size={40} className="text-text-tertiary" />
            <p className="text-sm">
              {search || filter !== "todos"
                ? "Nenhum produto encontrado com os filtros aplicados."
                : "Nenhum produto cadastrado."}
            </p>
          </div>
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border-primary bg-bg-light text-left text-xs font-semibold uppercase tracking-wider text-text-secondary">
                    <th className="px-4 py-3">Produto</th>
                    <th className="px-4 py-3 text-center">Código</th>
                    <th className="px-4 py-3 text-right">Qtd. Atual</th>
                    <th className="px-4 py-3 text-right">Mín.</th>
                    <th className="px-4 py-3 text-right">Máx.</th>
                    <th className="px-4 py-3 text-center">Status</th>
                    <th className="px-4 py-3 text-center">Localização</th>
                    <th className="px-4 py-3 text-right">Custo Médio</th>
                    <th className="px-4 py-3 text-right">Preço Venda</th>
                    <th className="px-4 py-3 text-center">Ações</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border-primary">
                  {paginated.map((p) => {
                    const cfg = STATUS_CONFIG[p._status];
                    return (
                      <tr
                        key={p.id}
                        className="transition-colors hover:bg-bg-light/60"
                      >
                        <td className="max-w-[240px] truncate px-4 py-3 font-medium text-text-primary">
                          {p.productName}
                          {p.categoriaNome && (
                            <span className="ml-2 text-xs text-text-tertiary">
                              ({p.categoriaNome})
                            </span>
                          )}
                        </td>
                        <td className="whitespace-nowrap px-4 py-3 text-center font-mono text-xs text-text-secondary">
                          {p.productCode}
                        </td>
                        <td
                          className={`whitespace-nowrap px-4 py-3 text-right font-semibold tabular-nums ${
                            p._status === "sem-estoque"
                              ? "text-red-600"
                              : p._status === "estoque-baixo"
                                ? "text-amber-600"
                                : "text-text-primary"
                          }`}
                        >
                          {p.productQnt}
                        </td>
                        <td className="whitespace-nowrap px-4 py-3 text-right tabular-nums text-text-secondary">
                          {p._min > 0 ? p.estoqueMinimo : "—"}
                        </td>
                        <td className="whitespace-nowrap px-4 py-3 text-right tabular-nums text-text-secondary">
                          {p._max > 0 ? p.estoqueMaximo : "—"}
                        </td>
                        <td className="whitespace-nowrap px-4 py-3 text-center">
                          <span
                            className={`inline-flex items-center gap-1 rounded-full border px-2.5 py-0.5 text-xs font-semibold ${cfg.bg} ${cfg.color} ${cfg.border}`}
                          >
                            {p._status === "sem-estoque" && (
                              <PackageX size={12} />
                            )}
                            {p._status === "estoque-baixo" && (
                              <AlertTriangle size={12} />
                            )}
                            {cfg.label}
                          </span>
                        </td>
                        <td className="whitespace-nowrap px-4 py-3 text-center text-xs text-text-secondary">
                          {p.localizacaoEstoque ? (
                            <span className="inline-flex items-center gap-1">
                              <MapPin size={12} />
                              {p.localizacaoEstoque}
                            </span>
                          ) : (
                            "—"
                          )}
                        </td>
                        <td className="whitespace-nowrap px-4 py-3 text-right tabular-nums text-text-secondary">
                          {p._cost > 0 ? `R$ ${formatMoneyBr(p._cost)}` : "—"}
                        </td>
                        <td className="whitespace-nowrap px-4 py-3 text-right tabular-nums text-text-primary">
                          {p._salePrice > 0 ? `R$ ${formatMoneyBr(p._salePrice)}` : "—"}
                        </td>
                        <td className="whitespace-nowrap px-4 py-3 text-center">
                          <div className="inline-flex items-center gap-1">
                            <button
                              type="button"
                              title="Entrada de estoque"
                              onClick={() => openAdjust(p, "entrada")}
                              className="rounded-lg p-1.5 text-emerald-600 transition hover:bg-emerald-50"
                            >
                              <ArrowUpCircle size={18} />
                            </button>
                            <button
                              type="button"
                              title="Saída de estoque"
                              onClick={() => openAdjust(p, "saida")}
                              className="rounded-lg p-1.5 text-red-600 transition hover:bg-red-50"
                            >
                              <ArrowDownCircle size={18} />
                            </button>
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>

            <div className="px-4 py-4">
              <TablePagination
                totalItems={filtered.length}
                currentPage={safePage}
                itemsPerPage={itemsPerPage}
                onPageChange={setCurrentPage}
                onItemsPerPageChange={(v) => {
                  setItemsPerPage(v);
                  setCurrentPage(1);
                }}
              />
            </div>
          </>
        )}
      </section>

      {/* Stock Adjustment Modal */}
      {adjustProduct && (
        <div
          className="dept-modal-overlay"
          onClick={(e) => {
            if (e.target === e.currentTarget) setAdjustProduct(null);
          }}
        >
          <div className="dept-modal-panel w-full max-w-md">
            <div className="flex items-center justify-between border-b border-border-primary px-5 py-4">
              <h2 className="text-lg font-bold text-text-primary">
                {adjustType === "entrada" ? "Entrada de Estoque" : "Saída de Estoque"}
              </h2>
              <button
                type="button"
                onClick={() => setAdjustProduct(null)}
                className="rounded-lg p-1 text-text-tertiary hover:text-text-primary"
              >
                <X size={20} />
              </button>
            </div>

            <div className="space-y-4 px-5 py-5">
              {/* Product info */}
              <div className="rounded-lg border border-border-primary bg-bg-light p-3">
                <p className="text-sm font-semibold text-text-primary">
                  {adjustProduct.productName}
                </p>
                <p className="mt-0.5 text-xs text-text-secondary">
                  Código: {adjustProduct.productCode} &bull; Estoque atual:{" "}
                  <span className="font-semibold">{adjustProduct.productQnt}</span>
                </p>
              </div>

              {/* Type toggle */}
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={() => setAdjustType("entrada")}
                  className={`flex flex-1 items-center justify-center gap-2 rounded-lg border px-3 py-2.5 text-sm font-semibold transition ${
                    adjustType === "entrada"
                      ? "border-emerald-300 bg-emerald-50 text-emerald-700"
                      : "border-border-primary bg-white text-text-secondary hover:border-emerald-200"
                  }`}
                >
                  <ArrowUpCircle size={16} />
                  Entrada
                </button>
                <button
                  type="button"
                  onClick={() => setAdjustType("saida")}
                  className={`flex flex-1 items-center justify-center gap-2 rounded-lg border px-3 py-2.5 text-sm font-semibold transition ${
                    adjustType === "saida"
                      ? "border-red-300 bg-red-50 text-red-700"
                      : "border-border-primary bg-white text-text-secondary hover:border-red-200"
                  }`}
                >
                  <ArrowDownCircle size={16} />
                  Saída
                </button>
              </div>

              {/* Quantity */}
              <div>
                <label className="mb-1 block text-xs font-semibold text-text-secondary">
                  Quantidade
                </label>
                <input
                  type="number"
                  min="0.0001"
                  step="any"
                  placeholder="0"
                  value={adjustQty}
                  onChange={(e) => setAdjustQty(e.target.value)}
                  className="input-field w-full text-sm"
                  autoFocus
                />
              </div>

              {/* Reason */}
              <div>
                <label className="mb-1 block text-xs font-semibold text-text-secondary">
                  Motivo do ajuste
                </label>
                <textarea
                  rows={2}
                  placeholder="Ex.: Contagem de inventário, avaria, devolução..."
                  value={adjustReason}
                  onChange={(e) => setAdjustReason(e.target.value)}
                  className="input-field w-full resize-none text-sm"
                />
              </div>

              {/* Preview */}
              {adjustQty && parseBrNumber(adjustQty) > 0 && (
                <div className="rounded-lg border border-border-primary bg-bg-light p-3 text-xs text-text-secondary">
                  <span>Estoque após ajuste: </span>
                  <span className="font-bold text-text-primary">
                    {formatMoneyBr(
                      Math.max(
                        0,
                        parseBrNumber(adjustProduct.productQnt) +
                          (adjustType === "entrada" ? 1 : -1) * parseBrNumber(adjustQty),
                      ),
                    )}
                  </span>
                </div>
              )}
            </div>

            <div className="flex items-center justify-end gap-3 border-t border-border-primary px-5 py-4">
              <button
                type="button"
                onClick={() => setAdjustProduct(null)}
                disabled={adjusting}
                className="btn-cancel px-4 py-2 text-sm"
              >
                Cancelar
              </button>
              <button
                type="button"
                onClick={handleAdjust}
                disabled={adjusting}
                className={`inline-flex items-center gap-2 rounded-lg px-4 py-2 text-sm font-semibold text-white transition ${
                  adjustType === "entrada"
                    ? "bg-emerald-600 hover:bg-emerald-700"
                    : "bg-red-600 hover:bg-red-700"
                } disabled:opacity-60`}
              >
                {adjusting && <Loader2 size={14} className="animate-spin" />}
                {adjustType === "entrada" ? "Registrar Entrada" : "Registrar Saída"}
              </button>
            </div>
          </div>
        </div>
      )}
    </PageLayout>
  );
}

// ---------------------------------------------------------------------------
// KPI Card sub-component
// ---------------------------------------------------------------------------

function KpiCard({
  icon,
  label,
  value,
  bg,
  alert,
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
  bg: string;
  alert?: boolean;
}) {
  return (
    <div className={`card flex items-center gap-3 p-4 ${alert ? "ring-1 ring-red-200" : ""}`}>
      <div className={`flex h-11 w-11 shrink-0 items-center justify-center rounded-xl ${bg}`}>
        {icon}
      </div>
      <div className="min-w-0">
        <p className="truncate text-xs font-medium text-text-secondary">{label}</p>
        <p className="text-lg font-bold tracking-tight text-text-primary">{value}</p>
      </div>
    </div>
  );
}
