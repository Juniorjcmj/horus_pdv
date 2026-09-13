/**
 * Arquivo: src/pages/Admin/FiadoPage.tsx
 * Objetivo: controle e gestão de conta corrente de clientes (fiado), acompanhamento de inadimplência,
 *           recebimento de débitos e emissão de extrato térmico 80mm.
 */
import {
  AlertTriangle,
  ArrowDownLeft,
  ArrowUpRight,
  CreditCard,
  DollarSign,
  FileText,
  Landmark,
  Loader2,
  Printer,
  RefreshCw,
  Search,
  Users,
  X,
} from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";
import PageHeader from "@/components/Admin/PageHeader";
import LoadingButton from "@/components/Loading/LoadingButton";
import TablePagination from "@/components/Pagination/TablePagination";
import { Toast, useStatusDialog } from "@/hooks/Dialog";
import useInputMasks from "@/hooks/InputMasks/useInputMasks";
import PageLayout from "@/layout/PageLayout";
import { companyService, type CompanyDto } from "@/services/api/companyService";
import {
  fiadoService,
  type FiadoDevedor,
  type FiadoMovimento,
  type FiadoResumo,
} from "@/services/api/fiadoService";

const FORMAS_PAGAMENTO = [
  { value: "dinheiro", label: "Dinheiro" },
  { value: "pix", label: "PIX" },
  { value: "debito", label: "Cartão de Débito" },
  { value: "credito", label: "Cartão de Crédito" },
];

function formatDateTimeBr(isoString?: string | null) {
  if (!isoString) return "-";
  try {
    const d = new Date(isoString);
    if (isNaN(d.getTime())) return isoString;
    return d.toLocaleString("pt-BR", {
      day: "2-digit",
      month: "2-digit",
      year: "numeric",
      hour: "2-digit",
      minute: "2-digit",
    });
  } catch {
    return isoString;
  }
}

function formatDateBr(isoString?: string | null) {
  if (!isoString) return "-";
  try {
    const d = new Date(isoString);
    if (isNaN(d.getTime())) return isoString;
    return d.toLocaleDateString("pt-BR", {
      day: "2-digit",
      month: "2-digit",
      year: "numeric",
    });
  } catch {
    return isoString;
  }
}

