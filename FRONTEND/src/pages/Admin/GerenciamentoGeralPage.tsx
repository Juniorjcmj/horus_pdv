/**
 * Arquivo: FRONTEND/src/pages/Admin/GerenciamentoGeralPage.tsx
 * Objetivo: Módulo de SuperAdmin para aprovação, auditoria, bloqueio e rejeição de empresas clientes no Hórus PDV.
 * Acesso exclusivo: Administradores da plataforma ('empresa-principal').
 */
import { useEffect, useState, useCallback } from "react";
import {
  AlertTriangle,
  Building2,
  Check,
  CheckCircle2,
  Clock,
  Eye,
  Info,
  Lock,
  Mail,
  Phone,
  RefreshCw,
  Search,
  Shield,
  ShieldAlert,
  ShieldCheck,
  User,
  Users,
  X,
  XCircle,
} from "lucide-react";
import PageHeader from "@/components/Admin/PageHeader";
import LoadingBar from "@/components/Loading/LoadingBar";
import PageLayout from "@/layout/PageLayout";
import {
  superAdminService,
  type EmpresaAdminItem,
  type EmpresasMetricas,
  type EmpresaStatus,
} from "@/services/api/superAdminService";
import { maskCnpj } from "@/utils/inputMasks";

type StatusTab = "todas" | "pendente" | "aprovada" | "rejeitada" | "bloqueada";

const QUICK_REJECTION_REASONS = [
  "CNPJ inválido ou inativo na Receita Federal",
  "Não foi possível confirmar os dados de contato do responsável",
  "Cadastro duplicado identificado",
  "Atividade não compatível com o escopo da plataforma",
  "Solicitação de cancelamento pelo próprio usuário",
];

