/**
 * Arquivo: src/pages/Admin/SalesHistoryPage.tsx
 * Objetivo: exibe histórico de vendas com busca local e ação de impressão por registro.
 * Entradas esperadas: não recebe props; processa filtro textual e renderiza dados vindos da API.
 */

import {
  AlertCircle,
  AlertTriangle,
  Ban,
  ChevronDown,
  ChevronRight,
  FileText,
  Printer,
  QrCode,
  RefreshCw,
  Search,
  X,
} from "lucide-react";
import { Fragment, useEffect, useMemo, useState } from "react";
import DanfePreviewModal from "@/components/Admin/DanfePreviewModal";
import FiscalErrorModal from "@/components/Admin/FiscalErrorModal";
import PageHeader from "@/components/Admin/PageHeader";
import ReceiptPreviewModal, { type SaleReceipt } from "@/components/Admin/ReceiptPreviewModal";
import RowActionsMenu from "@/components/Admin/RowActionsMenu";
import SaleCancelModal from "@/components/Admin/SaleCancelModal";
import TablePagination from "@/components/Pagination/TablePagination";
import { Toast } from "@/hooks/Dialog";
import useInputMasks from "@/hooks/InputMasks/useInputMasks";
import PageLayout from "@/layout/PageLayout";
import { companyService, type CompanyDto } from "@/services/api/companyService";
import {
  FISCAL_STATUS,
  fiscalService,
  fiscalStatusBadgeClass,
  fiscalStatusLabel,
  type FiscalDocumentDetailDto,
  type FiscalDocumentDto,
} from "@/services/api/fiscalService";
import { salesHistoryService, type SaleHistoryDto } from "@/services/api/salesHistoryService";
import { cashRegisterService, type CashMovementDto } from "@/services/api/cashRegisterService";
import { getLocalSalesHistory } from "@/application/sales/SaleOutboxAdapter";
import { getStoredAuthUser } from "@/utils/authStorage";
import {
  PAYMENT_GROUP_LABEL,
  buildPayments,
  isCancelledStatus,
  type PaymentGroup,
  type SalePayment,
} from "@/utils/salePayments";
import {
  buildDailySummaries,
  formatDateKey,
  parseDateParts,
  printDailyReport,
  type DailyMovement,
  type DailySale,
} from "@/utils/salesDailyReport";

type SaleHistoryRow = SaleHistoryDto;