export default function FiadoPage() {
  const { formatMoneyBr, maskMoneyBr, parseMoneyBr } = useInputMasks();
  const statusDialog = useStatusDialog();

  const [devedores, setDevedores] = useState<FiadoDevedor[]>([]);
  const [resumo, setResumo] = useState<FiadoResumo | null>(null);
  const [company, setCompany] = useState<CompanyDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState("");
  const [agingFilter, setAgingFilter] = useState<"todos" | "em_dia" | "atraso_30" | "atraso_60">(
    "todos",
  );

  // Paginação
  const [currentPage, setCurrentPage] = useState(1);
  const [itemsPerPage, setItemsPerPage] = useState(10);

  // Modal de Recebimento
  const [receberModalOpen, setReceberModalOpen] = useState(false);
  const [selectedDevedor, setSelectedDevedor] = useState<FiadoDevedor | null>(null);
  const [valorReceber, setValorReceber] = useState("");
  const [formaPagamento, setFormaPagamento] = useState("dinheiro");
  const [observacao, setObservacao] = useState("");
  const [recebendo, setRecebendo] = useState(false);

  // Modal de Extrato
  const [extratoModalOpen, setExtratoModalOpen] = useState(false);
  const [extratoCliente, setExtratoCliente] = useState<FiadoDevedor | null>(null);
  const [movimentos, setMovimentos] = useState<FiadoMovimento[]>([]);
  const [loadingExtrato, setLoadingExtrato] = useState(false);
  const [dataInicio, setDataInicio] = useState("");
  const [dataFim, setDataFim] = useState("");

  const loadData = useCallback(async () => {
    try {
      const [devList, resData, compData] = await Promise.all([
        fiadoService.listarDevedores(),
        fiadoService.resumo(),
        companyService.get().catch(() => null),
      ]);
      setDevedores(devList);
      setResumo(resData ?? null);
      setCompany(compData ?? null);
    } catch {
      Toast.error("Erro ao carregar dados do fiado.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  // Filtros de busca e aging
  const filteredDevedores = useMemo(() => {
    const q = search.trim().toLowerCase();
    return devedores.filter((d) => {
      const matchesSearch =
        !q ||
        d.clienteNome.toLowerCase().includes(q) ||
        d.document.toLowerCase().includes(q) ||
        (d.cellphone && d.cellphone.includes(q));

      if (!matchesSearch) return false;

      const dias = d.diasSemPagamento ?? 0;
      if (agingFilter === "em_dia") return dias < 30;
      if (agingFilter === "atraso_30") return dias >= 30 && dias < 60;
      if (agingFilter === "atraso_60") return dias >= 60;
      return true;
    });
  }, [devedores, search, agingFilter]);

  // Paginação
  const totalPages = Math.max(1, Math.ceil(filteredDevedores.length / itemsPerPage));
  const safeCurrentPage = Math.min(currentPage, totalPages);
  const paginatedDevedores = useMemo(() => {
    const start = (safeCurrentPage - 1) * itemsPerPage;
    return filteredDevedores.slice(start, start + itemsPerPage);
  }, [filteredDevedores, itemsPerPage, safeCurrentPage]);

  // Abrir modal de recebimento
  const handleOpenReceber = (devedor: FiadoDevedor) => {
    setSelectedDevedor(devedor);
    setValorReceber(formatMoneyBr(devedor.saldoDevedor));
    setFormaPagamento("dinheiro");
    setObservacao("");
    setReceberModalOpen(true);
  };

  // Confirmar recebimento
  const handleConfirmarReceber = async () => {
    if (!selectedDevedor) return;
    const valor = parseMoneyBr(valorReceber);
    if (valor <= 0) {
      Toast.error("Informe um valor maior que zero.");
      return;
    }
    if (valor > selectedDevedor.saldoDevedor + 0.009) {
      Toast.error(
        `O valor informado (R$ ${formatMoneyBr(
          valor,
        )}) excede o saldo devedor de R$ ${formatMoneyBr(selectedDevedor.saldoDevedor)}.`,
      );
      return;
    }

    setRecebendo(true);
    try {
      await fiadoService.receber({
        clienteId: selectedDevedor.clienteId,
        valor,
        formaPagamento,
        observacao: observacao.trim() || undefined,
      });
      Toast.success(
        `Pagamento de R$ ${formatMoneyBr(valor)} recebido de ${selectedDevedor.clienteNome}!`,
      );
      setReceberModalOpen(false);
      await loadData();
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao processar recebimento.");
    } finally {
      setRecebendo(false);
    }
  };

  // Abrir modal de extrato
  const handleOpenExtrato = async (devedor: FiadoDevedor) => {
    setExtratoCliente(devedor);
    setDataInicio("");
    setDataFim("");
    setExtratoModalOpen(true);
    await carregarExtrato(devedor.clienteId);
  };

  const carregarExtrato = async (clienteId: string, dIni?: string, dFim?: string) => {
    setLoadingExtrato(true);
    try {
      const list = await fiadoService.extrato(clienteId, dIni, dFim);
      setMovimentos(list);
    } catch {
      Toast.error("Erro ao carregar extrato do cliente.");
    } finally {
      setLoadingExtrato(false);
    }
  };

  // Impressão Térmica 80mm do Extrato
  const handleImprimirExtrato = () => {
    if (!extratoCliente) return;

    const companyName = company?.fantasyName || company?.corporateName || "HORUS PDV";
    const companyCnpj = company?.cnpj ? `CNPJ: ${company.cnpj}` : "";
    const companyPhone = company?.phone ? `Tel: ${company.phone}` : "";
    const companyAddress = company?.address
      ? `${company.address}${company.number ? `, ${company.number}` : ""}`
      : "";

    const rows = movimentos
      .map((m) => {
        const isDebito = m.tipo === 1;
        const tipoLabel = isDebito ? "[-] Compra Fiado" : `[+] Pgto (${m.formaPagamento || "Dinheiro"})`;
        const dataFmt = formatDateTimeBr(m.criadoEm);
        return `
          <div style="margin-bottom: 6px; padding-bottom: 4px; border-bottom: 1px dotted #ccc;">
            <div style="display: flex; justify-content: space-between; font-weight: bold;">
              <span>${tipoLabel}</span>
              <span>${isDebito ? "-" : "+"}R$ ${formatMoneyBr(m.valor)}</span>
            </div>
            <div style="display: flex; justify-content: space-between; font-size: 10px; color: #555;">
              <span>${dataFmt}</span>
              <span>Saldo: R$ ${formatMoneyBr(m.saldoAtual)}</span>
            </div>
            ${
              m.observacao
                ? `<div style="font-size: 10px; color: #666; font-style: italic;">Obs: ${m.observacao}</div>`
                : ""
            }
          </div>
        `;
      })
      .join("");

    const printHtml = `<!doctype html>
<html lang="pt-BR">
  <head>
    <meta charset="utf-8" />
    <title>Extrato Fiado - ${extratoCliente.clienteNome}</title>
    <style>
      @page { size: 80mm auto; margin: 4mm; }
      * { box-sizing: border-box; }
      body { margin: 0; color: #020617; font: 12px/1.3 ui-monospace, Menlo, Consolas, monospace; }
      .receipt { width: 72mm; margin: 0 auto; }
      .center { text-align: center; }
      .brand { font-size: 14px; font-weight: 800; text-transform: uppercase; }
      .divider { border-top: 1px dashed #475569; margin: 8px 0; }
      .line { display: flex; justify-content: space-between; gap: 8px; }
      .bold { font-weight: 800; }
    </style>
  </head>
  <body>
    <main class="receipt">
      <section class="center">
        <div class="brand">${companyName}</div>
        ${companyCnpj ? `<div>${companyCnpj}</div>` : ""}
        ${companyAddress ? `<div>${companyAddress}</div>` : ""}
        ${companyPhone ? `<div>${companyPhone}</div>` : ""}
      </section>
      <div class="divider"></div>
      <section class="center">
        <div class="bold" style="font-size: 13px;">EXTRATO DE CONTA CORRENTE</div>
        <div style="font-size: 10px; margin-top: 2px;">Emissão: ${new Date().toLocaleString("pt-BR")}</div>
      </section>
      <div class="divider"></div>
      <section>
        <div><strong>Cliente:</strong> ${extratoCliente.clienteNome}</div>
        <div><strong>CPF/CNPJ:</strong> ${extratoCliente.document}</div>
        ${extratoCliente.cellphone ? `<div><strong>Telefone:</strong> ${extratoCliente.cellphone}</div>` : ""}
        <div><strong>Limite de Crédito:</strong> ${
          extratoCliente.limiteCredito > 0 ? `R$ ${formatMoneyBr(extratoCliente.limiteCredito)}` : "Ilimitado"
        }</div>
      </section>
      <div class="divider"></div>
      <section>
        <div class="bold" style="margin-bottom: 6px;">HISTÓRICO DE MOVIMENTAÇÕES:</div>
        ${rows || "<div class='center'>Nenhuma movimentação no período.</div>"}
      </section>
      <div class="divider"></div>
      <section>
        <div class="line bold" style="font-size: 14px;">
          <span>SALDO DEVEDOR ATUAL:</span>
          <span>R$ ${formatMoneyBr(extratoCliente.saldoDevedor)}</span>
        </div>
        ${
          extratoCliente.limiteCredito > 0
            ? `<div class="line" style="font-size: 11px; margin-top: 4px;">
                 <span>Limite Disponível:</span>
                 <span>R$ ${formatMoneyBr(Math.max(0, extratoCliente.limiteCredito - extratoCliente.saldoDevedor))}</span>
               </div>`
            : ""
        }
      </section>
      <div class="divider"></div>
      <section class="center" style="margin-top: 28px;">
        <div style="border-top: 1px solid #000; width: 80%; margin: 0 auto; padding-top: 4px; font-size: 10px;">
          Assinatura do Cliente
        </div>
        <div style="margin-top: 12px; font-size: 9px; color: #666;">
          HORUS PDV - Sistema de Gestão Comercial
        </div>
      </section>
    </main>
    <script>
      setTimeout(function() { window.print(); }, 250);
    </script>
  </body>
</html>`;

    const printWin = window.open("", "_blank", "width=400,height=600");
    if (printWin) {
      printWin.document.write(printHtml);
      printWin.document.close();
    }
  };

  return (
    <PageLayout className="space-y-4 py-4 md:space-y-6 md:py-6 lg:py-8">
      <PageHeader
        title="Gestão de Fiado / Conta Corrente"
        description="Controle de compras a prazo, recebimentos, limites de crédito e extratos de clientes."
        action={
          <button
            type="button"
            onClick={loadData}
            className="btn-secondary inline-flex items-center gap-2"
            title="Recarregar dados"
          >
            <RefreshCw size={15} className={loading ? "animate-spin" : ""} />
            Atualizar
          </button>
        }
      />

      {/* KPI Cards */}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4 lg:grid-cols-5">
        <div className="card rounded-2xl p-4">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold uppercase text-text-secondary">
              Total a Receber
            </span>
            <div className="rounded-xl bg-amber-500/10 p-2 text-amber-500">
              <DollarSign size={18} />
            </div>
          </div>
          <p className="mt-2 text-2xl font-bold text-amber-500">
            R$ {formatMoneyBr(resumo?.totalAReceber ?? 0)}
          </p>
          <span className="mt-1 block text-[11px] text-text-tertiary">
            Saldo acumulado de todos clientes
          </span>
        </div>

        <div className="card rounded-2xl p-4">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold uppercase text-text-secondary">
              Clientes Devedores
            </span>
            <div className="rounded-xl bg-accent/10 p-2 text-accent">
              <Users size={18} />
            </div>
          </div>
          <p className="mt-2 text-2xl font-bold text-text-primary">
            {resumo?.quantidadeDevedores ?? 0}
          </p>
          <span className="mt-1 block text-[11px] text-text-tertiary">
            Clientes com saldo devedor &gt; 0
          </span>
        </div>

        <div className="card rounded-2xl p-4">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold uppercase text-text-secondary">
              Atraso &gt; 30 dias
            </span>
            <div className="rounded-xl bg-amber-600/10 p-2 text-amber-600">
              <AlertTriangle size={18} />
            </div>
          </div>
          <p className="mt-2 text-2xl font-bold text-amber-600">
            R$ {formatMoneyBr(resumo?.inadimplencia30Dias ?? 0)}
          </p>
          <span className="mt-1 block text-[11px] text-text-tertiary">
            Sem pagamentos há mais de 30 dias
          </span>
        </div>

        <div className="card rounded-2xl p-4">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold uppercase text-text-secondary">
              Crítico &gt; 60 dias
            </span>
            <div className="rounded-xl bg-danger/10 p-2 text-danger">
              <AlertTriangle size={18} />
            </div>
          </div>
          <p className="mt-2 text-2xl font-bold text-danger">
            R$ {formatMoneyBr(resumo?.inadimplencia60Dias ?? 0)}
          </p>
          <span className="mt-1 block text-[11px] text-text-tertiary">
            Sem pagamentos há mais de 60 dias
          </span>
        </div>

        <div className="card col-span-2 rounded-2xl p-4 sm:col-span-4 lg:col-span-1">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold uppercase text-text-secondary">
              Maior Débito
            </span>
            <div className="rounded-xl bg-primary/10 p-2 text-primary">
              <Landmark size={18} />
            </div>
          </div>
          <p className="mt-2 text-2xl font-bold text-text-primary">
            R$ {formatMoneyBr(resumo?.maiorDebito ?? 0)}
          </p>
          <span className="mt-1 block text-[11px] text-text-tertiary">
            Maior saldo individual em aberto
          </span>
        </div>
      </div>

      {/* Filtros e Busca */}
      <div className="card flex flex-col gap-3 p-4 sm:flex-row sm:items-center sm:justify-between">
        <div className="relative w-full max-w-md">
          <Search
            size={16}
            className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-text-tertiary"
          />
          <input
            value={search}
            onChange={(e) => {
              setSearch(e.target.value);
              setCurrentPage(1);
            }}
            placeholder="Buscar por cliente, CPF ou telefone..."
            className="input-field w-full pl-9"
          />
        </div>

        <div className="flex flex-wrap items-center gap-1.5">
          <button
            type="button"
            onClick={() => {
              setAgingFilter("todos");
              setCurrentPage(1);
            }}
            className={`rounded-xl px-3 py-1.5 text-xs font-semibold transition ${
              agingFilter === "todos"
                ? "bg-primary text-primary-foreground"
                : "bg-bg-primary text-text-secondary hover:text-text-primary"
            }`}
          >
            Todos ({devedores.length})
          </button>
          <button
            type="button"
            onClick={() => {
              setAgingFilter("em_dia");
              setCurrentPage(1);
            }}
            className={`rounded-xl px-3 py-1.5 text-xs font-semibold transition ${
              agingFilter === "em_dia"
                ? "bg-success text-success-foreground"
                : "bg-bg-primary text-text-secondary hover:text-text-primary"
            }`}
          >
            Em dia (&lt;30d)
          </button>
          <button
            type="button"
            onClick={() => {
              setAgingFilter("atraso_30");
              setCurrentPage(1);
            }}
            className={`rounded-xl px-3 py-1.5 text-xs font-semibold transition ${
              agingFilter === "atraso_30"
                ? "bg-amber-600 text-white"
                : "bg-bg-primary text-text-secondary hover:text-text-primary"
            }`}
          >
            Atraso (&gt;30d)
          </button>
          <button
            type="button"
            onClick={() => {
              setAgingFilter("atraso_60");
              setCurrentPage(1);
            }}
            className={`rounded-xl px-3 py-1.5 text-xs font-semibold transition ${
              agingFilter === "atraso_60"
                ? "bg-danger text-danger-foreground"
                : "bg-bg-primary text-text-secondary hover:text-text-primary"
            }`}
          >
            Crítico (&gt;60d)
          </button>
        </div>
      </div>

      {/* Tabela de Devedores */}
      <div className="card overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[900px] text-sm">
            <thead className="bg-bg-primary text-left text-text-secondary">
              <tr>
                <th className="px-4 py-3">Cliente</th>
                <th className="px-4 py-3">Documento</th>
                <th className="px-4 py-3">Contato</th>
                <th className="px-4 py-3">Limite</th>
                <th className="px-4 py-3">Saldo Devedor</th>
                <th className="px-4 py-3">Última Compra</th>
                <th className="px-4 py-3">Status Aging</th>
                <th className="px-4 py-3 text-right">Ações</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr>
                  <td colSpan={8} className="py-12 text-center text-text-secondary">
                    <Loader2 size={24} className="mx-auto mb-2 animate-spin text-accent" />
                    Carregando contas a receber...
                  </td>
                </tr>
              ) : paginatedDevedores.length === 0 ? (
                <tr>
                  <td colSpan={8} className="py-12 text-center text-text-secondary">
                    Nenhum cliente devedor encontrado para os filtros selecionados.
                  </td>
                </tr>
              ) : (
                paginatedDevedores.map((d) => {
                  const dias = d.diasSemPagamento ?? 0;
                  const isCritico = dias >= 60;
                  const isAtencao = dias >= 30 && dias < 60;

                  return (
                    <tr key={d.clienteId} className="border-t border-border-primary hover:bg-hover-light/40">
                      <td className="px-4 py-3 font-semibold text-text-primary">
                        {d.clienteNome}
                      </td>
                      <td className="px-4 py-3 text-text-secondary">{d.document}</td>
                      <td className="px-4 py-3 text-text-secondary">
                        {d.cellphone || d.telephone || "-"}
                      </td>
                      <td className="px-4 py-3 text-text-secondary">
                        {d.limiteCredito > 0 ? (
                          <span>R$ {formatMoneyBr(d.limiteCredito)}</span>
                        ) : (
                          <span className="text-xs text-text-tertiary">Ilimitado</span>
                        )}
                      </td>
                      <td className="px-4 py-3">
                        <span className="font-bold text-amber-500">
                          R$ {formatMoneyBr(d.saldoDevedor)}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-text-secondary">
                        {formatDateBr(d.ultimaCompra)}
                      </td>
                      <td className="px-4 py-3">
                        {isCritico ? (
                          <span className="inline-flex items-center rounded-full bg-danger/10 px-2.5 py-0.5 text-xs font-semibold text-danger">
                            &gt; 60 dias ({dias}d)
                          </span>
                        ) : isAtencao ? (
                          <span className="inline-flex items-center rounded-full bg-amber-600/10 px-2.5 py-0.5 text-xs font-semibold text-amber-600">
                            &gt; 30 dias ({dias}d)
                          </span>
                        ) : (
                          <span className="inline-flex items-center rounded-full bg-success/10 px-2.5 py-0.5 text-xs font-semibold text-success">
                            Em dia ({dias}d)
                          </span>
                        )}
                      </td>
                      <td className="px-4 py-3 text-right">
                        <div className="flex items-center justify-end gap-1.5">
                          <button
                            type="button"
                            onClick={() => handleOpenReceber(d)}
                            className="btn-success py-1 px-2.5 text-xs font-semibold inline-flex items-center gap-1"
                            title="Receber pagamento deste cliente"
                          >
                            <DollarSign size={13} /> Receber
                          </button>
                          <button
                            type="button"
                            onClick={() => handleOpenExtrato(d)}
                            className="btn-secondary py-1 px-2.5 text-xs inline-flex items-center gap-1"
                            title="Ver extrato completo"
                          >
                            <FileText size={13} /> Extrato
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>

        {filteredDevedores.length > itemsPerPage && (
          <div className="border-t border-border-primary p-3">
            <TablePagination
              currentPage={safeCurrentPage}
              itemsPerPage={itemsPerPage}
              totalItems={filteredDevedores.length}
              onPageChange={setCurrentPage}
              onItemsPerPageChange={(count) => {
                setItemsPerPage(count);
                setCurrentPage(1);
              }}
            />
          </div>
        )}
      </div>

      {/* Modal de Recebimento / Quitação */}
      {receberModalOpen && selectedDevedor && (
        <div className="dept-drawer-overlay flex items-center justify-center p-4">
          <aside
            className="card flex max-h-[90vh] w-full max-w-md flex-col rounded-2xl p-5 shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between border-b border-border-primary pb-3">
              <div className="flex items-center gap-2">
                <CreditCard size={18} className="text-success" />
                <h3 className="text-base font-semibold text-text-primary">Receber Pagamento Fiado</h3>
              </div>
              <button
                type="button"
                onClick={() => setReceberModalOpen(false)}
                className="rounded-lg p-1.5 text-text-secondary hover:bg-hover-light"
              >
                <X size={16} />
              </button>
            </div>

            <div className="mt-4 space-y-3">
              <div className="rounded-xl border border-border-primary bg-bg-primary/50 p-3 text-xs space-y-1">
                <div className="flex justify-between">
                  <span className="text-text-secondary">Cliente:</span>
                  <span className="font-semibold text-text-primary">{selectedDevedor.clienteNome}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-text-secondary">Documento:</span>
                  <span>{selectedDevedor.document}</span>
                </div>
                <div className="flex justify-between border-t border-border-primary pt-1.5 text-sm font-bold">
                  <span>Saldo Devedor Atual:</span>
                  <span className="text-amber-500">R$ {formatMoneyBr(selectedDevedor.saldoDevedor)}</span>
                </div>
              </div>

              <div>
                <div className="flex items-center justify-between mb-1.5">
                  <label className="block text-sm text-text-secondary">Valor a Receber (R$) *</label>
                  <button
                    type="button"
                    onClick={() => setValorReceber(formatMoneyBr(selectedDevedor.saldoDevedor))}
                    className="text-xs font-semibold text-accent hover:underline"
                  >
                    Quitar Total
                  </button>
                </div>
                <input
                  value={valorReceber}
                  onChange={(e) => setValorReceber(maskMoneyBr(e.target.value))}
                  className="input-field w-full text-base font-bold text-success"
                  placeholder="0,00"
                  autoFocus
                />
              </div>

              <div>
                <label className="mb-1.5 block text-sm text-text-secondary">
                  Forma de Pagamento *
                </label>
                <select
                  value={formaPagamento}
                  onChange={(e) => setFormaPagamento(e.target.value)}
                  className="input-field w-full"
                >
                  {FORMAS_PAGAMENTO.map((f) => (
                    <option key={f.value} value={f.value}>
                      {f.label}
                    </option>
                  ))}
                </select>
              </div>

              <div>
                <label className="mb-1.5 block text-sm text-text-secondary">
                  Observação (opcional)
                </label>
                <input
                  value={observacao}
                  onChange={(e) => setObservacao(e.target.value)}
                  className="input-field w-full"
                  placeholder="Ex: Quitação parcial da semana"
                />
              </div>
            </div>

            <div className="mt-5 grid grid-cols-2 gap-2 border-t border-border-primary pt-4">
              <button
                type="button"
                onClick={() => setReceberModalOpen(false)}
                disabled={recebendo}
                className="btn-cancel"
              >
                Cancelar
              </button>
              <LoadingButton
                type="button"
                onClick={handleConfirmarReceber}
                isLoading={recebendo}
                loadingLabel="Processando..."
                className="btn-success"
              >
                Confirmar Recebimento
              </LoadingButton>
            </div>
          </aside>
        </div>
      )}

      {/* Modal de Extrato */}
      {extratoModalOpen && extratoCliente && (
        <div className="dept-drawer-overlay flex items-center justify-center p-4">
          <aside
            className="card flex max-h-[90vh] w-full max-w-2xl flex-col rounded-2xl p-5 shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between border-b border-border-primary pb-3">
              <div className="flex items-center gap-2">
                <FileText size={18} className="text-accent" />
                <h3 className="text-base font-semibold text-text-primary">
                  Extrato de Conta Corrente — {extratoCliente.clienteNome}
                </h3>
              </div>
              <button
                type="button"
                onClick={() => setExtratoModalOpen(false)}
                className="rounded-lg p-1.5 text-text-secondary hover:bg-hover-light"
              >
                <X size={16} />
              </button>
            </div>

            {/* Cabeçalho do Cliente no Extrato */}
            <div className="mt-3 grid grid-cols-2 gap-2 rounded-xl border border-border-primary bg-bg-primary/50 p-3 text-xs sm:grid-cols-4">
              <div>
                <span className="block text-[11px] text-text-secondary">CPF/CNPJ</span>
                <span className="font-semibold text-text-primary">{extratoCliente.document}</span>
              </div>
              <div>
                <span className="block text-[11px] text-text-secondary">Telefone</span>
                <span>{extratoCliente.cellphone || extratoCliente.telephone || "-"}</span>
              </div>
              <div>
                <span className="block text-[11px] text-text-secondary">Limite de Crédito</span>
                <span className="font-semibold text-text-primary">
                  {extratoCliente.limiteCredito > 0
                    ? `R$ ${formatMoneyBr(extratoCliente.limiteCredito)}`
                    : "Ilimitado"}
                </span>
              </div>
              <div>
                <span className="block text-[11px] text-text-secondary">Saldo Devedor</span>
                <span className="font-bold text-amber-500">
                  R$ {formatMoneyBr(extratoCliente.saldoDevedor)}
                </span>
              </div>
            </div>

            {/* Filtro de datas */}
            <div className="mt-3 flex flex-wrap items-center gap-2 text-xs">
              <div className="flex items-center gap-1.5">
                <span className="text-text-secondary">De:</span>
                <input
                  type="date"
                  value={dataInicio}
                  onChange={(e) => setDataInicio(e.target.value)}
                  className="input-field py-1 px-2 text-xs"
                />
              </div>
              <div className="flex items-center gap-1.5">
                <span className="text-text-secondary">Até:</span>
                <input
                  type="date"
                  value={dataFim}
                  onChange={(e) => setDataFim(e.target.value)}
                  className="input-field py-1 px-2 text-xs"
                />
              </div>
              <button
                type="button"
                onClick={() => carregarExtrato(extratoCliente.clienteId, dataInicio, dataFim)}
                className="btn-secondary py-1 px-3 text-xs"
              >
                Filtrar
              </button>
            </div>

            {/* Lista cronológica dos movimentos */}
            <div className="mt-3 flex-1 overflow-y-auto pr-1">
              {loadingExtrato ? (
                <div className="py-12 text-center text-xs text-text-secondary">
                  <Loader2 size={20} className="mx-auto mb-2 animate-spin text-accent" />
                  Carregando histórico do cliente...
                </div>
              ) : movimentos.length === 0 ? (
                <div className="py-12 text-center text-xs text-text-secondary">
                  Nenhuma movimentação registrada no período.
                </div>
              ) : (
                <div className="space-y-2">
                  {movimentos.map((m) => {
                    const isDebito = m.tipo === 1;
                    return (
                      <div
                        key={m.id}
                        className={`flex items-center justify-between rounded-xl border p-3 text-xs transition ${
                          isDebito
                            ? "border-amber-500/20 bg-amber-500/5"
                            : "border-success/20 bg-success/5"
                        }`}
                      >
                        <div className="flex items-start gap-2.5">
                          <div
                            className={`rounded-lg p-2 ${
                              isDebito
                                ? "bg-amber-500/10 text-amber-500"
                                : "bg-success/10 text-success"
                            }`}
                          >
                            {isDebito ? <ArrowDownLeft size={16} /> : <ArrowUpRight size={16} />}
                          </div>
                          <div>
                            <p className="font-semibold text-text-primary">
                              {isDebito
                                ? "Compra a Prazo (Fiado)"
                                : `Pagamento Recebido (${m.formaPagamento || "Dinheiro"})`}
                            </p>
                            <p className="text-[11px] text-text-secondary">
                              {formatDateTimeBr(m.criadoEm)} • Op: {m.operadorNome || "Caixa"}
                            </p>
                            {m.observacao && (
                              <p className="text-[11px] text-text-tertiary italic">
                                Obs: {m.observacao}
                              </p>
                            )}
                          </div>
                        </div>

                        <div className="text-right">
                          <p
                            className={`font-bold ${
                              isDebito ? "text-amber-500" : "text-success"
                            }`}
                          >
                            {isDebito ? "-" : "+"} R$ {formatMoneyBr(m.valor)}
                          </p>
                          <p className="text-[11px] text-text-secondary">
                            Saldo: R$ {formatMoneyBr(m.saldoAtual)}
                          </p>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>

            {/* Rodapé com botão de impressão 80mm */}
            <div className="mt-4 flex items-center justify-between border-t border-border-primary pt-3">
              <button
                type="button"
                onClick={handleImprimirExtrato}
                className="btn-primary inline-flex items-center gap-2 text-xs"
              >
                <Printer size={15} /> Imprimir Extrato Térmico (80mm)
              </button>
              <button
                type="button"
                onClick={() => setExtratoModalOpen(false)}
                className="btn-cancel text-xs"
              >
                Fechar
              </button>
            </div>
          </aside>
        </div>
      )}

      {statusDialog.Dialog}
    </PageLayout>
  );
}
