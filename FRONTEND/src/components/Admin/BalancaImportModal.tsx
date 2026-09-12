/**
 * Arquivo: src/components/Admin/BalancaImportModal.tsx
 * Objetivo: Modal interativo para importação em lote dos 133 produtos da balança etiquetadora
 *           (Triunfo Quantum 30 T) diretamente para o Quack PDV, com visualização por categorias,
 *           filtro de busca, barra de progresso e opção de download/cópia do script SQL.
 */

import { CheckCircle2, Copy, Loader2, Scale, Search, X } from "lucide-react";
import { useMemo, useState } from "react";
import LoadingButton from "@/components/Loading/LoadingButton";
import { Toast } from "@/hooks/Dialog";
import useInputMasks from "@/hooks/InputMasks/useInputMasks";
import { productService, type ProductDto, type ProductPayload } from "@/services/api/productService";
import { PRODUTOS_BALANCA } from "@/utils/produtosBalancaData";

interface BalancaImportModalProps {
  existingProducts: ProductDto[];
  onClose: () => void;
  onImported: () => void;
}

export default function BalancaImportModal({
  existingProducts,
  onClose,
  onImported,
}: BalancaImportModalProps) {
  const { formatMoneyBr } = useInputMasks();

  const [selectedCategory, setSelectedCategory] = useState<string>("Todos");
  const [searchTerm, setSearchTerm] = useState("");
  const [isImporting, setIsImporting] = useState(false);
  const [progress, setProgress] = useState(0);
  const [currentImportingName, setCurrentImportingName] = useState("");
  const [skipExisting, setSkipExisting] = useState(true);

  // Mapeamento dos códigos já cadastrados no sistema
  const existingCodesSet = useMemo(() => {
    return new Set(existingProducts.map((p) => p.productCode.trim()));
  }, [existingProducts]);

  // Lista enriquecida com status de cadastro
  const enrichedProducts = useMemo(() => {
    return PRODUTOS_BALANCA.map((item) => {
      const isAlreadyRegistered =
        existingCodesSet.has(item.code) ||
        existingCodesSet.has(String(Number(item.code)));
      return {
        ...item,
        isRegistered: isAlreadyRegistered,
      };
    });
  }, [existingCodesSet]);

  // Categorias disponíveis e contagem
  const categories = useMemo(() => {
    return ["Todos", "Frutas", "Legumes", "Açougue", "Frios", "Rações"];
  }, []);

  // Filtragem da tabela
  const filteredProducts = useMemo(() => {
    return enrichedProducts.filter((item) => {
      const matchesCategory =
        selectedCategory === "Todos" || item.category === selectedCategory;
      const cleanSearch = searchTerm.trim().toLowerCase();
      const matchesSearch =
        cleanSearch === "" ||
        item.name.toLowerCase().includes(cleanSearch) ||
        item.code.includes(cleanSearch);
      return matchesCategory && matchesSearch;
    });
  }, [enrichedProducts, selectedCategory, searchTerm]);

  // Estatísticas
  const totalCount = PRODUTOS_BALANCA.length;
  const alreadyRegisteredCount = enrichedProducts.filter((p) => p.isRegistered).length;
  const toImportCount = totalCount - alreadyRegisteredCount;

  // Ação de importação em lote
  const handleStartImport = async () => {
    setIsImporting(true);
    setProgress(0);

    const itemsToProcess = skipExisting
      ? enrichedProducts.filter((p) => !p.isRegistered)
      : enrichedProducts;

    if (itemsToProcess.length === 0) {
      Toast.info("Todos os produtos da lista já estão cadastrados!");
      setIsImporting(false);
      return;
    }

    let successCount = 0;
    let failCount = 0;

    for (let i = 0; i < itemsToProcess.length; i++) {
      const item = itemsToProcess[i];
      setCurrentImportingName(`${item.code} - ${item.name}`);
      setProgress(Math.round(((i + 1) / itemsToProcess.length) * 100));

      const payload: ProductPayload = {
        productImageUrl: "",
        productImageName: "",
        productName: item.name,
        productCode: item.code,
        productSupplier: "Balança Etiquetadora",
        productDescription: `Produto de balança - ${item.category}`,
        productQnt: "9999,0000", // Estoque alto inicial para balança
        productUnitPrice: "0,00",
        productSalePrice: item.defaultSalePrice > 0 ? formatMoneyBr(item.defaultSalePrice) : "0,00",
        totalPriceOnProduct: "0,00",
        margemDesejadaPercentual: null,
        ncm: "00000000",
        cest: null,
        cfop: "5102",
        origemMercadoria: 0,
        unidadeComercial: item.unit,
        unidadeTributavel: item.unit,
        gtin: "SEM GTIN",
        csosnIcms: "102",
        cstIcms: null,
        aliquotaIcms: "0,00",
        cstPis: "07",
        cstCofins: "07",
        cstIbsCbs: null,
        cClassTrib: null,
      };

      try {
        const existing = existingProducts.find(
          (p) => p.productCode === item.code || Number(p.productCode) === Number(item.code),
        );
        if (existing && !skipExisting) {
          await productService.update(existing.id, payload);
        } else if (!existing) {
          await productService.create(payload);
        }
        successCount++;
      } catch (err) {
        console.error(`Erro ao importar produto ${item.code}:`, err);
        failCount++;
      }
    }

    setIsImporting(false);
    setCurrentImportingName("");

    if (successCount > 0) {
      Toast.success(`${successCount} produto(s) de balança cadastrado(s) com sucesso!`);
      onImported();
      if (failCount === 0) {
        onClose();
      }
    } else if (failCount > 0) {
      Toast.error("Falha ao comunicar com a API. Você também pode rodar o script SQL gerado.");
    }
  };

  const handleCopySql = () => {
    const sqlUrl = "API/NETCORE/DataBase/Seed_Produtos_Balanca.sql";
    navigator.clipboard.writeText(sqlUrl);
    Toast.success("Caminho do arquivo SQL copiado para a área de transferência!");
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-xs p-4">
      <div className="flex h-[90vh] w-full max-w-5xl flex-col rounded-2xl border border-border-primary bg-bg-light shadow-2xl overflow-hidden animate-in fade-in zoom-in-95 duration-200">
        {/* Cabeçalho */}
        <div className="flex items-center justify-between border-b border-border-primary px-6 py-4 bg-bg-gray-theme/50">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-secondary/10 text-secondary">
              <Scale size={22} />
            </div>
            <div>
              <h2 className="text-lg font-bold text-text-primary">
                Carga de Produtos da Balança (Triunfo Quantum)
              </h2>
              <p className="text-xs text-text-secondary">
                {totalCount} produtos catalogados • {alreadyRegisteredCount} já no sistema •{" "}
                <strong className="text-accent">{toImportCount} novos para importar</strong>
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={isImporting}
            className="rounded-lg p-2 text-text-tertiary hover:bg-hover-light hover:text-text-primary transition-colors"
          >
            <X size={20} />
          </button>
        </div>

        {/* Barra de Filtros e Categorias */}
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-border-primary px-6 py-3 bg-bg-light">
          <div className="flex flex-wrap items-center gap-1.5">
            {categories.map((cat) => (
              <button
                key={cat}
                type="button"
                onClick={() => setSelectedCategory(cat)}
                className={`rounded-lg px-3 py-1.5 text-xs font-semibold transition-all ${
                  selectedCategory === cat
                    ? "bg-secondary text-white shadow-xs"
                    : "bg-bg-gray-theme text-text-secondary hover:bg-hover-light"
                }`}
              >
                {cat}
              </button>
            ))}
          </div>

          <div className="relative w-full sm:w-64">
            <Search
              size={14}
              className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-text-tertiary"
            />
            <input
              type="text"
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              placeholder="Buscar por código ou nome..."
              className="h-9 w-full rounded-lg border border-border-primary bg-bg-light pl-9 pr-3 text-xs text-text-primary placeholder:text-text-tertiary focus:border-secondary focus:outline-hidden"
            />
          </div>
        </div>

        {/* Tabela de Pré-visualização */}
        <div className="flex-1 overflow-y-auto px-6 py-3">
          <table className="w-full text-left border-collapse">
            <thead>
              <tr className="border-b border-border-primary text-[11px] font-bold text-text-tertiary uppercase tracking-wider">
                <th className="py-2.5 px-3">Código (PLU)</th>
                <th className="py-2.5 px-3">Descrição</th>
                <th className="py-2.5 px-3">Categoria</th>
                <th className="py-2.5 px-3">Unidade</th>
                <th className="py-2.5 px-3">Preço Sugerido</th>
                <th className="py-2.5 px-3 text-right">Status</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border-primary/50 text-xs">
              {filteredProducts.map((item) => (
                <tr
                  key={item.code}
                  className={`hover:bg-hover-light/40 transition-colors ${
                    item.isRegistered ? "opacity-60 bg-bg-gray-theme/20" : ""
                  }`}
                >
                  <td className="py-2 px-3 font-mono font-bold text-text-primary">
                    {item.code}
                  </td>
                  <td className="py-2 px-3 font-medium text-text-primary">
                    {item.name}
                  </td>
                  <td className="py-2 px-3 text-text-secondary">
                    <span className="inline-flex rounded-md bg-bg-gray-theme px-2 py-0.5 text-[11px] font-medium text-text-secondary">
                      {item.category}
                    </span>
                  </td>
                  <td className="py-2 px-3">
                    <span className="font-semibold text-accent">{item.unit}</span>
                  </td>
                  <td className="py-2 px-3 font-mono text-text-secondary">
                    {item.defaultSalePrice > 0
                      ? `R$ ${formatMoneyBr(item.defaultSalePrice)}`
                      : "Sob consulta"}
                  </td>
                  <td className="py-2 px-3 text-right">
                    {item.isRegistered ? (
                      <span className="inline-flex items-center gap-1 rounded-full bg-success/10 px-2 py-0.5 text-[10px] font-bold text-success">
                        <CheckCircle2 size={12} />
                        Cadastrado
                      </span>
                    ) : (
                      <span className="inline-flex rounded-full bg-accent/10 px-2 py-0.5 text-[10px] font-bold text-accent">
                        Pendente
                      </span>
                    )}
                  </td>
                </tr>
              ))}
              {filteredProducts.length === 0 && (
                <tr>
                  <td colSpan={6} className="py-8 text-center text-text-tertiary">
                    Nenhum produto encontrado com os filtros atuais.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>

        {/* Rodapé e Progresso */}
        <div className="border-t border-border-primary bg-bg-gray-theme/40 px-6 py-4 flex flex-col sm:flex-row items-center justify-between gap-4">
          <div className="flex flex-col sm:flex-row items-start sm:items-center gap-3 text-xs text-text-secondary w-full sm:w-auto">
            <label className="flex items-center gap-2 cursor-pointer select-none">
              <input
                type="checkbox"
                checked={skipExisting}
                onChange={(e) => setSkipExisting(e.target.checked)}
                disabled={isImporting}
                className="rounded border-border-secondary text-secondary focus:ring-secondary"
              />
              <span>Pular produtos já cadastrados ({alreadyRegisteredCount})</span>
            </label>
            <span className="hidden sm:inline text-border-secondary">|</span>
            <button
              type="button"
              onClick={handleCopySql}
              className="inline-flex items-center gap-1 text-text-tertiary hover:text-secondary underline transition-colors"
              title="Arquivo: API/NETCORE/DataBase/Seed_Produtos_Balanca.sql"
            >
              <Copy size={13} />
              Ver caminho do Seed_Produtos_Balanca.sql
            </button>
          </div>

          <div className="flex items-center gap-3 w-full sm:w-auto justify-end">
            {isImporting && (
              <div className="flex flex-col items-end gap-1 mr-2 text-right">
                <span className="text-[11px] font-semibold text-secondary flex items-center gap-1">
                  <Loader2 size={12} className="animate-spin" />
                  {progress}% ({currentImportingName})
                </span>
                <div className="h-1.5 w-36 overflow-hidden rounded-full bg-border-primary">
                  <div
                    className="h-full bg-secondary transition-all duration-200"
                    style={{ width: `${progress}%` }}
                  />
                </div>
              </div>
            )}

            <button
              type="button"
              onClick={onClose}
              disabled={isImporting}
              className="btn-secondary text-xs"
            >
              Fechar
            </button>

            <LoadingButton
              type="button"
              onClick={handleStartImport}
              isLoading={isImporting}
              disabled={isImporting || (skipExisting && toImportCount === 0)}
              className="btn-primary inline-flex items-center gap-2 text-xs font-semibold"
            >
              <Scale size={15} />
              {toImportCount === 0 && skipExisting
                ? "Todos já cadastrados"
                : `Importar ${skipExisting ? toImportCount : totalCount} Produtos`}
            </LoadingButton>
          </div>
        </div>
      </div>
    </div>
  );
}
