/**
 * Arquivo: src/pages/Admin/FiscalPage.tsx
 * Objetivo: central avançada de controle, cancelamento homologado, reemissão, DANFE e XMLs
 *           de documentos fiscais (NFC-e / NF-e) com métricas em tempo real e filtros multicritério.
 * Entradas esperadas: não recebe props; opera com estado local e APIs de fiscal/vendas.
 */

import {
  AlertCircle,
  AlertOctagon,
  AlertTriangle,
  Calendar,
  Check,
  Copy,
  Download,
  ExternalLink,
  Eye,
  FileArchive,
  FileCode,
  Filter,
  Layers,
  Loader2,
  PackagePlus,
  Printer,
  QrCode,
  Receipt,
  RefreshCw,
  RotateCcw,
  Search,
  X,
  XCircle,
} from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import DanfePreviewModal from "@/components/Admin/DanfePreviewModal";
import PdvNfceCancelModal from "@/components/Admin/PdvNfceCancelModal";
import FiscalDetailModal from "@/components/Admin/FiscalDetailModal";
import FiscalErrorModal from "@/components/Admin/FiscalErrorModal";
import NfeImportModal from "@/components/Admin/NfeImportModal";
import PageHeader from "@/components/Admin/PageHeader";
import ReceiptPreviewModal, { type SaleReceipt } from "@/components/Admin/ReceiptPreviewModal";
import RowActionsMenu from "@/components/Admin/RowActionsMenu";
import TablePagination from "@/components/Pagination/TablePagination";
import { Toast } from "@/hooks/Dialog";
import { usePromptDialog } from "@/hooks/Dialog/usePromptDialog";
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
import { salesHistoryService } from "@/services/api/salesHistoryService";
import { getStoredAuthUser } from "@/utils/authStorage";
import { getSefazConsultaUrl } from "@/utils/danfePrint";

type FiscalTab = "notas" | "inutilizacao";
type StatusFilter = "todos" | "autorizado" | "cancelado" | "devolvido" | "rejeitado" | "contingencia";
type PeriodFilter = "todos" | "hoje" | "7dias" | "mes";

