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
  Check,
  Clipboard,
  FileUp,
  KeyRound,
  Loader2,
  PackageSearch,
  RotateCcw,
  Search,
  ShieldCheck,
  UploadCloud,
  X,
} from "lucide-react";
import { useRef, useState } from "react";
import LoadingButton from "@/components/Loading/LoadingButton";
import { Toast } from "@/hooks/Dialog";
import useInputMasks from "@/hooks/InputMasks/useInputMasks";
import {
  nfeImportService,
  type NfeImportFornecedorPreview,
  type NfeImportItemPreview,
  type NfeImportPreview,
} from "@/services/api/nfeImportService";

type EditableItem = NfeImportItemPreview & {
  quantidade: string;
  precoCusto: string;
  precoVenda: string;
  fatorConversao: string;
  quantidadeOriginal: number;
  precoCustoOriginal: number;
  precoVendaOriginal: number;
  unidadeOriginal: string;
};

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

  const [loading, setLoading] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [numeroNota, setNumeroNota] = useState("");
  const [serie, setSerie] = useState("");
  const [fornecedor, setFornecedor] = useState<NfeImportFornecedorPreview | null>(null);
  const [itens, setItens] = useState<EditableItem[]>([]);

  const hasPreview = fornecedor !== null;
  const cleanKey = chaveAcesso.replace(/\D/g, "");

  const aplicarPreview = (preview: NfeImportPreview) => {
    setNumeroNota(preview.numeroNota);
    setSerie(preview.serie);
    setFornecedor(preview.fornecedor);
    setItens(
      preview.itens.map((item) => {
        const qtdOriginal = parseMoneyBr(item.quantidade) || 1;
        const custoOriginal = parseMoneyBr(item.precoCusto) || 0;
        const vendaOriginal = parseMoneyBr(item.precoVendaSugerido) || 0;
        return {
          ...item,
          quantidade: item.quantidade,
          precoCusto: item.precoCusto,
          precoVenda: item.precoVendaSugerido,
          fatorConversao: "1",
          quantidadeOriginal: qtdOriginal,
          precoCustoOriginal: custoOriginal,
          precoVendaOriginal: vendaOriginal,
          unidadeOriginal: item.unidadeComercial || "UN",
          unidadeComercial: item.unidadeComercial || "UN",
        };
      }),
    );
  };

  const handleBuscarSefaz = async () => {
    if (cleanKey.length !== 44) {
      Toast.error("A chave de acesso da NF-e deve ter exatamente 44 dígitos numéricos.");
      return;
    }

    setLoading(true);
    setLoadingStatus("Consultando SEFAZ e baixando XML da NF-e...");
    try {
      const preview = await nfeImportService.previewPorChave(cleanKey);
      if (!preview) {
        Toast.error("A SEFAZ não retornou os dados da nota fiscal.");
        return;
      }
      aplicarPreview(preview);
      Toast.success(`NF-e nº ${preview.numeroNota} baixada da SEFAZ com sucesso!`);
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao consultar nota na SEFAZ.");
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

        return {
          ...item,
          fatorConversao: cleanFator,
          quantidade: novaQtd.toLocaleString("pt-BR", { maximumFractionDigits: 4 }),
          precoCusto: formatMoneyBr(novoCusto),
          precoVenda: novoPrecoVenda,
          unidadeComercial: novaUnidade,
        };
      }),
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
          productCode: item.productCode,
          productName: item.productName,
          gtin: item.gtin,
          ncm: item.ncm,
          cest: item.cest,
          unidadeComercial: item.unidadeComercial,
          quantidade: item.quantidade,
          precoCusto: item.precoCusto,
          precoVenda: item.precoVenda,
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
                            <td className="px-3 py-2">
                              <p className="font-medium text-text-primary">{item.productName}</p>
                              <div className="flex flex-wrap items-center gap-1.5 pt-0.5 text-xs text-text-tertiary">
                                <span>NCM {item.ncm}</span>
                                {isConverted ? (
                                  <span className="inline-flex items-center rounded bg-amber-500/15 px-1.5 py-0.5 text-[10px] font-bold text-amber-500">
                                    1 {item.unidadeOriginal} = {item.fatorConversao} UN
                                  </span>
                                ) : (
                                  <span>Unid. {item.unidadeOriginal}</span>
                                )}
                              </div>
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
                                  setItemField(item.numeroItem, "precoCusto", maskMoneyBr(event.target.value))
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
                                  setItemField(item.numeroItem, "precoVenda", maskMoneyBr(event.target.value))
                                }
                              />
                            </td>
                            <td className="px-3 py-2">
                              {existente ? (
                                <span className="inline-flex rounded-full bg-secondary/10 px-2 py-1 text-xs font-semibold text-secondary">
                                  Entrada de estoque
                                </span>
                              ) : (
                                <span className="inline-flex rounded-full bg-accent/10 px-2 py-1 text-xs font-semibold text-accent">
                                  Produto novo
                                </span>
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
    </div>
  );
}
