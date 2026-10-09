/**
 * Arquivo: src/pages/Admin/ValidadePage.tsx
 * Objetivo: controle de validade por lote — alertas por lote com saldo estimado e valor em risco,
 *           registro manual de lote e prazo padrão/janela de alerta por categoria.
 * Entradas esperadas: não recebe props; consome a API de lotes (api/Produto/lotes).
 *
 * Fase 1: o lote é informativo. A venda não baixa lote; o saldo mostrado é estimado assumindo que o
 * estoque é consumido na ordem FEFO (vence primeiro, sai primeiro).
 */
import TableScrollArea from "@/components/Admin/TableScrollArea";
import { AlertCircle, AlertTriangle, CalendarClock, Clock, PackageX, Plus, RefreshCw, Save, Search, X } from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";
import PageHeader from "@/components/Admin/PageHeader";
import TablePagination from "@/components/Pagination/TablePagination";
import { Toast, useStatusDialog } from "@/hooks/Dialog";
import useInputMasks from "@/hooks/InputMasks/useInputMasks";
import PageLayout from "@/layout/PageLayout";
import {
  loteService,
  type CategoriaValidadeDto,
  type LoteAlertaDto,
  type LoteAlertasResumoDto,
  type FefoModo,
  type FefoStatusDto,
  type LoteConsultaDto,
  type LoteFaixa,
} from "@/services/api/loteService";
import { productService, type ProductDto } from "@/services/api/productService";

type Tab = "alertas" | "consulta" | "fefo" | "categorias";

const MODO_INFO: Record<FefoModo, { label: string; description: string }> = {
  desligado: {
    label: "Desligado",
    description: "As vendas não baixam lote. O saldo por lote continua estimado (o que vence primeiro sai primeiro).",
  },
  sombra: {
    label: "Sombra",
    description:
      "A venda baixa o lote em segundo plano, mas as telas ainda usam o saldo estimado. Use para comparar o saldo real com o estimado antes de ativar.",
  },
  ativo: {
    label: "Ativo",
    description: "As telas passam a usar o saldo real de cada lote, baixado pela venda (vence primeiro, sai primeiro).",
  },
};
type FaixaFilter = "todas" | LoteFaixa;

const FAIXA_STYLE: Record<LoteFaixa, { label: string; badge: string }> = {
  vencido: { label: "Vencido", badge: "bg-red-500/15 text-red-600 dark:text-red-400 border-red-500/30" },
  critico: { label: "Crítico", badge: "bg-orange-500/15 text-orange-600 dark:text-orange-400 border-orange-500/30" },
  atencao: { label: "Atenção", badge: "bg-amber-500/15 text-amber-600 dark:text-amber-400 border-amber-500/30" },
  ok: { label: "No prazo", badge: "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 border-emerald-500/30" },
  esgotado: { label: "Sem saldo", badge: "bg-slate-500/15 text-slate-600 dark:text-slate-400 border-slate-500/30" },
};

const ORIGEM_LABEL: Record<string, string> = {
  nfe: "NF-e",
  compra: "Compra",
  ajuste: "Ajuste de estoque",
  cadastro: "Cadastro de produto",
  manual: "Registro manual",
  inicial: "Carga inicial",
};

type ConsultaFilters = {
  busca: string;
  de: string;
  ate: string;
  categoriaId: string;
  faixa: "" | LoteFaixa;
  origem: string;
  comSaldo: boolean;
};

const EMPTY_CONSULTA_FILTERS: ConsultaFilters = {
  busca: "",
  de: "",
  ate: "",
  categoriaId: "",
  faixa: "",
  origem: "",
  comSaldo: false,
};

