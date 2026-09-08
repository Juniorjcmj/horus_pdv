/**
 * Arquivo: src/pages/Admin/SalesHistoryPage.tsx
 * Objetivo: exibe histórico de vendas com busca local e ação de impressão por registro.
 * Entradas esperadas: não recebe props; processa filtro textual e renderiza dados vindos da API.
 */

import { AlertCircle, AlertTriangle, FileText, QrCode, RefreshCw, Search } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import DanfePreviewModal from "@/components/Admin/DanfePreviewModal";
import FiscalErrorModal from "@/components/Admin/FiscalErrorModal";
import PageHeader from "@/components/Admin/PageHeader";
import ReceiptPreviewModal, { type SaleReceipt } from "@/components/Admin/ReceiptPreviewModal";
import RowActionsMenu from "@/components/Admin/RowActionsMenu";
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
import { getStoredAuthUser } from "@/utils/authStorage";

type SaleHistoryRow = SaleHistoryDto;

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
    address: company.address,
    number: company.number,
    neighborhood: company.neighborhood,
    city: company.city,
    uf: company.uf,
    phone: company.phone,
    sacPhone: company.sacPhone,
  };
}

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

  const loadFiscalStatus = () => {
    fiscalService
      .list()
      .then((rows) => setFiscalBySale(new Map(rows.map((row) => [row.saleNumber, row]))))
      .catch(() => setFiscalBySale(new Map()));
  };

  useEffect(() => {
    salesHistoryService.list().then(setSalesHistory).catch(() => setSalesHistory([]));
    companyService.get().then((data) => setCompany(data ?? null)).catch(() => setCompany(null));
    loadFiscalStatus();
  }, []);

  const filteredSales = useMemo(() => {
    // Busca local por número da venda ou nome do cliente.
    const normalized = search.trim().toLowerCase();
    if (!normalized) return salesHistory;

    return salesHistory.filter(
      (sale) =>
        sale.saleNumber.toLowerCase().includes(normalized) ||
        sale.customerName.toLowerCase().includes(normalized),
    );
  }, [salesHistory, search]);

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
      const result = await salesHistoryService.print(sale.saleNumber);
      const rows =
        result?.rows && result.rows.length > 0
          ? result.rows
          : salesHistory.filter((item) => item.saleNumber === sale.saleNumber);
      const receipt = toReceipt(sale.saleNumber, rows, result?.printedAt);
      if (!receipt) {
        Toast.error("Venda não encontrada para impressão.");
        return;
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

      <section className="card p-4 md:p-5">
        <label className="relative mx-auto block w-full max-w-xl">
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
      </section>

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
                return (
                <tr key={`${sale.saleNumber}-${sale.productCode}`} className="border-t border-border-primary">
                  <td className="px-3 py-3 font-semibold text-text-primary">{sale.saleNumber}</td>
                  <td className="px-3 py-3">
                    <span className="block break-words leading-snug" title={sale.customerName}>
                      {sale.customerName}
                    </span>
                  </td>
                  <td className="px-3 py-3 break-words">{sale.customerCpf}</td>
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
                    R$ {formatMoneyBr(getItemTotal(sale))}
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
          companyName={company?.fantasyName || company?.corporateName || "Hórus PDV"}
          onClose={() => setDanfePreview(null)}
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
    </PageLayout>
  );
}
