/**
 * Arquivo: src/components/Admin/NfeImportModal.tsx
 * Objetivo: fluxo de importação de produtos a partir do XML de uma NF-e de compra — upload,
 *           pré-visualização editável (fornecedor + itens) e confirmação.
 * Entradas esperadas: nenhuma prop obrigatória além do fechamento/callback de sucesso; todo o
 *           estado do XML é local ao modal.
 *
 * Dados fiscais de VENDA (CFOP/CSOSN/CST) não aparecem aqui de propósito — o XML importado é uma
 * nota de ENTRADA (compra), com códigos fiscais diferentes dos usados para vender ao consumidor
 * final. O produto novo nasce com os defaults de sempre (ver NfeImportService no backend) e pode
 * ser revisado depois em Cadastro de Produto.
 */
import {
  AlertCircle,
  Check,
  CheckCircle2,
  Clipboard,
  FileUp,
  KeyRound,
  Link2,
  Loader2,
  PackageSearch,
  RotateCcw,
  Scale,
  Search,
  ShieldCheck,
  Unlink,
  UploadCloud,
  X,
} from "lucide-react";
import { useMemo, useRef, useState } from "react";
import LoadingButton from "@/components/Loading/LoadingButton";
import { Toast } from "@/hooks/Dialog";
import useInputMasks from "@/hooks/InputMasks/useInputMasks";
import {
  nfeImportService,
  type NfeImportFornecedorPreview,
  type NfeImportItemPreview,
  type NfeImportPreview,
} from "@/services/api/nfeImportService";
import { productService, type ProductDto } from "@/services/api/productService";
import { formatMoneyBr as fmtMoney, parseMoneyBr as parseMoney } from "@/utils/inputMasks";

type EditableItem = NfeImportItemPreview & {
  /** Margem sobre o custo, em % (pt-BR). Mesma regra do cadastro: venda = custo x (1 + margem/100). */
  margem: string;
  /** Lucro unitário em R$ (venda - custo, pt-BR). */
  lucro: string;
  /** true quando o operador digitou margem ou lucro: nesse caso custo/fator novos recalculam o preço de venda. */
  margemManual: boolean;
  quantidade: string;
  precoCusto: string;
  precoVenda: string;
  fatorConversao: string;
  quantidadeOriginal: number;
  precoCustoOriginal: number;
  precoVendaOriginal: number;
  unidadeOriginal: string;
  productCodeOriginal: string;
  dataValidade: string;
  numeroLote: string;
};

// Custo unitário de itens convertidos (caixa -> unidade): até 4 casas, igual ao DECIMAL(15,4) do banco.
function formatCusto(value: number) {
  if (!Number.isFinite(value)) return "0,00";
  return value.toLocaleString("pt-BR", { minimumFractionDigits: 2, maximumFractionDigits: 4, useGrouping: false });
}

type PricingSource = "custo" | "margem" | "venda" | "lucro";

/**
 * Mantém custo, preço de venda, margem (% sobre o custo) e lucro (R$) coerentes entre si.
 * `source` diz qual campo o operador acabou de mudar:
 *  - margem: recalcula a venda (custo x (1 + margem/100)) e o lucro;
 *  - lucro:  venda = custo + lucro, e a margem acompanha;
 *  - venda:  lucro e margem passam a refletir o preço digitado;
 *  - custo:  se a margem foi digitada, mantém a margem e recalcula a venda; senão mantém a
 *            venda e recalcula margem/lucro.
 */
function applyPricing(item: EditableItem, source: PricingSource): EditableItem {
  const custo = parseMoney(item.precoCusto);
  const venda = parseMoney(item.precoVenda);
  const toPercent = (lucro: number) => (custo > 0 ? fmtMoney((lucro / custo) * 100) : "");

  const fromMargin = (): EditableItem => {
    const margem = item.margem.trim();
    if (custo <= 0 || !margem) return item;
    const novaVenda = Math.round((custo * (1 + parseMoney(margem) / 100) + Number.EPSILON) * 100) / 100;
    return { ...item, precoVenda: fmtMoney(novaVenda), lucro: fmtMoney(novaVenda - custo) };
  };

  if (source === "margem") return fromMargin();
  if (source === "custo" && item.margemManual && item.margem.trim()) return fromMargin();

  if (source === "lucro") {
    const lucro = item.lucro.trim();
    if (custo <= 0 || !lucro) return item;
    const novaVenda = Math.round((custo + parseMoney(lucro) + Number.EPSILON) * 100) / 100;
    return { ...item, precoVenda: fmtMoney(novaVenda), margem: toPercent(parseMoney(lucro)) };
  }

  // venda (ou custo sem margem manual): deriva margem e lucro do preço atual
  if (custo <= 0 || venda <= 0) return { ...item, lucro: "", margem: "" };
  return { ...item, lucro: fmtMoney(venda - custo), margem: toPercent(venda - custo) };
}

function maskCusto4(value: string) {
  const digits = value.replace(/\D/g, "").slice(0, 12);
  if (!digits) return "";
  return formatCusto(Number(digits) / 10000);
}

