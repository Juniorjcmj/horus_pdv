/**
 * Arquivo: src/components/Admin/PdvSaleDetailModal.tsx
 * Objetivo: exibe o raio-x completo de uma venda no PDV (itens, formas de pagamento, valores,
 *           troco e detalhes fiscais de NFC-e/NF-e) com ação direta para cancelamento fiscal.
 */
import { useEffect, useState } from "react";
import {
  X,
  FileText,
  AlertOctagon,
  Ban,
  Printer,
  Copy,
  Check,
  CreditCard,
  Banknote,
  QrCode,
  User,
  ShoppingBag,
  Clock,
  ShieldCheck,
  AlertTriangle,
  RotateCcw,
} from "lucide-react";
import {
  salesHistoryService,
  type SaleDetailFullDto,
  type SalePaymentItemDto,
  type SaleHistoryDto,
} from "@/services/api/salesHistoryService";
import {
  FISCAL_STATUS,
  type FiscalDocumentDetailDto,
} from "@/services/api/fiscalService";
import { Toast } from "@/hooks/Dialog";
import { formatChaveAcesso, formatNumeroNf } from "@/utils/danfePrint";
import { db } from "@/infrastructure/database/dexie";

type PdvSaleDetailModalProps = {
  isOpen: boolean;
  onClose: () => void;
  saleNumber: string | null;
  onCancelFiscalDocument: (doc: FiscalDocumentDetailDto) => void;
  onCancelSale?: (sale: SaleDetailFullDto) => void;
  onPrintReceipt?: (saleNumber: string) => void;
};

function formatCurrency(val?: string | number | null): string {
  if (val === undefined || val === null || val === "") return "R$ 0,00";
  if (typeof val === "string") {
    const clean = val.replace("R$", "").trim();
    if (clean.includes(",")) return `R$ ${clean}`;
    const num = parseFloat(clean);
    return isNaN(num) ? val : num.toLocaleString("pt-BR", { style: "currency", currency: "BRL" });
  }
  return val.toLocaleString("pt-BR", { style: "currency", currency: "BRL" });
}

function getPaymentIcon(type?: string) {
  const t = (type || "").toLowerCase();
  if (t.includes("dinheiro")) return <Banknote size={16} className="text-emerald-500" />;
  if (t.includes("pix")) return <QrCode size={16} className="text-teal-500" />;
  if (t.includes("cartão") || t.includes("cartao") || t.includes("crédito") || t.includes("débito")) {
    return <CreditCard size={16} className="text-blue-500" />;
  }
  return <CreditCard size={16} className="text-amber-500" />;
}

