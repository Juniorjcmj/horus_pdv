/**
 * Arquivo: src/components/Admin/GondolaLabelModal.tsx
 * Objetivo: Modal interativo para configuração, pré-visualização e impressão de
 *           etiquetas de gôndola/prateleira para supermercado, com suporte a folha
 *           A4 adesiva (Pimaco) e impressora térmica de rolo contínuo.
 */

import {
  Barcode,
  Eye,
  Minus,
  Plus,
  Printer,
  Search,
  Settings2,
  Trash2,
  X,
} from "lucide-react";
import { useMemo, useRef, useState } from "react";
import LoadingButton from "@/components/Loading/LoadingButton";
import { Toast } from "@/hooks/Dialog";
import type { ProductDto } from "@/services/api/productService";
import BarcodeSvg from "./BarcodeSvg";

export type LabelFormat = "a4_pimaco_6180" | "a4_pimaco_6182" | "thermal_continuous";

interface LabelQueueItem {
  product: ProductDto;
  copies: number;
}

interface GondolaLabelModalProps {
  initialProducts: ProductDto[];
  allProducts: ProductDto[];
  onClose: () => void;
}

export default function GondolaLabelModal({
  initialProducts,
  allProducts,
  onClose,
}: GondolaLabelModalProps) {

  // Fila de itens com quantidade de cópias
  const [queue, setQueue] = useState<LabelQueueItem[]>(() => {
    return initialProducts.map((p) => ({ product: p, copies: 1 }));
  });

  // Configurações de impressão
  const [selectedFormat, setSelectedFormat] = useState<LabelFormat>("a4_pimaco_6180");
  const [startPosition, setStartPosition] = useState<number>(1);
  const [showDate, setShowDate] = useState<boolean>(true);
  const [showInternalCode, setShowInternalCode] = useState<boolean>(true);
  const [isPrinting, setIsPrinting] = useState<boolean>(false);

  // Busca para adicionar novos produtos à fila
  const [searchQuery, setSearchQuery] = useState<string>("");
  const [showSearchDropdown, setShowSearchDropdown] = useState<boolean>(false);
  const searchInputRef = useRef<HTMLInputElement>(null);

  // Data atual formatada (DD/MM/AAAA)
  const todayFormatted = useMemo(() => {
    const d = new Date();
    return d.toLocaleDateString("pt-BR", {
      day: "2-digit",
      month: "2-digit",
      year: "2-digit",
    });
  }, []);

  // Adiciona produto à fila
  const handleAddProduct = (product: ProductDto) => {
    setQueue((current) => {
      const existing = current.find((item) => item.product.id === product.id);
      if (existing) {
        return current.map((item) =>
          item.product.id === product.id
            ? { ...item, copies: item.copies + 1 }
            : item,
        );
      }
      return [{ product, copies: 1 }, ...current];
    });
    setSearchQuery("");
    setShowSearchDropdown(false);
  };

  // Altera quantidade de cópias
  const handleUpdateCopies = (productId: string, newCopies: number) => {
    if (newCopies <= 0) {
      handleRemoveItem(productId);
      return;
    }
    setQueue((current) =>
      current.map((item) =>
        item.product.id === productId ? { ...item, copies: Math.min(99, newCopies) } : item,
      ),
    );
  };

  // Remove produto da fila
  const handleRemoveItem = (productId: string) => {
    setQueue((current) => current.filter((item) => item.product.id === productId));
  };

  // Define 1 cópia para todos
  const handleResetCopiesToOne = () => {
    setQueue((current) => current.map((item) => ({ ...item, copies: 1 })));
  };

  // Limpa toda a fila
  const handleClearQueue = () => {
    if (queue.length === 0) return;
    setQueue([]);
    setStartPosition(1);
  };

  // Filtro da busca rápida de produtos
  const searchResults = useMemo(() => {
    const query = searchQuery.trim().toLowerCase();
    if (!query) return [];
    return allProducts
      .filter(
        (p) =>
          p.productName.toLowerCase().includes(query) ||
          p.productCode.toLowerCase().includes(query) ||
          p.gtin.toLowerCase().includes(query),
      )
      .slice(0, 8);
  }, [allProducts, searchQuery]);

  // Lista expandida de etiquetas a serem impressas (respeitando as cópias de cada uma)
  const expandedLabels = useMemo(() => {
    const labels: ProductDto[] = [];
    for (const item of queue) {
      for (let c = 0; c < item.copies; c++) {
        labels.push(item.product);
      }
    }
    return labels;
  }, [queue]);

  // Total de etiquetas
  const totalLabelsCount = expandedLabels.length;

  // Detalhes da paginação no formato A4
  const formatConfig = useMemo(() => {
    switch (selectedFormat) {
      case "a4_pimaco_6180":
        return {
          name: "Folha A4 (Pimaco 6180)",
          columns: 3,
          rows: 9,
          perPage: 27,
          labelWidth: "63.5mm",
          labelHeight: "31mm",
        };
      case "a4_pimaco_6182":
        return {
          name: "Folha A4 (Pimaco 6182)",
          columns: 2,
          rows: 8,
          perPage: 16,
          labelWidth: "105mm",
          labelHeight: "33.8mm",
        };
      case "thermal_continuous":
        return {
          name: "Impressora Térmica (Rolo 1 Coluna)",
          columns: 1,
          rows: 1,
          perPage: 1,
          labelWidth: "70mm",
          labelHeight: "35mm",
        };
    }
  }, [selectedFormat]);

  // Cálculo de páginas estimadas
  const totalSheetsCount = useMemo(() => {
    if (selectedFormat === "thermal_continuous") return totalLabelsCount;
    const effectiveTotal = Math.max(0, startPosition - 1) + totalLabelsCount;
    return Math.ceil(effectiveTotal / formatConfig.perPage) || 1;
  }, [selectedFormat, startPosition, totalLabelsCount, formatConfig.perPage]);

  // Disparo de impressão
  const handlePrint = () => {
    if (totalLabelsCount === 0) {
      Toast.error("Adicione ao menos um produto à fila de etiquetas.");
      return;
    }

    setIsPrinting(true);
    setTimeout(() => {
      window.print();
      setIsPrinting(false);
    }, 150);
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-2 backdrop-blur-xs sm:p-4 print:p-0">
      {/* DIALOG PRINCIPAL (Ocultado na impressão via @media print) */}
      <div className="relative flex h-[92vh] w-full max-w-6xl flex-col rounded-2xl border border-border-primary bg-bg-surface shadow-2xl overflow-hidden print:hidden">
        {/* CABEÇALHO */}
        <div className="flex items-center justify-between border-b border-border-primary px-5 py-4 bg-bg-primary/50">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-primary/15 text-primary">
              <Barcode size={22} />
            </div>
            <div>
              <h3 className="text-lg font-bold text-text-primary">
                Impressão de Etiquetas de Gôndola
              </h3>
              <p className="text-xs text-text-secondary">
                Gere e imprima etiquetas de prateleira com preços em destaque e código de barras.
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg p-2 text-text-secondary hover:bg-bg-light hover:text-text-primary"
            title="Fechar"
          >
            <X size={20} />
          </button>
        </div>

        {/* CORPO DO MODAL */}
        <div className="flex flex-1 overflow-hidden">
          {/* PAINEL LATERAL ESQUERDO: CONTROLES & FILA */}
          <div className="flex w-full flex-col border-r border-border-primary bg-bg-surface md:w-96 shrink-0 overflow-y-auto p-4 space-y-4">
            {/* BUSCADOR PARA ADICIONAR PRODUTO */}
            <div className="space-y-1.5 relative">
              <label className="text-xs font-semibold uppercase tracking-wide text-text-secondary">
                Adicionar Produto à Fila
              </label>
              <div className="relative">
                <Search
                  size={15}
                  className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-text-tertiary"
                />
                <input
                  ref={searchInputRef}
                  value={searchQuery}
                  onChange={(e) => {
                    setSearchQuery(e.target.value);
                    setShowSearchDropdown(true);
                  }}
                  onFocus={() => setShowSearchDropdown(true)}
                  placeholder="Nome, código ou EAN..."
                  className="input-field w-full pl-9 pr-8 text-xs"
                />
                {searchQuery ? (
                  <button
                    type="button"
                    onClick={() => {
                      setSearchQuery("");
                      setShowSearchDropdown(false);
                    }}
                    className="absolute right-2.5 top-1/2 -translate-y-1/2 text-text-tertiary hover:text-text-primary"
                  >
                    <X size={14} />
                  </button>
                ) : null}
              </div>

              {/* DROPDOWN DE RESULTADOS */}
              {showSearchDropdown && searchResults.length > 0 ? (
                <div className="absolute left-0 right-0 top-full z-20 mt-1 max-h-56 overflow-y-auto rounded-xl border border-border-primary bg-bg-surface p-1 shadow-xl">
                  {searchResults.map((prod) => (
                    <button
                      key={prod.id}
                      type="button"
                      onClick={() => handleAddProduct(prod)}
                      className="flex w-full items-center justify-between rounded-lg px-2.5 py-2 text-left text-xs hover:bg-bg-light transition-colors"
                    >
                      <div className="truncate pr-2">
                        <p className="font-semibold text-text-primary truncate">{prod.productName}</p>
                        <p className="text-[11px] text-text-tertiary">
                          Cód: {prod.productCode} • {prod.unidadeComercial}
                        </p>
                      </div>
                      <span className="shrink-0 font-bold text-primary">
                        R$ {prod.productSalePrice}
                      </span>
                    </button>
                  ))}
                </div>
              ) : null}
            </div>

            {/* CONFIGURAÇÕES DE FORMATO */}
            <div className="rounded-xl border border-border-primary bg-bg-primary/30 p-3 space-y-3">
              <div className="flex items-center gap-2 text-xs font-bold text-text-primary">
                <Settings2 size={15} className="text-primary" />
                <span>Modelo e Formato</span>
              </div>

              <div className="space-y-2">
                <label className="block">
                  <span className="mb-1 block text-[11px] font-medium text-text-secondary">
                    Tipo de Papel / Impressora
                  </span>
                  <select
                    value={selectedFormat}
                    onChange={(e) => {
                      setSelectedFormat(e.target.value as LabelFormat);
                      setStartPosition(1);
                    }}
                    className="input-field w-full text-xs py-1.5"
                  >
                    <option value="a4_pimaco_6180">
                      Folha A4 (Pimaco 6180 - 27 por folha, 3 colunas)
                    </option>
                    <option value="a4_pimaco_6182">
                      Folha A4 (Pimaco 6182 - 16 por folha, 2 colunas)
                    </option>
                    <option value="thermal_continuous">
                      Impressora Térmica (Rolo 1 Coluna)
                    </option>
                  </select>
                </label>

                {selectedFormat !== "thermal_continuous" ? (
                  <label className="block">
                    <div className="flex items-center justify-between mb-1">
                      <span className="text-[11px] font-medium text-text-secondary">
                        Começar na Etiqueta nº
                      </span>
                      <span className="text-[10px] text-text-tertiary">
                        (Reaproveitar folha)
                      </span>
                    </div>
                    <input
                      type="number"
                      min={1}
                      max={formatConfig.perPage}
                      value={startPosition}
                      onChange={(e) =>
                        setStartPosition(
                          Math.max(1, Math.min(formatConfig.perPage, Number(e.target.value) || 1)),
                        )
                      }
                      className="input-field w-full text-xs py-1"
                    />
                  </label>
                ) : null}
              </div>

              {/* OPÇÕES ADICIONAIS */}
              <div className="space-y-1.5 pt-1 border-t border-border-primary/60">
                <label className="flex items-center gap-2 text-xs text-text-secondary cursor-pointer">
                  <input
                    type="checkbox"
                    checked={showDate}
                    onChange={(e) => setShowDate(e.target.checked)}
                    className="h-3.5 w-3.5 rounded border-border-secondary accent-primary"
                  />
                  <span>Exibir data de emissão ({todayFormatted})</span>
                </label>
                <label className="flex items-center gap-2 text-xs text-text-secondary cursor-pointer">
                  <input
                    type="checkbox"
                    checked={showInternalCode}
                    onChange={(e) => setShowInternalCode(e.target.checked)}
                    className="h-3.5 w-3.5 rounded border-border-secondary accent-primary"
                  />
                  <span>Exibir código interno / PLU</span>
                </label>
              </div>
            </div>

            {/* LISTA DA FILA */}
            <div className="flex-1 flex flex-col min-h-[220px]">
              <div className="flex items-center justify-between mb-2">
                <span className="text-xs font-semibold text-text-secondary">
                  Itens Selecionados ({queue.length})
                </span>
                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    onClick={handleResetCopiesToOne}
                    className="text-[11px] text-text-tertiary hover:text-primary transition-colors"
                    title="1 cópia para todos"
                  >
                    1x Todos
                  </button>
                  <span className="text-text-tertiary">•</span>
                  <button
                    type="button"
                    onClick={handleClearQueue}
                    className="text-[11px] text-red-500 hover:text-red-600 transition-colors"
                    title="Limpar fila"
                  >
                    Limpar
                  </button>
                </div>
              </div>

              {queue.length === 0 ? (
                <div className="flex flex-1 flex-col items-center justify-center rounded-xl border border-dashed border-border-primary p-6 text-center text-text-tertiary">
                  <Barcode size={32} className="mb-2 opacity-40" />
                  <p className="text-xs font-medium">Nenhum produto na fila</p>
                  <p className="text-[11px]">Busque acima para adicionar etiquetas</p>
                </div>
              ) : (
                <div className="flex-1 overflow-y-auto space-y-1.5 pr-1 max-h-72">
                  {queue.map(({ product, copies }) => (
                    <div
                      key={product.id}
                      className="flex items-center justify-between rounded-lg border border-border-primary bg-bg-primary/20 p-2 text-xs"
                    >
                      <div className="truncate pr-2">
                        <p className="font-semibold text-text-primary truncate" title={product.productName}>
                          {product.productName}
                        </p>
                        <p className="text-[11px] text-text-tertiary">
                          R$ {product.productSalePrice} • {product.unidadeComercial}
                        </p>
                      </div>
                      <div className="flex items-center gap-1.5 shrink-0">
                        <button
                          type="button"
                          onClick={() => handleUpdateCopies(product.id, copies - 1)}
                          className="flex h-6 w-6 items-center justify-center rounded bg-bg-light text-text-secondary hover:bg-border-primary"
                        >
                          <Minus size={11} />
                        </button>
                        <input
                          type="number"
                          min={1}
                          max={99}
                          value={copies}
                          onChange={(e) =>
                            handleUpdateCopies(product.id, Number(e.target.value) || 1)
                          }
                          className="h-6 w-8 text-center font-bold text-xs bg-bg-surface border border-border-primary rounded"
                        />
                        <button
                          type="button"
                          onClick={() => handleUpdateCopies(product.id, copies + 1)}
                          className="flex h-6 w-6 items-center justify-center rounded bg-bg-light text-text-secondary hover:bg-border-primary"
                        >
                          <Plus size={11} />
                        </button>
                        <button
                          type="button"
                          onClick={() => handleRemoveItem(product.id)}
                          className="ml-1 text-text-tertiary hover:text-red-500 transition-colors"
                          title="Remover"
                        >
                          <Trash2 size={13} />
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>

          {/* PAINEL DIREITO: PRÉ-VISUALIZAÇÃO AO VIVO */}
          <div className="flex-1 flex flex-col bg-bg-primary/40 overflow-hidden">
            {/* BARRA SUPERIOR DA PRÉVIA */}
            <div className="flex items-center justify-between border-b border-border-primary px-5 py-2.5 bg-bg-surface text-xs">
              <div className="flex items-center gap-2 text-text-secondary">
                <Eye size={15} className="text-primary" />
                <span className="font-semibold text-text-primary">Prévia de Impressão:</span>
                <span>{formatConfig.name}</span>
              </div>
              <div className="flex items-center gap-3">
                <span className="font-bold text-text-primary">
                  {totalLabelsCount} etiqueta{totalLabelsCount !== 1 ? "s" : ""}
                </span>
                {selectedFormat !== "thermal_continuous" ? (
                  <span className="text-text-tertiary">
                    (~{totalSheetsCount} folha{totalSheetsCount !== 1 ? "s" : ""})
                  </span>
                ) : null}
              </div>
            </div>

            {/* ÁREA DE PRÉVIA ROLÁVEL */}
            <div className="flex-1 overflow-y-auto p-4 sm:p-6 flex justify-center items-start">
              {totalLabelsCount === 0 ? (
                <div className="flex flex-col items-center justify-center h-full text-center text-text-tertiary">
                  <Barcode size={48} className="mb-3 opacity-30" />
                  <p className="font-semibold text-sm">Nenhuma etiqueta para exibir</p>
                  <p className="text-xs">Selecione produtos na barra lateral para pré-visualizar o layout.</p>
                </div>
              ) : (
                <div
                  className={`bg-white text-black shadow-lg rounded-sm p-4 transition-all duration-200 border border-gray-300 ${
                    selectedFormat === "thermal_continuous"
                      ? "w-[300px]"
                      : "w-full max-w-[760px]"
                  }`}
                  style={{ minHeight: "350px" }}
                >
                  <div
                    className={`grid gap-2 ${
                      selectedFormat === "a4_pimaco_6180"
                        ? "grid-cols-3"
                        : selectedFormat === "a4_pimaco_6182"
                        ? "grid-cols-2"
                        : "grid-cols-1"
                    }`}
                  >
                    {/* Slots vazios para compensar startPosition */}
                    {selectedFormat !== "thermal_continuous" && startPosition > 1
                      ? Array.from({ length: startPosition - 1 }).map((_, idx) => (
                          <div
                            key={`empty-${idx}`}
                            className="flex items-center justify-center rounded border border-dashed border-gray-300 bg-gray-50 text-[10px] text-gray-400 p-3 min-h-[95px]"
                          >
                            Espaço vago ({idx + 1})
                          </div>
                        ))
                      : null}

                    {/* Etiquetas ativas */}
                    {expandedLabels.map((product, idx) => (
                      <GondolaLabelCard
                        key={`${product.id}-${idx}`}
                        product={product}
                        showDate={showDate}
                        showInternalCode={showInternalCode}
                        todayFormatted={todayFormatted}
                      />
                    ))}
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>

        {/* RODAPÉ DO MODAL */}
        <div className="flex items-center justify-between border-t border-border-primary px-5 py-3.5 bg-bg-surface">
          <div className="flex items-center gap-2 text-xs text-text-secondary">
            <span className="font-medium text-text-primary">Dica:</span>
            <span>Ao imprimir, marque &quot;Sem margens&quot; ou &quot;Tamanho real&quot; para encaixe milimétrico na folha.</span>
          </div>

          <div className="flex items-center gap-2.5">
            <button
              type="button"
              onClick={onClose}
              className="btn-secondary text-xs px-4 py-2"
            >
              Cancelar
            </button>
            <LoadingButton
              type="button"
              onClick={handlePrint}
              isLoading={isPrinting}
              loadingLabel="Abrindo impressão..."
              disabled={totalLabelsCount === 0}
              className="btn-primary inline-flex items-center gap-2 text-xs px-5 py-2"
            >
              <Printer size={15} />
              Imprimir {totalLabelsCount} Etiqueta{totalLabelsCount !== 1 ? "s" : ""}
            </LoadingButton>
          </div>
        </div>
      </div>

      {/* ÁREA DE IMPRESSÃO PURA (SOMENTE ATIVA VIA @media print) */}
      <div className="hidden print:block print:w-full print:bg-white print:text-black">
        <style>
          {`
            @media print {
              body {
                background: white !important;
                color: black !important;
                margin: 0 !important;
                padding: 0 !important;
              }
              @page {
                size: ${selectedFormat === "thermal_continuous" ? "auto" : "A4 portrait"};
                margin: ${selectedFormat === "thermal_continuous" ? "0mm" : "5mm"};
              }
              .print-sheet-grid {
                display: grid !important;
                gap: 2mm !important;
                grid-template-columns: ${
                  selectedFormat === "a4_pimaco_6180"
                    ? "repeat(3, 1fr)"
                    : selectedFormat === "a4_pimaco_6182"
                    ? "repeat(2, 1fr)"
                    : "1fr"
                } !important;
              }
              .print-label-box {
                break-inside: avoid !important;
                page-break-inside: avoid !important;
                border: 1px dashed #cccccc !important;
              }
            }
          `}
        </style>

        <div className="print-sheet-grid p-1">
          {/* Slots vazios no papel */}
          {selectedFormat !== "thermal_continuous" && startPosition > 1
            ? Array.from({ length: startPosition - 1 }).map((_, idx) => (
                <div key={`print-empty-${idx}`} className="print-label-box min-h-[30mm]" />
              ))
            : null}

          {/* Etiquetas */}
          {expandedLabels.map((product, idx) => (
            <div key={`print-lbl-${product.id}-${idx}`} className="print-label-box">
              <GondolaLabelCard
                product={product}
                showDate={showDate}
                showInternalCode={showInternalCode}
                todayFormatted={todayFormatted}
                isPrintMode
              />
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

/**
 * Cartão da Etiqueta de Gôndola Individual
 */
function GondolaLabelCard({
  product,
  showDate,
  showInternalCode,
  todayFormatted,
  isPrintMode = false,
}: {
  product: ProductDto;
  showDate: boolean;
  showInternalCode: boolean;
  todayFormatted: string;
  isPrintMode?: boolean;
}) {
  // Código de barras a ser renderizado: prioriza EAN-13/GTIN válido, senão productCode
  const barcodeToRender = useMemo(() => {
    const cleanGtin = (product.gtin || "").trim();
    if (cleanGtin && cleanGtin !== "SEM GTIN" && cleanGtin !== "0000000000000") {
      return cleanGtin;
    }
    return (product.productCode || "").trim();
  }, [product.gtin, product.productCode]);

  // Formatação de preço: quebra em parte inteira e centavos para destaque visual de gôndola
  const priceParts = useMemo(() => {
    const raw = String(product.productSalePrice || "0,00").replace(".", ",");
    const [inteiro, centavos] = raw.split(",");
    return {
      inteiro: inteiro || "0",
      centavos: (centavos || "00").padEnd(2, "0").slice(0, 2),
    };
  }, [product.productSalePrice]);

  return (
    <div
      className={`flex flex-col justify-between rounded-sm border border-gray-300 bg-white p-2 text-black transition-all ${
        isPrintMode ? "h-auto w-full p-2.5" : "min-h-[105px] shadow-xs"
      }`}
    >
      {/* NOME DO PRODUTO (EM DESTAQUE, 2 LINHAS) */}
      <div className="border-b border-gray-200 pb-1">
        <h4 className="line-clamp-2 text-[11px] font-black uppercase leading-tight tracking-tight text-gray-950">
          {product.productName}
        </h4>
      </div>

      {/* CORPO: CÓDIGO DE BARRAS À ESQUERDA + PREÇO GIGANTE À DIREITA */}
      <div className="flex items-center justify-between gap-1.5 py-1">
        {/* Lado Esquerdo: Código de Barras SVG */}
        <div className="flex flex-col justify-center min-w-0 max-w-[55%]">
          <BarcodeSvg
            value={barcodeToRender}
            height={24}
            width={1.2}
            fontSize={9}
            margin={0}
            className="w-full"
          />
        </div>

        {/* Lado Direito: Preço Chamativo de Gôndola */}
        <div className="flex flex-col items-end shrink-0 pl-1">
          <div className="flex items-baseline text-red-700 font-black">
            <span className="text-[10px] font-bold mr-0.5 text-gray-700">R$</span>
            <span className="text-xl leading-none tracking-tight">{priceParts.inteiro}</span>
            <span className="text-xs leading-none font-bold">,{priceParts.centavos}</span>
          </div>
          <span className="text-[9px] font-extrabold uppercase text-gray-700">
            {product.unidadeComercial || "UN"}
          </span>
        </div>
      </div>

      {/* RODAPÉ: CÓDIGO INTERNO & DATA */}
      <div className="flex items-center justify-between border-t border-gray-200 pt-0.5 text-[8px] font-medium text-gray-500">
        {showInternalCode ? (
          <span>Cód: {product.productCode}</span>
        ) : (
          <span />
        )}
        {showDate ? (
          <span>{todayFormatted}</span>
        ) : null}
      </div>
    </div>
  );
}
