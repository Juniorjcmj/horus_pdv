/**
 * Arquivo: src/pages/Admin/PromocoesPage.tsx
 * Objetivo: gerenciar promoções, regras de descontos dinâmicos e consultar resultados de vendas promocionais.
 */
import {
  Calendar,
  Check,
  Edit2,
  Loader2,
  Package,
  Percent,
  Plus,
  Search,
  Tag,
  Trash2,
  TrendingUp,
  X,
} from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";
import { Toast, useStatusDialog } from "@/hooks/Dialog";
import useInputMasks from "@/hooks/InputMasks/useInputMasks";
import { categoriaService, type Categoria } from "@/services/api/categoriaService";
import { productService, type ProductDto } from "@/services/api/productService";
import {
  promocaoService,
  type Promocao,
  type PromocaoPayload,
  type PromocaoResultado,
  type TipoPromocao,
} from "@/services/api/promocaoService";

const TIPO_LABELS: Record<TipoPromocao, string> = {
  desconto_percentual: "Desconto Percentual (%)",
  desconto_valor: "Desconto em Dinheiro (R$)",
  preco_fixo: "Preço Promocional Fixo",
  leve_x_pague_y: "Leve X, Pague Y",
  combo_quantidade: "Combo por Quantidade (ex: 3 por R$ 10)",
  preco_atacado: "Preço de Atacado (por volume)",
};