function shiftDays(days: number) {
  const date = new Date();
  date.setDate(date.getDate() + days);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

const QUICK_RANGES: Array<{ label: string; range: () => { de: string; ate: string } }> = [
  { label: "Vencidos", range: () => ({ de: "", ate: shiftDays(-1) }) },
  { label: "Vencem hoje", range: () => ({ de: shiftDays(0), ate: shiftDays(0) }) },
  { label: "Próx. 7 dias", range: () => ({ de: shiftDays(0), ate: shiftDays(7) }) },
  { label: "Próx. 15 dias", range: () => ({ de: shiftDays(0), ate: shiftDays(15) }) },
  { label: "Próx. 30 dias", range: () => ({ de: shiftDays(0), ate: shiftDays(30) }) },
  { label: "Próx. 60 dias", range: () => ({ de: shiftDays(0), ate: shiftDays(60) }) },
  { label: "Próx. 90 dias", range: () => ({ de: shiftDays(0), ate: shiftDays(90) }) },
];

function formatDate(isoDate: string) {
  const [y, m, d] = isoDate.split("-");
  return `${d}/${m}/${y}`;
}

function describeDays(days: number) {
  if (days < 0) return `vencido há ${Math.abs(days)} dia(s)`;
  if (days === 0) return "vence hoje";
  return `vence em ${days} dia(s)`;
}

function formatQty(value: number) {
  return value.toLocaleString("pt-BR", { maximumFractionDigits: 3 });
}

export default function ValidadePage() {
  const { formatMoneyBr } = useInputMasks();
  const statusDialog = useStatusDialog();

  const [tab, setTab] = useState<Tab>("alertas");
  const [loading, setLoading] = useState(true);
  const [resumo, setResumo] = useState<LoteAlertasResumoDto | null>(null);
  const [faixaFilter, setFaixaFilter] = useState<FaixaFilter>("todas");
  const [baixandoIds, setBaixandoIds] = useState<Set<string>>(() => new Set());

  const [consultaFilters, setConsultaFilters] = useState<ConsultaFilters>(EMPTY_CONSULTA_FILTERS);
  const [consultaPage, setConsultaPage] = useState(1);
  const [consultaPageSize, setConsultaPageSize] = useState(20);
  const [consulta, setConsulta] = useState<LoteConsultaDto | null>(null);
  const [consultaLoading, setConsultaLoading] = useState(false);

  const [fefo, setFefo] = useState<FefoStatusDto | null>(null);
  const [fefoLoading, setFefoLoading] = useState(false);
  const [modoChanging, setModoChanging] = useState(false);

  const [categorias, setCategorias] = useState<CategoriaValidadeDto[]>([]);
  const [edits, setEdits] = useState<Record<string, { prazo: string; alerta: string }>>({});
  const [savingCategoriaIds, setSavingCategoriaIds] = useState<Set<string>>(() => new Set());

  const [registerOpen, setRegisterOpen] = useState(false);
  const [products, setProducts] = useState<ProductDto[]>([]);
  const [productSearch, setProductSearch] = useState("");
  const [regProdutoId, setRegProdutoId] = useState("");
  const [regValidade, setRegValidade] = useState("");
  const [regQuantidade, setRegQuantidade] = useState("");
  const [regNumeroLote, setRegNumeroLote] = useState("");
  const [registering, setRegistering] = useState(false);

  const loadAlertas = useCallback(async () => {
    setLoading(true);
    try {
      setResumo((await loteService.alertas()) ?? null);
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao carregar alertas de validade.");
    } finally {
      setLoading(false);
    }
  }, []);

  const loadCategorias = useCallback(async () => {
    try {
      const rows = await loteService.categorias();
      setCategorias(rows);
      setEdits(
        Object.fromEntries(
          rows.map((row) => [
            row.categoriaId,
            { prazo: row.prazoPadraoDias?.toString() ?? "", alerta: row.diasAlerta?.toString() ?? "" },
          ]),
        ),
      );
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao carregar categorias.");
    }
  }, []);

  useEffect(() => {
    void loadAlertas();
    void loadCategorias();
  }, [loadAlertas, loadCategorias]);

  // Consulta de lotes: filtros e paginação resolvidos no servidor. A busca por texto espera 300 ms.
  useEffect(() => {
    if (tab !== "consulta") return;

    let cancelled = false;
    const timer = window.setTimeout(() => {
      setConsultaLoading(true);
      loteService
        .consulta({
          busca: consultaFilters.busca,
          de: consultaFilters.de || undefined,
          ate: consultaFilters.ate || undefined,
          categoriaId: consultaFilters.categoriaId || undefined,
          faixa: consultaFilters.faixa || undefined,
          origem: consultaFilters.origem || undefined,
          comSaldo: consultaFilters.comSaldo,
          pagina: consultaPage,
          tamanhoPagina: consultaPageSize,
        })
        .then((data) => {
          if (!cancelled) setConsulta(data ?? null);
        })
        .catch((error) => {
          if (!cancelled) Toast.error(error instanceof Error ? error.message : "Erro ao consultar lotes.");
        })
        .finally(() => {
          if (!cancelled) setConsultaLoading(false);
        });
    }, 300);

    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [tab, consultaFilters, consultaPage, consultaPageSize]);

  const loadFefo = useCallback(async () => {
    setFefoLoading(true);
    try {
      setFefo((await loteService.fefoStatus()) ?? null);
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao carregar a situação da baixa por lote.");
    } finally {
      setFefoLoading(false);
    }
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (tab === "fefo") void loadFefo();
  }, [tab, loadFefo]);

  const handleChangeModo = async (modo: FefoModo) => {
    if (!fefo || fefo.modo === modo) return;
    if (modo === "ativo") {
      const confirmed = await statusDialog.confirm(
        `Ativar o saldo real por lote? ${fefo.lotesComDivergencia} lote(s) em ${fefo.produtosComDivergencia} produto(s) divergem do saldo estimado. As telas de validade passam a mostrar o saldo real, baixado pelas vendas.`,
      );
      if (!confirmed) return;
    }

    setModoChanging(true);
    try {
      await loteService.definirModoFefo(modo);
      Toast.success(`Baixa por lote: modo ${MODO_INFO[modo].label.toLowerCase()}.`);
      await Promise.all([loadFefo(), loadAlertas()]);
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao alterar o modo.");
    } finally {
      setModoChanging(false);
    }
  };

  // Saldo por lote: real quando o modo é "ativo", estimado nos demais.
  const saldoLabel = (modo?: FefoModo) => (modo === "ativo" ? "Saldo (real)" : "Qtd. estimada");

  const updateConsultaFilters = (patch: Partial<ConsultaFilters>) => {
    setConsultaFilters((current) => ({ ...current, ...patch }));
    setConsultaPage(1);
  };

  const hasConsultaFilters = JSON.stringify(consultaFilters) !== JSON.stringify(EMPTY_CONSULTA_FILTERS);

  const itensFiltrados = useMemo(
    () => (resumo?.itens ?? []).filter((item) => faixaFilter === "todas" || item.faixa === faixaFilter),
    [resumo, faixaFilter],
  );

  const handleBaixarPerda = async (item: LoteAlertaDto) => {
    const confirmed = await statusDialog.confirm(
      `Dar baixa de ${formatQty(item.qtdEstimada)} un. de "${item.productName}" (lote com validade ${formatDate(item.dataValidade)}) como perda por vencimento? O estoque do produto será reduzido.`,
    );
    if (!confirmed) return;

    setBaixandoIds((current) => new Set(current).add(item.id));
    try {
      await productService.adjustStock(item.produtoId, {
        tipo: "saida",
        quantidade: item.qtdEstimada,
        motivo: `Perda por vencimento (lote ${formatDate(item.dataValidade)})`,
      });
      Toast.success("Baixa por perda registrada.");
      await loadAlertas();
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao registrar baixa.");
    } finally {
      setBaixandoIds((current) => {
        const next = new Set(current);
        next.delete(item.id);
        return next;
      });
    }
  };

  const openRegister = async () => {
    setRegisterOpen(true);
    if (products.length === 0) {
      try {
        setProducts(await productService.list());
      } catch {
        Toast.error("Não foi possível carregar os produtos.");
      }
    }
  };

  const closeRegister = () => {
    setRegisterOpen(false);
    setProductSearch("");
    setRegProdutoId("");
    setRegValidade("");
    setRegQuantidade("");
    setRegNumeroLote("");
  };

  const productOptions = useMemo(() => {
    const term = productSearch.trim().toLowerCase();
    const list = term
      ? products.filter(
          (product) =>
            product.productName.toLowerCase().includes(term) || product.productCode.toLowerCase().includes(term),
        )
      : products;
    return list.slice(0, 50);
  }, [products, productSearch]);

  const handleRegister = async () => {
    const quantidade = Number(regQuantidade.replace(",", "."));
    if (!regProdutoId) return Toast.error("Selecione o produto.");
    if (!regValidade) return Toast.error("Informe a data de validade.");
    if (!Number.isFinite(quantidade) || quantidade <= 0) return Toast.error("Informe uma quantidade maior que zero.");

    setRegistering(true);
    try {
      await loteService.registrar({
        produtoId: regProdutoId,
        dataValidade: regValidade,
        quantidade,
        numeroLote: regNumeroLote.trim() || undefined,
      });
      Toast.success("Lote registrado.");
      closeRegister();
      await loadAlertas();
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao registrar lote.");
    } finally {
      setRegistering(false);
    }
  };

  const handleSaveCategoria = async (categoria: CategoriaValidadeDto) => {
    const edit = edits[categoria.categoriaId];
    if (!edit) return;
    const toNumber = (value: string) => (value.trim() === "" ? null : Number(value));
    const prazo = toNumber(edit.prazo);
    const alerta = toNumber(edit.alerta);
    if ((prazo !== null && !Number.isInteger(prazo)) || (alerta !== null && !Number.isInteger(alerta))) {
      Toast.error("Use apenas números inteiros (dias).");
      return;
    }

    setSavingCategoriaIds((current) => new Set(current).add(categoria.categoriaId));
    try {
      await loteService.salvarCategoria(categoria.categoriaId, { prazoPadraoDias: prazo, diasAlerta: alerta });
      Toast.success(`Prazos de "${categoria.nome}" salvos.`);
      await Promise.all([loadCategorias(), loadAlertas()]);
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao salvar prazos.");
    } finally {
      setSavingCategoriaIds((current) => {
        const next = new Set(current);
        next.delete(categoria.categoriaId);
        return next;
      });
    }
  };

  // Departamentos primeiro, cada um seguido das suas subcategorias.
  const categoriasOrdenadas = useMemo(() => {
    const raizes = categorias.filter((categoria) => !categoria.categoriaPaiId);
    return raizes.flatMap((raiz) => [
      { categoria: raiz, nivel: 0 },
      ...categorias
        .filter((categoria) => categoria.categoriaPaiId === raiz.categoriaId)
        .map((categoria) => ({ categoria, nivel: 1 })),
    ]);
  }, [categorias]);

  const summaryCards = [
    { key: "vencido" as const, label: "Vencidos", value: resumo?.vencidos ?? 0, icon: <AlertCircle size={16} />, tone: "text-red-600 dark:text-red-400 bg-red-500/10 border-red-500/30" },
    { key: "critico" as const, label: "Críticos", value: resumo?.criticos ?? 0, icon: <AlertTriangle size={16} />, tone: "text-orange-600 dark:text-orange-400 bg-orange-500/10 border-orange-500/30" },
    { key: "atencao" as const, label: "Atenção", value: resumo?.atencao ?? 0, icon: <Clock size={16} />, tone: "text-amber-600 dark:text-amber-400 bg-amber-500/10 border-amber-500/30" },
  ];

  return (
    <PageLayout className="space-y-4 py-4 md:space-y-6 md:py-6 lg:py-8">
      <PageHeader
        title="Controle de Validade"
        description="Lotes próximos do vencimento, com saldo estimado e valor em risco."
        action={
          <button type="button" onClick={() => void openRegister()} className="btn-primary inline-flex items-center gap-2">
            <Plus size={16} /> Registrar lote
          </button>
        }
      />

      <div className="flex items-center gap-1.5 self-start rounded-lg bg-bg-primary p-1 border border-border-primary w-fit">
        {(
          [
            ["alertas", "Alertas por lote"],
            ["consulta", "Consulta de lotes"],
            ["fefo", "Baixa por lote"],
            ["categorias", "Prazos por categoria"],
          ] as const
        ).map(([key, label]) => (
          <button
            key={key}
            type="button"
            onClick={() => setTab(key)}
            className={`rounded-md px-3 py-1.5 text-xs font-semibold transition-all ${
              tab === key ? "bg-bg-card text-brand-primary shadow-xs" : "text-text-secondary hover:text-text-primary"
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      {tab === "alertas" ? (
        <>
          <section className="grid grid-cols-2 gap-3 md:grid-cols-5">
            {summaryCards.map((card) => (
              <button
                key={card.key}
                type="button"
                onClick={() => setFaixaFilter((current) => (current === card.key ? "todas" : card.key))}
                className={`card flex items-center gap-3 border p-3 text-left transition ${card.tone} ${
                  faixaFilter === card.key ? "ring-2 ring-current" : ""
                }`}
              >
                <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-current/10">{card.icon}</span>
                <span>
                  <span className="block text-xl font-bold leading-none">{card.value}</span>
                  <span className="mt-1 block text-[11px] font-medium">{card.label}</span>
                </span>
              </button>
            ))}
            <div className="card border border-border-primary p-3">
              <span className="block text-xs text-text-secondary">Valor em risco (custo)</span>
              <span className="mt-1 block text-lg font-bold text-text-primary">R$ {formatMoneyBr(resumo?.valorEmRisco ?? 0)}</span>
            </div>
            <div className="card border border-border-primary p-3">
              <span className="block text-xs text-text-secondary">Controlados sem lote</span>
              <span className="mt-1 block text-lg font-bold text-text-primary">{resumo?.produtosSemLote ?? 0}</span>
            </div>
          </section>

          <section className="card overflow-hidden">
            <div className="flex items-center justify-between border-b border-border-primary px-4 py-3">
              <p className="text-xs text-text-secondary">
                {resumo?.modo === "ativo" ? (
                  <>O saldo por lote é o <strong>real</strong>, baixado pelas vendas (vence primeiro, sai primeiro).</>
                ) : (
                  <>O saldo por lote é <strong>estimado</strong>: considera que o que vence primeiro sai primeiro.</>
                )}
              </p>
              <button type="button" onClick={() => void loadAlertas()} className="btn-secondary inline-flex items-center gap-1.5 text-xs">
                <RefreshCw size={13} className={loading ? "animate-spin" : ""} /> Atualizar
              </button>
            </div>
            <TableScrollArea label="Produtos e lotes" className="overflow-x-auto">
              <table className="w-full min-w-[900px] text-sm">
                <thead className="bg-bg-primary text-left text-text-secondary">
                  <tr>
                    <th className="px-3 py-3">Produto</th>
                    <th className="px-3 py-3">Lote</th>
                    <th className="px-3 py-3">Validade</th>
                    <th className="px-3 py-3 text-right">{saldoLabel(resumo?.modo)}</th>
                    <th className="px-3 py-3 text-right">Valor em risco</th>
                    <th className="px-3 py-3">Situação</th>
                    <th className="px-3 py-3 text-center">Ação</th>
                  </tr>
                </thead>
                <tbody>
                  {itensFiltrados.length === 0 ? (
                    <tr>
                      <td colSpan={7} className="px-3 py-10 text-center text-text-tertiary">
                        {loading ? "Carregando..." : "Nenhum lote em alerta. 🎉"}
                      </td>
                    </tr>
                  ) : null}
                  {itensFiltrados.map((item) => (
                    <tr key={item.id} className="border-t border-border-primary">
                      <td className="px-3 py-3">
                        <span className="block font-semibold text-text-primary">{item.productName}</span>
                        <span className="block text-xs text-text-secondary">
                          {item.productCode}
                          {item.categoriaNome ? ` · ${item.categoriaNome}` : ""}
                        </span>
                      </td>
                      <td className="px-3 py-3 text-xs text-text-secondary">
                        <span className="block">{item.numeroLote || "—"}</span>
                        {item.validadePadrao ? (
                          <span className="mt-0.5 inline-block rounded bg-sky-500/10 px-1.5 py-0.5 text-[10px] font-semibold text-sky-600 dark:text-sky-400" title="Validade sugerida pelo prazo padrão da categoria. Confira na embalagem.">
                            validade sugerida
                          </span>
                        ) : null}
                      </td>
                      <td className="px-3 py-3">
                        <span className="block font-medium">{formatDate(item.dataValidade)}</span>
                        <span className="block text-xs text-text-secondary">{describeDays(item.diasParaVencer)}</span>
                      </td>
                      <td className="px-3 py-3 text-right tabular-nums">{formatQty(item.qtdEstimada)}</td>
                      <td className="px-3 py-3 text-right font-semibold tabular-nums">R$ {formatMoneyBr(item.valorEmRisco)}</td>
                      <td className="px-3 py-3">
                        <span className={`inline-flex rounded-full border px-2 py-0.5 text-[11px] font-semibold ${FAIXA_STYLE[item.faixa].badge}`}>
                          {FAIXA_STYLE[item.faixa].label}
                        </span>
                      </td>
                      <td className="px-3 py-3 text-center">
                        <button
                          type="button"
                          disabled={baixandoIds.has(item.id)}
                          onClick={() => void handleBaixarPerda(item)}
                          className="btn-secondary inline-flex items-center gap-1.5 text-xs disabled:opacity-60"
                          title="Reduz o estoque do produto pela quantidade estimada do lote"
                        >
                          <PackageX size={13} /> {baixandoIds.has(item.id) ? "Baixando..." : "Baixar perda"}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </TableScrollArea>
          </section>
        </>
      ) : tab === "consulta" ? (
        <>
          <section className="card space-y-3 p-4 md:p-5">
            <label className="relative block w-full sm:max-w-md">
              <Search size={16} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-text-tertiary" />
              <input
                value={consultaFilters.busca}
                onChange={(event) => updateConsultaFilters({ busca: event.target.value })}
                className="input-field w-full pl-9"
                placeholder="Produto, código ou número do lote"
              />
            </label>

            <div className="flex flex-wrap items-center gap-1.5">
              <span className="mr-1 text-xs font-semibold text-text-secondary">Validade:</span>
              {QUICK_RANGES.map((quick) => (
                <button
                  key={quick.label}
                  type="button"
                  onClick={() => updateConsultaFilters(quick.range())}
                  className="rounded-md border border-border-primary px-2.5 py-1 text-xs font-medium text-text-secondary hover:bg-hover-light hover:text-text-primary"
                >
                  {quick.label}
                </button>
              ))}
            </div>

            <div className="grid grid-cols-2 gap-3 md:grid-cols-3 lg:grid-cols-6">
              <label className="block text-xs text-text-secondary">
                Validade de
                <input
                  type="date"
                  value={consultaFilters.de}
                  max={consultaFilters.ate || undefined}
                  onChange={(event) => updateConsultaFilters({ de: event.target.value })}
                  className="input-field mt-1 w-full py-1.5 text-xs"
                />
              </label>
              <label className="block text-xs text-text-secondary">
                Validade até
                <input
                  type="date"
                  value={consultaFilters.ate}
                  min={consultaFilters.de || undefined}
                  onChange={(event) => updateConsultaFilters({ ate: event.target.value })}
                  className="input-field mt-1 w-full py-1.5 text-xs"
                />
              </label>
              <label className="block text-xs text-text-secondary">
                Categoria
                <select
                  value={consultaFilters.categoriaId}
                  onChange={(event) => updateConsultaFilters({ categoriaId: event.target.value })}
                  className="input-field mt-1 w-full py-1.5 text-xs"
                >
                  <option value="">Todas</option>
                  {categoriasOrdenadas.map(({ categoria, nivel }) => (
                    <option key={categoria.categoriaId} value={categoria.categoriaId}>
                      {nivel === 1 ? `— ${categoria.nome}` : categoria.nome}
                    </option>
                  ))}
                </select>
              </label>
              <label className="block text-xs text-text-secondary">
                Situação
                <select
                  value={consultaFilters.faixa}
                  onChange={(event) => updateConsultaFilters({ faixa: event.target.value as ConsultaFilters["faixa"] })}
                  className="input-field mt-1 w-full py-1.5 text-xs"
                >
                  <option value="">Todas</option>
                  {(Object.keys(FAIXA_STYLE) as LoteFaixa[]).map((key) => (
                    <option key={key} value={key}>
                      {FAIXA_STYLE[key].label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="block text-xs text-text-secondary">
                Origem do lote
                <select
                  value={consultaFilters.origem}
                  onChange={(event) => updateConsultaFilters({ origem: event.target.value })}
                  className="input-field mt-1 w-full py-1.5 text-xs"
                >
                  <option value="">Todas</option>
                  {Object.entries(ORIGEM_LABEL).map(([key, label]) => (
                    <option key={key} value={key}>
                      {label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="flex items-end gap-2 pb-1.5 text-xs text-text-secondary">
                <input
                  type="checkbox"
                  checked={consultaFilters.comSaldo}
                  onChange={(event) => updateConsultaFilters({ comSaldo: event.target.checked })}
                />
                Só com saldo estimado
              </label>
            </div>

            {hasConsultaFilters ? (
              <button
                type="button"
                onClick={() => {
                  setConsultaFilters(EMPTY_CONSULTA_FILTERS);
                  setConsultaPage(1);
                }}
                className="inline-flex items-center gap-1 text-xs font-medium text-text-secondary hover:text-text-primary"
              >
                <X size={13} /> Limpar filtros
              </button>
            ) : null}
          </section>

          <section className="card overflow-hidden">
            <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border-primary px-4 py-3 text-xs text-text-secondary">
              <span>
                <strong className="text-text-primary">{consulta?.total ?? 0}</strong> lote(s) encontrado(s) · valor em risco{" "}
                <strong className="text-text-primary">R$ {formatMoneyBr(consulta?.valorEmRisco ?? 0)}</strong>
              </span>
              <span>
                {consulta?.modo === "ativo"
                  ? "Saldo real, baixado pelas vendas."
                  : "Saldo estimado: considera que o que vence primeiro sai primeiro."}
              </span>
            </div>
            <TableScrollArea label="Produtos e lotes" className="overflow-x-auto">
              <table className="w-full min-w-[960px] text-sm">
                <thead className="bg-bg-primary text-left text-text-secondary">
                  <tr>
                    <th className="px-3 py-3">Produto</th>
                    <th className="px-3 py-3">Lote</th>
                    <th className="px-3 py-3">Validade</th>
                    <th className="px-3 py-3 text-right">Qtd. inicial</th>
                    <th className="px-3 py-3 text-right">{saldoLabel(consulta?.modo)}</th>
                    <th className="px-3 py-3 text-right">Valor em risco</th>
                    <th className="px-3 py-3">Situação</th>
                    <th className="px-3 py-3">Origem</th>
                  </tr>
                </thead>
                <tbody>
                  {(consulta?.itens.length ?? 0) === 0 ? (
                    <tr>
                      <td colSpan={8} className="px-3 py-10 text-center text-text-tertiary">
                        {consultaLoading ? "Carregando..." : "Nenhum lote encontrado para os filtros selecionados."}
                      </td>
                    </tr>
                  ) : null}
                  {consulta?.itens.map((item) => (
                    <tr key={item.id} className="border-t border-border-primary">
                      <td className="px-3 py-3">
                        <span className="block font-semibold text-text-primary">{item.productName}</span>
                        <span className="block text-xs text-text-secondary">
                          {item.productCode}
                          {item.categoriaNome ? ` · ${item.categoriaNome}` : ""}
                        </span>
                      </td>
                      <td className="px-3 py-3 text-xs text-text-secondary">
                        <span className="block">{item.numeroLote || "—"}</span>
                        {item.validadePadrao ? (
                          <span className="mt-0.5 inline-block rounded bg-sky-500/10 px-1.5 py-0.5 text-[10px] font-semibold text-sky-600 dark:text-sky-400">
                            validade sugerida
                          </span>
                        ) : null}
                      </td>
                      <td className="px-3 py-3">
                        <span className="block font-medium">{formatDate(item.dataValidade)}</span>
                        <span className="block text-xs text-text-secondary">{describeDays(item.diasParaVencer)}</span>
                      </td>
                      <td className="px-3 py-3 text-right tabular-nums">{formatQty(item.qtdInicial)}</td>
                      <td className="px-3 py-3 text-right tabular-nums">{formatQty(item.qtdEstimada)}</td>
                      <td className="px-3 py-3 text-right font-semibold tabular-nums">R$ {formatMoneyBr(item.valorEmRisco)}</td>
                      <td className="px-3 py-3">
                        <span className={`inline-flex rounded-full border px-2 py-0.5 text-[11px] font-semibold ${FAIXA_STYLE[item.faixa].badge}`}>
                          {FAIXA_STYLE[item.faixa].label}
                        </span>
                      </td>
                      <td className="px-3 py-3 text-xs text-text-secondary">{ORIGEM_LABEL[item.origem] ?? item.origem}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </TableScrollArea>
            <div className="px-4 py-4">
              <TablePagination
                totalItems={consulta?.total ?? 0}
                currentPage={consulta?.pagina ?? consultaPage}
                itemsPerPage={consultaPageSize}
                onPageChange={setConsultaPage}
                onItemsPerPageChange={(value) => {
                  setConsultaPageSize(value);
                  setConsultaPage(1);
                }}
              />
            </div>
          </section>
        </>
      ) : tab === "fefo" ? (
        <>
          <section className="card space-y-4 p-4 md:p-5">
            <div>
              <h2 className="text-sm font-semibold text-text-primary">Baixa por lote na venda (FEFO)</h2>
              <p className="mt-1 text-xs text-text-secondary">
                Cada venda tira a quantidade dos lotes que vencem primeiro. Um lote vencido nunca bloqueia a venda: o PDV
                só avisa.
              </p>
            </div>

            <div className="grid gap-2 md:grid-cols-3">
              {(Object.keys(MODO_INFO) as FefoModo[]).map((modo) => {
                const selected = fefo?.modo === modo;
                return (
                  <button
                    key={modo}
                    type="button"
                    disabled={!fefo || modoChanging}
                    onClick={() => void handleChangeModo(modo)}
                    className={`rounded-xl border p-3 text-left transition disabled:opacity-60 ${
                      selected
                        ? "border-brand-primary bg-brand-primary/10"
                        : "border-border-primary hover:bg-hover-light"
                    }`}
                  >
                    <span className="flex items-center gap-2 text-sm font-semibold text-text-primary">
                      {MODO_INFO[modo].label}
                      {selected ? (
                        <span className="rounded-full bg-brand-primary/15 px-2 py-0.5 text-[10px] font-bold text-brand-primary">
                          atual
                        </span>
                      ) : null}
                    </span>
                    <span className="mt-1 block text-xs text-text-secondary">{MODO_INFO[modo].description}</span>
                  </button>
                );
              })}
            </div>

            <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
              <div className="card border border-border-primary p-3">
                <span className="block text-xs text-text-secondary">Lotes com saldo real</span>
                <span className="mt-1 block text-lg font-bold text-text-primary">{fefo?.lotesComSaldo ?? 0}</span>
              </div>
              <div className="card border border-border-primary p-3">
                <span className="block text-xs text-text-secondary">Lotes divergentes</span>
                <span className="mt-1 block text-lg font-bold text-text-primary">{fefo?.lotesComDivergencia ?? 0}</span>
              </div>
              <div className="card border border-border-primary p-3">
                <span className="block text-xs text-text-secondary">Produtos divergentes</span>
                <span className="mt-1 block text-lg font-bold text-text-primary">{fefo?.produtosComDivergencia ?? 0}</span>
              </div>
              <div
                className="card border border-border-primary p-3"
                title="Estoque do produto maior que a soma dos saldos dos lotes: entrada sem lote, ajuste manual, edição de quantidade."
              >
                <span className="block text-xs text-text-secondary">Estoque não coberto por lote</span>
                <span className="mt-1 block text-lg font-bold text-text-primary">{fefo?.produtosComEstoqueSemLote ?? 0}</span>
              </div>
            </div>
          </section>

          <section className="card overflow-hidden">
            <div className="flex items-center justify-between border-b border-border-primary px-4 py-3">
              <p className="text-xs text-text-secondary">
                Maiores divergências entre o saldo <strong>real</strong> (baixado pelas vendas) e o{" "}
                <strong>estimado</strong>. Poucas divergências indicam que a baixa por lote está confiável.
              </p>
              <button type="button" onClick={() => void loadFefo()} className="btn-secondary inline-flex items-center gap-1.5 text-xs">
                <RefreshCw size={13} className={fefoLoading ? "animate-spin" : ""} /> Atualizar
              </button>
            </div>
            <TableScrollArea label="Produtos e lotes" className="overflow-x-auto">
              <table className="w-full min-w-[760px] text-sm">
                <thead className="bg-bg-primary text-left text-text-secondary">
                  <tr>
                    <th className="px-3 py-3">Produto</th>
                    <th className="px-3 py-3">Lote</th>
                    <th className="px-3 py-3">Validade</th>
                    <th className="px-3 py-3 text-right">Estimado</th>
                    <th className="px-3 py-3 text-right">Real</th>
                    <th className="px-3 py-3 text-right">Diferença</th>
                  </tr>
                </thead>
                <tbody>
                  {(fefo?.itens.length ?? 0) === 0 ? (
                    <tr>
                      <td colSpan={6} className="px-3 py-10 text-center text-text-tertiary">
                        {fefoLoading ? "Carregando..." : "Nenhuma divergência. 🎉"}
                      </td>
                    </tr>
                  ) : null}
                  {fefo?.itens.map((item) => (
                    <tr key={item.loteId} className="border-t border-border-primary">
                      <td className="px-3 py-3">
                        <span className="block font-semibold text-text-primary">{item.productName}</span>
                        <span className="block text-xs text-text-secondary">{item.productCode}</span>
                      </td>
                      <td className="px-3 py-3 text-xs text-text-secondary">{item.numeroLote || "—"}</td>
                      <td className="px-3 py-3">{formatDate(item.dataValidade)}</td>
                      <td className="px-3 py-3 text-right tabular-nums">{formatQty(item.qtdEstimada)}</td>
                      <td className="px-3 py-3 text-right tabular-nums">{formatQty(item.qtdReal)}</td>
                      <td
                        className={`px-3 py-3 text-right font-semibold tabular-nums ${
                          item.diferenca < 0 ? "text-red-500" : "text-emerald-500"
                        }`}
                      >
                        {item.diferenca > 0 ? "+" : ""}
                        {formatQty(item.diferenca)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </TableScrollArea>
          </section>
        </>
      ) : (
        <section className="card overflow-hidden">
          <div className="border-b border-border-primary px-4 py-3 text-xs text-text-secondary">
            <CalendarClock size={14} className="mr-1.5 inline" />
            <strong>Prazo padrão</strong>: validade sugerida na entrada quando você não informa a data (dias a contar do recebimento).{" "}
            <strong>Alerta</strong>: quantos dias antes do vencimento o lote entra em alerta. Subcategorias sem valor usam o do departamento.
          </div>
          <TableScrollArea label="Produtos e lotes" className="overflow-x-auto">
            <table className="w-full min-w-[640px] text-sm">
              <thead className="bg-bg-primary text-left text-text-secondary">
                <tr>
                  <th className="px-3 py-3">Categoria</th>
                  <th className="w-40 px-3 py-3">Prazo padrão (dias)</th>
                  <th className="w-40 px-3 py-3">Alerta (dias antes)</th>
                  <th className="w-28 px-3 py-3" />
                </tr>
              </thead>
              <tbody>
                {categoriasOrdenadas.map(({ categoria, nivel }) => {
                  const edit = edits[categoria.categoriaId] ?? { prazo: "", alerta: "" };
                  const dirty =
                    edit.prazo !== (categoria.prazoPadraoDias?.toString() ?? "") ||
                    edit.alerta !== (categoria.diasAlerta?.toString() ?? "");
                  return (
                    <tr key={categoria.categoriaId} className="border-t border-border-primary">
                      <td className={`px-3 py-2 ${nivel === 0 ? "font-semibold text-text-primary" : "pl-8 text-text-secondary"}`}>
                        {categoria.nome}
                      </td>
                      <td className="px-3 py-2">
                        <input
                          inputMode="numeric"
                          className="input-field w-28 py-1.5 text-xs"
                          placeholder="sem prazo"
                          value={edit.prazo}
                          onChange={(event) =>
                            setEdits((current) => ({
                              ...current,
                              [categoria.categoriaId]: { ...edit, prazo: event.target.value.replace(/\D/g, "") },
                            }))
                          }
                        />
                      </td>
                      <td className="px-3 py-2">
                        <input
                          inputMode="numeric"
                          className="input-field w-28 py-1.5 text-xs"
                          placeholder="padrão do produto"
                          value={edit.alerta}
                          onChange={(event) =>
                            setEdits((current) => ({
                              ...current,
                              [categoria.categoriaId]: { ...edit, alerta: event.target.value.replace(/\D/g, "") },
                            }))
                          }
                        />
                      </td>
                      <td className="px-3 py-2 text-right">
                        {dirty ? (
                          <button
                            type="button"
                            disabled={savingCategoriaIds.has(categoria.categoriaId)}
                            onClick={() => void handleSaveCategoria(categoria)}
                            className="btn-primary inline-flex items-center gap-1.5 text-xs disabled:opacity-60"
                          >
                            <Save size={13} /> Salvar
                          </button>
                        ) : null}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </TableScrollArea>
        </section>
      )}

      {registerOpen ? (
        <div className="fixed inset-0 z-layer-dialog flex items-end bg-black/55 px-3 backdrop-blur-sm md:items-center md:justify-center">
          <div className="w-full max-w-lg rounded-t-2xl border border-border-primary bg-bg-light p-5 shadow-2xl md:rounded-2xl">
            <div className="mb-4 flex items-center justify-between">
              <h2 className="text-base font-semibold text-text-primary">Registrar lote</h2>
              <button type="button" onClick={closeRegister} aria-label="Fechar" className="rounded-lg border border-border-primary p-1.5 text-text-secondary hover:bg-hover-light">
                <X size={16} />
              </button>
            </div>
            <p className="mb-3 text-xs text-text-secondary">
              Registra a validade de uma entrada. Não altera o estoque: a quantidade entra pela nota ou pela compra.
            </p>
            <div className="space-y-3">
              <label className="block text-xs text-text-secondary">
                Produto
                <input
                  className="input-field mt-1 w-full"
                  placeholder="Buscar por nome ou código"
                  value={productSearch}
                  onChange={(event) => setProductSearch(event.target.value)}
                />
                <select
                  className="input-field mt-1 w-full"
                  size={5}
                  value={regProdutoId}
                  onChange={(event) => setRegProdutoId(event.target.value)}
                >
                  {productOptions.map((product) => (
                    <option key={product.id} value={product.id}>
                      {product.productCode} · {product.productName}
                    </option>
                  ))}
                </select>
              </label>
              <div className="grid grid-cols-2 gap-3">
                <label className="block text-xs text-text-secondary">
                  Validade
                  <input type="date" className="input-field mt-1 w-full" value={regValidade} onChange={(event) => setRegValidade(event.target.value)} />
                </label>
                <label className="block text-xs text-text-secondary">
                  Quantidade do lote
                  <input inputMode="decimal" className="input-field mt-1 w-full" value={regQuantidade} onChange={(event) => setRegQuantidade(event.target.value)} />
                </label>
              </div>
              <label className="block text-xs text-text-secondary">
                Nº do lote (opcional)
                <input className="input-field mt-1 w-full" value={regNumeroLote} onChange={(event) => setRegNumeroLote(event.target.value)} />
              </label>
            </div>
            <div className="mt-5 flex justify-end gap-2">
              <button type="button" onClick={closeRegister} className="btn-secondary">
                Cancelar
              </button>
              <button type="button" onClick={() => void handleRegister()} disabled={registering} className="btn-primary disabled:opacity-60">
                {registering ? "Salvando..." : "Registrar lote"}
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {statusDialog.Dialog}
    </PageLayout>
  );
}