function formatDate(value: string) {
  if (!value) return "—";
  const trimmed = value.trim();
  if (/^\d{2}\/\d{2}\/\d{4}/.test(trimmed)) return trimmed;

  const date = new Date(trimmed);
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

function formatChaveCurta(chave: string | null) {
  if (!chave) return "—";
  if (chave.length <= 16) return chave;
  return `${chave.slice(0, 4)} ... ${chave.slice(-6)}`;
}

export default function FiscalPage() {
  const { prompt, PromptDialog } = usePromptDialog();
  const { formatMoneyBr, parseMoneyBr } = useInputMasks();

  // Estados principais
  const [activeTab, setActiveTab] = useState<FiscalTab>("notas");
  const [company, setCompany] = useState<CompanyDto | null>(null);
  const [documents, setDocuments] = useState<FiscalDocumentDto[]>([]);
  const [loading, setLoading] = useState(true);

  // Filtros
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("todos");
  const [periodFilter, setPeriodFilter] = useState<PeriodFilter>("todos");
  const [search, setSearch] = useState("");
  const [currentPage, setCurrentPage] = useState(1);
  const [itemsPerPage, setItemsPerPage] = useState(10);

  // Estados de ações assíncronas
  const [busyIds, setBusyIds] = useState<Set<string>>(() => new Set());
  const [copiedChaveId, setCopiedChaveId] = useState<string | null>(null);

  // Modais
  const [docToCancel, setDocToCancel] = useState<FiscalDocumentDto | null>(null);
  const [docToDetail, setDocToDetail] = useState<FiscalDocumentDto | null>(null);
  const [errorModalDoc, setErrorModalDoc] = useState<FiscalDocumentDto | null>(null);
  const [danfePreview, setDanfePreview] = useState<FiscalDocumentDetailDto | null>(null);
  const [receiptPreview, setReceiptPreview] = useState<SaleReceipt | null>(null);
  const [importNfeModalOpen, setImportNfeModalOpen] = useState(false);

  // Exportação mensal
  const now = useMemo(() => new Date(), []);
  const [exportModalOpen, setExportModalOpen] = useState(false);
  const [exportMonth, setExportMonth] = useState<number>(now.getMonth() + 1);
  const [exportYear, setExportYear] = useState<number>(now.getFullYear());
  const [isExporting, setIsExporting] = useState(false);

  // Inutilização de numeração
  const [inutilizarSerie, setInutilizarSerie] = useState("1");
  const [inutilizarInicial, setInutilizarInicial] = useState("");
  const [inutilizarFinal, setInutilizarFinal] = useState("");
  const [inutilizando, setInutilizando] = useState(false);

  // Carregamento de dados
  const loadDocuments = () => {
    setLoading(true);
    fiscalService
      .list()
      .then(setDocuments)
      .catch(() => Toast.error("Não foi possível carregar os documentos fiscais."))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadDocuments();
    companyService
      .get()
      .then((data) => setCompany(data ?? null))
      .catch(() => setCompany(null));
  }, []);

  // Métricas calculadas para os KPIs do topo
  const metrics = useMemo(() => {
    let totalValor = 0;
    let qtdAutorizadas = 0;
    let valorAutorizadas = 0;
    let qtdCanceladas = 0;
    let valorCanceladas = 0;
    let qtdDevolvidas = 0;
    let valorDevolvidas = 0;
    let qtdRejeitadas = 0;
    let qtdContingencia = 0;

    for (const doc of documents) {
      const val = doc.totalAmount ? parseMoneyBr(doc.totalAmount) : 0;
      totalValor += val;

      const isDevolvido = doc.status === FISCAL_STATUS.Devolvido || Boolean(doc.devolvida);

      if (isDevolvido) {
        qtdDevolvidas++;
        valorDevolvidas += val;
      } else if (doc.status === FISCAL_STATUS.Cancelado) {
        qtdCanceladas++;
        valorCanceladas += val;
      } else if (doc.status === FISCAL_STATUS.Autorizado) {
        qtdAutorizadas++;
        valorAutorizadas += val;
      } else if (doc.status === FISCAL_STATUS.Rejeitado || Boolean(doc.motivoStatus)) {
        qtdRejeitadas++;
      } else if (
        doc.status === FISCAL_STATUS.ContingenciaPendente ||
        doc.status === FISCAL_STATUS.Transmitindo ||
        doc.status === FISCAL_STATUS.Assinado
      ) {
        qtdContingencia++;
      }
    }

    return {
      totalEmitidas: documents.length,
      totalValor,
      qtdAutorizadas,
      valorAutorizadas,
      qtdCanceladas,
      valorCanceladas,
      qtdDevolvidas,
      valorDevolvidas,
      qtdRejeitadas,
      qtdContingencia,
    };
  }, [documents, parseMoneyBr]);

  // Documentos filtrados
  const filteredDocuments = useMemo(() => {
    let list = documents;

    // 1. Filtro por status
    if (statusFilter === "autorizado") {
      list = list.filter((d) => d.status === FISCAL_STATUS.Autorizado && !d.devolvida);
    } else if (statusFilter === "cancelado") {
      list = list.filter((d) => d.status === FISCAL_STATUS.Cancelado);
    } else if (statusFilter === "devolvido") {
      list = list.filter(
        (d) =>
          d.status === FISCAL_STATUS.Devolvido ||
          Boolean(d.devolvida) ||
          (d.modelo === 55 && Boolean(d.chaveReferenciada)),
      );
    } else if (statusFilter === "rejeitado") {
      list = list.filter(
        (d) =>
          d.status === FISCAL_STATUS.Rejeitado ||
          Boolean(
            d.motivoStatus &&
              d.status !== FISCAL_STATUS.Autorizado &&
              d.status !== FISCAL_STATUS.Cancelado &&
              d.status !== FISCAL_STATUS.Devolvido &&
              !d.devolvida,
          ),
      );
    } else if (statusFilter === "contingencia") {
      list = list.filter(
        (d) =>
          d.status === FISCAL_STATUS.ContingenciaPendente ||
          d.status === FISCAL_STATUS.Transmitindo ||
          d.status === FISCAL_STATUS.Assinado,
      );
    }

    // 2. Filtro por período
    if (periodFilter !== "todos") {
      const hoje = new Date();
      const inicioDoDia = new Date(hoje.getFullYear(), hoje.getMonth(), hoje.getDate());

      list = list.filter((d) => {
        const date = new Date(d.criadoEm);
        if (Number.isNaN(date.getTime())) return true;

        if (periodFilter === "hoje") {
          return date >= inicioDoDia;
        }
        if (periodFilter === "7dias") {
          const seteDiasAtras = new Date(hoje.getTime() - 7 * 24 * 60 * 60 * 1000);
          return date >= seteDiasAtras;
        }
        if (periodFilter === "mes") {
          return date.getFullYear() === hoje.getFullYear() && date.getMonth() === hoje.getMonth();
        }
        return true;
      });
    }

    // 3. Filtro de busca textual
    const normalized = search.trim().toLowerCase();
    if (normalized) {
      list = list.filter(
        (doc) =>
          doc.saleNumber.toLowerCase().includes(normalized) ||
          String(doc.numeroNf).includes(normalized) ||
          (doc.chaveAcesso ?? "").toLowerCase().includes(normalized) ||
          (doc.customerName ?? "").toLowerCase().includes(normalized) ||
          (doc.customerCpf ?? "").toLowerCase().includes(normalized) ||
          (doc.protocolo ?? "").toLowerCase().includes(normalized),
      );
    }

    return list;
  }, [documents, statusFilter, periodFilter, search]);

  const totalPages = Math.max(1, Math.ceil(filteredDocuments.length / itemsPerPage));
  const safeCurrentPage = Math.min(currentPage, totalPages);
  const paginatedDocuments = useMemo(() => {
    const start = (safeCurrentPage - 1) * itemsPerPage;
    return filteredDocuments.slice(start, start + itemsPerPage);
  }, [filteredDocuments, itemsPerPage, safeCurrentPage]);

  const setBusy = (id: string, busy: boolean) => {
    setBusyIds((current) => {
      const next = new Set(current);
      if (busy) next.add(id);
      else next.delete(id);
      return next;
    });
  };

  const handleCopyChave = (doc: FiscalDocumentDto) => {
    if (!doc.chaveAcesso) return;
    navigator.clipboard.writeText(doc.chaveAcesso);
    setCopiedChaveId(doc.id);
    Toast.success("Chave de acesso copiada para a área de transferência!");
    setTimeout(() => setCopiedChaveId(null), 2500);
  };

  const handleDownloadXml = async (doc: FiscalDocumentDto, tipo?: "autorizado" | "cancelamento") => {
    try {
      await fiscalService.downloadXml(doc.id, doc.chaveAcesso || doc.saleNumber, tipo);
      Toast.success("Arquivo XML baixado com sucesso!");
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao baixar XML do documento.");
    }
  };

  const printDanfeDirect = async (saleNumber: string) => {
    try {
      const detail = await fiscalService.getBySaleNumber(saleNumber);
      if (!detail) {
        Toast.error("Não foi possível carregar os dados fiscais para impressão.");
        return;
      }

      const printData = await salesHistoryService.print(saleNumber);
      const rows = printData?.rows ?? [];
      const storedUser = getStoredAuthUser();
      const first = rows[0];
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
      const receiptTotal = parseMoneyBr(first?.totalAmount || "0,00") || itemsSubtotal;
      const paymentType = first?.paymentType || "dinheiro";

      const receipt: SaleReceipt = {
        saleNumber,
        issuedAt: first?.saleDate || detail.criadoEm,
        printedAt: printData?.printedAt,
        company: company
          ? {
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
            }
          : null,
        customerCpf: first?.customerCpf || "-",
        paymentType,
        paymentLabel: paymentType,
        operatorName: first?.operatorName || storedUser?.name || "Operador",
        subtotal: receiptTotal,
        cashGiven: paymentType === "dinheiro" ? receiptTotal : 0,
        change: 0,
        items,
        fiscalDetail: detail,
      };

      setReceiptPreview(receipt);
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao preparar DANFE para impressão.");
    }
  };

  const reemitir = async (doc: FiscalDocumentDto) => {
    setBusy(doc.id, true);
    try {
      await fiscalService.reemitir(doc.id);
      Toast.success("Documento reenfileirado para nova tentativa de emissão.");
      loadDocuments();
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao reemitir NFC-e.");
    } finally {
      setBusy(doc.id, false);
    }
  };

  const inutilizar = async () => {
    const inicial = Number(inutilizarInicial);
    const final = Number(inutilizarFinal);
    if (!Number.isInteger(inicial) || inicial <= 0 || !Number.isInteger(final) || final < inicial) {
      Toast.error("Informe uma faixa de numeração válida.");
      return;
    }

    const justificativa = await prompt("Justificativa (mínimo 15 caracteres)", "Explique o motivo para a SEFAZ");
    if (!justificativa || justificativa.trim().length < 15) {
      Toast.error("A justificativa deve ter no mínimo 15 caracteres.");
      return;
    }

    setInutilizando(true);
    try {
      await fiscalService.inutilizar({
        serie: Number(inutilizarSerie) || 1,
        numeroInicial: inicial,
        numeroFinal: final,
        justificativa: justificativa.trim(),
      });
      Toast.success("Faixa de numeração inutilizada com sucesso.");
      setInutilizarInicial("");
      setInutilizarFinal("");
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao inutilizar numeração.");
    } finally {
      setInutilizando(false);
    }
  };

  const handleExportXmls = async () => {
    setIsExporting(true);
    try {
      await fiscalService.exportarXmlsMes(exportYear, exportMonth);
      Toast.success(`Pacote de XMLs (${String(exportMonth).padStart(2, "0")}/${exportYear}) baixado com sucesso!`);
      setExportModalOpen(false);
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao exportar XMLs.");
    } finally {
      setIsExporting(false);
    }
  };

  return (
    <PageLayout className="space-y-4 py-4 md:space-y-6 md:py-6 lg:py-8">
      {/* Cabeçalho */}
      <PageHeader
        title="Central de Notas Fiscais"
        description="Gestão integrada de emissão, consulta em tempo real, cancelamento homologado pela SEFAZ, DANFE e XMLs."
        action={
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={loadDocuments}
              className="btn-secondary inline-flex items-center gap-1.5 text-xs font-medium"
              title="Atualizar lista de notas"
            >
              <RefreshCw size={14} className={loading ? "animate-spin" : ""} />
              Atualizar
            </button>
            <button
              type="button"
              onClick={() => setImportNfeModalOpen(true)}
              className="btn-secondary inline-flex items-center gap-1.5 text-xs font-medium"
              title="Dar entrada em notas fiscais de compra por chave SEFAZ ou arquivo XML"
            >
              <PackagePlus size={15} />
              Entrada de NF-e
            </button>
            <button
              type="button"
              onClick={() => setExportModalOpen(true)}
              className="btn-primary inline-flex items-center gap-2 text-xs font-medium"
            >
              <FileArchive size={15} />
              Exportar XMLs (Contabilidade)
            </button>
          </div>
        }
      />

      {/* Alerta de Certificado */}
      {!company?.certificadoHasValue && (
        <section className="card flex items-center gap-3 border border-amber-500/30 bg-amber-500/10 p-4 text-xs text-amber-700 dark:text-amber-400">
          <AlertTriangle size={20} className="shrink-0 text-amber-500" />
          <div>
            <p className="font-semibold">Nenhum certificado digital A1 configurado.</p>
            <p className="mt-0.5 opacity-90">
              Cadastre o certificado digital e o código CSC em <strong>Minha Empresa</strong> para transmitir suas NFC-e à SEFAZ. Sem isso, as vendas ficam aguardando em contingência local.
            </p>
          </div>
        </section>
      )}

      {/* Cards de Métricas Operacionais (KPIs) */}
      <section className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        {/* Total Emitido */}
        <div
          onClick={() => setStatusFilter("todos")}
          className={`card p-4 cursor-pointer transition-all border ${
            statusFilter === "todos" ? "ring-2 ring-accent border-accent" : "hover:border-border-secondary"
          }`}
        >
          <div className="flex items-center justify-between text-text-secondary">
            <span className="text-xs font-semibold uppercase tracking-wider">Total Emitido</span>
            <Receipt size={16} className="text-accent" />
          </div>
          <div className="mt-2 flex items-baseline gap-2">
            <span className="text-xl font-bold text-text-primary">{metrics.totalEmitidas}</span>
            <span className="text-xs text-text-secondary font-medium">notas</span>
          </div>
          <div className="mt-1 text-xs font-semibold text-accent">
            R$ {formatMoneyBr(metrics.totalValor)}
          </div>
        </div>

        {/* Autorizadas */}
        <div
          onClick={() => setStatusFilter("autorizado")}
          className={`card p-4 cursor-pointer transition-all border ${
            statusFilter === "autorizado" ? "ring-2 ring-success border-success" : "hover:border-border-secondary"
          }`}
        >
          <div className="flex items-center justify-between text-text-secondary">
            <span className="text-xs font-semibold uppercase tracking-wider text-success">Autorizadas</span>
            <Check size={16} className="text-success" />
          </div>
          <div className="mt-2 flex items-baseline gap-2">
            <span className="text-xl font-bold text-success">{metrics.qtdAutorizadas}</span>
            <span className="text-xs text-text-secondary font-medium">notas</span>
          </div>
          <div className="mt-1 text-xs font-semibold text-text-primary">
            R$ {formatMoneyBr(metrics.valorAutorizadas)}
          </div>
        </div>

        {/* Canceladas & Devolvidas */}
        <div
          onClick={() => setStatusFilter(statusFilter === "cancelado" ? "devolvido" : "cancelado")}
          className={`card p-4 cursor-pointer transition-all border ${
            statusFilter === "cancelado" || statusFilter === "devolvido" ? "ring-2 ring-primary border-primary" : "hover:border-border-secondary"
          }`}
        >
          <div className="flex items-center justify-between text-text-secondary">
            <span className="text-xs font-semibold uppercase tracking-wider text-rose-400">Canceladas / Devolvidas</span>
            <XCircle size={16} className="text-rose-400" />
          </div>
          <div className="mt-2 flex items-baseline gap-2">
            <span className="text-xl font-bold text-text-primary">{metrics.qtdCanceladas + metrics.qtdDevolvidas}</span>
            <span className="text-xs text-text-secondary font-medium">({metrics.qtdCanceladas} canc. · {metrics.qtdDevolvidas} dev.)</span>
          </div>
          <div className="mt-1 text-xs font-semibold text-text-secondary">
            R$ {formatMoneyBr(metrics.valorCanceladas + metrics.valorDevolvidas)}
          </div>
        </div>

        {/* Rejeitadas / Pendências */}
        <div
          onClick={() => setStatusFilter("rejeitado")}
          className={`card p-4 cursor-pointer transition-all border ${
            statusFilter === "rejeitado" ? "ring-2 ring-primary border-primary" : "hover:border-border-secondary"
          }`}
        >
          <div className="flex items-center justify-between text-text-secondary">
            <span className="text-xs font-semibold uppercase tracking-wider text-primary">Rejeitadas / Erros</span>
            <AlertCircle size={16} className="text-primary" />
          </div>
          <div className="mt-2 flex items-baseline gap-2">
            <span className={`text-xl font-bold ${metrics.qtdRejeitadas > 0 ? "text-primary" : "text-text-secondary"}`}>
              {metrics.qtdRejeitadas}
            </span>
            <span className="text-xs text-text-secondary font-medium">com pendência</span>
          </div>
          <div className="mt-1 text-xs text-text-secondary">
            {metrics.qtdRejeitadas > 0 ? "Clique para corrigir" : "Nenhum erro pendente"}
          </div>
        </div>
      </section>

      {/* Abas Superiores */}
      <div className="flex border-b border-border-primary">
        <button
          type="button"
          onClick={() => setActiveTab("notas")}
          className={`flex items-center gap-2 border-b-2 px-4 py-2.5 text-xs font-semibold transition-colors ${
            activeTab === "notas"
              ? "border-accent text-accent"
              : "border-transparent text-text-secondary hover:text-text-primary"
          }`}
        >
          <Layers size={14} />
          Painel de Notas Fiscais ({filteredDocuments.length})
        </button>
        <button
          type="button"
          onClick={() => setActiveTab("inutilizacao")}
          className={`flex items-center gap-2 border-b-2 px-4 py-2.5 text-xs font-semibold transition-colors ${
            activeTab === "inutilizacao"
              ? "border-accent text-accent"
              : "border-transparent text-text-secondary hover:text-text-primary"
          }`}
        >
          <RotateCcw size={14} />
          Inutilização de Numeração
        </button>
      </div>

      {activeTab === "notas" ? (
        <>
          {/* Barra de Filtros e Busca */}
          <section className="card p-4 space-y-3">
            <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
              {/* Barra de Busca */}
              <div className="relative flex-1 max-w-lg">
                <Search
                  size={15}
                  className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-text-tertiary"
                />
                <input
                  value={search}
                  onChange={(e) => {
                    setSearch(e.target.value);
                    setCurrentPage(1);
                  }}
                  placeholder="Buscar por nº da venda, número da NF, chave de 44 dígitos ou cliente..."
                  className="input-field w-full pl-9 text-xs"
                />
                {search && (
                  <button
                    type="button"
                    onClick={() => setSearch("")}
                    className="absolute right-2.5 top-1/2 -translate-y-1/2 text-text-secondary hover:text-text-primary"
                  >
                    <X size={14} />
                  </button>
                )}
              </div>

              {/* Filtro por Período */}
              <div className="flex items-center gap-1 bg-bg-secondary p-1 rounded-xl border border-border-secondary">
                <span className="px-2 text-[11px] font-semibold text-text-secondary flex items-center gap-1">
                  <Calendar size={12} /> Período:
                </span>
                <button
                  type="button"
                  onClick={() => setPeriodFilter("todos")}
                  className={`rounded-lg px-2.5 py-1 text-xs font-medium transition-colors ${
                    periodFilter === "todos" ? "bg-bg-light text-text-primary shadow-sm" : "text-text-secondary hover:text-text-primary"
                  }`}
                >
                  Todos
                </button>
                <button
                  type="button"
                  onClick={() => setPeriodFilter("hoje")}
                  className={`rounded-lg px-2.5 py-1 text-xs font-medium transition-colors ${
                    periodFilter === "hoje" ? "bg-bg-light text-text-primary shadow-sm" : "text-text-secondary hover:text-text-primary"
                  }`}
                >
                  Hoje
                </button>
                <button
                  type="button"
                  onClick={() => setPeriodFilter("7dias")}
                  className={`rounded-lg px-2.5 py-1 text-xs font-medium transition-colors ${
                    periodFilter === "7dias" ? "bg-bg-light text-text-primary shadow-sm" : "text-text-secondary hover:text-text-primary"
                  }`}
                >
                  7 Dias
                </button>
                <button
                  type="button"
                  onClick={() => setPeriodFilter("mes")}
                  className={`rounded-lg px-2.5 py-1 text-xs font-medium transition-colors ${
                    periodFilter === "mes" ? "bg-bg-light text-text-primary shadow-sm" : "text-text-secondary hover:text-text-primary"
                  }`}
                >
                  Este Mês
                </button>
              </div>
            </div>

            {/* Pílulas de Status */}
            <div className="flex flex-wrap items-center gap-1.5 pt-1 border-t border-border-primary/60">
              <span className="text-[11px] font-semibold text-text-secondary mr-1 flex items-center gap-1">
                <Filter size={12} /> Status:
              </span>
              <button
                type="button"
                onClick={() => setStatusFilter("todos")}
                className={`rounded-full px-3 py-1 text-xs font-medium transition-colors ${
                  statusFilter === "todos"
                    ? "bg-accent text-white font-semibold"
                    : "bg-bg-secondary text-text-secondary hover:bg-hover-light hover:text-text-primary"
                }`}
              >
                Todas ({documents.length})
              </button>
              <button
                type="button"
                onClick={() => setStatusFilter("autorizado")}
                className={`rounded-full px-3 py-1 text-xs font-medium transition-colors ${
                  statusFilter === "autorizado"
                    ? "bg-success text-white font-semibold"
                    : "bg-bg-secondary text-text-secondary hover:bg-hover-light hover:text-text-primary"
                }`}
              >
                Autorizadas ({metrics.qtdAutorizadas})
              </button>
              <button
                type="button"
                onClick={() => setStatusFilter("cancelado")}
                className={`rounded-full px-3 py-1 text-xs font-medium transition-colors ${
                  statusFilter === "cancelado"
                    ? "bg-rose-600 text-white font-semibold"
                    : "bg-bg-secondary text-text-secondary hover:bg-hover-light hover:text-text-primary"
                }`}
              >
                Canceladas ({metrics.qtdCanceladas})
              </button>
              <button
                type="button"
                onClick={() => setStatusFilter("devolvido")}
                className={`rounded-full px-3 py-1 text-xs font-medium transition-colors ${
                  statusFilter === "devolvido"
                    ? "bg-purple-600 text-white font-semibold"
                    : "bg-bg-secondary text-text-secondary hover:bg-hover-light hover:text-text-primary"
                }`}
              >
                Devolvidas ({metrics.qtdDevolvidas})
              </button>
              <button
                type="button"
                onClick={() => setStatusFilter("rejeitado")}
                className={`rounded-full px-3 py-1 text-xs font-medium transition-colors ${
                  statusFilter === "rejeitado"
                    ? "bg-primary text-white font-semibold"
                    : "bg-bg-secondary text-text-secondary hover:bg-hover-light hover:text-text-primary"
                }`}
              >
                Rejeitadas / Erros ({metrics.qtdRejeitadas})
              </button>
              <button
                type="button"
                onClick={() => setStatusFilter("contingencia")}
                className={`rounded-full px-3 py-1 text-xs font-medium transition-colors ${
                  statusFilter === "contingencia"
                    ? "bg-accent/80 text-white font-semibold"
                    : "bg-bg-secondary text-text-secondary hover:bg-hover-light hover:text-text-primary"
                }`}
              >
                Contingência / Transmitindo ({metrics.qtdContingencia})
              </button>

              {(statusFilter !== "todos" || periodFilter !== "todos" || search) && (
                <button
                  type="button"
                  onClick={() => {
                    setStatusFilter("todos");
                    setPeriodFilter("todos");
                    setSearch("");
                  }}
                  className="text-[11px] text-accent hover:underline ml-auto font-medium"
                >
                  Limpar todos os filtros
                </button>
              )}
            </div>
          </section>

          {/* Tabela de Documentos Fiscais */}
          <section className="card overflow-hidden">
            <div className="overflow-x-auto">
              <table className="w-full min-w-[950px] text-left text-xs">
                <thead className="bg-bg-primary text-text-secondary border-b border-border-primary">
                  <tr>
                    <th className="px-4 py-3 font-semibold">Venda / Data</th>
                    <th className="px-4 py-3 font-semibold">Nota (Série/Nº)</th>
                    <th className="px-4 py-3 font-semibold">Valor Total</th>
                    <th className="px-4 py-3 font-semibold">Destinatário</th>
                    <th className="px-4 py-3 font-semibold">Chave de Acesso</th>
                    <th className="px-4 py-3 font-semibold">Status SEFAZ</th>
                    <th className="px-4 py-3 font-semibold text-center">Ações Fiscais</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border-primary">
                  {loading && (
                    <tr>
                      <td colSpan={7} className="px-4 py-8 text-center text-text-secondary">
                        <div className="flex items-center justify-center gap-2">
                          <Loader2 size={16} className="animate-spin text-accent" />
                          <span>Carregando documentos fiscais...</span>
                        </div>
                      </td>
                    </tr>
                  )}

                  {!loading && paginatedDocuments.length === 0 && (
                    <tr>
                      <td colSpan={7} className="px-4 py-8 text-center text-text-secondary">
                        Nenhum documento fiscal encontrado com os filtros selecionados.
                      </td>
                    </tr>
                  )}

                  {!loading &&
                    paginatedDocuments.map((doc) => {
                      const isDevolvido = doc.status === FISCAL_STATUS.Devolvido || Boolean(doc.devolvida);
                      const isDevolucaoNfe = doc.modelo === 55 && Boolean(doc.chaveReferenciada);
                      const isCancelado = doc.status === FISCAL_STATUS.Cancelado;
                      const isAutorizado = doc.status === FISCAL_STATUS.Autorizado && !isDevolvido;
                      const isRejeitado =
                        doc.status === FISCAL_STATUS.Rejeitado ||
                        Boolean(doc.motivoStatus && !isAutorizado && !isCancelado && !isDevolvido && !isDevolucaoNfe);

                      return (
                        <tr key={doc.id} className="hover:bg-accent/5 transition-colors">
                          {/* Venda e Data */}
                          <td className="px-4 py-3">
                            <span className="block font-bold text-text-primary text-sm">{doc.saleNumber}</span>
                            <span className="block text-[11px] text-text-secondary mt-0.5">
                              {formatDate(doc.criadoEm)}
                            </span>
                          </td>

                          {/* Série / Número */}
                          <td className="px-4 py-3 font-mono text-text-primary">
                            <span className="font-semibold block">
                              {doc.modelo === 55 ? "NF-e (Mod. 55)" : "NFC-e"} · Série {doc.serie} · Nº {doc.numeroNf}
                            </span>
                            {isDevolvido && doc.numeroNfeDevolucao && (
                              <span className="text-[11px] text-purple-400 font-normal mt-0.5 flex items-center gap-1">
                                <RotateCcw size={10} /> Devolvida via NF-e nº {doc.numeroNfeDevolucao}
                              </span>
                            )}
                            {isDevolucaoNfe && (
                              <span className="text-[11px] text-indigo-400 font-normal mt-0.5 flex items-center gap-1">
                                <FileCode size={10} /> Devolução de Entrada
                              </span>
                            )}
                          </td>

                          {/* Valor Total */}
                          <td className="px-4 py-3">
                            <span className="font-bold text-text-primary text-sm">
                              {doc.totalAmount ? `R$ ${doc.totalAmount}` : "—"}
                            </span>
                            <span className="block text-[10px] text-text-secondary capitalize">
                              {doc.paymentType || "Dinheiro"}
                            </span>
                          </td>

                          {/* Destinatário */}
                          <td className="px-4 py-3 max-w-[160px]">
                            <span className="block font-medium text-text-primary truncate" title={doc.customerName || "Consumidor Final"}>
                              {doc.customerName || "Consumidor Final"}
                            </span>
                            <span className="block font-mono text-[10px] text-text-secondary">
                              {doc.customerCpf && doc.customerCpf !== "-" ? doc.customerCpf : "Não identificado"}
                            </span>
                          </td>

                          {/* Chave de Acesso com Cópia Rápida */}
                          <td className="px-4 py-3">
                            {doc.chaveAcesso ? (
                              <div className="flex items-center gap-1.5">
                                <span className="font-mono text-[11px] text-text-secondary" title={doc.chaveAcesso}>
                                  {formatChaveCurta(doc.chaveAcesso)}
                                </span>
                                <button
                                  type="button"
                                  onClick={() => handleCopyChave(doc)}
                                  className="p-1 rounded text-text-secondary hover:text-accent hover:bg-accent/10 transition-colors"
                                  title="Copiar chave de acesso completa"
                                >
                                  {copiedChaveId === doc.id ? (
                                    <Check size={12} className="text-success" />
                                  ) : (
                                    <Copy size={12} />
                                  )}
                                </button>
                              </div>
                            ) : (
                              <span className="text-text-secondary">—</span>
                            )}
                          </td>

                          {/* Status Badge */}
                          <td className="px-4 py-3">
                            {isRejeitado ? (
                              <button
                                type="button"
                                onClick={() => setErrorModalDoc(doc)}
                                className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-semibold cursor-pointer transition-transform hover:scale-105 ${fiscalStatusBadgeClass(
                                  doc.status,
                                  doc.modelo,
                                  isDevolucaoNfe,
                                )}`}
                                title="Ver motivo exato da rejeição na SEFAZ"
                              >
                                <AlertCircle size={12} />
                                {fiscalStatusLabel(doc.status, doc.modelo, isDevolucaoNfe)}
                              </button>
                            ) : isDevolvido ? (
                              <button
                                type="button"
                                onClick={() => setDocToDetail(doc)}
                                className="inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-semibold cursor-pointer transition-transform hover:scale-105 border border-purple-500/40 bg-purple-500/15 text-purple-400"
                                title={
                                  doc.numeroNfeDevolucao
                                    ? `Devolvida via NF-e nº ${doc.numeroNfeDevolucao}`
                                    : "Nota fiscal devolvida (estorno homologado)"
                                }
                              >
                                <RotateCcw size={12} />
                                <span>Devolvida</span>
                              </button>
                            ) : isCancelado ? (
                              <button
                                type="button"
                                onClick={() => setDocToDetail(doc)}
                                className="inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-semibold cursor-pointer transition-transform hover:scale-105 border border-rose-500/40 bg-rose-500/15 text-rose-400"
                                title="Nota fiscal cancelada perante a SEFAZ"
                              >
                                <AlertOctagon size={12} />
                                <span>Cancelada</span>
                              </button>
                            ) : (
                              <button
                                type="button"
                                onClick={() => setDocToDetail(doc)}
                                className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-semibold cursor-pointer transition-transform hover:scale-105 ${fiscalStatusBadgeClass(
                                  doc.status,
                                  doc.modelo,
                                  isDevolucaoNfe,
                                )}`}
                                title="Clique para ver detalhes completos da nota"
                              >
                                {isAutorizado && <Check size={11} />}
                                {fiscalStatusLabel(doc.status, doc.modelo, isDevolucaoNfe)}
                              </button>
                            )}
                          </td>

                          {/* Ações Diretas */}
                          <td className="px-4 py-3 text-center">
                            <div className="flex items-center justify-center gap-1">
                              {/* Botão Raio-X Detalhes */}
                              <button
                                type="button"
                                onClick={() => setDocToDetail(doc)}
                                className="rounded-lg p-1.5 text-text-secondary hover:bg-hover-light hover:text-accent transition-colors"
                                title="Visualizar Raio-X da Nota"
                              >
                                <Eye size={15} />
                              </button>

                              {/* Botão Imprimir DANFE */}
                              {isAutorizado && (
                                <button
                                  type="button"
                                  onClick={() => printDanfeDirect(doc.saleNumber)}
                                  disabled={busyIds.has(doc.id)}
                                  className="rounded-lg p-1.5 text-text-secondary hover:bg-hover-light hover:text-accent transition-colors"
                                  title="Imprimir DANFE 80mm"
                                >
                                  <Printer size={15} />
                                </button>
                              )}

                              {/* Botão Cancelar Direto */}
                              {isAutorizado && (
                                <button
                                  type="button"
                                  onClick={() => setDocToCancel(doc)}
                                  className="rounded-lg p-1.5 text-primary hover:bg-primary/10 transition-colors"
                                  title="Cancelar esta nota fiscal perante a SEFAZ"
                                >
                                  <AlertOctagon size={15} />
                                </button>
                              )}

                              {/* Botão Reemitir se Rejeitada */}
                              {isRejeitado && (
                                <button
                                  type="button"
                                  onClick={() => reemitir(doc)}
                                  disabled={busyIds.has(doc.id)}
                                  className="rounded-lg p-1.5 text-accent hover:bg-accent/10 transition-colors"
                                  title="Tentar reemitir na SEFAZ"
                                >
                                  <RefreshCw size={15} className={busyIds.has(doc.id) ? "animate-spin" : ""} />
                                </button>
                              )}

                              {/* Menu Suspenso Mais Opções */}
                              <RowActionsMenu
                                items={[
                                  {
                                    key: "detalhes",
                                    label: "Raio-X da Nota",
                                    icon: <Eye size={13} />,
                                    onClick: () => setDocToDetail(doc),
                                  },
                                  ...(isAutorizado
                                    ? [
                                        {
                                          key: "danfe-screen",
                                          label: "Ver DANFE na Tela (QR Code)",
                                          icon: <QrCode size={13} />,
                                          onClick: async () => {
                                            const detail = await fiscalService.getBySaleNumber(doc.saleNumber);
                                            if (detail) setDanfePreview(detail);
                                          },
                                        },
                                        {
                                          key: "printDanfe",
                                          label: "Imprimir DANFE 80mm",
                                          icon: <Printer size={13} />,
                                          onClick: () => printDanfeDirect(doc.saleNumber),
                                        },
                                        {
                                          key: "cancelar",
                                          label: "Cancelar Nota na SEFAZ",
                                          icon: <AlertOctagon size={13} />,
                                          danger: true,
                                          onClick: () => setDocToCancel(doc),
                                        },
                                      ]
                                    : []),
                                  ...(doc.hasXml
                                    ? [
                                        {
                                          key: "xml-aut",
                                          label: "Baixar XML Autorizado",
                                          icon: <FileCode size={13} />,
                                          onClick: () => handleDownloadXml(doc, "autorizado"),
                                        },
                                      ]
                                    : []),
                                  ...(isCancelado && doc.hasCancelXml
                                    ? [
                                        {
                                          key: "xml-canc",
                                          label: "Baixar XML de Cancelamento",
                                          icon: <Download size={13} />,
                                          onClick: () => handleDownloadXml(doc, "cancelamento"),
                                        },
                                      ]
                                    : []),
                                  ...(doc.chaveAcesso
                                    ? [
                                        {
                                          key: "copiar-chave",
                                          label: "Copiar Chave de Acesso",
                                          icon: <Copy size={13} />,
                                          onClick: () => handleCopyChave(doc),
                                        },
                                        {
                                          key: "portal-sefaz",
                                          label: "Consultar no Portal SEFAZ",
                                          icon: <ExternalLink size={13} />,
                                          onClick: () => {
                                            window.open(`${getSefazConsultaUrl()}?p=${doc.chaveAcesso}`, "_blank");
                                          },
                                        },
                                      ]
                                    : []),
                                  ...(isRejeitado
                                    ? [
                                        {
                                          key: "motivo-erro",
                                          label: "Ver Motivo da Rejeição",
                                          icon: <AlertTriangle size={13} />,
                                          onClick: () => setErrorModalDoc(doc),
                                        },
                                        {
                                          key: "reemitir-menu",
                                          label: "Reemitir Nota Fiscal",
                                          icon: <RefreshCw size={13} />,
                                          onClick: () => reemitir(doc),
                                        },
                                      ]
                                    : []),
                                ]}
                              />
                            </div>
                          </td>
                        </tr>
                      );
                    })}
                </tbody>
              </table>
            </div>

            {/* Paginação */}
            <div className="px-4 py-3 border-t border-border-primary">
              <TablePagination
                totalItems={filteredDocuments.length}
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
        </>
      ) : (
        /* Aba de Inutilização de Faixa de Numeração */
        <section className="card p-5 space-y-4 max-w-2xl">
          <div className="space-y-1">
            <h2 className="text-base font-bold text-text-primary flex items-center gap-2">
              <RotateCcw size={18} className="text-accent" />
              Inutilizar Faixa de Numeração Fiscal
            </h2>
            <p className="text-xs text-text-secondary leading-relaxed">
              A inutilização de numeração é exigida pela SEFAZ quando uma quebra de sequência numérica ocorre no estabelecimento e esses números não chegaram a emitir NFC-e. Não use para cancelar notas que já foram autorizadas.
            </p>
          </div>

          <div className="rounded-xl border border-amber-500/30 bg-amber-500/10 p-3 text-xs text-amber-700 dark:text-amber-400 space-y-1">
            <p className="font-semibold">Aviso Importante:</p>
            <p className="leading-relaxed opacity-90">
              A faixa inutilizada não poderá mais ser emitida por nenhum PDV. A justificativa informada é registrada oficialmente nos servidores da SEFAZ para fins de auditoria tributária.
            </p>
          </div>

          <div className="grid gap-3 sm:grid-cols-3">
            <label className="block">
              <span className="mb-1 block text-xs font-semibold text-text-secondary">Série Fiscal</span>
              <input
                className="input-field w-full text-xs font-medium"
                inputMode="numeric"
                value={inutilizarSerie}
                onChange={(e) => setInutilizarSerie(e.target.value.replace(/\D/g, "").slice(0, 3))}
              />
            </label>
            <label className="block">
              <span className="mb-1 block text-xs font-semibold text-text-secondary">Número Inicial</span>
              <input
                className="input-field w-full text-xs font-medium"
                inputMode="numeric"
                value={inutilizarInicial}
                onChange={(e) => setInutilizarInicial(e.target.value.replace(/\D/g, "").slice(0, 9))}
                placeholder="Ex: 101"
              />
            </label>
            <label className="block">
              <span className="mb-1 block text-xs font-semibold text-text-secondary">Número Final</span>
              <input
                className="input-field w-full text-xs font-medium"
                inputMode="numeric"
                value={inutilizarFinal}
                onChange={(e) => setInutilizarFinal(e.target.value.replace(/\D/g, "").slice(0, 9))}
                placeholder="Ex: 105"
              />
            </label>
          </div>

          <div className="pt-2">
            <button
              type="button"
              onClick={inutilizar}
              disabled={inutilizando || !inutilizarInicial || !inutilizarFinal}
              className="btn-danger inline-flex items-center gap-2 text-xs font-semibold"
            >
              {inutilizando ? (
                <>
                  <Loader2 size={14} className="animate-spin" />
                  Inutilizando perante a SEFAZ...
                </>
              ) : (
                <>
                  <RotateCcw size={14} />
                  Homologar Inutilização na SEFAZ
                </>
              )}
            </button>
          </div>
        </section>
      )}

      {/* Modal de Cancelamento/Devolução Estruturado (mesmas regras do caixa/PDV) */}
      {docToCancel && (
        <PdvNfceCancelModal
          isOpen={Boolean(docToCancel)}
          initialDocument={docToCancel}
          onClose={() => setDocToCancel(null)}
          onSuccess={() => {
            setDocToCancel(null);
            loadDocuments();
          }}
        />
      )}

      {/* Modal de Raio-X Detalhes */}
      {docToDetail && (
        <FiscalDetailModal
          document={docToDetail}
          companyName={company?.fantasyName || company?.corporateName || "Quack PDV"}
          onClose={() => setDocToDetail(null)}
          onPrintDanfe={(doc) => {
            setDocToDetail(null);
            printDanfeDirect(doc.saleNumber);
          }}
          onOpenCancel={(doc) => {
            setDocToDetail(null);
            setDocToCancel(doc);
          }}
          onReemitir={async (doc) => {
            setDocToDetail(null);
            await reemitir(doc);
          }}
          onDownloadXml={(doc, tipo) => handleDownloadXml(doc, tipo)}
        />
      )}

      {/* Modal de Erro SEFAZ */}
      {errorModalDoc && (
        <FiscalErrorModal
          document={errorModalDoc}
          onClose={() => setErrorModalDoc(null)}
          onReemitir={async (doc) => {
            await reemitir(doc);
            setErrorModalDoc(null);
          }}
          isReemitindo={busyIds.has(errorModalDoc.id)}
        />
      )}

      {/* Modal DANFE na Tela */}
      {danfePreview && (
        <DanfePreviewModal
          detail={danfePreview}
          companyName={company?.fantasyName || company?.corporateName || "Quack PDV"}
          onClose={() => setDanfePreview(null)}
          onPrintDanfe={(detail) => {
            setDanfePreview(null);
            void printDanfeDirect(detail.saleNumber);
          }}
        />
      )}

      {/* Modal de Impressão Térmica de Recibo/DANFE */}
      {receiptPreview && (
        <ReceiptPreviewModal
          receipt={receiptPreview}
          formatMoney={formatMoneyBr}
          onClose={() => setReceiptPreview(null)}
        />
      )}

      {/* Modal de Exportação Mensal de XMLs (.ZIP) */}
      {exportModalOpen && (
        <div className="fixed inset-0 z-layer-dialog flex items-end bg-black/60 px-3 backdrop-blur-sm md:items-center md:justify-center">
          <div className="w-full max-w-md overflow-hidden rounded-2xl border border-border-primary bg-bg-light shadow-2xl">
            <div className="flex items-center justify-between border-b border-border-primary px-5 py-4">
              <div className="flex items-center gap-2.5">
                <span className="inline-flex h-9 w-9 items-center justify-center rounded-xl bg-accent/10 text-accent">
                  <FileArchive size={18} />
                </span>
                <div>
                  <h3 className="text-base font-bold text-text-primary">Exportar XMLs (Contabilidade)</h3>
                  <p className="text-xs text-text-secondary">Pacote compactado .ZIP de notas autorizadas e canceladas</p>
                </div>
              </div>
              <button
                type="button"
                onClick={() => setExportModalOpen(false)}
                disabled={isExporting}
                className="inline-flex h-8 w-8 items-center justify-center rounded-lg text-text-secondary hover:bg-hover-light"
                aria-label="Fechar"
              >
                <X size={16} />
              </button>
            </div>

            <div className="space-y-4 p-5">
              <div className="rounded-xl border border-border-secondary bg-bg-primary p-3.5 text-xs text-text-secondary leading-relaxed">
                <p className="font-semibold text-text-primary mb-1">📦 Fechamento Mensal / SPED / Simples Nacional</p>
                Este arquivo .ZIP contém todos os arquivos XML com a assinatura digital e protocolo oficial da SEFAZ, organizados por chave de acesso para envio direto ao seu contador.
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="mb-1 block text-xs font-semibold text-text-secondary">Mês de Competência</label>
                  <select
                    value={exportMonth}
                    onChange={(e) => setExportMonth(Number(e.target.value))}
                    disabled={isExporting}
                    className="input-field w-full text-xs font-medium"
                  >
                    <option value={1}>01 - Janeiro</option>
                    <option value={2}>02 - Fevereiro</option>
                    <option value={3}>03 - Março</option>
                    <option value={4}>04 - Abril</option>
                    <option value={5}>05 - Maio</option>
                    <option value={6}>06 - Junho</option>
                    <option value={7}>07 - Julho</option>
                    <option value={8}>08 - Agosto</option>
                    <option value={9}>09 - Setembro</option>
                    <option value={10}>10 - Outubro</option>
                    <option value={11}>11 - Novembro</option>
                    <option value={12}>12 - Dezembro</option>
                  </select>
                </div>
                <div>
                  <label className="mb-1 block text-xs font-semibold text-text-secondary">Ano</label>
                  <select
                    value={exportYear}
                    onChange={(e) => setExportYear(Number(e.target.value))}
                    disabled={isExporting}
                    className="input-field w-full text-xs font-medium"
                  >
                    {Array.from({ length: 5 }, (_, i) => now.getFullYear() - i).map((year) => (
                      <option key={year} value={year}>
                        {year}
                      </option>
                    ))}
                  </select>
                </div>
              </div>
            </div>

            <div className="flex justify-end gap-2 border-t border-border-primary bg-bg-secondary px-5 py-3">
              <button
                type="button"
                onClick={() => setExportModalOpen(false)}
                disabled={isExporting}
                className="btn-secondary text-xs"
              >
                Cancelar
              </button>
              <button
                type="button"
                onClick={handleExportXmls}
                disabled={isExporting}
                className="btn-primary inline-flex items-center gap-2 text-xs font-semibold"
              >
                {isExporting ? <Loader2 size={14} className="animate-spin" /> : <Download size={14} />}
                {isExporting ? "Compactando e baixando..." : "Baixar Pacote (.ZIP)"}
              </button>
            </div>
          </div>
        </div>
      )}

      {importNfeModalOpen && (
        <NfeImportModal
          onClose={() => setImportNfeModalOpen(false)}
          onImported={() => {
            setImportNfeModalOpen(false);
            loadDocuments();
          }}
        />
      )}

      {PromptDialog}
    </PageLayout>
  );
}