export default function PromocoesPage() {
  const { formatMoneyBr } = useInputMasks();
  const statusDialog = useStatusDialog();

  const [promocoes, setPromocoes] = useState<Promocao[]>([]);
  const [categories, setCategories] = useState<Categoria[]>([]);
  const [products, setProducts] = useState<ProductDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState("");
  const [statusFilter, setStatusFilter] = useState<"todas" | "ativas" | "encerradas">("todas");

  // Modal de Criação / Edição
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  // Form State
  const [formNome, setFormNome] = useState("");
  const [formTipo, setFormTipo] = useState<TipoPromocao>("desconto_percentual");
  const [formValorDesconto, setFormValorDesconto] = useState("");
  const [formPrecoFixo, setFormPrecoFixo] = useState("");
  const [formQtdLeva, setFormQtdLeva] = useState("");
  const [formQtdPaga, setFormQtdPaga] = useState("");
  const [formQtdMinima, setFormQtdMinima] = useState("");
  const [formInicio, setFormInicio] = useState("");
  const [formFim, setFormFim] = useState("");
  const [formCategoriaId, setFormCategoriaId] = useState<string>("");
  const [formProdutoIds, setFormProdutoIds] = useState<string[]>([]);
  const [productSearch, setProductSearch] = useState("");

  // Modal de Resultados
  const [resultadoModalOpen, setResultadoModalOpen] = useState(false);
  const [resultado, setResultado] = useState<PromocaoResultado | null>(null);
  const [loadingResultado, setLoadingResultado] = useState(false);

  const loadData = useCallback(async () => {
    try {
      const [promos, cats, prods] = await Promise.all([
        promocaoService.list(),
        categoriaService.listTodas(true),
        productService.list(),
      ]);
      setPromocoes(promos);
      setCategories(cats);
      setProducts(prods);
    } catch {
      Toast.error("Não foi possível carregar promoções.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const filteredPromotions = useMemo(() => {
    const now = new Date();
    return promocoes.filter((p) => {
      const matchesSearch = p.nome.toLowerCase().includes(search.toLowerCase());
      if (!matchesSearch) return false;

      const fim = new Date(p.fimVigencia);
      const isExpired = fim < now;

      if (statusFilter === "ativas") return p.ativa && !isExpired;
      if (statusFilter === "encerradas") return !p.ativa || isExpired;
      return true;
    });
  }, [promocoes, search, statusFilter]);

  const kpis = useMemo(() => {
    const now = new Date();
    const total = promocoes.length;
    const ativas = promocoes.filter((p) => p.ativa && new Date(p.fimVigencia) >= now).length;
    const prodsEmOferta = new Set(
      promocoes
        .filter((p) => p.ativa && new Date(p.fimVigencia) >= now)
        .flatMap((p) => p.produtoIds),
    ).size;
    return { total, ativas, prodsEmOferta };
  }, [promocoes]);

  const handleOpenCreate = () => {
    setEditingId(null);
    setFormNome("");
    setFormTipo("desconto_percentual");
    setFormValorDesconto("");
    setFormPrecoFixo("");
    setFormQtdLeva("3");
    setFormQtdPaga("2");
    setFormQtdMinima("3");

    const today = new Date();
    const nextWeek = new Date();
    nextWeek.setDate(today.getDate() + 7);

    setFormInicio(today.toISOString().slice(0, 16));
    setFormFim(nextWeek.toISOString().slice(0, 16));
    setFormCategoriaId("");
    setFormProdutoIds([]);
    setProductSearch("");
    setModalOpen(true);
  };

  const handleOpenEdit = (promo: Promocao) => {
    setEditingId(promo.id);
    setFormNome(promo.nome);
    setFormTipo(promo.tipo);
    setFormValorDesconto(promo.valorDesconto ? String(promo.valorDesconto) : "");
    setFormPrecoFixo(promo.precoFixo ? String(promo.precoFixo) : "");
    setFormQtdLeva(promo.quantidadeLeva ? String(promo.quantidadeLeva) : "");
    setFormQtdPaga(promo.quantidadePaga ? String(promo.quantidadePaga) : "");
    setFormQtdMinima(promo.quantidadeMinima ? String(promo.quantidadeMinima) : "");
    setFormInicio(new Date(promo.inicioVigencia).toISOString().slice(0, 16));
    setFormFim(new Date(promo.fimVigencia).toISOString().slice(0, 16));
    setFormCategoriaId(promo.categoriaId || "");
    setFormProdutoIds(promo.produtoIds || []);
    setProductSearch("");
    setModalOpen(true);
  };

  const handleSave = async () => {
    if (!formNome.trim()) {
      Toast.error("Informe o nome da promoção.");
      return;
    }
    if (!formInicio || !formFim) {
      Toast.error("Informe o período de vigência completo.");
      return;
    }
    if (new Date(formFim) <= new Date(formInicio)) {
      Toast.error("A data final deve ser posterior à data inicial.");
      return;
    }
    if (formProdutoIds.length === 0 && !formCategoriaId) {
      Toast.error("Selecione ao menos um produto ou uma categoria.");
      return;
    }

    const payload: PromocaoPayload = {
      nome: formNome.trim(),
      tipo: formTipo,
      inicioVigencia: new Date(formInicio).toISOString(),
      fimVigencia: new Date(formFim).toISOString(),
      categoriaId: formCategoriaId ? formCategoriaId : null,
      produtoIds: formProdutoIds,
      valorDesconto: formValorDesconto ? Number(formValorDesconto) : null,
      precoFixo: formPrecoFixo ? Number(formPrecoFixo) : null,
      quantidadeLeva: formQtdLeva ? parseInt(formQtdLeva, 10) : null,
      quantidadePaga: formQtdPaga ? parseInt(formQtdPaga, 10) : null,
      quantidadeMinima: formQtdMinima ? parseInt(formQtdMinima, 10) : null,
      ativa: true,
    };

    setSaving(true);
    try {
      if (editingId) {
        await promocaoService.update(editingId, payload);
        Toast.success("Promoção atualizada com sucesso!");
      } else {
        await promocaoService.create(payload);
        Toast.success("Promoção criada com sucesso!");
      }
      setModalOpen(false);
      await loadData();
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao salvar promoção.");
    } finally {
      setSaving(false);
    }
  };

  const handleToggleStatus = async (promo: Promocao) => {
    const nextState = !promo.ativa;
    try {
      await promocaoService.toggleStatus(promo.id, nextState);
      setPromocoes((current) =>
        current.map((p) => (p.id === promo.id ? { ...p, ativa: nextState } : p)),
      );
      Toast.success(nextState ? "Promoção ativada." : "Promoção desativada.");
    } catch {
      Toast.error("Não foi possível alterar status da promoção.");
    }
  };

  const handleDelete = async (promo: Promocao) => {
    const confirmed = await statusDialog.confirm(
      `Tem certeza que deseja excluir a promoção "${promo.nome}"?`,
    );
    if (!confirmed) return;

    try {
      await promocaoService.remove(promo.id);
      setPromocoes((current) => current.filter((p) => p.id !== promo.id));
      Toast.success("Promoção excluída com sucesso.");
    } catch {
      Toast.error("Erro ao excluir promoção.");
    }
  };

  const handleVerResultado = async (promo: Promocao) => {
    setLoadingResultado(true);
    setResultado(null);
    setResultadoModalOpen(true);
    try {
      const res = await promocaoService.getResultado(promo.id);
      setResultado(res ?? null);
    } catch {
      Toast.error("Erro ao carregar métricas da promoção.");
    } finally {
      setLoadingResultado(false);
    }
  };

  const formatPromoDetail = (promo: Promocao) => {
    switch (promo.tipo) {
      case "desconto_percentual":
        return `${promo.valorDesconto}% de desconto`;
      case "desconto_valor":
        return `R$ ${formatMoneyBr(promo.valorDesconto || 0)} de desconto`;
      case "preco_fixo":
        return `Preço fixo: R$ ${formatMoneyBr(promo.precoFixo || 0)}`;
      case "leve_x_pague_y":
        return `Leve ${promo.quantidadeLeva}, Pague ${promo.quantidadePaga}`;
      case "combo_quantidade":
        return `${promo.quantidadeMinima} unidades por R$ ${formatMoneyBr(promo.precoFixo || 0)}`;
      case "preco_atacado":
        return `A partir de ${promo.quantidadeMinima} un: R$ ${formatMoneyBr(promo.precoFixo || 0)} cada`;
      default:
        return "-";
    }
  };

  const filteredModalProducts = useMemo(() => {
    const term = productSearch.trim().toLowerCase();
    if (!term) return products.slice(0, 30);
    return products
      .filter(
        (p) =>
          p.productName.toLowerCase().includes(term) ||
          p.productCode.toLowerCase().includes(term),
      )
      .slice(0, 30);
  }, [products, productSearch]);

  const toggleSelectProduct = (id: string) => {
    setFormProdutoIds((curr) =>
      curr.includes(id) ? curr.filter((item) => item !== id) : [...curr, id],
    );
  };

  return (
    <div className="flex h-full flex-col overflow-y-auto bg-bg-light p-4 text-text-primary md:p-6">
      {/* Header */}
      <div className="mb-6 flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
        <div>
          <h1 className="font-display text-2xl font-bold tracking-tight">
            Promoções & Preços Dinâmicos
          </h1>
          <p className="text-sm text-text-secondary">
            Configure campanhas promocionais, combos e descontos aplicados automaticamente no PDV.
          </p>
        </div>
        <button
          type="button"
          onClick={handleOpenCreate}
          className="btn-primary inline-flex items-center justify-center gap-2 rounded-xl px-4 py-2.5 font-semibold"
        >
          <Plus size={18} />
          Nova Promoção
        </button>
      </div>

      {/* KPI Cards */}
      <div className="mb-6 grid grid-cols-1 gap-4 sm:grid-cols-3">
        <div className="rounded-2xl border border-border-primary bg-bg-primary p-4 shadow-sm">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-primary/10 text-primary">
              <Tag size={20} />
            </div>
            <div>
              <p className="text-xs text-text-secondary">Total de Campanhas</p>
              <p className="text-xl font-bold">{kpis.total}</p>
            </div>
          </div>
        </div>

        <div className="rounded-2xl border border-border-primary bg-bg-primary p-4 shadow-sm">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-emerald-500/10 text-emerald-600 dark:text-emerald-400">
              <Percent size={20} />
            </div>
            <div>
              <p className="text-xs text-text-secondary">Promoções Ativas</p>
              <p className="text-xl font-bold text-emerald-600 dark:text-emerald-400">
                {kpis.ativas}
              </p>
            </div>
          </div>
        </div>

        <div className="rounded-2xl border border-border-primary bg-bg-primary p-4 shadow-sm">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-blue-500/10 text-blue-600 dark:text-blue-400">
              <Package size={20} />
            </div>
            <div>
              <p className="text-xs text-text-secondary">Produtos em Oferta</p>
              <p className="text-xl font-bold">{kpis.prodsEmOferta}</p>
            </div>
          </div>
        </div>
      </div>

      {/* Filtros e Busca */}
      <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="relative flex-1 sm:max-w-xs">
          <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-text-secondary" />
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Buscar promoção..."
            className="input-primary w-full rounded-xl pl-9 pr-3 py-2 text-sm"
          />
        </div>

        <div className="flex gap-2">
          {(["todas", "ativas", "encerradas"] as const).map((filter) => (
            <button
              key={filter}
              type="button"
              onClick={() => setStatusFilter(filter)}
              className={`rounded-xl px-3 py-1.5 text-xs font-semibold capitalize transition-colors ${
                statusFilter === filter
                  ? "bg-primary text-white"
                  : "border border-border-primary bg-bg-primary text-text-secondary hover:bg-bg-light"
              }`}
            >
              {filter}
            </button>
          ))}
        </div>
      </div>

      {/* Lista de Promoções */}
      {loading ? (
        <div className="flex flex-1 items-center justify-center p-12">
          <Loader2 className="h-8 w-8 animate-spin text-primary" />
        </div>
      ) : filteredPromotions.length === 0 ? (
        <div className="flex flex-1 flex-col items-center justify-center rounded-2xl border border-dashed border-border-primary bg-bg-primary p-8 text-center text-text-secondary">
          <Tag size={40} className="mb-2 opacity-30" />
          <p className="text-base font-semibold">Nenhuma promoção encontrada.</p>
          <p className="text-xs">Crie novas campanhas ou altere os filtros acima.</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
          {filteredPromotions.map((promo) => {
            const now = new Date();
            const fim = new Date(promo.fimVigencia);
            const isExpired = fim < now;
            const isAtiva = promo.ativa && !isExpired;

            return (
              <div
                key={promo.id}
                className="flex flex-col justify-between rounded-2xl border border-border-primary bg-bg-primary p-4 shadow-sm transition-all hover:border-primary/40"
              >
                <div>
                  <div className="flex items-start justify-between gap-2">
                    <div>
                      <span
                        className={`inline-block rounded-md px-2 py-0.5 text-[10px] font-bold uppercase ${
                          isAtiva
                            ? "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400"
                            : isExpired
                            ? "bg-slate-500/15 text-slate-600 dark:text-slate-400"
                            : "bg-amber-500/15 text-amber-600 dark:text-amber-400"
                        }`}
                      >
                        {isExpired ? "Encerrada" : isAtiva ? "Ativa" : "Desativada"}
                      </span>
                      <h3 className="mt-1 font-semibold text-text-primary">{promo.nome}</h3>
                    </div>

                    <button
                      type="button"
                      onClick={() => handleToggleStatus(promo)}
                      title={promo.ativa ? "Desativar promoção" : "Ativar promoção"}
                      className={`relative inline-flex h-5 w-9 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none ${
                        promo.ativa ? "bg-emerald-500" : "bg-slate-300 dark:bg-slate-700"
                      }`}
                    >
                      <span
                        className={`pointer-events-none inline-block h-4 w-4 transform rounded-full bg-white shadow-lg ring-0 transition duration-200 ease-in-out ${
                          promo.ativa ? "translate-x-4" : "translate-x-0"
                        }`}
                      />
                    </button>
                  </div>

                  <div className="mt-3 rounded-xl bg-bg-light p-2.5 text-xs">
                    <p className="font-semibold text-primary">{TIPO_LABELS[promo.tipo]}</p>
                    <p className="mt-0.5 font-bold text-text-primary">{formatPromoDetail(promo)}</p>
                  </div>

                  <div className="mt-3 space-y-1 text-xs text-text-secondary">
                    <div className="flex items-center gap-1.5">
                      <Calendar size={13} />
                      <span>
                        {new Date(promo.inicioVigencia).toLocaleDateString("pt-BR")} até{" "}
                        {new Date(promo.fimVigencia).toLocaleDateString("pt-BR")}
                      </span>
                    </div>
                    <div className="flex items-center gap-1.5">
                      <Package size={13} />
                      <span>
                        {promo.categoriaNome ? (
                          <>Categoria: <strong>{promo.categoriaNome}</strong> ({promo.produtoIds?.length || 0} produtos adicionais)</>
                        ) : (
                          <>{promo.produtoIds?.length || 0} produto(s) vinculado(s)</>
                        )}
                      </span>
                    </div>
                  </div>
                </div>

                <div className="mt-4 flex items-center justify-between border-t border-border-primary pt-3">
                  <button
                    type="button"
                    onClick={() => handleVerResultado(promo)}
                    className="inline-flex items-center gap-1 text-xs font-semibold text-primary hover:underline"
                  >
                    <TrendingUp size={13} />
                    Resultados
                  </button>

                  <div className="flex items-center gap-1">
                    <button
                      type="button"
                      onClick={() => handleOpenEdit(promo)}
                      className="rounded-lg p-1.5 text-text-secondary hover:bg-bg-light hover:text-text-primary"
                      title="Editar"
                    >
                      <Edit2 size={14} />
                    </button>
                    <button
                      type="button"
                      onClick={() => handleDelete(promo)}
                      className="rounded-lg p-1.5 text-red-500 hover:bg-red-500/10"
                      title="Excluir"
                    >
                      <Trash2 size={14} />
                    </button>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* Modal de Criação / Edição */}
      {modalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4 backdrop-blur-xs">
          <div className="flex max-h-[90vh] w-full max-w-xl flex-col rounded-2xl border border-border-primary bg-bg-primary shadow-2xl">
            <div className="flex items-center justify-between border-b border-border-primary p-4">
              <h2 className="text-lg font-bold">
                {editingId ? "Editar Promoção" : "Nova Promoção"}
              </h2>
              <button
                type="button"
                onClick={() => setModalOpen(false)}
                className="rounded-lg p-1 text-text-secondary hover:bg-bg-light"
              >
                <X size={18} />
              </button>
            </div>

            <div className="flex-1 overflow-y-auto p-4 space-y-4">
              <div>
                <label className="mb-1 block text-xs font-semibold">Nome da Campanha *</label>
                <input
                  type="text"
                  value={formNome}
                  onChange={(e) => setFormNome(e.target.value)}
                  placeholder="Ex: Festival de Cervejas, Oferta da Semana"
                  className="input-primary w-full rounded-xl p-2.5 text-sm"
                />
              </div>

              <div>
                <label className="mb-1 block text-xs font-semibold">Tipo de Promoção *</label>
                <select
                  value={formTipo}
                  onChange={(e) => setFormTipo(e.target.value as TipoPromocao)}
                  className="input-primary w-full rounded-xl p-2.5 text-sm"
                >
                  {(Object.keys(TIPO_LABELS) as TipoPromocao[]).map((tipo) => (
                    <option key={tipo} value={tipo}>
                      {TIPO_LABELS[tipo]}
                    </option>
                  ))}
                </select>
              </div>

              {/* Campos dinâmicos conforme tipo */}
              <div className="rounded-xl border border-border-primary bg-bg-light p-3 space-y-3">
                {formTipo === "desconto_percentual" && (
                  <div>
                    <label className="mb-1 block text-xs font-semibold">Percentual de Desconto (%) *</label>
                    <input
                      type="number"
                      step="0.01"
                      min="0.01"
                      max="100"
                      value={formValorDesconto}
                      onChange={(e) => setFormValorDesconto(e.target.value)}
                      placeholder="Ex: 10 para 10%"
                      className="input-primary w-full rounded-xl p-2 text-sm"
                    />
                  </div>
                )}

                {formTipo === "desconto_valor" && (
                  <div>
                    <label className="mb-1 block text-xs font-semibold">Valor do Desconto por Unidade (R$) *</label>
                    <input
                      type="number"
                      step="0.01"
                      min="0.01"
                      value={formValorDesconto}
                      onChange={(e) => setFormValorDesconto(e.target.value)}
                      placeholder="Ex: 2.50"
                      className="input-primary w-full rounded-xl p-2 text-sm"
                    />
                  </div>
                )}

                {formTipo === "preco_fixo" && (
                  <div>
                    <label className="mb-1 block text-xs font-semibold">Preço Promocional Fixo (R$) *</label>
                    <input
                      type="number"
                      step="0.01"
                      min="0.01"
                      value={formPrecoFixo}
                      onChange={(e) => setFormPrecoFixo(e.target.value)}
                      placeholder="Ex: 4.99"
                      className="input-primary w-full rounded-xl p-2 text-sm"
                    />
                  </div>
                )}

                {formTipo === "leve_x_pague_y" && (
                  <div className="grid grid-cols-2 gap-3">
                    <div>
                      <label className="mb-1 block text-xs font-semibold">Quantidade Leva *</label>
                      <input
                        type="number"
                        min="2"
                        value={formQtdLeva}
                        onChange={(e) => setFormQtdLeva(e.target.value)}
                        placeholder="Ex: 3"
                        className="input-primary w-full rounded-xl p-2 text-sm"
                      />
                    </div>
                    <div>
                      <label className="mb-1 block text-xs font-semibold">Quantidade Paga *</label>
                      <input
                        type="number"
                        min="1"
                        value={formQtdPaga}
                        onChange={(e) => setFormQtdPaga(e.target.value)}
                        placeholder="Ex: 2"
                        className="input-primary w-full rounded-xl p-2 text-sm"
                      />
                    </div>
                  </div>
                )}

                {formTipo === "combo_quantidade" && (
                  <div className="grid grid-cols-2 gap-3">
                    <div>
                      <label className="mb-1 block text-xs font-semibold">Qtd do Combo *</label>
                      <input
                        type="number"
                        min="2"
                        value={formQtdMinima}
                        onChange={(e) => setFormQtdMinima(e.target.value)}
                        placeholder="Ex: 3"
                        className="input-primary w-full rounded-xl p-2 text-sm"
                      />
                    </div>
                    <div>
                      <label className="mb-1 block text-xs font-semibold">Preço Total do Combo (R$) *</label>
                      <input
                        type="number"
                        step="0.01"
                        min="0.01"
                        value={formPrecoFixo}
                        onChange={(e) => setFormPrecoFixo(e.target.value)}
                        placeholder="Ex: 10.00"
                        className="input-primary w-full rounded-xl p-2 text-sm"
                      />
                    </div>
                  </div>
                )}

                {formTipo === "preco_atacado" && (
                  <div className="grid grid-cols-2 gap-3">
                    <div>
                      <label className="mb-1 block text-xs font-semibold">A partir de (Qtd Mínima) *</label>
                      <input
                        type="number"
                        min="2"
                        value={formQtdMinima}
                        onChange={(e) => setFormQtdMinima(e.target.value)}
                        placeholder="Ex: 10"
                        className="input-primary w-full rounded-xl p-2 text-sm"
                      />
                    </div>
                    <div>
                      <label className="mb-1 block text-xs font-semibold">Preço Unitário Atacado (R$) *</label>
                      <input
                        type="number"
                        step="0.01"
                        min="0.01"
                        value={formPrecoFixo}
                        onChange={(e) => setFormPrecoFixo(e.target.value)}
                        placeholder="Ex: 3.50"
                        className="input-primary w-full rounded-xl p-2 text-sm"
                      />
                    </div>
                  </div>
                )}
              </div>

              {/* Vigência */}
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="mb-1 block text-xs font-semibold">Início da Vigência *</label>
                  <input
                    type="datetime-local"
                    value={formInicio}
                    onChange={(e) => setFormInicio(e.target.value)}
                    className="input-primary w-full rounded-xl p-2 text-sm"
                  />
                </div>
                <div>
                  <label className="mb-1 block text-xs font-semibold">Fim da Vigência *</label>
                  <input
                    type="datetime-local"
                    value={formFim}
                    onChange={(e) => setFormFim(e.target.value)}
                    className="input-primary w-full rounded-xl p-2 text-sm"
                  />
                </div>
              </div>

              {/* Categoria Opcional */}
              <div>
                <label className="mb-1 block text-xs font-semibold">
                  Aplicar à Categoria Inteira (Opcional)
                </label>
                <select
                  value={formCategoriaId}
                  onChange={(e) => setFormCategoriaId(e.target.value)}
                  className="input-primary w-full rounded-xl p-2.5 text-sm"
                >
                  <option value="">Nenhuma categoria específica</option>
                  {categories.map((cat) => (
                    <option key={cat.id} value={cat.id}>
                      {cat.nome}
                    </option>
                  ))}
                </select>
              </div>

              {/* Seleção de Produtos */}
              <div>
                <div className="mb-1 flex items-center justify-between text-xs font-semibold">
                  <span>Produtos Específicos ({formProdutoIds.length} selecionados)</span>
                  {formProdutoIds.length > 0 && (
                    <button
                      type="button"
                      onClick={() => setFormProdutoIds([])}
                      className="text-red-500 hover:underline"
                    >
                      Limpar seleção
                    </button>
                  )}
                </div>

                <div className="relative mb-2">
                  <Search size={14} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-text-secondary" />
                  <input
                    type="text"
                    value={productSearch}
                    onChange={(e) => setProductSearch(e.target.value)}
                    placeholder="Filtrar produtos por nome ou código..."
                    className="input-primary w-full rounded-xl pl-8 pr-3 py-1.5 text-xs"
                  />
                </div>

                <div className="max-h-40 overflow-y-auto rounded-xl border border-border-primary bg-bg-light p-2 divide-y divide-border-primary/50">
                  {filteredModalProducts.length === 0 ? (
                    <p className="py-2 text-center text-xs text-text-secondary">
                      Nenhum produto localizado.
                    </p>
                  ) : (
                    filteredModalProducts.map((p) => {
                      const isSelected = formProdutoIds.includes(p.id);
                      return (
                        <div
                          key={p.id}
                          onClick={() => toggleSelectProduct(p.id)}
                          className="flex cursor-pointer items-center justify-between py-1.5 px-2 hover:bg-bg-primary rounded-lg text-xs transition-colors"
                        >
                          <div className="min-w-0 pr-2">
                            <p className="font-semibold text-text-primary truncate">{p.productName}</p>
                            <p className="text-[10px] text-text-secondary">
                              Cód: {p.productCode} • R$ {p.productSalePrice || "0,00"}
                            </p>
                          </div>
                          <div
                            className={`flex h-4 w-4 shrink-0 items-center justify-center rounded border ${
                              isSelected
                                ? "border-primary bg-primary text-white"
                                : "border-border-primary bg-bg-primary"
                            }`}
                          >
                            {isSelected && <Check size={10} />}
                          </div>
                        </div>
                      );
                    })
                  )}
                </div>
              </div>
            </div>

            <div className="flex items-center justify-end gap-2 border-t border-border-primary p-4">
              <button
                type="button"
                onClick={() => setModalOpen(false)}
                className="rounded-xl border border-border-primary px-4 py-2 text-sm font-semibold hover:bg-bg-light"
              >
                Cancelar
              </button>
              <button
                type="button"
                onClick={handleSave}
                disabled={saving}
                className="btn-primary rounded-xl px-5 py-2 text-sm font-semibold flex items-center gap-2"
              >
                {saving && <Loader2 size={14} className="animate-spin" />}
                Salvar Promoção
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Modal de Resultados (Task 3.9.1) */}
      {resultadoModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4 backdrop-blur-xs">
          <div className="w-full max-w-md rounded-2xl border border-border-primary bg-bg-primary p-5 shadow-2xl">
            <div className="flex items-center justify-between border-b border-border-primary pb-3">
              <div className="flex items-center gap-2 text-primary">
                <TrendingUp size={18} />
                <h3 className="font-bold">Desempenho da Promoção</h3>
              </div>
              <button
                type="button"
                onClick={() => setResultadoModalOpen(false)}
                className="rounded-lg p-1 text-text-secondary hover:bg-bg-light"
              >
                <X size={18} />
              </button>
            </div>

            {loadingResultado ? (
              <div className="flex items-center justify-center py-10">
                <Loader2 className="h-6 w-6 animate-spin text-primary" />
              </div>
            ) : !resultado ? (
              <p className="py-6 text-center text-sm text-text-secondary">
                Nenhum dado de vendas encontrado para esta promoção.
              </p>
            ) : (
              <div className="mt-4 space-y-3">
                <p className="font-semibold text-text-primary text-sm">{resultado.nome}</p>

                <div className="grid grid-cols-2 gap-3">
                  <div className="rounded-xl bg-bg-light p-3">
                    <p className="text-xs text-text-secondary">Vendas Impactadas</p>
                    <p className="text-lg font-bold text-text-primary">{resultado.quantidadeVendas}</p>
                  </div>
                  <div className="rounded-xl bg-bg-light p-3">
                    <p className="text-xs text-text-secondary">Desconto Concedido</p>
                    <p className="text-lg font-bold text-red-500">
                      R$ {formatMoneyBr(resultado.descontoTotal)}
                    </p>
                  </div>
                  <div className="rounded-xl bg-bg-light p-3">
                    <p className="text-xs text-text-secondary">Receita Bruta</p>
                    <p className="text-lg font-bold text-text-primary">
                      R$ {formatMoneyBr(resultado.receitaBruta)}
                    </p>
                  </div>
                  <div className="rounded-xl bg-bg-light p-3">
                    <p className="text-xs text-text-secondary">Receita Líquida</p>
                    <p className="text-lg font-bold text-emerald-600 dark:text-emerald-400">
                      R$ {formatMoneyBr(resultado.receitaLiquida)}
                    </p>
                  </div>
                </div>

                <div className="rounded-xl border border-emerald-500/20 bg-emerald-500/10 p-3 text-xs text-emerald-700 dark:text-emerald-300">
                  <p className="font-semibold">Métricas de Conversão</p>
                  <p className="mt-0.5">
                    O desconto médio por venda foi de{" "}
                    <strong>
                      R${" "}
                      {resultado.quantidadeVendas > 0
                        ? formatMoneyBr(resultado.descontoTotal / resultado.quantidadeVendas)
                        : "0,00"}
                    </strong>
                    .
                  </p>
                </div>
              </div>
            )}

            <div className="mt-5 flex justify-end">
              <button
                type="button"
                onClick={() => setResultadoModalOpen(false)}
                className="btn-primary rounded-xl px-4 py-2 text-xs font-semibold"
              >
                Fechar
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