export default function PdvSaleDetailModal({
  isOpen,
  onClose,
  saleNumber,
  onCancelFiscalDocument,
  onCancelSale,
  onPrintReceipt,
}: PdvSaleDetailModalProps) {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<SaleDetailFullDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [copiedKey, setCopiedKey] = useState(false);
  const [isPrinting, setIsPrinting] = useState(false);

  useEffect(() => {
    if (!isOpen || !saleNumber) {
      setData(null);
      setError(null);
      setCopiedKey(false);
      return;
    }

    setLoading(true);
    setError(null);

    salesHistoryService
      .getDetails(saleNumber)
      .then((res) => {
        if (!res) {
          throw new Error("Venda não encontrada na API.");
        }
        setData(res);
      })
      .catch(async (err: any) => {
        try {
          const localSale =
            (await db.sales.where("saleNumber").equals(saleNumber).first()) ||
            (await db.sales.get(saleNumber));
          if (localSale) {
            const items = await db.saleItems.where("saleId").equals(localSale.id).toArray();
            const payments = await db.payments.where("saleId").equals(localSale.id).toArray();
            const mappedItems: SaleHistoryDto[] = items.map((it) => ({
              saleNumber: localSale.saleNumber,
              customerName: localSale.customerName || "-",
              customerCpf: localSale.customerId || "-",
              paymentType: payments.map((p) => p.paymentType).join(" + ") || "Dinheiro",
              totalAmount: localSale.totalAmount.toFixed(2).replace(".", ","),
              operatorName: "Operador Local",
              productCode: it.productCode,
              productName: it.productName,
              quantity: it.quantity,
              unitPrice: it.unitPrice.toFixed(2).replace(".", ","),
              desconto: it.discount,
              itemTotal: it.total.toFixed(2).replace(".", ","),
              saleDate: new Date(localSale.createdAt).toLocaleString("pt-BR"),
              clientSaleId: localSale.id,
              offlineReference: localSale.saleNumber,
            }));
            const mappedPayments: SalePaymentItemDto[] = payments.map((p, idx) => ({
              id: p.id || `loc-pay-${idx}`,
              companyId: "",
              vendaId: localSale.id,
              paymentType: p.paymentType,
              amount: p.amount,
              cashGiven: p.cashGiven || p.amount,
              changeAmount: p.changeAmount || 0,
              createdAt: localSale.createdAt,
            }));
            setData({
              vendaId: localSale.id,
              saleNumber: localSale.saleNumber,
              customerName: localSale.customerName || "-",
              customerCpf: localSale.customerId || "-",
              paymentType: payments.map((p) => p.paymentType).join(" + ") || "Dinheiro",
              totalAmount: localSale.totalAmount.toFixed(2).replace(".", ","),
              operatorName: "Operador Local",
              saleDate: new Date(localSale.createdAt).toLocaleString("pt-BR"),
              clientSaleId: localSale.id,
              offlineReference: localSale.saleNumber,
              items: mappedItems,
              payments: mappedPayments,
              documentoFiscal: null,
            });
            return;
          }
        } catch (dexErr) {
          console.warn("Falha no fallback Dexie em PdvSaleDetailModal:", dexErr);
        }
        setError(err?.message || "Não foi possível carregar os detalhes da venda.");
      })
      .finally(() => {
        setLoading(false);
      });
  }, [isOpen, saleNumber]);

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

  const handleCopyKey = () => {
    if (!data?.documentoFiscal?.chaveAcesso) return;
    navigator.clipboard.writeText(data.documentoFiscal.chaveAcesso);
    setCopiedKey(true);
    Toast.success("Chave de acesso copiada para a área de transferência!");
    window.setTimeout(() => setCopiedKey(false), 2500);
  };

  const handlePrint = async () => {
    if (!saleNumber) return;
    setIsPrinting(true);
    try {
      if (onPrintReceipt) {
        onPrintReceipt(saleNumber);
      } else {
        await salesHistoryService.print(saleNumber);
        Toast.success("Comprovante enviado para impressão.");
      }
    } catch {
      Toast.error("Erro ao imprimir comprovante.");
    } finally {
      setIsPrinting(false);
    }
  };

  if (!isOpen) return null;

  const doc = data?.documentoFiscal;
  const isAuthorized = doc?.status === FISCAL_STATUS.Autorizado;
  const isCancelled = doc?.status === FISCAL_STATUS.Cancelado;
  const isReturned = doc?.status === FISCAL_STATUS.Devolvido;
  const isSaleCancelled = data?.status?.toLowerCase() === "cancelada";

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-3 backdrop-blur-xs animate-in fade-in duration-150 sm:p-4">
      <div className="relative flex max-h-[92vh] w-full max-w-3xl flex-col rounded-2xl border border-border-primary bg-bg-primary shadow-2xl text-text-primary overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-border-primary bg-bg-secondary px-5 py-4">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-accent/15 text-accent">
              <ShoppingBag size={20} />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h2 className="text-lg font-bold text-text-primary">
                  Venda #{saleNumber || "—"}
                </h2>
                {isSaleCancelled && (
                  <span className="inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-bold bg-rose-500/15 text-rose-600 dark:text-rose-400 border border-rose-500/30">
                    <Ban size={12} />
                    Venda Cancelada
                  </span>
                )}
                {doc && (
                  <span
                    className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-semibold ${
                      isAuthorized
                        ? "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400"
                        : isCancelled
                        ? "bg-rose-500/15 text-rose-600 dark:text-rose-400"
                        : isReturned
                        ? "bg-purple-500/15 text-purple-600 dark:text-purple-400"
                        : "bg-amber-500/15 text-amber-600 dark:text-amber-400"
                    }`}
                  >
                    {isAuthorized && <ShieldCheck size={12} />}
                    {isCancelled && <AlertOctagon size={12} />}
                    {isReturned && <RotateCcw size={12} />}
                    {doc.modelo === 55 ? "NF-e" : "NFC-e"}{" "}
                    {isAuthorized ? "Autorizada" : isCancelled ? "Cancelada" : isReturned ? "Devolvida" : "Pendente"}
                  </span>
                )}
              </div>
              <p className="text-xs text-text-secondary flex items-center gap-2 mt-0.5">
                <Clock size={12} />
                <span>{data?.saleDate || "Carregando data..."}</span>
                {data?.operatorName && (
                  <>
                    <span>•</span>
                    <span>Operador: <strong>{data.operatorName}</strong></span>
                  </>
                )}
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg p-1.5 text-text-secondary hover:bg-bg-primary hover:text-text-primary transition"
            title="Fechar (Esc)"
          >
            <X size={20} />
          </button>
        </div>

        {/* Corpo do Modal */}
        <div className="flex-1 overflow-y-auto p-5 space-y-5">
          {loading && (
            <div className="flex flex-col items-center justify-center py-16 text-text-secondary">
              <div className="h-8 w-8 animate-spin rounded-full border-2 border-accent border-t-transparent" />
              <p className="mt-3 text-sm">Carregando detalhes completos da venda...</p>
            </div>
          )}

          {error && !loading && (
            <div className="rounded-xl border border-rose-500/30 bg-rose-500/10 p-4 text-center text-sm text-rose-500">
              <AlertTriangle className="mx-auto mb-2 text-rose-500" size={24} />
              <p className="font-semibold">{error}</p>
            </div>
          )}

          {data && !loading && (
            <>
              {/* Banner de Cancelamento se a Venda foi cancelada */}
              {isSaleCancelled && (
                <div className="flex items-start gap-3 rounded-xl border border-rose-500/30 bg-rose-500/10 p-3.5 text-xs text-rose-700 dark:text-rose-300">
                  <Ban size={18} className="shrink-0 mt-0.5 text-rose-500" />
                  <div className="space-y-1">
                    <p className="font-bold text-sm">Esta venda foi cancelada no sistema</p>
                    <p className="text-[11px] text-text-secondary">
                      {data.canceladoEm ? `Cancelada em: ${data.canceladoEm}` : ""}
                      {data.canceladoPorSupervisorNome ? ` • Supervisor: ${data.canceladoPorSupervisorNome}` : ""}
                      {data.canceladoPorOperadorNome ? ` • Operador: ${data.canceladoPorOperadorNome}` : ""}
                    </p>
                    {data.canceladoJustificativa && (
                      <p className="text-[11px] font-medium bg-bg-primary/60 rounded p-1.5 mt-1 border border-border-primary">
                        Motivo: &quot;{data.canceladoJustificativa}&quot;
                      </p>
                    )}
                  </div>
                </div>
              )}

              {/* Card de Cliente e Resumo */}
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div className="rounded-xl border border-border-primary bg-bg-secondary p-3.5 text-xs">
                  <div className="flex items-center gap-2 text-text-secondary font-medium mb-1">
                    <User size={14} className="text-accent" />
                    <span>Dados do Cliente</span>
                  </div>
                  <p className="text-sm font-semibold text-text-primary truncate">
                    {data.customerName || "Consumidor Final"}
                  </p>
                  <p className="text-text-secondary mt-0.5">
                    {data.customerCpf && data.customerCpf !== "-"
                      ? `CPF/CNPJ: ${data.customerCpf}`
                      : "Sem CPF/CNPJ identificado"}
                  </p>
                </div>

                <div className="rounded-xl border border-border-primary bg-bg-secondary p-3.5 text-xs">
                  <div className="flex items-center gap-2 text-text-secondary font-medium mb-1">
                    <CreditCard size={14} className="text-accent" />
                    <span>Total da Venda</span>
                  </div>
                  <p className="text-xl font-bold text-accent">
                    {formatCurrency(data.totalAmount)}
                  </p>
                  <p className="text-text-secondary mt-0.5">
                    Forma principal: <strong className="capitalize">{data.paymentType || "Dinheiro"}</strong>
                  </p>
                </div>
              </div>

              {/* Tabela de Itens Comprados */}
              <div className="rounded-xl border border-border-primary bg-bg-secondary overflow-hidden">
                <div className="border-b border-border-primary px-4 py-2.5 bg-bg-tertiary/40">
                  <h3 className="text-xs font-semibold uppercase tracking-wider text-text-secondary">
                    Itens da Venda ({data.items?.length || 0})
                  </h3>
                </div>
                <div className="max-h-56 overflow-y-auto">
                  <table className="w-full text-left text-xs">
                    <thead className="border-b border-border-primary bg-bg-primary/50 text-text-secondary sticky top-0">
                      <tr>
                        <th className="px-4 py-2 font-medium">Produto</th>
                        <th className="px-3 py-2 text-center font-medium">Qtd</th>
                        <th className="px-3 py-2 text-right font-medium">Unitário</th>
                        <th className="px-3 py-2 text-right font-medium">Desc.</th>
                        <th className="px-4 py-2 text-right font-medium">Total</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-border-primary">
                      {data.items?.map((item, idx) => (
                        <tr key={idx} className="hover:bg-bg-primary/40 transition-colors">
                          <td className="px-4 py-2.5">
                            <p className="font-medium text-text-primary">{item.productName}</p>
                            <p className="text-[11px] text-text-secondary font-mono">Cód: {item.productCode}</p>
                          </td>
                          <td className="px-3 py-2.5 text-center font-semibold text-text-primary">
                            {Number(item.quantity).toLocaleString("pt-BR", { maximumFractionDigits: 3 })}
                          </td>
                          <td className="px-3 py-2.5 text-right text-text-secondary font-mono">
                            {formatCurrency(item.unitPrice)}
                          </td>
                          <td className="px-3 py-2.5 text-right text-rose-500 font-mono">
                            {item.desconto && Number(item.desconto) > 0 ? `-${formatCurrency(item.desconto)}` : "—"}
                          </td>
                          <td className="px-4 py-2.5 text-right font-semibold text-text-primary font-mono">
                            {formatCurrency(item.itemTotal)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>

              {/* Formas de Pagamento e Valores */}
              <div className="rounded-xl border border-border-primary bg-bg-secondary p-4 space-y-3">
                <h3 className="text-xs font-semibold uppercase tracking-wider text-text-secondary">
                  Detalhamento de Pagamentos e Troco
                </h3>
                <div className="space-y-2">
                  {data.payments && data.payments.length > 0 ? (
                    data.payments.map((p, idx) => (
                      <div
                        key={idx}
                        className="flex items-center justify-between rounded-lg border border-border-primary/60 bg-bg-primary/60 px-3.5 py-2.5 text-xs"
                      >
                        <div className="flex items-center gap-2.5">
                          {getPaymentIcon(p.paymentType)}
                          <div>
                            <p className="font-semibold capitalize text-text-primary">
                              {p.paymentType}
                            </p>
                            {p.cashGiven > p.amount && (
                              <p className="text-[11px] text-text-secondary">
                                Recebido: {formatCurrency(p.cashGiven)} • Troco: {formatCurrency(p.changeAmount)}
                              </p>
                            )}
                          </div>
                        </div>
                        <span className="font-bold font-mono text-sm text-text-primary">
                          {formatCurrency(p.amount)}
                        </span>
                      </div>
                    ))
                  ) : (
                    <div className="flex items-center justify-between rounded-lg border border-border-primary/60 bg-bg-primary/60 px-3.5 py-2.5 text-xs">
                      <div className="flex items-center gap-2.5">
                        {getPaymentIcon(data.paymentType)}
                        <span className="font-semibold capitalize text-text-primary">
                          {data.paymentType || "Dinheiro"}
                        </span>
                      </div>
                      <span className="font-bold font-mono text-sm text-text-primary">
                        {formatCurrency(data.totalAmount)}
                      </span>
                    </div>
                  )}
                </div>
              </div>

              {/* Painel Fiscal (NFC-e / NF-e) */}
              <div className="rounded-xl border border-border-primary bg-bg-secondary p-4 space-y-3">
                <div className="flex items-center justify-between">
                  <h3 className="text-xs font-semibold uppercase tracking-wider text-text-secondary flex items-center gap-1.5">
                    <FileText size={14} className="text-accent" />
                    Informações Fiscais SEFAZ
                  </h3>
                  {doc ? (
                    <span className="text-xs font-semibold text-text-secondary">
                      {doc.modelo === 55 ? "NF-e (Modelo 55)" : "NFC-e (Modelo 65)"}
                    </span>
                  ) : (
                    <span className="text-xs text-text-secondary italic">Não emitida</span>
                  )}
                </div>

                {doc ? (
                  <div className="space-y-3 text-xs">
                    <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5">
                      <div className="rounded-lg bg-bg-primary/60 border border-border-primary/50 p-2.5">
                        <span className="text-[11px] text-text-secondary block">Número da Nota</span>
                        <strong className="text-sm font-mono text-text-primary">
                          {formatNumeroNf(doc.numeroNf)}
                        </strong>
                      </div>
                      <div className="rounded-lg bg-bg-primary/60 border border-border-primary/50 p-2.5">
                        <span className="text-[11px] text-text-secondary block">Série</span>
                        <strong className="text-sm font-mono text-text-primary">
                          {doc.serie || 1}
                        </strong>
                      </div>
                      <div className="rounded-lg bg-bg-primary/60 border border-border-primary/50 p-2.5">
                        <span className="text-[11px] text-text-secondary block">Protocolo</span>
                        <strong className="text-xs font-mono text-text-primary truncate block" title={doc.protocolo || "—"}>
                          {doc.protocolo || "—"}
                        </strong>
                      </div>
                      <div className="rounded-lg bg-bg-primary/60 border border-border-primary/50 p-2.5">
                        <span className="text-[11px] text-text-secondary block">Status SEFAZ</span>
                        <strong
                          className={`text-xs block ${
                            isAuthorized
                              ? "text-emerald-500"
                              : isCancelled
                              ? "text-rose-500"
                              : isReturned
                              ? "text-purple-500"
                              : "text-amber-500"
                          }`}
                        >
                          {doc.motivoStatus || (isAuthorized ? "Autorizado o uso" : "Processado")}
                        </strong>
                      </div>
                    </div>

                    {/* Chave de Acesso */}
                    {doc.chaveAcesso && (
                      <div className="rounded-lg bg-bg-primary/60 border border-border-primary/50 p-2.5 flex items-center justify-between gap-2">
                        <div className="min-w-0 flex-1">
                          <span className="text-[11px] text-text-secondary block">Chave de Acesso (44 dígitos)</span>
                          <span className="font-mono text-xs text-text-primary break-all">
                            {formatChaveAcesso(doc.chaveAcesso)}
                          </span>
                        </div>
                        <button
                          type="button"
                          onClick={handleCopyKey}
                          className="shrink-0 rounded-lg border border-border-primary bg-bg-secondary p-1.5 text-text-secondary hover:text-text-primary hover:bg-bg-tertiary transition"
                          title="Copiar Chave de Acesso"
                        >
                          {copiedKey ? <Check size={14} className="text-emerald-500" /> : <Copy size={14} />}
                        </button>
                      </div>
                    )}
                  </div>
                ) : (
                  <p className="text-xs text-text-secondary">
                    Nenhum documento fiscal registrado para esta venda.
                  </p>
                )}
              </div>
            </>
          )}
        </div>

        {/* Footer com Ações */}
        <div className="flex flex-wrap items-center justify-between gap-2.5 border-t border-border-primary bg-bg-secondary px-5 py-3">
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={handlePrint}
              disabled={isPrinting || !data}
              className="inline-flex items-center gap-1.5 rounded-xl border border-border-primary bg-bg-primary px-3 py-2 text-xs font-semibold text-text-primary shadow-xs hover:bg-bg-tertiary disabled:opacity-50 transition active:scale-95"
            >
              <Printer size={15} />
              <span>{isPrinting ? "Imprimindo..." : "Imprimir Recibo"}</span>
            </button>
          </div>

          <div className="flex items-center gap-2">
            {/* Botão de Cancelamento de Venda com Supervisor */}
            {data && !isSaleCancelled && onCancelSale && (
              <button
                type="button"
                onClick={() => {
                  if (data) {
                    onCancelSale(data);
                  }
                }}
                className="inline-flex items-center gap-1.5 rounded-xl border border-rose-500/30 bg-rose-500/10 hover:bg-rose-500/20 text-rose-600 dark:text-rose-400 px-3.5 py-2 text-xs font-semibold shadow-xs transition active:scale-95"
                title="Cancelar a venda completa (estorno de estoque, fiado e caixa)"
              >
                <Ban size={15} />
                <span>Cancelar Venda</span>
              </button>
            )}

            {/* Botão de Cancelamento de Nota Fiscal */}
            {doc && isAuthorized && (
              <button
                type="button"
                onClick={() => {
                  if (doc) {
                    onCancelFiscalDocument(doc);
                  }
                }}
                className="inline-flex items-center gap-1.5 rounded-xl border border-rose-500/30 bg-rose-600 hover:bg-rose-700 text-white px-4 py-2 text-xs font-bold shadow-md transition active:scale-95"
                title="Abrir cancelamento de nota com autorização de supervisor"
              >
                <AlertOctagon size={16} />
                <span>Cancelar Nota Fiscal</span>
              </button>
            )}

            {doc && (isCancelled || isReturned) && (
              <span className="text-xs text-rose-500 font-semibold flex items-center gap-1">
                <AlertOctagon size={14} />
                Nota {isCancelled ? "Cancelada" : "Devolvida"}
              </span>
            )}

            <button
              type="button"
              onClick={onClose}
              className="rounded-xl border border-border-primary bg-bg-primary px-4 py-2 text-xs font-semibold text-text-primary hover:bg-bg-tertiary transition active:scale-95"
            >
              Fechar (Esc)
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