export default function NfeImportModal({
  onClose,
  onImported,
}: {
  onClose: () => void;
  onImported: () => void;
}) {
  const {
    maskCnpj,
    maskMoneyBr,
    formatMoneyBr,
    parseMoneyBr,
    sanitizeDecimalInput,
    sanitizeIntegerInput,
  } = useInputMasks();
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const [importMethod, setImportMethod] = useState<"sefaz" | "xml">("sefaz");
  const [chaveAcesso, setChaveAcesso] = useState("");
  const [loadingStatus, setLoadingStatus] = useState("Lendo dados...");
  const [errorDetails, setErrorDetails] = useState<string | null>(null);

  const [loading, setLoading] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [numeroNota, setNumeroNota] = useState("");
  const [serie, setSerie] = useState("");
  const [fornecedor, setFornecedor] = useState<NfeImportFornecedorPreview | null>(null);
  const [itens, setItens] = useState<EditableItem[]>([]);

  // Vínculo manual de produto (ex.: presunto fatiado / balança)
  const [itemParaVincular, setItemParaVincular] = useState<EditableItem | null>(null);
  const [produtosCadastrados, setProdutosCadastrados] = useState<ProductDto[]>([]);
  const [loadingProdutos, setLoadingProdutos] = useState(false);
  const [buscaProdutoModal, setBuscaProdutoModal] = useState("");
  const [filtroBalancaModal, setFiltroBalancaModal] = useState(false);

  const hasPreview = fornecedor !== null;
  const cleanKey = chaveAcesso.replace(/\D/g, "");

  const aplicarPreview = (preview: NfeImportPreview) => {
    setErrorDetails(null);
    setNumeroNota(preview.numeroNota);
    setSerie(preview.serie);
    setFornecedor(preview.fornecedor);
    setItens(
      preview.itens.map((item) => {
        const qtdOriginal = parseMoneyBr(item.quantidade) || 1;
        const custoOriginal = parseMoneyBr(item.precoCusto) || 0;
        const vendaOriginal = parseMoneyBr(item.precoVendaSugerido) || 0;
        return applyPricing({
          ...item,
          margem: "",
          lucro: "",
          margemManual: false,
          quantidade: item.quantidade,
          precoCusto: item.precoCusto,
          precoVenda: item.precoVendaSugerido,
          fatorConversao: "1",
          quantidadeOriginal: qtdOriginal,
          precoCustoOriginal: custoOriginal,
          precoVendaOriginal: vendaOriginal,
          unidadeOriginal: item.unidadeComercial || "UN",
          unidadeComercial: item.unidadeComercial || "UN",
          productCodeOriginal: item.productCode,
          dataValidade: "",
          numeroLote: "",
        }, "venda");
      }),
    );
  };

  const handleAbrirVincular = (item: EditableItem) => {
    setItemParaVincular(item);
    setBuscaProdutoModal("");
    setFiltroBalancaModal(false);
    if (produtosCadastrados.length === 0) {
      setLoadingProdutos(true);
      productService
        .list()
        .then((data) => setProdutosCadastrados(data || []))
        .catch(() => Toast.error("Não foi possível carregar a lista de produtos."))
        .finally(() => setLoadingProdutos(false));
    }
  };

  const handleSelecionarProdutoExistente = (produto: ProductDto) => {
    if (!itemParaVincular) return;
    const targetNumero = itemParaVincular.numeroItem;
    setItens((current) =>
      current.map((it) => {
        if (it.numeroItem !== targetNumero) return it;
        // Produto existente: SEM margem cadastrada o preço de venda atual é mantido (margem e lucro só
        // informam o resultado sobre o novo custo). COM margem cadastrada o backend recalcula o preço
        // a partir do novo custo, então a prévia mostra esse valor projetado (mesma regra do servidor).
        const margemCadastrada = parseMoneyBr(produto.margemDesejadaPercentual ?? "0");
        const custoNovo = parseMoneyBr(it.precoCusto);
        const precoProjetado =
          margemCadastrada > 0 && custoNovo > 0
            ? Math.round((custoNovo * (1 + margemCadastrada / 100) + Number.EPSILON) * 100) / 100
            : parseMoneyBr(produto.productSalePrice);
        return applyPricing(
          {
            ...it,
            produtoExistenteId: produto.id,
            produtoExistenteNome: produto.productName,
            productCode: produto.productCode,
            unidadeComercial: produto.unidadeComercial || "UN",
            precoVenda: formatMoneyBr(precoProjetado),
            margemManual: false,
          },
          "venda",
        );
      }),
    );
    setItemParaVincular(null);
    Toast.success(`Item atrelado ao produto "${produto.productName}"`);
  };

  const handleDesvincular = (numeroItem: number) => {
    setItens((current) =>
      current.map((it) => {
        if (it.numeroItem !== numeroItem) return it;
        return applyPricing(
          {
            ...it,
            produtoExistenteId: null,
            produtoExistenteNome: null,
            productCode: it.productCodeOriginal,
            unidadeComercial: it.unidadeOriginal,
            precoVenda: formatMoneyBr(it.precoVendaOriginal),
            margemManual: false,
          },
          "venda",
        );
      }),
    );
    Toast.info("Item desvinculado. Será cadastrado como produto novo.");
  };

  const produtosFiltrados = useMemo(() => {
    if (!itemParaVincular) return [];
    const termo = buscaProdutoModal.trim().toLowerCase();
    return produtosCadastrados.filter((p) => {
      const isKg = (p.unidadeComercial || "").toUpperCase() === "KG";
      const hasBalancaWord =
        (p.productName || "").toLowerCase().includes("balança") ||
        (p.productName || "").toLowerCase().includes("fatiado") ||
        (p.productName || "").toLowerCase().includes("presunto") ||
        (p.productName || "").toLowerCase().includes("queijo") ||
        (p.productName || "").toLowerCase().includes("kg");

      if (filtroBalancaModal && !isKg && !hasBalancaWord) {
        return false;
      }

      if (!termo) return true;
      return (
        p.productName.toLowerCase().includes(termo) ||
        p.productCode.toLowerCase().includes(termo) ||
        (p.gtin && p.gtin.toLowerCase().includes(termo)) ||
        (p.categoriaNome && p.categoriaNome.toLowerCase().includes(termo))
      );
    });
  }, [produtosCadastrados, buscaProdutoModal, filtroBalancaModal, itemParaVincular]);

  const handleBuscarSefaz = async () => {
    if (cleanKey.length !== 44) {
      Toast.error("A chave de acesso da NF-e deve ter exatamente 44 dígitos numéricos.");
      return;
    }

    setLoading(true);
    setErrorDetails(null);
    setLoadingStatus("Consultando SEFAZ e baixando XML da NF-e...");
    try {
      const preview = await nfeImportService.previewPorChave(cleanKey);
      if (!preview) {
        const msg = "A SEFAZ não retornou os dados da nota fiscal.";
        setErrorDetails(msg);
        Toast.error(msg);
        return;
      }
      aplicarPreview(preview);
      Toast.success(`NF-e nº ${preview.numeroNota} baixada da SEFAZ com sucesso!`);
    } catch (error) {
      const msg = error instanceof Error ? error.message : "Erro ao consultar nota na SEFAZ.";
      setErrorDetails(msg);
      Toast.error(msg);
    } finally {
      setLoading(false);
      setLoadingStatus("Lendo dados...");
    }
  };

  const handlePasteKey = async () => {
    try {
      const text = await navigator.clipboard.readText();
      const digits = text.replace(/\D/g, "").slice(0, 44);
      if (digits) {
        setChaveAcesso(digits);
        Toast.success(`Chave colada: ${digits.length} dígitos.`);
      } else {
        Toast.info("Nenhum número encontrado na área de transferência.");
      }
    } catch {
      Toast.error("Não foi possível acessar a área de transferência. Use Ctrl+V para colar.");
    }
  };

  const handleFile = async (file: File) => {
    setLoading(true);
    setLoadingStatus("Lendo o XML da nota...");
    try {
      const preview = await nfeImportService.preview(file);
      if (!preview) return;
      aplicarPreview(preview);
      Toast.success(`XML da NF-e nº ${preview.numeroNota} carregado com sucesso!`);
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao ler o XML da nota.");
    } finally {
      setLoading(false);
      setLoadingStatus("Lendo dados...");
    }
  };

  const setFornecedorField = <K extends keyof NfeImportFornecedorPreview>(
    key: K,
    fieldValue: NfeImportFornecedorPreview[K],
  ) => {
    setFornecedor((current) => (current ? { ...current, [key]: fieldValue } : current));
  };

  const setItemField = <K extends keyof EditableItem>(
    numeroItem: number,
    key: K,
    fieldValue: EditableItem[K],
  ) => {
    setItens((current) =>
      current.map((item) => {
        if (item.numeroItem !== numeroItem) return item;

        // Se o operador digitar um código de barras (EAN com 8 ou mais dígitos numéricos),
        // sincroniza também no GTIN para garantir consistência fiscal e no PDV:
        if (key === "productCode" && typeof fieldValue === "string") {
          const trimmed = fieldValue.trim();
          const isEan = trimmed.length >= 8 && /^\d+$/.test(trimmed);
          return {
            ...item,
            productCode: fieldValue,
            gtin: isEan ? trimmed : item.gtin,
          };
        }

        return { ...item, [key]: fieldValue };
      }),
    );
  };

  const handleFatorChange = (numeroItem: number, rawFator: string) => {
    const cleanFator = sanitizeIntegerInput(rawFator);
    const fator = parseInt(cleanFator, 10);

    setItens((current) =>
      current.map((item) => {
        if (item.numeroItem !== numeroItem) return item;

        if (!fator || fator <= 0) {
          return {
            ...item,
            fatorConversao: cleanFator,
          };
        }

        const novaQtd = item.quantidadeOriginal * fator;
        const novoCusto = item.precoCustoOriginal / fator;

        // Se for produto novo e tinha preço de venda sugerido, divide proporcionalmente
        const novoPrecoVenda =
          !item.produtoExistenteId && item.precoVendaOriginal > 0
            ? formatMoneyBr(item.precoVendaOriginal / fator)
            : item.precoVenda;

        // Se o fator for maior que 1, a unidade de venda vira UN (unidade avulsa)
        const novaUnidade = fator > 1 ? "UN" : item.unidadeOriginal;

        // Margem digitada pelo operador (produto novo): mantém a margem e recalcula a venda com o
        // custo novo. Sem margem digitada, usa a venda proporcional e a margem/lucro acompanham.
        return applyPricing(
          {
            ...item,
            fatorConversao: cleanFator,
            // Sem separador de milhar: o backend lê "1.000" como 1, não como mil.
            quantidade: novaQtd.toLocaleString("pt-BR", { maximumFractionDigits: 4, useGrouping: false }),
            // Com conversão o custo unitário pode ter mais de 2 casas (ex.: 37,25 / 100 = 0,3725);
            // o banco guarda 4 casas, então não arredonda para centavos aqui.
            precoCusto: fator > 1 ? formatCusto(novoCusto) : formatMoneyBr(novoCusto),
            precoVenda: novoPrecoVenda,
            unidadeComercial: novaUnidade,
          },
          item.margemManual && !item.produtoExistenteId ? "custo" : "venda",
        );
      }),
    );
  };

  /** Atualiza um campo de preço (custo, venda, margem ou lucro) e recalcula os demais. */
  const updateItemPricing = (numeroItem: number, patch: Partial<EditableItem>, source: PricingSource) => {
    setItens((current) =>
      current.map((item) =>
        item.numeroItem === numeroItem ? applyPricing({ ...item, ...patch }, source) : item,
      ),
    );
  };

  const handleConfirmar = async () => {
    if (!fornecedor) return;
    setConfirming(true);
    try {
      const resultado = await nfeImportService.confirmar({
        fornecedor: {
          cnpj: fornecedor.cnpj,
          companyName: fornecedor.companyName,
          fantasyName: fornecedor.fantasyName,
          cep: fornecedor.cep,
          city: fornecedor.city,
          state: fornecedor.state,
          address: fornecedor.address,
          neighborhood: fornecedor.neighborhood,
          number: fornecedor.number,
          telephone: fornecedor.telephone,
        },
        itens: itens.map((item) => ({
          numeroItem: item.numeroItem,
          produtoExistenteId: item.produtoExistenteId,
          produtoExistenteNome: item.produtoExistenteNome,
          codigoFornecedor: item.codigoFornecedor,
          productCode: item.productCode,
          productName: item.productName,
          gtin: item.gtin,
          ncm: item.ncm,
          cest: item.cest,
          unidadeComercial: item.unidadeComercial,
          quantidade: item.quantidade,
          precoCusto: item.precoCusto,
          precoVenda: item.precoVenda,
          dataValidade: item.dataValidade || null,
          numeroLote: item.numeroLote.trim() || null,
          // Só vai como margem desejada do produto novo quando o operador a digitou (ela passa a
          // recalcular o preço de venda nas próximas entradas); a margem derivada é só informativa.
          margemPercentual:
            !item.produtoExistenteId && item.margemManual && item.margem.trim() ? item.margem : null,
        })),
      });
      if (!resultado) return;

      const partes = [
        resultado.produtosCriados > 0 ? `${resultado.produtosCriados} produto(s) cadastrado(s)` : null,
        resultado.produtosAtualizados > 0 ? `${resultado.produtosAtualizados} produto(s) com entrada de estoque` : null,
        resultado.fornecedorCriado ? "fornecedor cadastrado" : null,
      ].filter(Boolean);
      Toast.success(partes.length > 0 ? partes.join(" · ") : "Importação concluída.");
      onImported();
      onClose();
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao confirmar importação.");
    } finally {
      setConfirming(false);
    }
  };

  return (
    <div className="fixed inset-0 z-layer-dialog flex items-end bg-black/55 px-3 backdrop-blur-sm md:items-center md:justify-center">
      <div className="flex max-h-[92vh] w-full max-w-5xl flex-col overflow-hidden rounded-t-2xl border border-border-primary bg-bg-light shadow-2xl md:rounded-2xl">
        <div className="flex items-center justify-between border-b border-border-primary px-4 py-3">
          <div className="flex items-center gap-2">
            <span className="inline-flex h-9 w-9 items-center justify-center rounded-xl bg-accent/10 text-accent">
              <PackageSearch size={18} />
            </span>
            <div>
              <h2 className="text-base font-semibold text-text-primary">
                Entrada de NF-e · Importação de Produtos
              </h2>
              <p className="text-xs text-text-secondary">
                {hasPreview
                  ? `Nota ${numeroNota} · Série ${serie} · Se o produto veio em caixa ou fardo, ajuste o Fator para converter em unidades.`
                  : "Busque diretamente da SEFAZ pela chave de acesso ou selecione o arquivo XML de compra."}
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            {hasPreview && (
              <button
                type="button"
                onClick={() => setFornecedor(null)}
                className="inline-flex items-center gap-1.5 rounded-lg border border-border-primary px-2.5 py-1 text-xs font-medium text-text-secondary hover:bg-hover-light"
                title="Voltar e consultar outra nota ou enviar outro XML"
              >
                <RotateCcw size={13} />
                Trocar Nota
              </button>
            )}
            <button
              type="button"
              onClick={onClose}
              className="inline-flex h-9 w-9 items-center justify-center rounded-lg border border-border-primary text-text-secondary hover:bg-hover-light"
              aria-label="Fechar importação de NF-e"
            >
              <X size={16} />
            </button>
          </div>
        </div>

        <div className="flex-1 space-y-5 overflow-y-auto p-5">
          {!hasPreview ? (
            <div className="space-y-4">
              {/* Abas de Escolha do Método */}
              <div className="flex rounded-xl border border-border-primary bg-bg-primary/50 p-1">
                <button
                  type="button"
                  onClick={() => setImportMethod("sefaz")}
                  className={`flex flex-1 items-center justify-center gap-2 rounded-lg py-2.5 text-xs font-semibold transition-all ${
                    importMethod === "sefaz"
                      ? "bg-accent text-white shadow-sm"
                      : "text-text-secondary hover:text-text-primary"
                  }`}
                >
                  <KeyRound size={15} />
                  Consultar Chave na SEFAZ (Automático)
                </button>
                <button
                  type="button"
                  onClick={() => setImportMethod("xml")}
                  className={`flex flex-1 items-center justify-center gap-2 rounded-lg py-2.5 text-xs font-semibold transition-all ${
                    importMethod === "xml"
                      ? "bg-accent text-white shadow-sm"
                      : "text-text-secondary hover:text-text-primary"
                  }`}
                >
                  <UploadCloud size={15} />
                  Upload de Arquivo XML (Manual)
                </button>
              </div>

              {/* Modo 1: Consulta Direta na SEFAZ por Chave */}
              {importMethod === "sefaz" && (
                <div className="space-y-4">
                  <div className="rounded-xl border border-border-secondary bg-bg-primary/30 p-5">
                    <div className="mb-2 flex items-center justify-between">
                      <label className="text-xs font-semibold uppercase tracking-wider text-text-secondary">
                        Chave de Acesso da NF-e (44 dígitos numéricos)
                      </label>
                      <span
                        className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-semibold font-mono transition-colors ${
                          cleanKey.length === 44
                            ? "bg-success/15 text-success"
                            : "bg-bg-light text-text-secondary border border-border-primary"
                        }`}
                      >
                        {cleanKey.length === 44 && <Check size={12} />}
                        {cleanKey.length} / 44 dígitos
                      </span>
                    </div>

                    <div className="relative flex items-center">
                      <input
                        type="text"
                        value={chaveAcesso}
                        onChange={(e) => {
                          const digits = e.target.value.replace(/\D/g, "").slice(0, 44);
                          setChaveAcesso(digits);
                        }}
                        onKeyDown={(e) => {
                          if (e.key === "Enter" && cleanKey.length === 44 && !loading) {
                            void handleBuscarSefaz();
                          }
                        }}
                        placeholder="Digite ou cole os 44 números da chave de acesso da nota..."
                        disabled={loading}
                        className="input-field w-full pr-24 font-mono text-sm tracking-wider"
                        maxLength={44}
                        autoFocus
                      />
                      <div className="absolute right-2 flex items-center gap-1">
                        {chaveAcesso && !loading && (
                          <button
                            type="button"
                            onClick={() => setChaveAcesso("")}
                            className="rounded p-1 text-text-tertiary hover:bg-hover-light hover:text-text-primary"
                            title="Limpar campo"
                          >
                            <X size={15} />
                          </button>
                        )}
                        <button
                          type="button"
                          onClick={handlePasteKey}
                          disabled={loading}
                          className="inline-flex items-center gap-1 rounded-md border border-border-primary bg-bg-light px-2.5 py-1 text-xs font-medium text-text-secondary hover:bg-hover-light"
                          title="Colar da área de transferência"
                        >
                          <Clipboard size={12} />
                          Colar
                        </button>
                      </div>
                    </div>

                    <div className="mt-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                      <div className="flex items-start gap-2 text-xs text-text-secondary">
                        <ShieldCheck size={16} className="mt-0.5 shrink-0 text-accent" />
                        <p>
                          A consulta utiliza o <strong>Certificado Digital A1</strong> da empresa. Caso a SEFAZ retorne apenas o resumo, o sistema enviará a <strong>Ciência da Operação</strong> para obter a nota completa com todos os itens.
                        </p>
                      </div>

                      <button
                        type="button"
                        onClick={handleBuscarSefaz}
                        disabled={cleanKey.length !== 44 || loading}
                        className="btn-primary inline-flex shrink-0 items-center justify-center gap-2 px-5 py-2.5 text-xs font-semibold disabled:opacity-50"
                      >
                        {loading ? (
                          <>
                            <Loader2 size={16} className="animate-spin" />
                            <span>Buscando na SEFAZ...</span>
                          </>
                        ) : (
                          <>
                            <Search size={16} />
                            <span>Consultar e Baixar Nota</span>
                          </>
                        )}
                      </button>
                    </div>
                  </div>

                  {errorDetails && (
                    <div className="rounded-xl border border-danger/40 bg-danger/10 p-4 text-xs">
                      <div className="flex items-start gap-2.5">
                        <AlertCircle size={18} className="mt-0.5 shrink-0 text-danger" />
                        <div className="flex-1 space-y-2">
                          <p className="font-semibold text-danger">Falha na consulta da SEFAZ:</p>
                          <p className="text-text-primary whitespace-pre-wrap">{errorDetails}</p>
                          <div className="rounded-lg bg-bg-primary/50 p-2.5 text-[11px] text-text-secondary space-y-1">
                            <p><strong>💡 Dicas importantes:</strong></p>
                            <p>• A consulta direta via SEFAZ exige que a NF-e tenha sido emitida para o <strong>mesmo CNPJ</strong> da empresa configurada no sistema e que o <strong>Certificado Digital A1</strong> esteja cadastrado em Minha Empresa.</p>
                            <p>• Caso a SEFAZ retorne que não localizou a nota (cStat 137) ou haja demora no Ambiente Nacional, você pode <strong>importar diretamente o arquivo XML</strong> da nota de compra.</p>
                          </div>
                          <div className="pt-1">
                            <button
                              type="button"
                              onClick={() => {
                                setErrorDetails(null);
                                setImportMethod("xml");
                              }}
                              className="btn-secondary inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold text-accent hover:bg-accent/10"
                            >
                              <FileUp size={14} /> Fazer Upload do Arquivo XML (Manual)
                            </button>
                          </div>
                        </div>
                      </div>
                    </div>
                  )}

                  {loading && (
                    <div className="flex flex-col items-center justify-center gap-3 rounded-xl border border-accent/20 bg-accent/5 p-8 text-center">
                      <Loader2 size={32} className="animate-spin text-accent" />
                      <div>
                        <p className="text-sm font-semibold text-text-primary">{loadingStatus}</p>
                        <p className="mt-1 text-xs text-text-secondary">
                          Conectando com o WebService da SEFAZ Nacional... Esse procedimento pode levar de 3 a 10 segundos.
                        </p>
                      </div>
                    </div>
                  )}
                </div>
              )}

              {/* Modo 2: Upload Manual de XML */}
              {importMethod === "xml" && (
                <div className="flex flex-col items-center gap-4 rounded-xl border border-dashed border-border-secondary bg-bg-primary/50 p-10 text-center">
                  {loading ? (
                    <>
                      <Loader2 size={28} className="animate-spin text-accent" />
                      <p className="text-sm text-text-secondary">{loadingStatus}</p>
                    </>
                  ) : (
                    <>
                      <UploadCloud size={32} className="text-text-tertiary" />
                      <div>
                        <p className="text-sm font-medium text-text-primary">
                          Selecione o XML autorizado da nota de compra
                        </p>
                        <p className="mt-1 text-xs text-text-secondary">
                          Os produtos, o preço de custo e o fornecedor vêm da nota — dados fiscais de venda
                          (CFOP, CSOSN/CST) continuam com os padrões do cadastro manual.
                        </p>
                      </div>
                      <button
                        type="button"
                        onClick={() => fileInputRef.current?.click()}
                        className="btn-primary inline-flex items-center gap-2"
                      >
                        <FileUp size={16} />
                        Escolher arquivo .xml
                      </button>
                      <input
                        ref={fileInputRef}
                        type="file"
                        accept=".xml,text/xml"
                        className="hidden"
                        onChange={(event) => {
                          const file = event.target.files?.[0];
                          event.target.value = "";
                          if (file) void handleFile(file);
                        }}
                      />
                    </>
                  )}
                </div>
              )}
            </div>
          ) : (
            <>
              <section className="rounded-xl border border-border-secondary p-4">
                <div className="mb-3 flex items-center justify-between">
                  <h3 className="text-sm font-semibold text-text-primary">Fornecedor</h3>
                  <span
                    className={`inline-flex rounded-full px-2.5 py-1 text-xs font-semibold ${
                      fornecedor.jaExiste ? "bg-secondary/10 text-secondary" : "bg-accent/10 text-accent"
                    }`}
                  >
                    {fornecedor.jaExiste ? "Já cadastrado — será atualizado" : "Novo fornecedor"}
                  </span>
                </div>
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
                  <label className="block">
                    <span className="mb-1 block text-xs text-text-secondary">CNPJ</span>
                    <input
                      className="input-field w-full"
                      value={fornecedor.cnpj}
                      onChange={(event) => setFornecedorField("cnpj", maskCnpj(event.target.value))}
                    />
                  </label>
                  <label className="block sm:col-span-2 lg:col-span-1">
                    <span className="mb-1 block text-xs text-text-secondary">Razão social</span>
                    <input
                      className="input-field w-full"
                      value={fornecedor.companyName}
                      onChange={(event) => setFornecedorField("companyName", event.target.value)}
                    />
                  </label>
                  <label className="block">
                    <span className="mb-1 block text-xs text-text-secondary">Nome fantasia</span>
                    <input
                      className="input-field w-full"
                      value={fornecedor.fantasyName}
                      onChange={(event) => setFornecedorField("fantasyName", event.target.value)}
                    />
                  </label>
                  <label className="block">
                    <span className="mb-1 block text-xs text-text-secondary">Telefone</span>
                    <input
                      className="input-field w-full"
                      value={fornecedor.telephone}
                      onChange={(event) => setFornecedorField("telephone", event.target.value)}
                    />
                  </label>
                </div>
              </section>

              <section className="overflow-hidden rounded-xl border border-border-secondary">
                <div className="overflow-x-auto">
                  <table className="w-full min-w-[760px] text-left text-sm">
                    <thead className="bg-bg-primary/60 text-xs uppercase text-text-tertiary">
                      <tr>
                        <th className="px-3 py-2">Produto</th>
                        <th className="px-3 py-2">Código / Barras</th>
                        <th className="px-3 py-2 text-center" title="Fator de conversão: quantas unidades vêm na embalagem (ex: 6 para caixa com 6)">
                          Fator
                        </th>
                        <th className="px-3 py-2">Qtd.</th>
                        <th className="px-3 py-2">Custo unit.</th>
                        <th className="px-3 py-2">Preço venda</th>
                        <th className="px-3 py-2" title="Margem sobre o custo (%): preço de venda = custo × (1 + margem/100).">
                          Margem %
                        </th>
                        <th className="px-3 py-2" title="Lucro por unidade (R$): preço de venda − custo.">
                          Lucro R$
                        </th>
                        <th className="px-3 py-2" title="Validade do lote recebido. Em branco, usa o prazo padrão da categoria do produto (se houver).">
                          Validade
                        </th>
                        <th className="px-3 py-2" title="Número do lote impresso na embalagem (opcional).">
                          Lote
                        </th>
                        <th className="px-3 py-2">Situação</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-border-primary">
                      {itens.map((item) => {
                        const existente = Boolean(item.produtoExistenteId);
                        const fatorNum = parseInt(item.fatorConversao, 10);
                        const isConverted = Number.isFinite(fatorNum) && fatorNum > 1;

                        return (
                          <tr key={item.numeroItem} className="hover:bg-hover-light/40 transition-colors">
                            <td className="px-3 py-2 max-w-xs">
                              <p className="font-semibold text-text-primary text-xs">{item.productName}</p>
                              <div className="flex flex-wrap items-center gap-1.5 pt-0.5 text-[11px] text-text-tertiary">
                                <span>NCM {item.ncm}</span>
                                {item.codigoFornecedor && (
                                  <span className="font-mono text-[10px] bg-bg-secondary px-1.5 py-0.5 rounded">
                                    Cód. Fornec: {item.codigoFornecedor}
                                  </span>
                                )}
                                {isConverted ? (
                                  <span className="inline-flex items-center rounded bg-amber-500/15 px-1.5 py-0.5 text-[10px] font-bold text-amber-500">
                                    1 {item.unidadeOriginal} = {item.fatorConversao} UN
                                  </span>
                                ) : (
                                  <span>Unid. {item.unidadeOriginal}</span>
                                )}
                              </div>

                              {/* Vínculo com Produto Existente */}
                              {existente ? (
                                <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
                                  <span className="inline-flex items-center gap-1 rounded-md bg-emerald-500/15 border border-emerald-500/30 px-2 py-0.5 text-[11px] font-semibold text-emerald-700 dark:text-emerald-400">
                                    <CheckCircle2 size={12} className="text-emerald-500 shrink-0" />
                                    <span className="truncate max-w-[200px]">
                                      Vinculado a: <strong>[{item.productCode}] {item.produtoExistenteNome || item.productName}</strong>
                                    </span>
                                  </span>
                                  <button
                                    type="button"
                                    onClick={() => handleAbrirVincular(item)}
                                    className="text-[10px] text-accent hover:underline font-semibold"
                                    title="Trocar produto associado"
                                  >
                                    Trocar
                                  </button>
                                  <button
                                    type="button"
                                    onClick={() => handleDesvincular(item.numeroItem)}
                                    className="text-[10px] text-rose-500 hover:underline font-semibold inline-flex items-center gap-0.5"
                                    title="Desvincular e cadastrar como produto novo"
                                  >
                                    <Unlink size={10} /> Desvincular
                                  </button>
                                </div>
                              ) : (
                                <div className="mt-1.5">
                                  <button
                                    type="button"
                                    onClick={() => handleAbrirVincular(item)}
                                    className="inline-flex items-center gap-1.5 rounded-lg border border-accent/40 bg-accent/10 px-2.5 py-1 text-[11px] font-semibold text-accent hover:bg-accent/20 transition-all shadow-sm"
                                    title="Atrelar a um produto já existente no sistema (ex: presunto de balança)"
                                  >
                                    <Link2 size={12} />
                                    Atrelar a produto existente...
                                  </button>
                                </div>
                              )}
                            </td>
                            <td className="px-3 py-2">
                              <input
                                className="input-field w-32 font-mono text-xs"
                                value={item.productCode}
                                placeholder="Código / EAN"
                                title="Código de barras da unidade individual"
                                onChange={(event) =>
                                  setItemField(item.numeroItem, "productCode", event.target.value)
                                }
                              />
                              {item.gtin && item.gtin !== "SEM GTIN" && item.gtin !== item.productCode ? (
                                <p
                                  className="mt-0.5 max-w-[128px] truncate text-[10px] text-text-tertiary"
                                  title={`GTIN original da nota: ${item.gtin}`}
                                >
                                  XML: {item.gtin}
                                </p>
                              ) : null}
                            </td>
                            <td className="px-3 py-2">
                              <div className="flex flex-col items-center">
                                <input
                                  type="text"
                                  inputMode="numeric"
                                  className={`input-field w-14 text-center text-xs font-bold ${
                                    isConverted ? "border-amber-500/60 bg-amber-500/10 text-amber-500" : ""
                                  }`}
                                  value={item.fatorConversao}
                                  placeholder="1"
                                  title="Digite quantas unidades vêm na caixa/fardo (ex: 6)"
                                  onChange={(event) => handleFatorChange(item.numeroItem, event.target.value)}
                                />
                                <span className="mt-0.5 text-[10px] text-text-tertiary">
                                  {item.unidadeOriginal !== "UN" ? item.unidadeOriginal : "un"}
                                </span>
                              </div>
                            </td>
                            <td className="px-3 py-2">
                              <div className="flex items-center gap-1">
                                <input
                                  className="input-field w-20 text-right font-medium"
                                  value={item.quantidade}
                                  onChange={(event) =>
                                    setItemField(
                                      item.numeroItem,
                                      "quantidade",
                                      sanitizeDecimalInput(event.target.value, 4).replace(".", ","),
                                    )
                                  }
                                />
                                <span className="text-xs font-semibold text-text-secondary">
                                  {item.unidadeComercial}
                                </span>
                              </div>
                            </td>
                            <td className="px-3 py-2">
                              <input
                                className="input-field w-24"
                                value={item.precoCusto}
                                onChange={(event) =>
                                  updateItemPricing(
                                    item.numeroItem,
                                    {
                                      precoCusto: isConverted
                                        ? maskCusto4(event.target.value)
                                        : maskMoneyBr(event.target.value),
                                    },
                                    "custo",
                                  )
                                }
                              />
                            </td>
                            <td className="px-3 py-2">
                              <input
                                className="input-field w-24 disabled:cursor-not-allowed disabled:opacity-60"
                                value={item.precoVenda}
                                disabled={existente}
                                title={existente ? "Produto já cadastrado — preço de venda não muda na importação." : undefined}
                                onChange={(event) =>
                                  // Preço digitado manda: margem e lucro passam a refletir esse preço.
                                  updateItemPricing(
                                    item.numeroItem,
                                    { precoVenda: maskMoneyBr(event.target.value), margemManual: false },
                                    "venda",
                                  )
                                }
                              />
                            </td>
                            <td className="px-3 py-2">
                              <input
                                className="input-field w-20 text-right disabled:cursor-not-allowed disabled:opacity-60"
                                inputMode="numeric"
                                placeholder="0,00"
                                value={item.margem}
                                disabled={existente}
                                title={
                                  existente
                                    ? "Produto já cadastrado: mostra a margem do preço de venda atual sobre o novo custo."
                                    : "Margem sobre o custo (%). Ao digitar, o preço de venda é calculado: custo × (1 + margem/100)."
                                }
                                onChange={(event) =>
                                  updateItemPricing(
                                    item.numeroItem,
                                    { margem: maskMoneyBr(event.target.value), margemManual: true },
                                    "margem",
                                  )
                                }
                              />
                            </td>
                            <td className="px-3 py-2">
                              <input
                                className={`input-field w-24 text-right disabled:cursor-not-allowed disabled:opacity-60 ${
                                  parseMoneyBr(item.lucro || "0") < 0 ? "text-red-500" : ""
                                }`}
                                inputMode="numeric"
                                placeholder="0,00"
                                value={item.lucro}
                                disabled={existente}
                                title={
                                  existente
                                    ? "Produto já cadastrado: lucro do preço de venda atual sobre o novo custo."
                                    : "Lucro por unidade (R$) = preço de venda − custo. Ao digitar, o preço de venda é recalculado."
                                }
                                onChange={(event) =>
                                  updateItemPricing(
                                    item.numeroItem,
                                    { lucro: maskMoneyBr(event.target.value), margemManual: true },
                                    "lucro",
                                  )
                                }
                              />
                            </td>
                            <td className="px-3 py-2">
                              <input
                                type="date"
                                className="input-field w-36 text-xs"
                                value={item.dataValidade}
                                onChange={(event) => setItemField(item.numeroItem, "dataValidade", event.target.value)}
                              />
                            </td>
                            <td className="px-3 py-2">
                              <input
                                type="text"
                                maxLength={60}
                                className="input-field w-28 text-xs"
                                placeholder="opcional"
                                value={item.numeroLote}
                                onChange={(event) => setItemField(item.numeroItem, "numeroLote", event.target.value)}
                              />
                            </td>
                            <td className="px-3 py-2">
                              {existente ? (
                                <div className="flex flex-col gap-0.5">
                                  <span className="inline-flex items-center gap-1 rounded-full bg-emerald-500/15 border border-emerald-500/30 px-2 py-0.5 text-[11px] font-bold text-emerald-600 dark:text-emerald-400">
                                    <Check size={11} /> Entrada de estoque
                                  </span>
                                  <span className="text-[10px] text-text-tertiary">
                                    Soma ao estoque atual
                                  </span>
                                </div>
                              ) : (
                                <div className="flex flex-col gap-0.5">
                                  <span className="inline-flex items-center rounded-full bg-accent/10 border border-accent/30 px-2 py-0.5 text-[11px] font-semibold text-accent">
                                    Produto novo
                                  </span>
                                  <span className="text-[10px] text-text-tertiary">
                                    Cadastrará novo item
                                  </span>
                                </div>
                              )}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </section>
            </>
          )}
        </div>

        <div className="flex justify-end gap-2 border-t border-border-primary px-4 py-3">
          <button type="button" onClick={onClose} className="btn-secondary">
            Cancelar
          </button>
          {hasPreview ? (
            <LoadingButton
              type="button"
              isLoading={confirming}
              loadingLabel="Importando..."
              onClick={handleConfirmar}
              className="btn-primary inline-flex items-center gap-2"
            >
              Confirmar importação ({itens.length} {itens.length === 1 ? "item" : "itens"})
            </LoadingButton>
          ) : null}
        </div>
      </div>

      {/* Modal de Busca e Seleção de Produto para Vínculo */}
      {itemParaVincular && (
        <div className="fixed inset-0 z-layer-dialog flex items-center justify-center bg-black/60 backdrop-blur-sm p-4 animate-in fade-in duration-150">
          <div className="flex max-h-[88vh] w-full max-w-2xl flex-col overflow-hidden rounded-2xl border border-border-primary bg-bg-light shadow-2xl">
            {/* Header */}
            <div className="flex items-center justify-between border-b border-border-primary px-5 py-4 bg-bg-primary/40">
              <div className="space-y-0.5">
                <h3 className="text-base font-bold text-text-primary flex items-center gap-2">
                  <Link2 size={18} className="text-accent" />
                  Atrelar Item a Produto Existente
                </h3>
                <p className="text-xs text-text-secondary">
                  Item da Nota: <strong className="text-text-primary">{itemParaVincular.productName}</strong> (Cód. XML: {itemParaVincular.productCodeOriginal || itemParaVincular.productCode} · Qtd: {itemParaVincular.quantidade} {itemParaVincular.unidadeOriginal})
                </p>
              </div>
              <button
                type="button"
                onClick={() => setItemParaVincular(null)}
                className="rounded-lg p-1.5 text-text-secondary hover:bg-hover-light hover:text-text-primary"
              >
                <X size={18} />
              </button>
            </div>

            {/* Barra de Busca e Filtros */}
            <div className="p-4 border-b border-border-secondary space-y-3 bg-bg-light">
              <div className="relative">
                <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-text-tertiary" />
                <input
                  type="text"
                  autoFocus
                  value={buscaProdutoModal}
                  onChange={(e) => setBuscaProdutoModal(e.target.value)}
                  placeholder="Pesquise por nome (ex: presunto), código da balança (ex: 00025, 200025) ou código de barras..."
                  className="input-field w-full pl-9 text-xs"
                />
                {buscaProdutoModal && (
                  <button
                    type="button"
                    onClick={() => setBuscaProdutoModal("")}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-text-secondary hover:text-text-primary"
                  >
                    <X size={14} />
                  </button>
                )}
              </div>

              {/* Filtro Rápido */}
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={() => setFiltroBalancaModal(false)}
                  className={`px-3 py-1 rounded-full text-xs font-semibold transition-colors ${
                    !filtroBalancaModal
                      ? "bg-accent text-white"
                      : "bg-bg-secondary text-text-secondary hover:text-text-primary"
                  }`}
                >
                  Todos ({produtosCadastrados.length})
                </button>
                <button
                  type="button"
                  onClick={() => setFiltroBalancaModal(true)}
                  className={`inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-semibold transition-colors ${
                    filtroBalancaModal
                      ? "bg-amber-500 text-white"
                      : "bg-bg-secondary text-text-secondary hover:text-text-primary border border-amber-500/30"
                  }`}
                >
                  <Scale size={13} />
                  Balança / Peso (KG)
                </button>
              </div>
            </div>

            {/* Lista de Resultados */}
            <div className="flex-1 overflow-y-auto p-4 space-y-2 divide-y divide-border-secondary/50">
              {loadingProdutos ? (
                <div className="py-12 text-center text-text-secondary">
                  <Loader2 size={24} className="animate-spin inline mr-2 text-accent" />
                  Carregando catálogo de produtos...
                </div>
              ) : produtosFiltrados.length === 0 ? (
                <div className="py-12 text-center text-text-secondary space-y-1">
                  <p className="font-semibold text-text-primary">Nenhum produto encontrado</p>
                  <p className="text-xs">Tente buscar por outro termo ou desative o filtro de balança.</p>
                </div>
              ) : (
                produtosFiltrados.map((prod) => {
                  const isKg = (prod.unidadeComercial || "").toUpperCase() === "KG";
                  return (
                    <div
                      key={prod.id}
                      className="pt-2 pb-2 first:pt-0 flex items-center justify-between gap-4 hover:bg-hover-light/40 px-2 rounded-xl transition-colors"
                    >
                      <div className="space-y-1 min-w-0">
                        <div className="flex items-center gap-2 flex-wrap">
                          <span className="font-mono text-xs font-bold text-accent bg-accent/10 px-2 py-0.5 rounded">
                            #{prod.productCode}
                          </span>
                          <span className="font-semibold text-sm text-text-primary truncate">
                            {prod.productName}
                          </span>
                          {isKg && (
                            <span className="inline-flex items-center gap-1 text-[10px] font-bold bg-amber-500/15 text-amber-600 dark:text-amber-400 px-2 py-0.5 rounded-full border border-amber-500/30">
                              <Scale size={11} /> Balança (KG)
                            </span>
                          )}
                          {prod.categoriaNome && (
                            <span className="text-[10px] text-text-tertiary bg-bg-secondary px-2 py-0.5 rounded">
                              {prod.categoriaNome}
                            </span>
                          )}
                        </div>
                        <div className="flex items-center gap-4 text-xs text-text-secondary">
                          <span>
                            Estoque atual: <strong className="text-text-primary">{prod.productQnt} {prod.unidadeComercial || "UN"}</strong>
                          </span>
                          <span>
                            Venda: <strong className="text-emerald-600 dark:text-emerald-400">R$ {formatMoneyBr(parseMoneyBr(prod.productSalePrice))}</strong>
                          </span>
                          {prod.gtin && prod.gtin !== "SEM GTIN" && (
                            <span className="font-mono text-[11px] text-text-tertiary">
                              GTIN: {prod.gtin}
                            </span>
                          )}
                        </div>
                      </div>

                      <button
                        type="button"
                        onClick={() => handleSelecionarProdutoExistente(prod)}
                        className="btn-primary inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold shrink-0"
                      >
                        <Check size={13} />
                        Vincular
                      </button>
                    </div>
                  );
                })
              )}
            </div>

            {/* Footer */}
            <div className="flex justify-end border-t border-border-primary px-4 py-3 bg-bg-primary/20">
              <button
                type="button"
                onClick={() => setItemParaVincular(null)}
                className="btn-secondary text-xs"
              >
                Cancelar
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