function toDateKey(date: Date) {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function shiftDays(days: number) {
  const date = new Date();
  date.setDate(date.getDate() + days);
  return toDateKey(date);
}

const QUICK_RANGES: Array<{ label: string; range: () => [string, string] }> = [
  { label: "Hoje", range: () => [shiftDays(0), shiftDays(0)] },
  { label: "Ontem", range: () => [shiftDays(-1), shiftDays(-1)] },
  { label: "7 dias", range: () => [shiftDays(-6), shiftDays(0)] },
  { label: "30 dias", range: () => [shiftDays(-29), shiftDays(0)] },
  {
    label: "Este mês",
    range: () => {
      const now = new Date();
      return [toDateKey(new Date(now.getFullYear(), now.getMonth(), 1)), shiftDays(0)];
    },
  },
];

const PAYMENT_LABEL: Record<string, string> = {
  dinheiro: "Dinheiro",
  pix: "PIX",
  debito: "Cartão Débito",
  credito: "Cartão Crédito",
};

function toCompanyReceipt(company: CompanyDto | null): SaleReceipt["company"] {
  if (!company) return null;
  return {
    fantasyName: company.fantasyName,
    corporateName: company.corporateName,
    cnpj: company.cnpj,
    stateRegistration: company.stateRegistration,
    address: company.address,
    number: company.number,
    neighborhood: company.neighborhood,
    city: company.city,
    uf: company.uf,
    phone: company.phone,
    sacPhone: company.sacPhone,
    ambienteFiscal: company.ambienteFiscal,
  };
}

const PAYMENT_FILTER_OPTIONS: PaymentGroup[] = ["dinheiro", "cartao", "pix", "fiado", "outros"];

function splitSaleDate(value: string) {
  const [date = value, time = ""] = value.split(" ");
  return { date, time };
}

export default function SalesHistoryPage() {
  const { formatMoneyBr, parseMoneyBr } = useInputMasks();
  const [search, setSearch] = useState("");
  const [salesHistory, setSalesHistory] = useState<SaleHistoryRow[]>([]);
  const [company, setCompany] = useState<CompanyDto | null>(null);
  const [currentPage, setCurrentPage] = useState(1);
  const [itemsPerPage, setItemsPerPage] = useState(10);
  const [printingSaleNumbers, setPrintingSaleNumbers] = useState<Set<string>>(() => new Set());
  const [receiptPreview, setReceiptPreview] = useState<SaleReceipt | null>(null);
  const [fiscalBySale, setFiscalBySale] = useState<Map<string, FiscalDocumentDto>>(new Map());
  const [danfePreview, setDanfePreview] = useState<FiscalDocumentDetailDto | null>(null);
  const [errorFiscalModal, setErrorFiscalModal] = useState<FiscalDocumentDto | null>(null);
  const [loadingDanfeSaleNumber, setLoadingDanfeSaleNumber] = useState<string | null>(null);
  const [reemitindoIds, setReemitindoIds] = useState<Set<string>>(() => new Set());
  const [saleToCancel, setSaleToCancel] = useState<SaleHistoryRow | null>(null);
  const [statusFilter, setStatusFilter] = useState<"todas" | "finalizadas" | "canceladas">("todas");
  const [dateFrom, setDateFrom] = useState("");
  const [dateTo, setDateTo] = useState("");
  const [operatorFilter, setOperatorFilter] = useState("");
  const [paymentFilter, setPaymentFilter] = useState("");
  const [minValue, setMinValue] = useState("");
  const [maxValue, setMaxValue] = useState("");
  const [viewMode, setViewMode] = useState<"vendas" | "diario">("vendas");
  const [movements, setMovements] = useState<CashMovementDto[]>([]);
  const [expandedDays, setExpandedDays] = useState<Set<string>>(() => new Set());
  const [printMovements, setPrintMovements] = useState(true);

  const loadFiscalStatus = () => {
    fiscalService
      .list()
      .then((rows) => setFiscalBySale(new Map(rows.map((row) => [row.saleNumber, row]))))
      .catch(() => setFiscalBySale(new Map()));
  };

  const loadSalesHistory = async () => {
    try {
      const data = await salesHistoryService.list();
      if (Array.isArray(data) && data.length > 0) {
        setSalesHistory(data);
        return;
      }
    } catch {
      // Falha de rede / offline — consulta vendas locais no IndexedDB
    }

    try {
      const localData = await getLocalSalesHistory();
      setSalesHistory(localData);
    } catch {
      setSalesHistory([]);
    }
  };

  useEffect(() => {
    void loadSalesHistory();
    companyService.get().then((data) => setCompany(data ?? null)).catch(() => setCompany(null));
    loadFiscalStatus();
  }, []);

  const operatorOptions = useMemo(
    () =>
      [...new Set(salesHistory.map((sale) => sale.operatorName).filter(Boolean))].sort((a, b) =>
        a.localeCompare(b, "pt-BR"),
      ),
    [salesHistory],
  );

  const saleTotalOf = (sale: SaleHistoryRow) => parseMoneyBr(sale.totalAmount || "0,00");

  // Uma entrada por venda (o histórico vem uma linha por item): total, pagamentos com valor e se foi cancelada.
  const saleMeta = useMemo(() => {
    const itemsTotals = new Map<string, number>();
    for (const row of salesHistory) {
      itemsTotals.set(
        row.saleNumber,
        (itemsTotals.get(row.saleNumber) ?? 0) + (parseMoneyBr(row.itemTotal || "0,00") || 0),
      );
    }

    const map = new Map<string, { total: number; payments: SalePayment[]; cancelled: boolean }>();
    for (const row of salesHistory) {
      if (map.has(row.saleNumber)) continue;
      const total = parseMoneyBr(row.totalAmount || "0,00") || itemsTotals.get(row.saleNumber) || 0;
      map.set(row.saleNumber, {
        total,
        payments: buildPayments(row.paymentBreakdown, row.paymentType || "", total),
        cancelled: isCancelledStatus(row.status),
      });
    }
    return map;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [salesHistory]);

  const filteredSales = useMemo(() => {
    // Busca local por número da venda ou nome do cliente + filtros avançados (período, operador,
    // forma de pagamento, faixa de valor e status).
    const normalized = search.trim().toLowerCase();
    const min = minValue ? parseMoneyBr(minValue) : null;
    const max = maxValue ? parseMoneyBr(maxValue) : null;

    return salesHistory.filter((sale) => {
      const isCancelled = isCancelledStatus(sale.status);
      if (statusFilter === "finalizadas" && isCancelled) return false;
      if (statusFilter === "canceladas" && !isCancelled) return false;

      if (dateFrom || dateTo) {
        const dateKey = parseDateParts(sale.saleDate)?.dateKey;
        if (!dateKey) return false;
        if (dateFrom && dateKey < dateFrom) return false;
        if (dateTo && dateKey > dateTo) return false;
      }
      if (operatorFilter && sale.operatorName !== operatorFilter) return false;
      if (paymentFilter) {
        const meta = saleMeta.get(sale.saleNumber);
        if (!meta || !meta.payments.some((payment) => payment.group === paymentFilter)) return false;
      }
      if (min !== null || max !== null) {
        const total = saleTotalOf(sale);
        if (min !== null && total < min) return false;
        if (max !== null && total > max) return false;
      }

      if (!normalized) return true;
      return (
        sale.saleNumber.toLowerCase().includes(normalized) ||
        sale.customerName.toLowerCase().includes(normalized)
      );
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [salesHistory, saleMeta, search, statusFilter, dateFrom, dateTo, operatorFilter, paymentFilter, minValue, maxValue]);

  // Resumo do período filtrado (todas as páginas): acompanha período, operador, pagamento, valor e busca.
  // Venda com mais de uma forma de pagamento conta, no filtro de pagamento, só a parcela daquela forma.
  const periodSummary = useMemo(() => {
    const seen = new Set<string>();
    const byGroup: Record<PaymentGroup, number> = { dinheiro: 0, cartao: 0, pix: 0, fiado: 0, outros: 0 };
    let vendas = 0;
    let total = 0;
    let canceladas = 0;
    let totalCanceladas = 0;

    for (const row of filteredSales) {
      if (seen.has(row.saleNumber)) continue;
      seen.add(row.saleNumber);
      const meta = saleMeta.get(row.saleNumber);
      if (!meta) continue;

      const parcelas = meta.payments.filter((payment) => !paymentFilter || payment.group === paymentFilter);
      const valor = paymentFilter ? parcelas.reduce((sum, payment) => sum + payment.amount, 0) : meta.total;
      if (meta.cancelled) {
        canceladas += 1;
        totalCanceladas += valor;
        continue;
      }

      vendas += 1;
      total += valor;
      for (const payment of parcelas) byGroup[payment.group] += payment.amount;
    }

    return { vendas, total, ticketMedio: vendas > 0 ? total / vendas : 0, canceladas, totalCanceladas, byGroup };
  }, [filteredSales, saleMeta, paymentFilter]);

  const periodLabel =
    dateFrom || dateTo
      ? `${dateFrom ? formatDateKey(dateFrom) : "início"} a ${dateTo ? formatDateKey(dateTo) : "hoje"}`
      : "Todo o histórico";

  // Reinicia a paginação sempre que um filtro avançado muda.
  const advancedFilterKey = [dateFrom, dateTo, operatorFilter, paymentFilter, minValue, maxValue].join("|");
  const [appliedFilterKey, setAppliedFilterKey] = useState(advancedFilterKey);
  if (appliedFilterKey !== advancedFilterKey) {
    setAppliedFilterKey(advancedFilterKey);
    setCurrentPage(1);
  }

  const hasAdvancedFilters = Boolean(
    dateFrom || dateTo || operatorFilter || paymentFilter || minValue || maxValue,
  );

  const clearAdvancedFilters = () => {
    setDateFrom("");
    setDateTo("");
    setOperatorFilter("");
    setPaymentFilter("");
    setMinValue("");
    setMaxValue("");
    setCurrentPage(1);
  };

  // Intervalo usado para buscar sangrias/reforços: o filtro de datas ou, sem ele, o das vendas carregadas.
  const movementRange = useMemo(() => {
    const keys = salesHistory
      .map((sale) => parseDateParts(sale.saleDate)?.dateKey)
      .filter((key): key is string => Boolean(key))
      .sort();
    return {
      de: dateFrom || keys[0] || "",
      ate: dateTo || keys[keys.length - 1] || "",
    };
  }, [salesHistory, dateFrom, dateTo]);

  useEffect(() => {
    let cancelled = false;
    cashRegisterService
      .movimentos(movementRange.de || undefined, movementRange.ate || undefined)
      .then((rows) => {
        if (!cancelled) setMovements(rows);
      })
      .catch(() => {
        if (!cancelled) setMovements([]);
      });
    return () => {
      cancelled = true;
    };
  }, [movementRange.de, movementRange.ate]);

  const dailySummaries = useMemo(() => {
    // Uma entrada por venda (o histórico vem uma linha por item). Com filtro de pagamento, vale só a
    // parcela da venda naquela forma.
    const bySale = new Map<string, DailySale>();
    for (const row of filteredSales) {
      if (bySale.has(row.saleNumber)) continue;
      const parts = parseDateParts(row.saleDate);
      const meta = saleMeta.get(row.saleNumber);
      if (!parts || !meta) continue;

      const payments = meta.payments
        .filter((payment) => !paymentFilter || payment.group === paymentFilter)
        .map((payment) => ({ type: payment.type, amount: payment.amount }));
      bySale.set(row.saleNumber, {
        saleNumber: row.saleNumber,
        dateKey: parts.dateKey,
        time: parts.time,
        operatorName: row.operatorName,
        customerName: row.customerName,
        paymentType: row.paymentType || "",
        total: paymentFilter ? payments.reduce((sum, payment) => sum + payment.amount, 0) : meta.total,
        cancelled: meta.cancelled,
        payments,
      });
    }

    // Sangria/reforço é sempre dinheiro: some quando o filtro é outra forma de pagamento ou venda cancelada.
    const normalizedOperator = operatorFilter.trim().toLowerCase();
    const dailyMovements: DailyMovement[] =
      (paymentFilter && paymentFilter !== "dinheiro") || statusFilter === "canceladas"
        ? []
        : movements
            .map((movement): DailyMovement | null => {
              const parts = parseDateParts(movement.createdAt);
              if (!parts) return null;
              if (dateFrom && parts.dateKey < dateFrom) return null;
              if (dateTo && parts.dateKey > dateTo) return null;
              if (normalizedOperator && movement.operatorName.trim().toLowerCase() !== normalizedOperator) {
                return null;
              }
              return {
                id: movement.id,
                tipo: movement.tipo === "Sangria" ? "Sangria" : "Reforco",
                dateKey: parts.dateKey,
                time: parts.time,
                valor: parseMoneyBr(movement.valor || "0,00"),
                motivo: movement.motivo,
                operatorName: movement.operatorName,
              };
            })
            .filter((item): item is DailyMovement => item !== null);

    return buildDailySummaries([...bySale.values()], dailyMovements);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filteredSales, saleMeta, movements, dateFrom, dateTo, operatorFilter, paymentFilter, statusFilter]);

  const toggleDay = (dateKey: string) =>
    setExpandedDays((current) => {
      const next = new Set(current);
      if (next.has(dateKey)) next.delete(dateKey);
      else next.add(dateKey);
      return next;
    });

  const handlePrintDaily = () => {
    if (dailySummaries.length === 0) {
      Toast.error("Não há lançamentos no período para imprimir.");
      return;
    }
    const filters = [
      operatorFilter && `Operador: ${operatorFilter}`,
      paymentFilter && `Pgto: ${PAYMENT_GROUP_LABEL[paymentFilter as PaymentGroup] ?? paymentFilter}`,
      statusFilter !== "todas" && (statusFilter === "canceladas" ? "Somente canceladas" : "Somente concluidas"),
    ].filter(Boolean);
    const ok = printDailyReport(dailySummaries, {
      companyName: company?.fantasyName || company?.corporateName || "Quack PDV",
      periodLabel:
        movementRange.de && movementRange.ate
          ? `${formatDateKey(movementRange.de)} a ${formatDateKey(movementRange.ate)}`
          : "Todos",
      filtersLabel: filters.join(" | "),
      includeMovements: printMovements,
      formatMoney: formatMoneyBr,
      paymentLabel: (type) => PAYMENT_LABEL[type] || type || "-",
    });
    if (!ok) Toast.error("O navegador bloqueou a janela de impressão. Libere pop-ups para este site.");
  };

  const totalPages = Math.max(1, Math.ceil(filteredSales.length / itemsPerPage));
  const safeCurrentPage = Math.min(currentPage, totalPages);
  const paginatedSales = useMemo(() => {
    const start = (safeCurrentPage - 1) * itemsPerPage;
    return filteredSales.slice(start, start + itemsPerPage);
  }, [filteredSales, itemsPerPage, safeCurrentPage]);

  const getUnitPrice = (sale: SaleHistoryRow) => parseMoneyBr(sale.unitPrice || "0,00");
  const getItemTotal = (sale: SaleHistoryRow) => {
    const unitPrice = getUnitPrice(sale);
    return parseMoneyBr(sale.itemTotal || "0,00") || unitPrice * sale.quantity;
  };

  const toReceipt = (
    saleNumber: string,
    rows: SaleHistoryRow[],
    printedAt?: string,
  ): SaleReceipt | null => {
    if (rows.length === 0) return null;

    const [first] = rows;
    const items = rows.map((row, index) => {
      const unitPrice = parseMoneyBr(row.unitPrice || "0,00");
      const itemTotal = parseMoneyBr(row.itemTotal || "0,00") || unitPrice * row.quantity;
      return {
        id: `${row.saleNumber}-${row.productCode}-${index}`,
        code: row.productCode,
        name: row.productName,
        quantity: row.quantity,
        unitPrice,
        total: itemTotal,
      };
    });
    const itemsSubtotal = items.reduce((sum, item) => sum + item.total, 0);
    const receiptTotal = parseMoneyBr(first.totalAmount || "0,00") || itemsSubtotal;
    const paymentType = first.paymentType || "-";
    const storedUser = getStoredAuthUser();

    return {
      saleNumber,
      issuedAt: first.saleDate,
      printedAt,
      company: toCompanyReceipt(company),
      customerCpf: first.customerCpf || "-",
      paymentType,
      paymentLabel: PAYMENT_LABEL[paymentType] || paymentType || "-",
      operatorName: first.operatorName || storedUser?.name || "Operador",
      subtotal: receiptTotal,
      cashGiven: paymentType === "dinheiro" ? receiptTotal : 0,
      change: 0,
      items,
    };
  };

  const openPrintPreview = async (sale: SaleHistoryRow) => {
    setPrintingSaleNumbers((current) => new Set(current).add(sale.saleNumber));
    try {
      const [result, fiscalDetail] = await Promise.all([
        salesHistoryService.print(sale.saleNumber),
        fiscalService.getBySaleNumber(sale.saleNumber).catch(() => null),
      ]);
      const rows =
        result?.rows && result.rows.length > 0
          ? result.rows
          : salesHistory.filter((item) => item.saleNumber === sale.saleNumber);
      const receipt = toReceipt(sale.saleNumber, rows, result?.printedAt);
      if (!receipt) {
        Toast.error("Venda não encontrada para impressão.");
        return;
      }
      if (
        fiscalDetail &&
        (fiscalDetail.status === FISCAL_STATUS.Autorizado ||
          fiscalDetail.status === FISCAL_STATUS.ContingenciaPendente)
      ) {
        receipt.fiscalDetail = fiscalDetail;
      }
      setReceiptPreview(receipt);
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao preparar impressão.");
    } finally {
      setPrintingSaleNumbers((current) => {
        const next = new Set(current);
        next.delete(sale.saleNumber);
        return next;
      });
    }
  };

  const openDanfe = async (saleNumber: string) => {
    setLoadingDanfeSaleNumber(saleNumber);
    try {
      const detail = await fiscalService.getBySaleNumber(saleNumber);
      if (!detail) {
        Toast.error("NFC-e ainda não foi enfileirada para esta venda.");
        return;
      }
      setDanfePreview(detail);
    } finally {
      setLoadingDanfeSaleNumber(null);
    }
  };

  const reemitirNfce = async (fiscal: FiscalDocumentDto) => {
    setReemitindoIds((current) => new Set(current).add(fiscal.id));
    try {
      await fiscalService.reemitir(fiscal.id);
      Toast.success("NFC-e reenfileirada para nova tentativa de emissão.");
      loadFiscalStatus();
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao reenfileirar NFC-e.");
    } finally {
      setReemitindoIds((current) => {
        const next = new Set(current);
        next.delete(fiscal.id);
        return next;
      });
    }
  };

  return (
    <PageLayout className="space-y-4 py-4 md:space-y-6 md:py-6 lg:py-8">
      <PageHeader
        title="Histórico de Vendas"
        description="Consulta de vendas com detalhes por cliente e itens vendidos."
      />

      <section className="card p-4 md:p-5 space-y-3">
        <div className="flex flex-col sm:flex-row items-center justify-between gap-3">
          <label className="relative block w-full sm:max-w-md">
            <Search
              size={16}
              className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-text-tertiary"
            />
            <input
              value={search}
              onChange={(event) => {
                setSearch(event.target.value);
                setCurrentPage(1);
              }}
              className="input-field w-full pl-9"
              placeholder="Pesquise pelo número da venda ou cliente"
            />
          </label>

          <div className="flex items-center gap-1.5 self-stretch sm:self-auto rounded-lg bg-bg-primary p-1 border border-border-primary">
            <button
              type="button"
              onClick={() => {
                setStatusFilter("todas");
                setCurrentPage(1);
              }}
              className={`rounded-md px-3 py-1.5 text-xs font-semibold transition-all ${
                statusFilter === "todas"
                  ? "bg-bg-card text-brand-primary shadow-xs"
                  : "text-text-secondary hover:text-text-primary"
              }`}
            >
              Todas ({salesHistory.length})
            </button>
            <button
              type="button"
              onClick={() => {
                setStatusFilter("finalizadas");
                setCurrentPage(1);
              }}
              className={`rounded-md px-3 py-1.5 text-xs font-semibold transition-all ${
                statusFilter === "finalizadas"
                  ? "bg-bg-card text-emerald-500 shadow-xs"
                  : "text-text-secondary hover:text-text-primary"
              }`}
            >
              Concluídas ({salesHistory.filter((s) => s.status?.toLowerCase() !== "cancelada").length})
            </button>
            <button
              type="button"
              onClick={() => {
                setStatusFilter("canceladas");
                setCurrentPage(1);
              }}
              className={`rounded-md px-3 py-1.5 text-xs font-semibold transition-all ${
                statusFilter === "canceladas"
                  ? "bg-bg-card text-rose-500 shadow-xs"
                  : "text-text-secondary hover:text-text-primary"
              }`}
            >
              Canceladas ({salesHistory.filter((s) => s.status?.toLowerCase() === "cancelada").length})
            </button>
          </div>
        </div>

        {/* Filtros avançados */}
        <div className="border-t border-border-primary pt-3 space-y-3">
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="text-xs font-semibold text-text-secondary mr-1">Período:</span>
            {QUICK_RANGES.map((quick) => (
              <button
                key={quick.label}
                type="button"
                onClick={() => {
                  const [from, to] = quick.range();
                  setDateFrom(from);
                  setDateTo(to);
                }}
                className="rounded-md border border-border-primary px-2.5 py-1 text-xs font-medium text-text-secondary hover:bg-hover-light hover:text-text-primary"
              >
                {quick.label}
              </button>
            ))}
          </div>

          <div className="grid grid-cols-2 gap-3 md:grid-cols-3 lg:grid-cols-6">
            <label className="block text-xs text-text-secondary">
              De
              <input
                type="date"
                value={dateFrom}
                max={dateTo || undefined}
                onChange={(event) => setDateFrom(event.target.value)}
                className="input-field mt-1 w-full py-1.5 text-xs"
              />
            </label>
            <label className="block text-xs text-text-secondary">
              Até
              <input
                type="date"
                value={dateTo}
                min={dateFrom || undefined}
                onChange={(event) => setDateTo(event.target.value)}
                className="input-field mt-1 w-full py-1.5 text-xs"
              />
            </label>
            <label className="block text-xs text-text-secondary">
              Operador
              <select
                value={operatorFilter}
                onChange={(event) => setOperatorFilter(event.target.value)}
                className="input-field mt-1 w-full py-1.5 text-xs"
              >
                <option value="">Todos</option>
                {operatorOptions.map((name) => (
                  <option key={name} value={name}>
                    {name}
                  </option>
                ))}
              </select>
            </label>
            <label className="block text-xs text-text-secondary">
              Forma de pagamento
              <select
                value={paymentFilter}
                onChange={(event) => setPaymentFilter(event.target.value)}
                className="input-field mt-1 w-full py-1.5 text-xs"
              >
                <option value="">Todas</option>
                {PAYMENT_FILTER_OPTIONS.map((group) => (
                  <option key={group} value={group}>
                    {PAYMENT_GROUP_LABEL[group]}
                  </option>
                ))}
              </select>
            </label>
            <label className="block text-xs text-text-secondary">
              Valor mínimo (R$)
              <input
                inputMode="decimal"
                value={minValue}
                onChange={(event) => setMinValue(event.target.value)}
                placeholder="0,00"
                className="input-field mt-1 w-full py-1.5 text-xs"
              />
            </label>
            <label className="block text-xs text-text-secondary">
              Valor máximo (R$)
              <input
                inputMode="decimal"
                value={maxValue}
                onChange={(event) => setMaxValue(event.target.value)}
                placeholder="0,00"
                className="input-field mt-1 w-full py-1.5 text-xs"
              />
            </label>
          </div>

          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-1.5 rounded-lg bg-bg-primary p-1 border border-border-primary">
              {(
                [
                  ["vendas", "Vendas"],
                  ["diario", "Detalhamento diário"],
                ] as const
              ).map(([mode, label]) => (
                <button
                  key={mode}
                  type="button"
                  onClick={() => setViewMode(mode)}
                  className={`rounded-md px-3 py-1.5 text-xs font-semibold transition-all ${
                    viewMode === mode
                      ? "bg-bg-card text-brand-primary shadow-xs"
                      : "text-text-secondary hover:text-text-primary"
                  }`}
                >
                  {label}
                </button>
              ))}
            </div>
            {hasAdvancedFilters ? (
              <button
                type="button"
                onClick={clearAdvancedFilters}
                className="inline-flex items-center gap-1 text-xs font-medium text-text-secondary hover:text-text-primary"
              >
                <X size={13} /> Limpar filtros
              </button>
            ) : null}
          </div>
        </div>
      </section>

      <section className="card space-y-3 p-4 md:p-5" aria-label="Resumo do período">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-sm font-semibold text-text-primary">Resumo do período</h2>
          <span className="text-xs text-text-secondary">
            {periodLabel}
            {paymentFilter ? ` · ${PAYMENT_GROUP_LABEL[paymentFilter as PaymentGroup] ?? paymentFilter}` : ""}
          </span>
        </div>

        <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
          <div className="rounded-xl border border-emerald-500/30 bg-emerald-500/10 p-3">
            <span className="block text-[11px] text-text-secondary">
              {paymentFilter ? `Total em ${PAYMENT_GROUP_LABEL[paymentFilter as PaymentGroup]}` : "Total vendido"}
            </span>
            <strong className="mt-1 block font-mono text-lg text-emerald-600 dark:text-emerald-400">
              R$ {formatMoneyBr(periodSummary.total)}
            </strong>
          </div>
          <div className="rounded-xl border border-border-primary bg-bg-primary/60 p-3">
            <span className="block text-[11px] text-text-secondary">Vendas concluídas</span>
            <strong className="mt-1 block text-lg text-text-primary">{periodSummary.vendas}</strong>
          </div>
          <div className="rounded-xl border border-border-primary bg-bg-primary/60 p-3">
            <span className="block text-[11px] text-text-secondary">Ticket médio</span>
            <strong className="mt-1 block font-mono text-lg text-text-primary">
              R$ {formatMoneyBr(periodSummary.ticketMedio)}
            </strong>
          </div>
          <div className="rounded-xl border border-rose-500/30 bg-rose-500/5 p-3">
            <span className="block text-[11px] text-text-secondary">Canceladas (fora do total)</span>
            <strong className="mt-1 block text-lg text-rose-600 dark:text-rose-400">
              {periodSummary.canceladas}
              {periodSummary.canceladas > 0 ? (
                <span className="ml-1.5 font-mono text-xs font-medium">R$ {formatMoneyBr(periodSummary.totalCanceladas)}</span>
              ) : null}
            </strong>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2 text-xs">
          <span className="font-semibold text-text-secondary">Por forma de pagamento:</span>
          {PAYMENT_FILTER_OPTIONS.map((group) => {
            const value = periodSummary.byGroup[group];
            const active = paymentFilter === group;
            if (group === "outros" && value === 0 && !active) return null;
            return (
              <button
                key={group}
                type="button"
                onClick={() => setPaymentFilter(active ? "" : group)}
                title={active ? "Clique para remover o filtro" : `Filtrar somente ${PAYMENT_GROUP_LABEL[group]}`}
                className={`rounded-lg border px-2.5 py-1 font-semibold transition ${
                  active
                    ? "border-brand-primary bg-brand-primary/10 text-brand-primary"
                    : "border-border-primary text-text-secondary hover:bg-hover-light hover:text-text-primary"
                }`}
              >
                {PAYMENT_GROUP_LABEL[group]}{" "}
                <span className="font-mono text-text-primary">R$ {formatMoneyBr(value)}</span>
              </button>
            );
          })}
        </div>
      </section>

      {viewMode === "diario" ? (
        <section className="card overflow-hidden">
          <div className="flex flex-wrap items-center justify-between gap-3 border-b border-border-primary px-4 py-3">
            <label className="inline-flex cursor-pointer items-center gap-2 text-xs text-text-secondary">
              <input
                type="checkbox"
                checked={printMovements}
                onChange={(event) => setPrintMovements(event.target.checked)}
              />
              Imprimir sangrias e reforços como lançamentos de venda
            </label>
            <button
              type="button"
              onClick={handlePrintDaily}
              className="btn-primary inline-flex items-center gap-2 text-xs"
            >
              <Printer size={14} /> Imprimir detalhamento
            </button>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[900px] text-sm">
              <thead className="bg-bg-primary text-left text-text-secondary">
                <tr>
                  <th className="w-8 px-3 py-3" />
                  <th className="px-3 py-3">Data</th>
                  <th className="px-3 py-3 text-center">Vendas</th>
                  <th className="px-3 py-3 text-center">Canc.</th>
                  <th className="px-3 py-3 text-right">Total vendido</th>
                  <th className="px-3 py-3 text-right">Dinheiro</th>
                  <th className="px-3 py-3 text-right">Reforços</th>
                  <th className="px-3 py-3 text-right">Sangrias</th>
                  <th className="px-3 py-3 text-right">Saldo dinheiro</th>
                </tr>
              </thead>
              <tbody>
                {dailySummaries.length === 0 ? (
                  <tr>
                    <td colSpan={9} className="px-3 py-8 text-center text-text-tertiary">
                      Nenhum lançamento para os filtros selecionados.
                    </td>
                  </tr>
                ) : null}
                {dailySummaries.map((day) => {
                  const expanded = expandedDays.has(day.dateKey);
                  return (
                    <Fragment key={day.dateKey}>
                      <tr
                        className="cursor-pointer border-t border-border-primary hover:bg-hover-light"
                        onClick={() => toggleDay(day.dateKey)}
                      >
                        <td className="px-3 py-3 text-text-secondary">
                          {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                        </td>
                        <td className="px-3 py-3 font-semibold text-text-primary">{formatDateKey(day.dateKey)}</td>
                        <td className="px-3 py-3 text-center tabular-nums">{day.salesCount}</td>
                        <td className="px-3 py-3 text-center tabular-nums text-rose-500">{day.cancelledCount}</td>
                        <td className="px-3 py-3 text-right font-semibold">R$ {formatMoneyBr(day.totalSold)}</td>
                        <td className="px-3 py-3 text-right">R$ {formatMoneyBr(day.byPayment.dinheiro ?? 0)}</td>
                        <td className="px-3 py-3 text-right text-emerald-500">+ R$ {formatMoneyBr(day.reforcos)}</td>
                        <td className="px-3 py-3 text-right text-rose-500">- R$ {formatMoneyBr(day.sangrias)}</td>
                        <td className="px-3 py-3 text-right font-bold text-text-primary">
                          R$ {formatMoneyBr(day.netCash)}
                        </td>
                      </tr>
                      {expanded ? (
                        <tr className="border-t border-border-primary bg-bg-primary/60">
                          <td />
                          <td colSpan={8} className="px-3 py-3">
                            <div className="mb-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-text-secondary">
                              {Object.entries(day.byPayment).map(([type, total]) => (
                                <span key={type}>
                                  {PAYMENT_LABEL[type] || type}:{" "}
                                  <strong className="text-text-primary">R$ {formatMoneyBr(total)}</strong>
                                </span>
                              ))}
                            </div>
                            <ul className="divide-y divide-border-primary text-xs">
                              {[
                                ...day.sales.map((sale) => ({ time: sale.time, kind: "sale" as const, sale })),
                                ...day.movements.map((movement) => ({
                                  time: movement.time,
                                  kind: "movement" as const,
                                  movement,
                                })),
                              ]
                                .sort((a, b) => a.time.localeCompare(b.time))
                                .map((entry) =>
                                  entry.kind === "sale" ? (
                                    <li key={`s-${entry.sale.saleNumber}`} className="flex items-center justify-between gap-3 py-1.5">
                                      <span className={entry.sale.cancelled ? "line-through text-text-tertiary" : ""}>
                                        {entry.time} · Venda {entry.sale.saleNumber} ·{" "}
                                        {PAYMENT_LABEL[entry.sale.paymentType.toLowerCase()] || entry.sale.paymentType} ·{" "}
                                        {entry.sale.operatorName || "-"}
                                      </span>
                                      <span className="font-semibold tabular-nums">
                                        R$ {formatMoneyBr(entry.sale.total)}
                                      </span>
                                    </li>
                                  ) : (
                                    <li key={`m-${entry.movement.id}`} className="flex items-center justify-between gap-3 py-1.5">
                                      <span>
                                        {entry.time} ·{" "}
                                        <strong className={entry.movement.tipo === "Sangria" ? "text-rose-500" : "text-emerald-500"}>
                                          {entry.movement.tipo === "Sangria" ? "Sangria" : "Reforço"}
                                        </strong>{" "}
                                        · {entry.movement.motivo || "-"} · {entry.movement.operatorName || "-"}
                                      </span>
                                      <span
                                        className={`font-semibold tabular-nums ${
                                          entry.movement.tipo === "Sangria" ? "text-rose-500" : "text-emerald-500"
                                        }`}
                                      >
                                        {entry.movement.tipo === "Sangria" ? "-" : "+"} R$ {formatMoneyBr(entry.movement.valor)}
                                      </span>
                                    </li>
                                  ),
                                )}
                            </ul>
                          </td>
                        </tr>
                      ) : null}
                    </Fragment>
                  );
                })}
              </tbody>
            </table>
          </div>
        </section>
      ) : (
      <section className="card overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[980px] table-fixed text-sm">
            <thead className="bg-bg-primary text-left text-text-secondary">
              <tr>
                <th className="w-[8%] px-3 py-3">Venda</th>
                <th className="w-[13%] px-3 py-3">Cliente</th>
                <th className="w-[10%] px-3 py-3">CPF</th>
                <th className="w-[16%] px-3 py-3">Cód. Produto</th>
                <th className="w-[16%] px-3 py-3">Produto</th>
                <th className="w-[5%] px-3 py-3 text-center">QNT</th>
                <th className="w-[10%] px-3 py-3 text-right">Vl. Unit.</th>
                <th className="w-[9%] px-3 py-3 text-right">Vl. Total</th>
                <th className="w-[9%] px-3 py-3">Data</th>
                <th className="w-[8%] px-3 py-3">Fiscal</th>
                <th className="w-[4%] px-3 py-3 text-center">Ações</th>
              </tr>
            </thead>
            <tbody>
              {paginatedSales.map((sale) => {
                const fiscal = fiscalBySale.get(sale.saleNumber);
                const isCancelled = sale.status?.toLowerCase() === "cancelada";
                return (
                <tr
                  key={`${sale.saleNumber}-${sale.productCode}`}
                  className={`border-t border-border-primary transition-colors ${
                    isCancelled ? "bg-rose-500/[0.04] dark:bg-rose-500/[0.07]" : ""
                  }`}
                >
                  <td className="px-3 py-3 font-semibold text-text-primary">
                    <div className="flex flex-col gap-1">
                      <span className={isCancelled ? "line-through text-text-tertiary" : ""}>
                        {sale.saleNumber}
                      </span>
                      {isCancelled && (
                        <span
                          className="inline-flex items-center gap-1 rounded bg-rose-500/10 px-1.5 py-0.5 text-[10px] font-bold text-rose-600 dark:text-rose-400 border border-rose-500/20 w-fit"
                          title={`Cancelada por ${sale.canceladoPorSupervisorNome || "Supervisor"}${
                            sale.canceladoJustificativa ? `: ${sale.canceladoJustificativa}` : ""
                          }`}
                        >
                          <Ban size={10} /> Cancelada
                        </span>
                      )}
                    </div>
                  </td>
                  <td className="px-3 py-3">
                    <span
                      className={`block break-words leading-snug ${isCancelled ? "line-through text-text-tertiary" : ""}`}
                      title={sale.customerName}
                    >
                      {sale.customerName}
                    </span>
                  </td>
                  <td className="px-3 py-3 break-words text-text-secondary">{sale.customerCpf}</td>
                  <td className="px-3 py-3">
                    <span className="block break-all font-medium leading-snug text-text-primary" title={sale.productCode}>
                      {sale.productCode}
                    </span>
                  </td>
                  <td className="px-3 py-3">
                    <span className="block break-words leading-snug" title={sale.productName}>
                      {sale.productName}
                    </span>
                  </td>
                  <td className="px-3 py-3 text-center tabular-nums">{sale.quantity}</td>
                  <td className="px-3 py-3 text-right font-medium text-text-primary">
                    R$ {formatMoneyBr(getUnitPrice(sale))}
                  </td>
                  <td className="px-3 py-3 text-right font-semibold text-text-primary">
                    <span className={isCancelled ? "line-through text-text-tertiary" : ""}>
                      R$ {formatMoneyBr(getItemTotal(sale))}
                    </span>
                  </td>
                  <td className="px-3 py-3">
                    <span className="block whitespace-nowrap">{splitSaleDate(sale.saleDate).date}</span>
                    <span className="block whitespace-nowrap text-xs text-text-secondary">
                      {splitSaleDate(sale.saleDate).time}
                    </span>
                  </td>
                  <td className="px-3 py-3">
                    {fiscal ? (
                      fiscal.status === FISCAL_STATUS.Rejeitado || fiscal.motivoStatus ? (
                        <button
                          type="button"
                          onClick={() => setErrorFiscalModal(fiscal)}
                          className={`inline-flex items-center gap-1 rounded-full px-2 py-1 text-[11px] font-semibold cursor-pointer transition-all hover:scale-105 ${fiscalStatusBadgeClass(fiscal.status)}`}
                          title="Clique para ver o motivo do erro da SEFAZ"
                        >
                          <AlertCircle size={10} className="shrink-0" />
                          {fiscalStatusLabel(fiscal.status)}
                        </button>
                      ) : (
                        <span
                          className={`inline-flex rounded-full px-2 py-1 text-[11px] font-semibold ${fiscalStatusBadgeClass(fiscal.status)}`}
                        >
                          {fiscalStatusLabel(fiscal.status)}
                        </span>
                      )
                    ) : (
                      <span className="text-xs text-text-tertiary">—</span>
                    )}
                  </td>
                  <td className="px-3 py-3 text-center">
                    <RowActionsMenu
                      items={[
                        {
                          key: "print",
                          label: "Imprimir venda",
                          icon: <FileText size={13} />,
                          loading: printingSaleNumbers.has(sale.saleNumber),
                          loadingLabel: "Preparando...",
                          onClick: () => openPrintPreview(sale),
                        },
                        {
                          key: "danfe",
                          label: "Ver DANFE",
                          icon: <QrCode size={13} />,
                          disabled: !fiscal,
                          loading: loadingDanfeSaleNumber === sale.saleNumber,
                          loadingLabel: "Carregando...",
                          onClick: () => openDanfe(sale.saleNumber),
                        },
                        {
                          key: "cancelSale",
                          label: isCancelled ? "Venda Cancelada" : "Cancelar Venda (Supervisor)",
                          icon: <Ban size={13} className={isCancelled ? "text-text-tertiary" : "text-rose-500"} />,
                          disabled: isCancelled,
                          onClick: () => setSaleToCancel(sale),
                        },
                        ...(fiscal && (fiscal.status === FISCAL_STATUS.Rejeitado || fiscal.motivoStatus)
                          ? [
                              {
                                key: "fiscalError",
                                label: "Ver motivo do erro fiscal",
                                icon: <AlertTriangle size={13} />,
                                onClick: () => setErrorFiscalModal(fiscal),
                              },
                            ]
                          : []),
                        ...(fiscal && fiscal.status === FISCAL_STATUS.Rejeitado
                          ? [
                              {
                                key: "reemitir",
                                label: "Reemitir NFC-e",
                                icon: <RefreshCw size={13} />,
                                loading: reemitindoIds.has(fiscal.id),
                                loadingLabel: "Reenfileirando...",
                                onClick: () => reemitirNfce(fiscal),
                              },
                            ]
                          : []),
                      ]}
                    />
                  </td>
                </tr>
              );})}
            </tbody>
          </table>
        </div>
        <div className="px-4 py-4">
          <TablePagination
            totalItems={filteredSales.length}
            currentPage={safeCurrentPage}
            itemsPerPage={itemsPerPage}
            onPageChange={setCurrentPage}
            onItemsPerPageChange={(value) => {
              setItemsPerPage(value);
              setCurrentPage(1);
            }}
          />
        </div>
      </section>
      )}

      {receiptPreview ? (
        <ReceiptPreviewModal
          receipt={receiptPreview}
          formatMoney={formatMoneyBr}
          onClose={() => setReceiptPreview(null)}
        />
      ) : null}

      {danfePreview ? (
        <DanfePreviewModal
          detail={danfePreview}
          companyName={company?.fantasyName || company?.corporateName || "Quack PDV"}
          onClose={() => setDanfePreview(null)}
          onPrintDanfe={(detail) => {
            const sale = salesHistory.find((s) => s.saleNumber === detail.saleNumber);
            if (sale) {
              setDanfePreview(null);
              void openPrintPreview(sale);
            }
          }}
        />
      ) : null}

      {errorFiscalModal ? (
        <FiscalErrorModal
          document={errorFiscalModal}
          onClose={() => setErrorFiscalModal(null)}
          onReemitir={async (doc) => {
            await reemitirNfce(doc);
            setErrorFiscalModal(null);
          }}
          isReemitindo={reemitindoIds.has(errorFiscalModal.id)}
        />
      ) : null}

      {saleToCancel ? (
        <SaleCancelModal
          isOpen={Boolean(saleToCancel)}
          saleNumber={saleToCancel.saleNumber}
          customerName={saleToCancel.customerName}
          totalAmount={saleToCancel.totalAmount || formatMoneyBr(getItemTotal(saleToCancel))}
          paymentType={saleToCancel.paymentType}
          saleDate={saleToCancel.saleDate}
          onClose={() => setSaleToCancel(null)}
          onSuccess={() => {
            setSaleToCancel(null);
            void loadSalesHistory();
            loadFiscalStatus();
          }}
        />
      ) : null}
    </PageLayout>
  );
}
