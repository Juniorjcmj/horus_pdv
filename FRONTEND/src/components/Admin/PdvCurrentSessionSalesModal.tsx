/**
 * Arquivo: src/components/Admin/PdvCurrentSessionSalesModal.tsx
 * Objetivo: exibe a listagem de todas as vendas do caixa/turno atual do operador no PDV,
 *           com filtros rápidos, resumo financeiro e acesso ao detalhamento de cada venda.
 */
import { useEffect, useMemo, useState } from "react";
import {
  X,
  Search,
  RefreshCw,
  ShoppingBag,
  Clock,
  ArrowRight,
  ShieldCheck,
  AlertOctagon,
  RotateCcw,
  DollarSign,
  Receipt,
  User,
} from "lucide-react";
import {
  salesHistoryService,
  type SaleHistoryDto,
} from "@/services/api/salesHistoryService";
import { FISCAL_STATUS } from "@/services/api/fiscalService";
import { formatNumeroNf } from "@/utils/danfePrint";
import { getLocalSalesHistory } from "@/application/sales/SaleOutboxAdapter";

type PdvCurrentSessionSalesModalProps = {
  isOpen: boolean;
  onClose: () => void;
  cashSessionOpenedAt?: string | null;
  operatorName?: string;
  onSelectSale: (saleNumber: string) => void;
};

type GroupedSale = {
  saleNumber: string;
  customerName: string;
  customerCpf: string;
  paymentType: string;
  totalAmount: string;
  totalAmountNum: number;
  operatorName: string;
  saleDate: string;
  itemsCount: number;
  fiscalDocId?: string | null;
  fiscalModelo?: number | null;
  fiscalNumeroNf?: number | null;
  fiscalSerie?: number | null;
  fiscalStatus?: number | null;
  fiscalChaveAcesso?: string | null;
};

function parseMoney(val?: string | null): number {
  if (!val) return 0;
  const clean = val.replace("R$", "").replace(/\./g, "").replace(",", ".").trim();
  const n = parseFloat(clean);
  return isNaN(n) ? 0 : n;
}

function formatCurrency(num: number): string {
  return num.toLocaleString("pt-BR", { style: "currency", currency: "BRL" });
}

