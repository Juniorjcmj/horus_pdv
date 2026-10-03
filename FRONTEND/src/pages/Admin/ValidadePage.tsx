/**
 * Arquivo: src/pages/Admin/ValidadePage.tsx
 * Objetivo: controle de validade por lote — alertas por lote com saldo estimado e valor em risco,
 *           registro manual de lote e prazo padrão/janela de alerta por categoria.
 * Entradas esperadas: não recebe props; consome a API de lotes (api/Produto/lotes).
 *
 * Fase 1: o lote é informativo. A venda não baixa lote; o saldo mostrado é estimado assumindo que o
 * estoque é consumido na ordem FEFO (vence primeiro, sai primeiro).
 */
import { AlertCircle, AlertTriangle, CalendarClock, Clock, PackageX, Plus, RefreshCw, Save, X } from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";
import PageHeader from "@/components/Admin/PageHeader";
import { Toast, useStatusDialog } from "@/hooks/Dialog";
import useInputMasks from "@/hooks/InputMasks/useInputMasks";
import PageLayout from "@/layout/PageLayout";
import {
  loteService,
  type CategoriaValidadeDto,
  type LoteAlertaDto,
  type LoteAlertasResumoDto,
  type LoteFaixa,
} from "@/services/api/loteService";
import { productService, type ProductDto } from "@/services/api/productService";

type Tab = "alertas" | "categorias";
type FaixaFilter = "todas" | LoteFaixa;

const FAIXA_STYLE: Record<LoteFaixa, { label: string; badge: string }> = {
  vencido: { label: "Vencido", badge: "bg-red-500/15 text-red-600 dark:text-red-400 border-red-500/30" },
  critico: { label: "Crítico", badge: "bg-orange-500/15 text-orange-600 dark:text-orange-400 border-orange-500/30" },
  atencao: { label: "Atenção", badge: "bg-amber-500/15 text-amber-600 dark:text-amber-400 border-amber-500/30" },
};

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
                O saldo por lote é <strong>estimado</strong>: considera que o que vence primeiro sai primeiro.
              </p>
              <button type="button" onClick={() => void loadAlertas()} className="btn-secondary inline-flex items-center gap-1.5 text-xs">
                <RefreshCw size={13} className={loading ? "animate-spin" : ""} /> Atualizar
              </button>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full min-w-[900px] text-sm">
                <thead className="bg-bg-primary text-left text-text-secondary">
                  <tr>
                    <th className="px-3 py-3">Produto</th>
                    <th className="px-3 py-3">Lote</th>
                    <th className="px-3 py-3">Validade</th>
                    <th className="px-3 py-3 text-right">Qtd. estimada</th>
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
            </div>
          </section>
        </>
      ) : (
        <section className="card overflow-hidden">
          <div className="border-b border-border-primary px-4 py-3 text-xs text-text-secondary">
            <CalendarClock size={14} className="mr-1.5 inline" />
            <strong>Prazo padrão</strong>: validade sugerida na entrada quando você não informa a data (dias a contar do recebimento).{" "}
            <strong>Alerta</strong>: quantos dias antes do vencimento o lote entra em alerta. Subcategorias sem valor usam o do departamento.
          </div>
          <div className="overflow-x-auto">
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
          </div>
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