export default function GerenciamentoGeralPage() {
  const [metricas, setMetricas] = useState<EmpresasMetricas>({
    total: 0,
    pendentes: 0,
    aprovadas: 0,
    rejeitadas: 0,
    bloqueadas: 0,
    requireApprovalForNewCompanies: true,
  });
  const [empresas, setEmpresas] = useState<EmpresaAdminItem[]>([]);
  const [totalCount, setTotalCount] = useState(0);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [isUpdatingConfig, setIsUpdatingConfig] = useState(false);
  const [actionLoadingId, setActionLoadingId] = useState<string | null>(null);

  // Filtros e paginação
  const [searchTerm, setSearchTerm] = useState("");
  const [activeTab, setActiveTab] = useState<StatusTab>("todas");
  const [currentPage, setCurrentPage] = useState(1);
  const pageSize = 15;

  // Modais
  const [selectedEmpresa, setSelectedEmpresa] = useState<EmpresaAdminItem | null>(null);
  const [empresaToApprove, setEmpresaToApprove] = useState<EmpresaAdminItem | null>(null);
  const [empresaToReject, setEmpresaToReject] = useState<EmpresaAdminItem | null>(null);
  const [empresaToBlock, setEmpresaToBlock] = useState<EmpresaAdminItem | null>(null);
  const [rejectionReason, setRejectionReason] = useState("");
  const [blockReason, setBlockReason] = useState("");

  // Feedback banner
  const [feedback, setFeedback] = useState<{ type: "success" | "error"; message: string } | null>(
    null
  );

  const showToast = (message: string, type: "success" | "error" = "success") => {
    setFeedback({ type, message });
    setTimeout(() => {
      setFeedback((current) => (current?.message === message ? null : current));
    }, 5000);
  };

  const loadData = useCallback(
    async (silent = false) => {
      if (!silent) setIsLoading(true);
      else setIsRefreshing(true);

      try {
        const [metricasRes, listRes] = await Promise.all([
          superAdminService.getMetrics(),
          superAdminService.listCompanies({
            search: searchTerm,
            status: activeTab === "todas" ? undefined : activeTab,
            page: currentPage,
            pageSize,
          }),
        ]);

        setMetricas(metricasRes);
        setEmpresas(listRes.items);
        setTotalCount(listRes.totalCount);
      } catch (err) {
        showToast(
          err instanceof Error ? err.message : "Erro ao carregar dados das empresas.",
          "error"
        );
      } finally {
        setIsLoading(false);
        setIsRefreshing(false);
      }
    },
    [searchTerm, activeTab, currentPage]
  );

  useEffect(() => {
    loadData();
  }, [loadData]);

  // Alternar exigência de aprovação
  const handleToggleApprovalRequirement = async () => {
    const nextValue = !metricas.requireApprovalForNewCompanies;
    setIsUpdatingConfig(true);
    try {
      await superAdminService.updateConfig(nextValue);
      setMetricas((prev) => ({ ...prev, requireApprovalForNewCompanies: nextValue }));
      showToast(
        nextValue
          ? "Aprovação prévia ATIVADA: novos cadastros passarão por sua análise."
          : "Aprovação automática ATIVADA: novos cadastros terão acesso imediato.",
        "success"
      );
    } catch (err) {
      showToast(
        err instanceof Error ? err.message : "Erro ao atualizar configuração.",
        "error"
      );
    } finally {
      setIsUpdatingConfig(false);
    }
  };

  // Aprovação
  const handleConfirmApprove = async () => {
    if (!empresaToApprove) return;
    const emp = empresaToApprove;
    setActionLoadingId(emp.id);
    try {
      await superAdminService.approveCompany(emp.id);
      showToast(`Empresa "${emp.fantasyName}" aprovada com sucesso! O acesso foi liberado.`);
      setEmpresaToApprove(null);
      if (selectedEmpresa?.id === emp.id) {
        setSelectedEmpresa(null);
      }
      loadData(true);
    } catch (err) {
      showToast(
        err instanceof Error ? err.message : "Erro ao aprovar empresa.",
        "error"
      );
    } finally {
      setActionLoadingId(null);
    }
  };

  // Rejeição
  const handleConfirmReject = async () => {
    if (!empresaToReject) return;
    if (!rejectionReason.trim()) {
      showToast("Por favor, informe ou selecione o motivo da recusa.", "error");
      return;
    }
    const emp = empresaToReject;
    setActionLoadingId(emp.id);
    try {
      await superAdminService.rejectCompany(emp.id, rejectionReason.trim());
      showToast(`Inscrição de "${emp.fantasyName}" recusada com sucesso.`);
      setEmpresaToReject(null);
      setRejectionReason("");
      if (selectedEmpresa?.id === emp.id) {
        setSelectedEmpresa(null);
      }
      loadData(true);
    } catch (err) {
      showToast(
        err instanceof Error ? err.message : "Erro ao rejeitar inscrição.",
        "error"
      );
    } finally {
      setActionLoadingId(null);
    }
  };

  // Bloqueio
  const handleConfirmBlock = async () => {
    if (!empresaToBlock) return;
    if (!blockReason.trim()) {
      showToast("Informe o motivo do bloqueio.", "error");
      return;
    }
    const emp = empresaToBlock;
    setActionLoadingId(emp.id);
    try {
      await superAdminService.blockCompany(emp.id, blockReason.trim());
      showToast(`Empresa "${emp.fantasyName}" suspensa e bloqueada com sucesso.`);
      setEmpresaToBlock(null);
      setBlockReason("");
      if (selectedEmpresa?.id === emp.id) {
        setSelectedEmpresa(null);
      }
      loadData(true);
    } catch (err) {
      showToast(
        err instanceof Error ? err.message : "Erro ao bloquear empresa.",
        "error"
      );
    } finally {
      setActionLoadingId(null);
    }
  };

  // Reativação
  const handleReactivate = async (empresa: EmpresaAdminItem) => {
    setActionLoadingId(empresa.id);
    try {
      await superAdminService.reactivateCompany(empresa.id);
      showToast(`Empresa "${empresa.fantasyName}" reativada com sucesso! Acesso normalizado.`);
      if (selectedEmpresa?.id === empresa.id) {
        setSelectedEmpresa(null);
      }
      loadData(true);
    } catch (err) {
      showToast(
        err instanceof Error ? err.message : "Erro ao reativar empresa.",
        "error"
      );
    } finally {
      setActionLoadingId(null);
    }
  };

  const formatDate = (isoString?: string | null) => {
    if (!isoString) return "-";
    try {
      const d = new Date(isoString);
      return new Intl.DateTimeFormat("pt-BR", {
        day: "2-digit",
        month: "2-digit",
        year: "numeric",
        hour: "2-digit",
        minute: "2-digit",
      }).format(d);
    } catch {
      return isoString;
    }
  };

  const formatRelativeTime = (isoString?: string | null) => {
    if (!isoString) return "";
    try {
      const diffMs = Date.now() - new Date(isoString).getTime();
      const diffMins = Math.floor(diffMs / 60000);
      if (diffMins < 1) return "agora mesmo";
      if (diffMins < 60) return `há ${diffMins} min`;
      const diffHours = Math.floor(diffMins / 60);
      if (diffHours < 24) return `há ${diffHours} h`;
      const diffDays = Math.floor(diffHours / 24);
      if (diffDays === 1) return "ontem";
      return `há ${diffDays} dias`;
    } catch {
      return "";
    }
  };

  const renderStatusBadge = (status: EmpresaStatus) => {
    switch (status) {
      case "pendente":
        return (
          <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-semibold bg-amber-500/10 text-amber-400 border border-amber-500/20">
            <span className="relative flex h-2 w-2">
              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-amber-400 opacity-75"></span>
              <span className="relative inline-flex rounded-full h-2 w-2 bg-amber-500"></span>
            </span>
            Aguardando Aprovação
          </span>
        );
      case "aprovada":
        return (
          <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-semibold bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
            <CheckCircle2 size={13} className="text-emerald-400" />
            Aprovada
          </span>
        );
      case "rejeitada":
        return (
          <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-semibold bg-rose-500/10 text-rose-400 border border-rose-500/20">
            <XCircle size={13} className="text-rose-400" />
            Inscrição Recusada
          </span>
        );
      case "bloqueada":
        return (
          <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-semibold bg-purple-500/10 text-purple-300 border border-purple-500/20">
            <Lock size={13} className="text-purple-300" />
            Acesso Bloqueado
          </span>
        );
      default:
        return null;
    }
  };

  const totalPages = Math.max(1, Math.ceil(totalCount / pageSize));

  return (
    <PageLayout>
      <PageHeader
        title="Gerenciamento Geral de Empresas"
        description="Painel Master da Plataforma Hórus PDV: controle de autorizações de novas empresas, triagem de inscrições e governança de acesso."
        action={
          <div className="flex flex-wrap items-center gap-3">
            {/* Toggle de aprovação obrigatória */}
            <div className="flex items-center gap-2.5 rounded-xl border border-border-primary bg-bg-light px-3.5 py-2 shadow-sm">
              <div className="flex flex-col">
                <span className="text-xs font-bold text-text-primary flex items-center gap-1.5">
                  <Shield size={13} className="text-accent" />
                  Aprovação Prévia de Novos Cadastros
                </span>
                <span className="text-[11px] text-text-secondary">
                  {metricas.requireApprovalForNewCompanies
                    ? "Ativa: exige autorização antes do 1º login"
                    : "Automática: novos cadastros entram liberados"}
                </span>
              </div>
              <button
                type="button"
                role="switch"
                aria-checked={metricas.requireApprovalForNewCompanies}
                disabled={isUpdatingConfig}
                onClick={handleToggleApprovalRequirement}
                className={`relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none focus-visible:ring-2 focus-visible:ring-accent ${
                  metricas.requireApprovalForNewCompanies ? "bg-accent" : "bg-slate-700"
                }`}
              >
                <span
                  className={`pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow-lg ring-0 transition duration-200 ease-in-out ${
                    metricas.requireApprovalForNewCompanies ? "translate-x-5" : "translate-x-0"
                  }`}
                />
              </button>
            </div>

            <button
              type="button"
              onClick={() => loadData(true)}
              disabled={isRefreshing}
              className="inline-flex items-center gap-1.5 rounded-xl border border-border-primary bg-bg-light px-3 py-2 text-xs font-semibold text-text-primary shadow-sm hover:bg-secondary/10 transition"
              title="Recarregar dados"
            >
              <RefreshCw size={14} className={isRefreshing ? "animate-spin text-accent" : ""} />
              <span>Atualizar</span>
            </button>
          </div>
        }
      />

      {/* Feedback Alert */}
      {feedback && (
        <div
          className={`mb-6 p-4 rounded-xl border flex items-center justify-between transition-all animate-fadeIn ${
            feedback.type === "success"
              ? "bg-emerald-500/10 border-emerald-500/30 text-emerald-300"
              : "bg-rose-500/10 border-rose-500/30 text-rose-300"
          }`}
        >
          <div className="flex items-center gap-3">
            {feedback.type === "success" ? (
              <CheckCircle2 size={18} className="text-emerald-400 shrink-0" />
            ) : (
              <AlertTriangle size={18} className="text-rose-400 shrink-0" />
            )}
            <p className="text-sm font-medium">{feedback.message}</p>
          </div>
          <button
            type="button"
            onClick={() => setFeedback(null)}
            className="text-text-secondary hover:text-text-primary p-1 rounded-lg"
          >
            <X size={16} />
          </button>
        </div>
      )}

      {/* Cards de Métricas */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
        {/* Pendentes */}
        <div
          onClick={() => {
            setActiveTab("pendente");
            setCurrentPage(1);
          }}
          className={`cursor-pointer rounded-2xl border p-4 transition-all duration-200 ${
            activeTab === "pendente"
              ? "bg-amber-500/15 border-amber-500/40 shadow-lg shadow-amber-500/10"
              : "bg-bg-light border-border-primary hover:border-amber-500/30 hover:bg-amber-500/5"
          }`}
        >
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold uppercase tracking-wider text-text-secondary">
              Aguardando Aprovação
            </span>
            <div className="h-9 w-9 rounded-xl bg-amber-500/15 border border-amber-500/30 flex items-center justify-center text-amber-400">
              <Clock size={18} />
            </div>
          </div>
          <div className="mt-2 flex items-baseline gap-2">
            <span className="text-3xl font-extrabold text-amber-400">{metricas.pendentes}</span>
            {metricas.pendentes > 0 && (
              <span className="relative flex h-2.5 w-2.5">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-amber-400 opacity-75"></span>
                <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-amber-500"></span>
              </span>
            )}
          </div>
          <p className="mt-1 text-xs text-text-secondary">Cadastros requerem sua análise</p>
        </div>

        {/* Aprovadas */}
        <div
          onClick={() => {
            setActiveTab("aprovada");
            setCurrentPage(1);
          }}
          className={`cursor-pointer rounded-2xl border p-4 transition-all duration-200 ${
            activeTab === "aprovada"
              ? "bg-emerald-500/15 border-emerald-500/40 shadow-lg shadow-emerald-500/10"
              : "bg-bg-light border-border-primary hover:border-emerald-500/30 hover:bg-emerald-500/5"
          }`}
        >
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold uppercase tracking-wider text-text-secondary">
              Empresas Aprovadas
            </span>
            <div className="h-9 w-9 rounded-xl bg-emerald-500/15 border border-emerald-500/30 flex items-center justify-center text-emerald-400">
              <ShieldCheck size={18} />
            </div>
          </div>
          <div className="mt-2 flex items-baseline gap-2">
            <span className="text-3xl font-extrabold text-emerald-400">{metricas.aprovadas}</span>
          </div>
          <p className="mt-1 text-xs text-text-secondary">Operando com acesso liberado</p>
        </div>

        {/* Inscrições Rejeitadas */}
        <div
          onClick={() => {
            setActiveTab("rejeitada");
            setCurrentPage(1);
          }}
          className={`cursor-pointer rounded-2xl border p-4 transition-all duration-200 ${
            activeTab === "rejeitada"
              ? "bg-rose-500/15 border-rose-500/40 shadow-lg shadow-rose-500/10"
              : "bg-bg-light border-border-primary hover:border-rose-500/30 hover:bg-rose-500/5"
          }`}
        >
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold uppercase tracking-wider text-text-secondary">
              Inscrições Recusadas
            </span>
            <div className="h-9 w-9 rounded-xl bg-rose-500/15 border border-rose-500/30 flex items-center justify-center text-rose-400">
              <XCircle size={18} />
            </div>
          </div>
          <div className="mt-2 flex items-baseline gap-2">
            <span className="text-3xl font-extrabold text-rose-400">{metricas.rejeitadas}</span>
          </div>
          <p className="mt-1 text-xs text-text-secondary">Negadas pelo administrador</p>
        </div>

        {/* Bloqueadas */}
        <div
          onClick={() => {
            setActiveTab("bloqueada");
            setCurrentPage(1);
          }}
          className={`cursor-pointer rounded-2xl border p-4 transition-all duration-200 ${
            activeTab === "bloqueada"
              ? "bg-purple-500/15 border-purple-500/40 shadow-lg shadow-purple-500/10"
              : "bg-bg-light border-border-primary hover:border-purple-500/30 hover:bg-purple-500/5"
          }`}
        >
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold uppercase tracking-wider text-text-secondary">
              Acesso Suspenso
            </span>
            <div className="h-9 w-9 rounded-xl bg-purple-500/15 border border-purple-500/30 flex items-center justify-center text-purple-300">
              <Lock size={18} />
            </div>
          </div>
          <div className="mt-2 flex items-baseline gap-2">
            <span className="text-3xl font-extrabold text-purple-300">{metricas.bloqueadas}</span>
          </div>
          <p className="mt-1 text-xs text-text-secondary">Empresas temporariamente travadas</p>
        </div>
      </div>

      {/* Barra de Filtros e Busca */}
      <div className="mb-6 rounded-2xl border border-border-primary bg-bg-light p-4 shadow-sm flex flex-col gap-4">
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-3">
          {/* Tabs de Status */}
          <div className="flex flex-wrap gap-1.5 p-1 rounded-xl bg-slate-900/50 border border-border-primary">
            <button
              type="button"
              onClick={() => {
                setActiveTab("todas");
                setCurrentPage(1);
              }}
              className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition ${
                activeTab === "todas"
                  ? "bg-secondary text-white shadow-sm"
                  : "text-text-secondary hover:text-text-primary hover:bg-white/5"
              }`}
            >
              Todas ({metricas.total})
            </button>
            <button
              type="button"
              onClick={() => {
                setActiveTab("pendente");
                setCurrentPage(1);
              }}
              className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition flex items-center gap-1.5 ${
                activeTab === "pendente"
                  ? "bg-amber-500 text-slate-950 shadow-sm font-bold"
                  : "text-amber-400 hover:bg-amber-500/10"
              }`}
            >
              Pendentes ({metricas.pendentes})
              {metricas.pendentes > 0 && (
                <span className="h-2 w-2 rounded-full bg-amber-400 inline-block animate-pulse"></span>
              )}
            </button>
            <button
              type="button"
              onClick={() => {
                setActiveTab("aprovada");
                setCurrentPage(1);
              }}
              className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition ${
                activeTab === "aprovada"
                  ? "bg-emerald-600 text-white shadow-sm font-bold"
                  : "text-emerald-400 hover:bg-emerald-500/10"
              }`}
            >
              Aprovadas ({metricas.aprovadas})
            </button>
            <button
              type="button"
              onClick={() => {
                setActiveTab("rejeitada");
                setCurrentPage(1);
              }}
              className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition ${
                activeTab === "rejeitada"
                  ? "bg-rose-600 text-white shadow-sm font-bold"
                  : "text-rose-400 hover:bg-rose-500/10"
              }`}
            >
              Recusadas ({metricas.rejeitadas})
            </button>
            <button
              type="button"
              onClick={() => {
                setActiveTab("bloqueada");
                setCurrentPage(1);
              }}
              className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition ${
                activeTab === "bloqueada"
                  ? "bg-purple-600 text-white shadow-sm font-bold"
                  : "text-purple-300 hover:bg-purple-500/10"
              }`}
            >
              Bloqueadas ({metricas.bloqueadas})
            </button>
          </div>

          {/* Input de Busca */}
          <div className="relative min-w-[260px] max-w-md w-full">
            <Search
              size={16}
              className="absolute left-3 top-1/2 -translate-y-1/2 text-text-secondary"
            />
            <input
              type="text"
              placeholder="Buscar por nome, razão social, CNPJ ou email..."
              value={searchTerm}
              onChange={(e) => {
                setSearchTerm(e.target.value);
                setCurrentPage(1);
              }}
              className="w-full rounded-xl border border-border-primary bg-bg-primary pl-9 pr-8 py-2 text-xs text-text-primary placeholder:text-text-secondary focus:border-secondary focus:outline-none"
            />
            {searchTerm && (
              <button
                type="button"
                onClick={() => {
                  setSearchTerm("");
                  setCurrentPage(1);
                }}
                className="absolute right-2.5 top-1/2 -translate-y-1/2 text-text-secondary hover:text-text-primary p-0.5"
              >
                <X size={14} />
              </button>
            )}
          </div>
        </div>
      </div>

      {/* Tabela de Empresas */}
      <div className="rounded-2xl border border-border-primary bg-bg-light shadow-sm overflow-hidden mb-6">
        {isLoading ? (
          <div className="flex flex-col items-center justify-center p-12 text-text-secondary">
            <LoadingBar />
            <span className="mt-3 text-xs">Consultando empresas...</span>
          </div>
        ) : empresas.length === 0 ? (
          <div className="p-12 text-center">
            <Building2 size={40} className="mx-auto text-text-secondary/40 mb-3" />
            <h3 className="text-base font-semibold text-text-primary">Nenhuma empresa encontrada</h3>
            <p className="text-xs text-text-secondary max-w-sm mx-auto mt-1">
              {searchTerm || activeTab !== "todas"
                ? "Nenhum resultado corresponde aos filtros selecionados. Tente limpar os filtros de busca."
                : "Ainda não há novas inscrições de empresas no sistema."}
            </p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs border-collapse">
              <thead>
                <tr className="border-b border-border-primary bg-slate-900/40 text-text-secondary font-medium">
                  <th className="py-3.5 px-4">Empresa / Razão Social</th>
                  <th className="py-3.5 px-4">Responsável & Contato</th>
                  <th className="py-3.5 px-4">Data da Inscrição</th>
                  <th className="py-3.5 px-4">Status</th>
                  <th className="py-3.5 px-4">Operação</th>
                  <th className="py-3.5 px-4 text-right">Ações de Governança</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border-primary">
                {empresas.map((empresa) => {
                  const isBusy = actionLoadingId === empresa.id;
                  return (
                    <tr
                      key={empresa.id}
                      className="hover:bg-accent/5 transition-colors group"
                    >
                      {/* Empresa */}
                      <td className="py-3 px-4">
                        <div className="flex flex-col">
                          <span className="font-bold text-sm text-text-primary group-hover:text-accent transition-colors">
                            {empresa.fantasyName || "Nome não informado"}
                          </span>
                          <span className="text-[11px] text-text-secondary">
                            {empresa.corporateName && empresa.corporateName !== empresa.fantasyName
                              ? empresa.corporateName
                              : "Razão não informada"}
                          </span>
                          <span className="mt-1 inline-flex items-center gap-1 font-mono text-[10px] text-accent bg-accent/10 px-1.5 py-0.5 rounded w-fit">
                            CNPJ: {maskCnpj(empresa.cnpj) || empresa.cnpj || "Sem CNPJ"}
                          </span>
                        </div>
                      </td>

                      {/* Responsável & Contato */}
                      <td className="py-3 px-4">
                        <div className="flex flex-col gap-0.5">
                          <span className="font-semibold text-text-primary flex items-center gap-1">
                            <User size={12} className="text-secondary" />
                            {empresa.adminUserName || "Usuário Admin"}
                          </span>
                          <span className="text-[11px] text-text-secondary flex items-center gap-1">
                            <Mail size={11} className="text-text-secondary" />
                            {empresa.email || empresa.adminUserEmail || "-"}
                          </span>
                          {(empresa.phone || empresa.mobile || empresa.adminUserPhone) && (
                            <span className="text-[11px] text-text-secondary flex items-center gap-1">
                              <Phone size={11} className="text-text-secondary" />
                              {empresa.phone || empresa.mobile || empresa.adminUserPhone}
                            </span>
                          )}
                        </div>
                      </td>

                      {/* Data */}
                      <td className="py-3 px-4 whitespace-nowrap">
                        <div className="flex flex-col">
                          <span className="font-medium text-text-primary">
                            {formatDate(empresa.createdAt)}
                          </span>
                          <span className="text-[10px] text-text-secondary">
                            {formatRelativeTime(empresa.createdAt)}
                          </span>
                        </div>
                      </td>

                      {/* Status */}
                      <td className="py-3 px-4 whitespace-nowrap">
                        <div className="flex flex-col gap-1 items-start">
                          {renderStatusBadge(empresa.status)}
                          {empresa.rejectionReason && (
                            <span
                              className="text-[10px] text-rose-400/90 max-w-[180px] truncate"
                              title={`Motivo: ${empresa.rejectionReason}`}
                            >
                              Motivo: {empresa.rejectionReason}
                            </span>
                          )}
                        </div>
                      </td>

                      {/* Operação */}
                      <td className="py-3 px-4 whitespace-nowrap">
                        <div className="flex items-center gap-3 text-text-secondary text-[11px]">
                          <span title="Total de Usuários" className="flex items-center gap-1">
                            <Users size={12} className="text-text-secondary" />
                            {empresa.totalUsers} usr
                          </span>
                          <span title="Total de Vendas" className="flex items-center gap-1">
                            <span className="text-accent font-semibold">{empresa.totalSales}</span>{" "}
                            vendas
                          </span>
                        </div>
                      </td>

                      {/* Ações */}
                      <td className="py-3 px-4 text-right whitespace-nowrap">
                        <div className="flex items-center justify-end gap-1.5">
                          {/* Ações para Pendente */}
                          {empresa.status === "pendente" && (
                            <>
                              <button
                                type="button"
                                disabled={isBusy}
                                onClick={() => setEmpresaToApprove(empresa)}
                                className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white font-semibold text-xs shadow-sm transition"
                                title="Aprovar inscrição da empresa"
                              >
                                <Check size={13} />
                                <span>Aprovar</span>
                              </button>
                              <button
                                type="button"
                                disabled={isBusy}
                                onClick={() => {
                                  setEmpresaToReject(empresa);
                                  setRejectionReason("");
                                }}
                                className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-rose-600/20 hover:bg-rose-600 text-rose-300 hover:text-white border border-rose-500/30 font-semibold text-xs transition"
                                title="Recusar cadastro"
                              >
                                <X size={13} />
                                <span>Recusar</span>
                              </button>
                            </>
                          )}

                          {/* Ações para Aprovada */}
                          {empresa.status === "aprovada" && (
                            <button
                              type="button"
                              disabled={isBusy}
                              onClick={() => {
                                setEmpresaToBlock(empresa);
                                setBlockReason("");
                              }}
                              className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-purple-500/15 hover:bg-purple-500 text-purple-300 hover:text-white border border-purple-500/30 font-semibold text-xs transition"
                              title="Suspender ou bloquear acesso"
                            >
                              <Lock size={13} />
                              <span>Bloquear</span>
                            </button>
                          )}

                          {/* Ações para Rejeitada ou Bloqueada */}
                          {(empresa.status === "rejeitada" || empresa.status === "bloqueada") && (
                            <button
                              type="button"
                              disabled={isBusy}
                              onClick={() => handleReactivate(empresa)}
                              className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-emerald-600/20 hover:bg-emerald-600 text-emerald-300 hover:text-white border border-emerald-500/30 font-semibold text-xs transition"
                              title="Reativar e aprovar acesso da empresa"
                            >
                              <ShieldCheck size={13} />
                              <span>Reativar</span>
                            </button>
                          )}

                          {/* Detalhes Drawer/Modal */}
                          <button
                            type="button"
                            onClick={() => setSelectedEmpresa(empresa)}
                            className="inline-flex items-center justify-center p-1.5 rounded-lg border border-border-primary bg-bg-light hover:bg-secondary/15 text-text-secondary hover:text-text-primary transition"
                            title="Ver detalhes completos"
                          >
                            <Eye size={15} />
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}

        {/* Paginação */}
        {totalCount > pageSize && (
          <div className="border-t border-border-primary p-3 flex items-center justify-between text-xs text-text-secondary">
            <span>
              Mostrando {Math.min((currentPage - 1) * pageSize + 1, totalCount)} até{" "}
              {Math.min(currentPage * pageSize, totalCount)} de {totalCount} empresas
            </span>
            <div className="flex items-center gap-1">
              <button
                type="button"
                disabled={currentPage <= 1}
                onClick={() => setCurrentPage((p) => Math.max(1, p - 1))}
                className="px-2.5 py-1 rounded-lg border border-border-primary bg-bg-light disabled:opacity-40 disabled:cursor-not-allowed hover:bg-secondary/10"
              >
                Anterior
              </button>
              <span className="px-2 font-semibold text-text-primary">
                {currentPage} / {totalPages}
              </span>
              <button
                type="button"
                disabled={currentPage >= totalPages}
                onClick={() => setCurrentPage((p) => Math.min(totalPages, p + 1))}
                className="px-2.5 py-1 rounded-lg border border-border-primary bg-bg-light disabled:opacity-40 disabled:cursor-not-allowed hover:bg-secondary/10"
              >
                Próxima
              </button>
            </div>
          </div>
        )}
      </div>

      {/* MODAL 1: Confirmação de Aprovação */}
      {empresaToApprove && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4 backdrop-blur-xs animate-fadeIn">
          <div className="w-full max-w-md rounded-2xl border border-emerald-500/30 bg-slate-900 p-6 shadow-2xl">
            <div className="flex items-center gap-3 text-emerald-400 mb-4">
              <div className="h-10 w-10 rounded-xl bg-emerald-500/20 border border-emerald-500/30 flex items-center justify-center shrink-0">
                <CheckCircle2 size={22} />
              </div>
              <div>
                <h3 className="text-base font-bold text-white">Aprovar Inscrição da Empresa</h3>
                <p className="text-xs text-slate-400">Liberação de acesso ao sistema</p>
              </div>
            </div>

            <div className="rounded-xl bg-slate-950/60 border border-slate-800 p-4 space-y-2 mb-5">
              <div>
                <span className="text-[11px] text-slate-400 uppercase font-semibold">Empresa:</span>
                <p className="text-sm font-bold text-white">{empresaToApprove.fantasyName}</p>
              </div>
              <div className="flex items-center justify-between text-xs">
                <span className="text-slate-400">CNPJ:</span>
                <span className="font-mono text-emerald-400">{maskCnpj(empresaToApprove.cnpj)}</span>
              </div>
              <div className="flex items-center justify-between text-xs">
                <span className="text-slate-400">Responsável:</span>
                <span className="text-slate-200">{empresaToApprove.adminUserName || "-"}</span>
              </div>
              <div className="flex items-center justify-between text-xs">
                <span className="text-slate-400">E-mail de Login:</span>
                <span className="text-slate-200">{empresaToApprove.email}</span>
              </div>
            </div>

            <p className="text-xs text-slate-300 leading-relaxed mb-6">
              Ao aprovar, o status da empresa mudará para <strong className="text-emerald-400">Aprovada</strong> e os usuários cadastrados por esta empresa poderão realizar login imediatamente no Hórus PDV.
            </p>

            <div className="flex items-center justify-end gap-2.5">
              <button
                type="button"
                onClick={() => setEmpresaToApprove(null)}
                disabled={actionLoadingId === empresaToApprove.id}
                className="px-4 py-2 rounded-xl border border-slate-700 bg-slate-800 text-xs font-semibold text-slate-300 hover:bg-slate-700 transition"
              >
                Cancelar
              </button>
              <button
                type="button"
                onClick={handleConfirmApprove}
                disabled={actionLoadingId === empresaToApprove.id}
                className="px-4 py-2 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-xs font-bold text-white shadow-lg shadow-emerald-600/30 transition flex items-center gap-1.5"
              >
                {actionLoadingId === empresaToApprove.id ? (
                  <RefreshCw size={14} className="animate-spin" />
                ) : (
                  <Check size={14} />
                )}
                <span>Sim, Aprovar Empresa</span>
              </button>
            </div>
          </div>
        </div>
      )}

      {/* MODAL 2: Recusa / Rejeição de Inscrição */}
      {empresaToReject && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4 backdrop-blur-xs animate-fadeIn">
          <div className="w-full max-w-lg rounded-2xl border border-rose-500/30 bg-slate-900 p-6 shadow-2xl">
            <div className="flex items-center gap-3 text-rose-400 mb-4">
              <div className="h-10 w-10 rounded-xl bg-rose-500/20 border border-rose-500/30 flex items-center justify-center shrink-0">
                <ShieldAlert size={22} />
              </div>
              <div>
                <h3 className="text-base font-bold text-white">Recusar Inscrição da Empresa</h3>
                <p className="text-xs text-slate-400">O acesso de login permanecerá bloqueado</p>
              </div>
            </div>

            <div className="rounded-xl bg-slate-950/60 border border-slate-800 p-3.5 mb-4">
              <span className="text-xs font-bold text-white">{empresaToReject.fantasyName}</span>
              <span className="text-[11px] text-slate-400 block font-mono">
                CNPJ: {maskCnpj(empresaToReject.cnpj)}
              </span>
            </div>

            <div className="space-y-3 mb-6">
              <label className="block text-xs font-semibold text-slate-300">
                Selecione ou digite o motivo da recusa:
              </label>

              {/* Botões de atalho de motivos */}
              <div className="flex flex-wrap gap-1.5">
                {QUICK_REJECTION_REASONS.map((reason) => (
                  <button
                    key={reason}
                    type="button"
                    onClick={() => setRejectionReason(reason)}
                    className={`text-[11px] px-2.5 py-1 rounded-lg border text-left transition ${
                      rejectionReason === reason
                        ? "bg-rose-500/25 border-rose-500/50 text-rose-200 font-semibold"
                        : "bg-slate-950 border-slate-800 text-slate-400 hover:text-slate-200 hover:border-slate-700"
                    }`}
                  >
                    {reason}
                  </button>
                ))}
              </div>

              <textarea
                rows={3}
                placeholder="Descreva detalhadamente o motivo da recusa para histórico de auditoria..."
                value={rejectionReason}
                onChange={(e) => setRejectionReason(e.target.value)}
                className="w-full rounded-xl border border-slate-700 bg-slate-950 p-3 text-xs text-slate-200 placeholder:text-slate-500 focus:border-rose-500 focus:outline-none"
              />
            </div>

            <div className="flex items-center justify-end gap-2.5">
              <button
                type="button"
                onClick={() => setEmpresaToReject(null)}
                disabled={actionLoadingId === empresaToReject.id}
                className="px-4 py-2 rounded-xl border border-slate-700 bg-slate-800 text-xs font-semibold text-slate-300 hover:bg-slate-700 transition"
              >
                Voltar
              </button>
              <button
                type="button"
                onClick={handleConfirmReject}
                disabled={actionLoadingId === empresaToReject.id}
                className="px-4 py-2 rounded-xl bg-rose-600 hover:bg-rose-500 text-xs font-bold text-white shadow-lg shadow-rose-600/30 transition flex items-center gap-1.5"
              >
                {actionLoadingId === empresaToReject.id ? (
                  <RefreshCw size={14} className="animate-spin" />
                ) : (
                  <XCircle size={14} />
                )}
                <span>Confirmar Recusa</span>
              </button>
            </div>
          </div>
        </div>
      )}

      {/* MODAL 3: Bloqueio de Empresa Aprovada */}
      {empresaToBlock && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4 backdrop-blur-xs animate-fadeIn">
          <div className="w-full max-w-md rounded-2xl border border-purple-500/30 bg-slate-900 p-6 shadow-2xl">
            <div className="flex items-center gap-3 text-purple-300 mb-4">
              <div className="h-10 w-10 rounded-xl bg-purple-500/20 border border-purple-500/30 flex items-center justify-center shrink-0">
                <Lock size={22} />
              </div>
              <div>
                <h3 className="text-base font-bold text-white">Suspender e Bloquear Acesso</h3>
                <p className="text-xs text-slate-400">Revoga todas as sessões ativas imediatamente</p>
              </div>
            </div>

            <div className="rounded-xl bg-slate-950/60 border border-slate-800 p-3.5 mb-4">
              <span className="text-xs font-bold text-white">{empresaToBlock.fantasyName}</span>
              <span className="text-[11px] text-slate-400 block font-mono">
                CNPJ: {maskCnpj(empresaToBlock.cnpj)}
              </span>
            </div>

            <div className="space-y-2 mb-6">
              <label className="block text-xs font-semibold text-slate-300">
                Motivo do bloqueio / suspensão:
              </label>
              <textarea
                rows={3}
                placeholder="Ex.: Inadimplência, violação de termos de uso, solicitação judicial..."
                value={blockReason}
                onChange={(e) => setBlockReason(e.target.value)}
                className="w-full rounded-xl border border-slate-700 bg-slate-950 p-3 text-xs text-slate-200 placeholder:text-slate-500 focus:border-purple-500 focus:outline-none"
              />
            </div>

            <div className="flex items-center justify-end gap-2.5">
              <button
                type="button"
                onClick={() => setEmpresaToBlock(null)}
                disabled={actionLoadingId === empresaToBlock.id}
                className="px-4 py-2 rounded-xl border border-slate-700 bg-slate-800 text-xs font-semibold text-slate-300 hover:bg-slate-700 transition"
              >
                Cancelar
              </button>
              <button
                type="button"
                onClick={handleConfirmBlock}
                disabled={actionLoadingId === empresaToBlock.id}
                className="px-4 py-2 rounded-xl bg-purple-600 hover:bg-purple-500 text-xs font-bold text-white shadow-lg shadow-purple-600/30 transition flex items-center gap-1.5"
              >
                {actionLoadingId === empresaToBlock.id ? (
                  <RefreshCw size={14} className="animate-spin" />
                ) : (
                  <Lock size={14} />
                )}
                <span>Confirmar Bloqueio</span>
              </button>
            </div>
          </div>
        </div>
      )}

      {/* MODAL 4: Detalhes Completos da Empresa */}
      {selectedEmpresa && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4 backdrop-blur-xs animate-fadeIn">
          <div className="w-full max-w-2xl rounded-2xl border border-border-primary bg-slate-900 p-6 shadow-2xl max-h-[90vh] overflow-y-auto">
            <div className="flex items-start justify-between pb-4 border-b border-slate-800 mb-5">
              <div className="flex items-center gap-3">
                <div className="h-11 w-11 rounded-2xl bg-secondary/15 border border-secondary/30 flex items-center justify-center text-secondary font-bold text-lg">
                  <Building2 size={22} />
                </div>
                <div>
                  <h3 className="text-lg font-bold text-white">{selectedEmpresa.fantasyName}</h3>
                  <p className="text-xs text-slate-400">{selectedEmpresa.corporateName}</p>
                </div>
              </div>
              <button
                type="button"
                onClick={() => setSelectedEmpresa(null)}
                className="text-slate-400 hover:text-white p-1.5 rounded-lg bg-slate-800 hover:bg-slate-700"
              >
                <X size={18} />
              </button>
            </div>

            {/* Status atual */}
            <div className="mb-5 flex items-center justify-between p-3.5 rounded-xl bg-slate-950/80 border border-slate-800">
              <div className="flex items-center gap-2">
                <span className="text-xs text-slate-400 font-semibold">Situação na Plataforma:</span>
                {renderStatusBadge(selectedEmpresa.status)}
              </div>
              <span className="text-[11px] text-slate-400">
                Cadastrada em {formatDate(selectedEmpresa.createdAt)}
              </span>
            </div>

            {/* Informações detalhadas */}
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4 mb-5">
              {/* Cadastrais */}
              <div className="p-4 rounded-xl bg-slate-950/50 border border-slate-800/80 space-y-2.5">
                <h4 className="text-xs font-bold uppercase tracking-wider text-accent flex items-center gap-1.5">
                  <Building2 size={13} /> Dados da Empresa
                </h4>
                <div className="text-xs space-y-1 text-slate-300">
                  <p>
                    <strong className="text-slate-400">CNPJ:</strong>{" "}
                    <span className="font-mono text-emerald-400">
                      {maskCnpj(selectedEmpresa.cnpj) || selectedEmpresa.cnpj}
                    </span>
                  </p>
                  <p>
                    <strong className="text-slate-400">E-mail:</strong> {selectedEmpresa.email || "-"}
                  </p>
                  <p>
                    <strong className="text-slate-400">Telefone:</strong>{" "}
                    {selectedEmpresa.phone || selectedEmpresa.mobile || "-"}
                  </p>
                  <p>
                    <strong className="text-slate-400">Localização:</strong>{" "}
                    {selectedEmpresa.city
                      ? `${selectedEmpresa.city} - ${selectedEmpresa.uf}`
                      : "Não informada"}
                  </p>
                </div>
              </div>

              {/* Responsável Admin */}
              <div className="p-4 rounded-xl bg-slate-950/50 border border-slate-800/80 space-y-2.5">
                <h4 className="text-xs font-bold uppercase tracking-wider text-secondary flex items-center gap-1.5">
                  <User size={13} /> Administrador Inicial
                </h4>
                <div className="text-xs space-y-1 text-slate-300">
                  <p>
                    <strong className="text-slate-400">Nome:</strong>{" "}
                    {selectedEmpresa.adminUserName || "Administrador"}
                  </p>
                  <p>
                    <strong className="text-slate-400">E-mail:</strong>{" "}
                    {selectedEmpresa.adminUserEmail || selectedEmpresa.email}
                  </p>
                  <p>
                    <strong className="text-slate-400">Telefone:</strong>{" "}
                    {selectedEmpresa.adminUserPhone || selectedEmpresa.phone || "-"}
                  </p>
                </div>
              </div>
            </div>

            {/* Histórico de Auditoria */}
            {(selectedEmpresa.reviewedAt || selectedEmpresa.rejectionReason) && (
              <div className="mb-5 p-4 rounded-xl bg-slate-950/80 border border-slate-800 space-y-2">
                <h4 className="text-xs font-bold uppercase tracking-wider text-slate-300 flex items-center gap-1.5">
                  <Info size={13} className="text-accent" /> Registro de Decisão
                </h4>
                {selectedEmpresa.reviewedAt && (
                  <p className="text-xs text-slate-300">
                    <strong className="text-slate-400">Revisado em:</strong>{" "}
                    {formatDate(selectedEmpresa.reviewedAt)} por{" "}
                    <span className="text-white font-semibold">
                      {selectedEmpresa.reviewedBy || "Administrador Geral"}
                    </span>
                  </p>
                )}
                {selectedEmpresa.rejectionReason && (
                  <div className="p-3 rounded-lg bg-rose-500/10 border border-rose-500/20 text-xs text-rose-300">
                    <strong>Motivo informado:</strong> {selectedEmpresa.rejectionReason}
                  </div>
                )}
              </div>
            )}

            {/* Ações dentro do modal de detalhes */}
            <div className="flex flex-wrap items-center justify-end gap-2 pt-4 border-t border-slate-800">
              {selectedEmpresa.status === "pendente" && (
                <>
                  <button
                    type="button"
                    onClick={() => {
                      setEmpresaToReject(selectedEmpresa);
                      setRejectionReason("");
                    }}
                    className="px-4 py-2 rounded-xl bg-rose-600/20 hover:bg-rose-600 text-rose-300 hover:text-white border border-rose-500/30 text-xs font-semibold transition"
                  >
                    Recusar Inscrição
                  </button>
                  <button
                    type="button"
                    onClick={() => setEmpresaToApprove(selectedEmpresa)}
                    className="px-4 py-2 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-bold shadow-lg shadow-emerald-600/20 transition flex items-center gap-1.5"
                  >
                    <Check size={14} />
                    <span>Aprovar Empresa</span>
                  </button>
                </>
              )}

              {selectedEmpresa.status === "aprovada" && (
                <button
                  type="button"
                  onClick={() => {
                    setEmpresaToBlock(selectedEmpresa);
                    setBlockReason("");
                  }}
                  className="px-4 py-2 rounded-xl bg-purple-600/20 hover:bg-purple-600 text-purple-300 hover:text-white border border-purple-500/30 text-xs font-semibold transition flex items-center gap-1.5"
                >
                  <Lock size={14} />
                  <span>Suspender / Bloquear Acesso</span>
                </button>
              )}

              {(selectedEmpresa.status === "rejeitada" || selectedEmpresa.status === "bloqueada") && (
                <button
                  type="button"
                  onClick={() => handleReactivate(selectedEmpresa)}
                  className="px-4 py-2 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-bold transition flex items-center gap-1.5"
                >
                  <ShieldCheck size={14} />
                  <span>Reativar / Liberar Acesso</span>
                </button>
              )}

              <button
                type="button"
                onClick={() => setSelectedEmpresa(null)}
                className="px-4 py-2 rounded-xl border border-slate-700 bg-slate-800 text-xs font-semibold text-slate-300 hover:bg-slate-700 transition"
              >
                Fechar
              </button>
            </div>
          </div>
        </div>
      )}
    </PageLayout>
  );
}