export default function PdvCurrentSessionSalesModal({
  isOpen,
  onClose,
  cashSessionOpenedAt,
  operatorName,
  onSelectSale,
}: PdvCurrentSessionSalesModalProps) {
  const [loading, setLoading] = useState(false);
  const [salesRows, setSalesRows] = useState<SaleHistoryDto[]>([]);
  const [searchTerm, setSearchTerm] = useState("");
  const [paymentFilter, setPaymentFilter] = useState("all");
  const [scopeFilter, setScopeFilter] = useState<"session" | "all">("session");

  const loadSales = async (overrideScope?: "session" | "all") => {
    setLoading(true);
    const activeScope = overrideScope || scopeFilter;
    try {
      let rows: SaleHistoryDto[] = [];
      const filterDate = activeScope === "session" ? cashSessionOpenedAt : undefined;

      try {
        rows = await salesHistoryService.list(filterDate || undefined);
      } catch (err) {
        console.warn("Falha ao buscar vendas na API com filtro:", err);
      }

      // Se filtrou por sessão mas não encontrou nada na API, tenta buscar todas da API
      // para evitar que o operador fique sem visualização por desencontro de horário
      if ((!rows || rows.length === 0) && activeScope === "session") {
        try {
          const allRows = await salesHistoryService.list();
          if (allRows && allRows.length > 0) {
            rows = allRows;
          }
        } catch (err) {
          console.warn("Falha ao buscar todas as vendas da API:", err);
        }
      }

      // Carrega também as vendas locais do Dexie (IndexedDB)
      try {
        const localRows = await getLocalSalesHistory();
        if (localRows && localRows.length > 0) {
          const seenNumbers = new Set(rows.map((r) => r.saleNumber || r.clientSaleId || r.offlineReference));
          for (const lr of localRows) {
            const key = lr.saleNumber || lr.clientSaleId || lr.offlineReference;
            if (key && !seenNumbers.has(key)) {
              rows.push(lr);
              seenNumbers.add(key);
            }
          }
        }
      } catch (err) {
        console.warn("Falha ao ler vendas locais do Dexie:", err);
      }

      setSalesRows(rows || []);
    } catch (e) {
      console.error("Erro ao carregar vendas:", e);
      setSalesRows([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (!isOpen) return;
    loadSales();
  }, [isOpen, cashSessionOpenedAt, scopeFilter]);

  // Fechar com Escape
  useEffect(() => {
    if (!isOpen) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        onClose();
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [isOpen, onClose]);

  // Agrupa os itens por número da venda
  const groupedSales = useMemo(() => {
    const map = new Map<string, GroupedSale>();

    for (const row of salesRows) {
      if (!row.saleNumber) continue;

      if (!map.has(row.saleNumber)) {
        const totalNum = parseMoney(row.totalAmount);
        map.set(row.saleNumber, {
          saleNumber: row.saleNumber,
          customerName: row.customerName || "Consumidor Final",
          customerCpf: row.customerCpf || "-",
          paymentType: row.paymentType || "Dinheiro",
          totalAmount: row.totalAmount,
          totalAmountNum: totalNum,
          operatorName: row.operatorName || "",
          saleDate: row.saleDate || "",
          itemsCount: 1,
          fiscalDocId: row.fiscalDocId,
          fiscalModelo: row.fiscalModelo,
          fiscalNumeroNf: row.fiscalNumeroNf,
          fiscalSerie: row.fiscalSerie,
          fiscalStatus: row.fiscalStatus,
          fiscalChaveAcesso: row.fiscalChaveAcesso,
        });
      } else {
        const existing = map.get(row.saleNumber)!;
        existing.itemsCount += 1;
        // Se a linha tiver dados fiscais atualizados, preenche
        if (!existing.fiscalDocId && row.fiscalDocId) {
          existing.fiscalDocId = row.fiscalDocId;
          existing.fiscalModelo = row.fiscalModelo;
          existing.fiscalNumeroNf = row.fiscalNumeroNf;
          existing.fiscalSerie = row.fiscalSerie;
          existing.fiscalStatus = row.fiscalStatus;
          existing.fiscalChaveAcesso = row.fiscalChaveAcesso;
        }
      }
    }

    return Array.from(map.values());
  }, [salesRows]);

  // Filtra por termo de busca e pagamento
  const filteredSales = useMemo(() => {
    const term = searchTerm.toLowerCase().trim();

    return groupedSales.filter((sale) => {
      const matchSearch =
        !term ||
        sale.saleNumber.toLowerCase().includes(term) ||
        sale.customerName.toLowerCase().includes(term) ||
        sale.customerCpf.toLowerCase().includes(term) ||
        sale.paymentType.toLowerCase().includes(term) ||
        (sale.fiscalNumeroNf && String(sale.fiscalNumeroNf).includes(term)) ||
        (sale.fiscalChaveAcesso && sale.fiscalChaveAcesso.includes(term));

      const matchPayment =
        paymentFilter === "all" ||
        sale.paymentType.toLowerCase().includes(paymentFilter.toLowerCase());

      return matchSearch && matchPayment;
    });
  }, [groupedSales, searchTerm, paymentFilter]);

  // Resumo de totais
  const summary = useMemo(() => {
    const totalVendas = groupedSales.length;
    const totalValor = groupedSales.reduce((acc, s) => acc + s.totalAmountNum, 0);
    const totalAutorizadas = groupedSales.filter(
      (s) => s.fiscalStatus === FISCAL_STATUS.Autorizado
    ).length;
    return { totalVendas, totalValor, totalAutorizadas };
  }, [groupedSales]);

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-3 backdrop-blur-xs animate-in fade-in duration-150 sm:p-4">
      <div className="relative flex max-h-[92vh] w-full max-w-4xl flex-col rounded-2xl border border-border-primary bg-bg-primary shadow-2xl text-text-primary overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-border-primary bg-bg-secondary px-5 py-4">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-accent/15 text-accent">
              <Receipt size={22} />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h2 className="text-lg font-bold text-text-primary">
                  Vendas do Caixa Atual
                </h2>
                <span className="rounded-full bg-accent/15 px-2.5 py-0.5 text-xs font-semibold text-accent">
                  {summary.totalVendas} venda{summary.totalVendas !== 1 ? "s" : ""}
                </span>
              </div>
              <p className="text-xs text-text-secondary flex items-center gap-2 mt-0.5">
                <Clock size={12} />
                <span>
                  {cashSessionOpenedAt
                    ? `Turno aberto em: ${new Date(cashSessionOpenedAt).toLocaleTimeString("pt-BR", { hour: "2-digit", minute: "2-digit" })}`
                    : "Turno atual"}
                </span>
                {operatorName && (
                  <>
                    <span>•</span>
                    <span>Operador: <strong>{operatorName}</strong></span>
                  </>
                )}
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => loadSales()}
              disabled={loading}
              className="rounded-xl border border-border-primary bg-bg-primary p-2 text-text-secondary hover:text-text-primary hover:bg-bg-tertiary transition active:scale-95 disabled:opacity-50"
              title="Atualizar lista de vendas"
            >
              <RefreshCw size={16} className={loading ? "animate-spin" : ""} />
            </button>
            <button
              type="button"
              onClick={onClose}
              className="rounded-xl p-2 text-text-secondary hover:bg-bg-primary hover:text-text-primary transition"
              title="Fechar (Esc)"
            >
              <X size={20} />
            </button>
          </div>
        </div>

        {/* Resumo Financeiro no Topo */}
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 border-b border-border-primary bg-bg-secondary/50 px-5 py-3 text-xs">
          <div className="flex items-center gap-3 rounded-xl border border-border-primary/60 bg-bg-primary/60 p-2.5">
            <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-accent/15 text-accent">
              <ShoppingBag size={16} />
            </div>
            <div>
              <span className="text-[11px] text-text-secondary block">Total de Vendas</span>
              <strong className="text-sm text-text-primary">{summary.totalVendas} concluídas</strong>
            </div>
          </div>

          <div className="flex items-center gap-3 rounded-xl border border-border-primary/60 bg-bg-primary/60 p-2.5">
            <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-emerald-500/15 text-emerald-500">
              <DollarSign size={16} />
            </div>
            <div>
              <span className="text-[11px] text-text-secondary block">Total Faturado</span>
              <strong className="text-sm font-mono text-emerald-600 dark:text-emerald-400">
                {formatCurrency(summary.totalValor)}
              </strong>
            </div>
          </div>

          <div className="flex items-center gap-3 rounded-xl border border-border-primary/60 bg-bg-primary/60 p-2.5">
            <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-blue-500/15 text-blue-500">
              <ShieldCheck size={16} />
            </div>
            <div>
              <span className="text-[11px] text-text-secondary block">Notas Autorizadas</span>
              <strong className="text-sm text-blue-600 dark:text-blue-400">
                {summary.totalAutorizadas} na SEFAZ
              </strong>
            </div>
          </div>
        </div>

        {/* Barra de Filtros e Busca */}
        <div className="border-b border-border-primary bg-bg-secondary/30 px-5 py-3 flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2 flex-1 min-w-[280px]">
            <div className="relative flex-1">
              <Search
                size={16}
                className="absolute left-3 top-1/2 -translate-y-1/2 text-text-secondary"
              />
              <input
                type="text"
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                placeholder="Buscar por nº da venda, cliente, CPF ou nota fiscal..."
                className="w-full rounded-xl border border-border-primary bg-bg-primary pl-9 pr-3 py-1.5 text-xs text-text-primary focus:border-accent focus:outline-none transition shadow-xs"
              />
            </div>

            {/* Alternador Turno Atual / Todas as Vendas */}
            <div className="flex items-center gap-1 bg-bg-primary p-1 rounded-xl border border-border-primary text-xs shrink-0">
              <button
                type="button"
                onClick={() => {
                  setScopeFilter("session");
                  loadSales("session");
                }}
                className={`rounded-lg px-2.5 py-1 font-semibold transition ${
                  scopeFilter === "session"
                    ? "bg-accent text-white shadow-xs"
                    : "text-text-secondary hover:text-text-primary"
                }`}
                title="Mostrar apenas vendas abertas neste turno"
              >
                Deste Turno
              </button>
              <button
                type="button"
                onClick={() => {
                  setScopeFilter("all");
                  loadSales("all");
                }}
                className={`rounded-lg px-2.5 py-1 font-semibold transition ${
                  scopeFilter === "all"
                    ? "bg-accent text-white shadow-xs"
                    : "text-text-secondary hover:text-text-primary"
                }`}
                title="Mostrar histórico geral de vendas"
              >
                Todas as Vendas
              </button>
            </div>
          </div>

          <div className="flex items-center gap-1.5 overflow-x-auto text-xs">
            <button
              type="button"
              onClick={() => setPaymentFilter("all")}
              className={`rounded-lg px-2.5 py-1 font-semibold transition ${
                paymentFilter === "all"
                  ? "bg-accent text-white"
                  : "bg-bg-primary text-text-secondary hover:text-text-primary border border-border-primary"
              }`}
            >
              Todos
            </button>
            <button
              type="button"
              onClick={() => setPaymentFilter("dinheiro")}
              className={`rounded-lg px-2.5 py-1 font-semibold transition ${
                paymentFilter === "dinheiro"
                  ? "bg-accent text-white"
                  : "bg-bg-primary text-text-secondary hover:text-text-primary border border-border-primary"
              }`}
            >
              Dinheiro
            </button>
            <button
              type="button"
              onClick={() => setPaymentFilter("cart")}
              className={`rounded-lg px-2.5 py-1 font-semibold transition ${
                paymentFilter === "cart"
                  ? "bg-accent text-white"
                  : "bg-bg-primary text-text-secondary hover:text-text-primary border border-border-primary"
              }`}
            >
              Cartão
            </button>
            <button
              type="button"
              onClick={() => setPaymentFilter("pix")}
              className={`rounded-lg px-2.5 py-1 font-semibold transition ${
                paymentFilter === "pix"
                  ? "bg-accent text-white"
                  : "bg-bg-primary text-text-secondary hover:text-text-primary border border-border-primary"
              }`}
            >
              PIX
            </button>
            <button
              type="button"
              onClick={() => setPaymentFilter("fiado")}
              className={`rounded-lg px-2.5 py-1 font-semibold transition ${
                paymentFilter === "fiado"
                  ? "bg-accent text-white"
                  : "bg-bg-primary text-text-secondary hover:text-text-primary border border-border-primary"
              }`}
            >
              Fiado
            </button>
          </div>
        </div>

        {/* Tabela de Vendas */}
        <div className="flex-1 overflow-y-auto">
          {loading && (
            <div className="flex flex-col items-center justify-center py-16 text-text-secondary">
              <div className="h-8 w-8 animate-spin rounded-full border-2 border-accent border-t-transparent" />
              <p className="mt-3 text-sm">Carregando vendas do caixa...</p>
            </div>
          )}

          {!loading && filteredSales.length === 0 && (
            <div className="flex flex-col items-center justify-center py-16 text-text-secondary">
              <ShoppingBag size={36} className="text-text-secondary/40 mb-2" />
              <p className="font-semibold text-sm">Nenhuma venda encontrada.</p>
              <p className="text-xs text-text-secondary mt-1">
                {searchTerm
                  ? "Tente ajustar o termo da pesquisa."
                  : "As vendas concluídas neste caixa aparecerão aqui."}
              </p>
            </div>
          )}

          {!loading && filteredSales.length > 0 && (
            <table className="w-full text-left text-xs">
              <thead className="border-b border-border-primary bg-bg-secondary text-text-secondary sticky top-0 z-10">
                <tr>
                  <th className="px-4 py-2.5 font-medium">Venda #</th>
                  <th className="px-3 py-2.5 font-medium">Data / Hora</th>
                  <th className="px-3 py-2.5 font-medium">Cliente</th>
                  <th className="px-3 py-2.5 font-medium">Pagamento</th>
                  <th className="px-3 py-2.5 text-center font-medium">Itens</th>
                  <th className="px-3 py-2.5 text-right font-medium">Total</th>
                  <th className="px-3 py-2.5 text-center font-medium">Status Fiscal</th>
                  <th className="px-4 py-2.5 text-right font-medium">Ações</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border-primary">
                {filteredSales.map((sale) => {
                  const isAuth = sale.fiscalStatus === FISCAL_STATUS.Autorizado;
                  const isCanc = sale.fiscalStatus === FISCAL_STATUS.Cancelado;
                  const isRet = sale.fiscalStatus === FISCAL_STATUS.Devolvido;

                  return (
                    <tr
                      key={sale.saleNumber}
                      onClick={() => onSelectSale(sale.saleNumber)}
                      className="cursor-pointer hover:bg-bg-secondary/60 transition-colors group"
                    >
                      <td className="px-4 py-3 font-bold font-mono text-accent">
                        #{sale.saleNumber}
                      </td>
                      <td className="px-3 py-3 text-text-secondary whitespace-nowrap">
                        {sale.saleDate}
                      </td>
                      <td className="px-3 py-3">
                        <div className="flex items-center gap-1.5">
                          <User size={12} className="text-text-secondary shrink-0" />
                          <span className="font-medium text-text-primary truncate max-w-[140px] block">
                            {sale.customerName}
                          </span>
                        </div>
                      </td>
                      <td className="px-3 py-3">
                        <span className="inline-flex items-center rounded-md bg-bg-secondary border border-border-primary px-2 py-0.5 text-[11px] font-semibold text-text-secondary capitalize">
                          {sale.paymentType}
                        </span>
                      </td>
                      <td className="px-3 py-3 text-center text-text-secondary font-mono">
                        {sale.itemsCount}
                      </td>
                      <td className="px-3 py-3 text-right font-bold font-mono text-text-primary">
                        {formatCurrency(sale.totalAmountNum)}
                      </td>
                      <td className="px-3 py-3 text-center whitespace-nowrap">
                        {sale.fiscalDocId || sale.fiscalNumeroNf ? (
                          <span
                            className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold ${
                              isAuth
                                ? "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400"
                                : isCanc
                                ? "bg-rose-500/15 text-rose-600 dark:text-rose-400"
                                : isRet
                                ? "bg-purple-500/15 text-purple-600 dark:text-purple-400"
                                : "bg-amber-500/15 text-amber-600 dark:text-amber-400"
                            }`}
                          >
                            {isAuth && <ShieldCheck size={11} />}
                            {isCanc && <AlertOctagon size={11} />}
                            {isRet && <RotateCcw size={11} />}
                            {sale.fiscalModelo === 55 ? "NF-e" : "NFC-e"}{" "}
                            {sale.fiscalNumeroNf ? `#${formatNumeroNf(sale.fiscalNumeroNf)}` : ""}{" "}
                            ({isAuth ? "Autorizada" : isCanc ? "Cancelada" : isRet ? "Devolvida" : "Pendente"})
                          </span>
                        ) : (
                          <span className="text-[11px] text-text-secondary italic">
                            Sem nota
                          </span>
                        )}
                      </td>
                      <td className="px-4 py-3 text-right">
                        <button
                          type="button"
                          onClick={(e) => {
                            e.stopPropagation();
                            onSelectSale(sale.saleNumber);
                          }}
                          className="inline-flex items-center gap-1 rounded-lg border border-border-primary bg-bg-primary px-2.5 py-1 text-[11px] font-semibold text-text-primary hover:bg-accent hover:text-white hover:border-accent transition group-hover:border-accent/40"
                        >
                          <span>Ver</span>
                          <ArrowRight size={12} />
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-between border-t border-border-primary bg-bg-secondary px-5 py-3 text-xs">
          <span className="text-text-secondary">
            Clique em qualquer venda para ver itens, pagamentos e emitir cancelamento fiscal.
          </span>
          <button
            type="button"
            onClick={onClose}
            className="rounded-xl border border-border-primary bg-bg-primary px-4 py-1.5 font-semibold text-text-primary hover:bg-bg-tertiary transition active:scale-95"
          >
            Fechar (Esc)
          </button>
        </div>
      </div>
    </div>
  );
}
