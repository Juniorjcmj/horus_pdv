/**
 * Arquivo: src/pages/Admin/ProductRegisterPage.tsx
 * Objetivo: gerencia cadastro de produtos com formulário em drawer, busca e ações de editar/remover.
 * Entradas esperadas: não recebe props; opera com estado local de lista e formulário de produto.
 */

import TableScrollArea from "@/components/Admin/TableScrollArea";
import {
  AlertTriangle,
  Barcode,
  Calendar,
  ChevronDown,
  ChevronUp,
  Copy,
  Database,
  DollarSign,
  FileUp,
  FileCheck2,
  Filter,
  Loader2,
  PackageX,
  Pencil,
  Plus,
  RotateCcw,
  Scale,
  Search,
  SlidersHorizontal,
  Tag,
  Trash2,
  UploadCloud,
  X,
} from "lucide-react";
import { type ClipboardEvent, type FormEvent, type KeyboardEvent as ReactKeyboardEvent, useEffect, useMemo, useRef, useState } from "react";
import BalancaImportModal from "@/components/Admin/BalancaImportModal";
import GondolaLabelModal from "@/components/Admin/GondolaLabelModal";
import ProductFiscalPanel from "@/components/Admin/ProductFiscalPanel";
import NotasEntradaPanel from "@/components/Admin/NotasEntradaPanel";
import NfeImportModal from "@/components/Admin/NfeImportModal";
import PageHeader from "@/components/Admin/PageHeader";
import RowActionsMenu from "@/components/Admin/RowActionsMenu";
import { SearchableSelectField } from "@/components/Form";
import LoadingButton from "@/components/Loading/LoadingButton";
import TablePagination from "@/components/Pagination/TablePagination";
import AddressContactFields from "@/components/Register/AddressContactFields";
import { Toast, useStatusDialog } from "@/hooks/Dialog";
import useInputMasks from "@/hooks/InputMasks/useInputMasks";
import PageLayout from "@/layout/PageLayout";
import ListState from "@/components/Admin/ListState";
import useRemoteList from "@/hooks/useRemoteList";
import { categoriaService, type CategoriaArvore } from "@/services/api/categoriaService";
import { productService } from "@/services/api/productService";
import { supplierService, type SupplierPayload } from "@/services/api/supplierService";
import { lookupAddressByCep } from "@/utils/cepLookup";
import { onlyDigits } from "@/utils/inputMasks";
import { isValidCnpj, isValidEmail } from "@/utils/validators";

type Product = {
  id: string;
  productImageUrl: string;
  productImageName: string;
  productName: string;
  productCode: string;
  productSupplier: string;
  productDescription: string;
  productQnt: string;
  estoqueMinimo: string;
  productUnitPrice: string;
  productSalePrice: string;
  totalPriceOnProduct: string;
  lucro: string;
  margemDesejadaPercentual: string | null;
  categoriaId?: string | null;
  categoriaNome?: string | null;
  dataValidade?: string | null;
  /** Lote da quantidade inicial; só é enviado/usado ao criar o produto. */
  numeroLote?: string | null;
  controlaValidade?: boolean;
  diasAlertaValidade?: number;
  diasRestantes?: number | null;

  // Unidades de medida (compra/conversão)
  unidadeCompra: string;
  fatorConversao: string;
  qtdEmbalagem: string;

  // Marca e fabricante
  marca?: string | null;
  fabricante?: string | null;
  referenciaFabricante?: string | null;

  // Peso e dimensões
  pesoLiquidoKg: string;
  pesoBrutoKg: string;
  larguraCm: string;
  alturaCm: string;
  comprimentoCm: string;

  // Estoque expandido
  estoqueMaximo: string;
  localizacaoEstoque?: string | null;

  // Dados de custo detalhados
  custoMedio: string;
  custoComImposto: string;
  custoSemImposto: string;

  // Campos comerciais
  descontoMaximoPercentual: string;
  comissaoPercentual: string;
  markupCadastrado: string;
  markupPraticado: string;

  // Dados fiscais (NFC-e modelo 65)
  ncm: string;
  cest: string | null;
  cfop: string;
  origemMercadoria: number;
  unidadeComercial: string;
  unidadeTributavel: string;
  gtin: string;
  csosnIcms: string | null;
  cstIcms: string | null;
  aliquotaIcms: string;
  cstPis: string;
  cstCofins: string;
  cstIbsCbs: string | null;
  cClassTrib: string | null;
};

type ProductFormData = Omit<Product, "id">;

type QuickSupplierDraft = SupplierPayload;

// Produtos vendidos por peso/volume aceitam estoque e venda fracionados (ex.: 12,500 kg).
function isFractionableUnit(unit: string) {
  return unit.trim().toUpperCase() !== "UN";
}

const SEFAZ_UNITS = [
  "UN", "KG", "LT", "MT", "M2", "M3", "PC", "CX", "RL", "PAR",
  "SC", "GL", "CT", "BD", "BL", "JG", "BOB", "ML", "SACH", "DZ",
  "FD", "GF", "PT", "TB", "TN",
] as const;

const EMPTY_FORM: ProductFormData = {
  productImageUrl: "",
  productImageName: "",
  productName: "",
  productCode: "",
  productSupplier: "",
  productDescription: "",
  productQnt: "0",
  estoqueMinimo: "0",
  productUnitPrice: "",
  productSalePrice: "",
  totalPriceOnProduct: "",
  lucro: "0,00",
  margemDesejadaPercentual: "",
  categoriaId: null,
  categoriaNome: null,
  dataValidade: "",
  numeroLote: "",
  controlaValidade: false,
  diasAlertaValidade: 15,
  diasRestantes: null,
  unidadeCompra: "UN",
  fatorConversao: "1",
  qtdEmbalagem: "1",
  marca: "",
  fabricante: "",
  referenciaFabricante: "",
  pesoLiquidoKg: "0",
  pesoBrutoKg: "0",
  larguraCm: "0",
  alturaCm: "0",
  comprimentoCm: "0",
  estoqueMaximo: "0",
  localizacaoEstoque: "",
  custoMedio: "0,00",
  custoComImposto: "0,00",
  custoSemImposto: "0,00",
  descontoMaximoPercentual: "0",
  comissaoPercentual: "0",
  markupCadastrado: "0",
  markupPraticado: "0",
  ncm: "",
  cest: "",
  cfop: "5102",
  origemMercadoria: 0,
  unidadeComercial: "UN",
  unidadeTributavel: "UN",
  gtin: "SEM GTIN",
  csosnIcms: "102",
  cstIcms: "",
  aliquotaIcms: "0,00",
  cstPis: "07",
  cstCofins: "07",
  cstIbsCbs: null,
  cClassTrib: null,
};

const EMPTY_SUPPLIER_DRAFT: QuickSupplierDraft = {
  companyName: "",
  fantasyName: "",
  cnpj: "",
  cep: "",
  city: "",
  state: "",
  address: "",
  neighborhood: "",
  streetComplement: "",
  number: "",
  referencePoint: "",
  telephone: "",
  cellphone: "",
  email: "",
};

function preventNonDigitBeforeInput(event: FormEvent<HTMLInputElement>) {
  const data = (event.nativeEvent as InputEvent).data ?? "";
  if (data && /\D/.test(data)) {
    event.preventDefault();
  }
}

function ProductFormDrawer({
  open,
  isEditMode,
  value,
  isSaving,
  onClose,
  onChange,
  onSave,
  supplierOptions,
  onCreateSupplier,
  categories,
  cloneSourceName,
}: {
  open: boolean;
  isEditMode: boolean;
  cloneSourceName?: string | null;
  value: ProductFormData;
  isSaving: boolean;
  onClose: () => void;
  onChange: (next: ProductFormData) => void;
  onSave: () => void;
  supplierOptions: string[];
  onCreateSupplier: (draft: QuickSupplierDraft) => Promise<string | null>;
  categories: CategoriaArvore[];
}) {
  const {
    maskCnpj,
    maskMoneyBr,
    parseMoneyBr,
    formatMoneyBr,
    sanitizeIntegerInput,
    sanitizeDecimalInput,
  } = useInputMasks();
  const quantityIsFractionable =
    isFractionableUnit(value.unidadeComercial || "UN") ||
    isFractionableUnit(value.unidadeCompra || "UN");
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const [isDragActive, setIsDragActive] = useState(false);
  const [supplierModalOpen, setSupplierModalOpen] = useState(false);
  const [supplierDraft, setSupplierDraft] = useState<QuickSupplierDraft>(EMPTY_SUPPLIER_DRAFT);
  const [savingSupplier, setSavingSupplier] = useState(false);
  const [loadingSupplierCep, setLoadingSupplierCep] = useState(false);
  const [showPesoDimensoes, setShowPesoDimensoes] = useState(false);
  const [showDadosComerciais, setShowDadosComerciais] = useState(false);

  let selectedDepId = "";
  let selectedSubId = "";
  let selectedSubSubId = "";
  if (value.categoriaId) {
    // Try to find the categoriaId at any of the 3 levels
    const asRoot = categories.find((c) => c.id === value.categoriaId);
    if (asRoot) {
      selectedDepId = asRoot.id;
    } else {
      for (const root of categories) {
        const sub = root.subcategorias?.find((s) => s.id === value.categoriaId);
        if (sub) {
          selectedDepId = root.id;
          selectedSubId = sub.id;
          break;
        }
        for (const mid of root.subcategorias ?? []) {
          const subsub = mid.subcategorias?.find((ss) => ss.id === value.categoriaId);
          if (subsub) {
            selectedDepId = root.id;
            selectedSubId = mid.id;
            selectedSubSubId = subsub.id;
            break;
          }
        }
        if (selectedDepId) break;
      }
    }
  }

  const currentSubcategories = useMemo(() => {
    if (!selectedDepId) return [];
    const root = categories.find((c) => c.id === selectedDepId);
    return root?.subcategorias ?? [];
  }, [categories, selectedDepId]);

  const currentSubSubcategories = useMemo(() => {
    if (!selectedSubId) return [];
    const sub = currentSubcategories.find((c) => c.id === selectedSubId);
    return sub?.subcategorias ?? [];
  }, [currentSubcategories, selectedSubId]);

  const handleDepartmentChange = (depId: string) => {
    if (!depId) {
      onChange({ ...value, categoriaId: null, categoriaNome: null });
    } else {
      const dep = categories.find((c) => c.id === depId);
      onChange({ ...value, categoriaId: depId, categoriaNome: dep?.nome ?? null });
    }
  };

  const handleSubcategoryChange = (subId: string) => {
    if (!subId) {
      const dep = categories.find((c) => c.id === selectedDepId);
      onChange({ ...value, categoriaId: selectedDepId || null, categoriaNome: dep?.nome ?? null });
    } else {
      const sub = currentSubcategories.find((s) => s.id === subId);
      onChange({ ...value, categoriaId: subId, categoriaNome: sub?.nome ?? null });
    }
  };

  const handleSubSubcategoryChange = (subSubId: string) => {
    if (!subSubId) {
      const sub = currentSubcategories.find((s) => s.id === selectedSubId);
      onChange({ ...value, categoriaId: selectedSubId || null, categoriaNome: sub?.nome ?? null });
    } else {
      const subsub = currentSubSubcategories.find((s) => s.id === subSubId);
      onChange({ ...value, categoriaId: subSubId, categoriaNome: subsub?.nome ?? null });
    }
  };

  if (!open) return null;

  const setField = <K extends keyof ProductFormData>(
    key: K,
    fieldValue: ProductFormData[K],
  ) => {
    const next = { ...value, [key]: fieldValue };
    const quantity = parseMoneyBr(next.productQnt || "0");
    const unitPrice = parseMoneyBr(next.productUnitPrice);
    next.totalPriceOnProduct = quantity > 0 ? formatMoneyBr(quantity * unitPrice) : "";

    // Custo ou margem desejada mudou: recalcula o preço de venda sozinho pra manter a margem
    // configurada (ex.: custo R$10,00 + 30% = venda R$13,00). Preço de venda continua editável
    // manualmente depois — só é recalculado quando um desses dois campos muda.
    if (key === "productUnitPrice" || key === "margemDesejadaPercentual") {
      const margin = next.margemDesejadaPercentual?.trim();
      if (margin) {
        next.productSalePrice = formatMoneyBr(unitPrice * (1 + parseMoneyBr(margin) / 100));
      }
    }

    onChange(next);
  };

  const handleUnidadeComercialChange = (newUnit: string) => {
    const prevUnit = value.unidadeComercial || "UN";
    const next: ProductFormData = {
      ...value,
      unidadeComercial: newUnit,
    };
    if (!value.unidadeTributavel || value.unidadeTributavel === prevUnit || value.unidadeTributavel === "UN") {
      next.unidadeTributavel = newUnit;
    }
    if (!value.unidadeCompra || value.unidadeCompra === prevUnit || value.unidadeCompra === "UN") {
      next.unidadeCompra = newUnit;
    }
    onChange(next);
  };

  const handleUnidadeCompraChange = (newUnit: string) => {
    const next: ProductFormData = {
      ...value,
      unidadeCompra: newUnit,
    };
    if ((!value.unidadeComercial || value.unidadeComercial === "UN") && newUnit !== "UN") {
      next.unidadeComercial = newUnit;
      if (!value.unidadeTributavel || value.unidadeTributavel === "UN") {
        next.unidadeTributavel = newUnit;
      }
    }
    onChange(next);
  };

  const setQuantityField = (rawValue: string) => {
    const isDecimal = rawValue.includes(",") || rawValue.includes(".");
    const allowDecimal = quantityIsFractionable || isDecimal;
    const sanitized = allowDecimal
      ? sanitizeDecimalInput(rawValue, 4).replace(".", ",")
      : sanitizeIntegerInput(rawValue).slice(0, 8);
    setField("productQnt", sanitized);
  };

  const setEstoqueMinimoField = (rawValue: string) => {
    const isDecimal = rawValue.includes(",") || rawValue.includes(".");
    const allowDecimal = quantityIsFractionable || isDecimal;
    const sanitized = allowDecimal
      ? sanitizeDecimalInput(rawValue, 4).replace(".", ",")
      : sanitizeIntegerInput(rawValue).slice(0, 8);
    setField("estoqueMinimo", sanitized);
  };

  const setEstoqueMaximoField = (rawValue: string) => {
    const isDecimal = rawValue.includes(",") || rawValue.includes(".");
    const allowDecimal = quantityIsFractionable || isDecimal;
    const sanitized = allowDecimal
      ? sanitizeDecimalInput(rawValue, 4).replace(".", ",")
      : sanitizeIntegerInput(rawValue).slice(0, 8);
    setField("estoqueMaximo", sanitized);
  };

  const applyImage = (file: File | null) => {
    if (!file || !file.type.startsWith("image/")) return;

    const reader = new FileReader();
    reader.onload = () => {
      const result = typeof reader.result === "string" ? reader.result : "";
      onChange({
        ...value,
        productImageName: file.name,
        productImageUrl: result,
      });
    };
    reader.readAsDataURL(file);
  };

  const setMoneyField = (
    key: "productUnitPrice" | "productSalePrice" | "margemDesejadaPercentual",
    fieldValue: string,
  ) => {
    setField(key, maskMoneyBr(fieldValue));
  };

  const pasteMoneyField = (
    event: ClipboardEvent<HTMLInputElement>,
    key: "productUnitPrice" | "productSalePrice" | "margemDesejadaPercentual",
  ) => {
    event.preventDefault();
    setMoneyField(key, event.clipboardData.getData("text"));
  };

  const openSupplierModal = (searchTerm = "") => {
    const name = searchTerm || value.productSupplier;
    setSupplierDraft({
      ...EMPTY_SUPPLIER_DRAFT,
      companyName: name,
      fantasyName: name,
    });
    setSupplierModalOpen(true);
  };

  const setSupplierField = <K extends keyof QuickSupplierDraft>(
    key: K,
    fieldValue: QuickSupplierDraft[K],
  ) => {
    setSupplierDraft((current) => ({ ...current, [key]: fieldValue }));
  };

  const fillSupplierAddressFromCep = async () => {
    if (onlyDigits(supplierDraft.cep).length !== 8) {
      Toast.error("CEP inválido.");
      return;
    }

    setLoadingSupplierCep(true);
    const result = await lookupAddressByCep(supplierDraft.cep);
    setLoadingSupplierCep(false);

    if (!result.success) {
      Toast.error("CEP não encontrado.");
      return;
    }

    setSupplierDraft((current) => ({
      ...current,
      address: result.data.endereco || current.address,
      neighborhood: result.data.bairro || current.neighborhood,
      city: result.data.cidade || current.city,
      state: result.data.estado || current.state,
      streetComplement: result.data.complemento || current.streetComplement,
    }));
  };

  const validateSupplierDraft = () => {
    const requiredFields: Array<keyof QuickSupplierDraft> = [
      "companyName",
      "fantasyName",
      "cnpj",
      "cep",
      "city",
      "state",
      "address",
      "neighborhood",
      "number",
      "cellphone",
    ];

    const missing = requiredFields.some((field) => !String(supplierDraft[field]).trim());
    if (missing) {
      Toast.error("Preencha os campos obrigatórios do fornecedor.");
      return false;
    }

    if (supplierDraft.companyName.trim().length < 3) {
      Toast.error("A razão social deve ter no mínimo 3 caracteres.");
      return false;
    }

    if (supplierDraft.fantasyName.trim().length < 3) {
      Toast.error("O nome fantasia deve ter no mínimo 3 caracteres.");
      return false;
    }

    if (!isValidCnpj(supplierDraft.cnpj)) {
      Toast.error("CNPJ inválido.");
      return false;
    }

    if (!isValidEmail(supplierDraft.email)) {
      Toast.error("E-mail inválido.");
      return false;
    }

    return true;
  };

  const submitSupplier = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();

    if (!validateSupplierDraft()) return;

    setSavingSupplier(true);
    try {
      const createdName = await onCreateSupplier(supplierDraft);
      if (createdName) {
        setField("productSupplier", createdName);
        setSupplierModalOpen(false);
        setSupplierDraft(EMPTY_SUPPLIER_DRAFT);
      }
    } finally {
      setSavingSupplier(false);
    }
  };

  return (
    <div className="dept-drawer-overlay" onClick={onClose}>
      <aside className="dept-drawer-panel" onClick={(event) => event.stopPropagation()}>
        <div className="flex items-start justify-between border-b border-border-primary p-5">
          <div>
            <h3 className="text-xl font-semibold text-text-primary">
              {isEditMode ? "Editar produto" : "Novo produto"}
            </h3>
            <p className="mt-1 text-sm text-text-secondary">
              {cloneSourceName
                ? `Clone de "${cloneSourceName}": dados fiscais e comerciais copiados. Ajuste a descrição e os preços. O código será gerado se não for informado.`
                : "Informe descrição, custo e preço de venda. Fornecedor e estoque inicial são opcionais."}
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg p-2 text-text-secondary transition hover:bg-hover-light hover:text-text-primary"
            aria-label="Fechar formulário"
          >
            <X size={18} />
          </button>
        </div>

        <div className="flex-1 space-y-4 overflow-y-auto p-5">
          <section className="card rounded-2xl p-4">
            <h4 className="text-sm font-semibold text-text-secondary">Dados do produto</h4>
            <div className="mt-3 grid gap-3 md:grid-cols-2">
              <label className="block md:col-span-2">
                <span className="mb-1.5 block text-sm text-text-secondary">Imagem do Produto</span>
                <div
                  onDragEnter={(event) => {
                    event.preventDefault();
                    setIsDragActive(true);
                  }}
                  onDragOver={(event) => {
                    event.preventDefault();
                    setIsDragActive(true);
                  }}
                  onDragLeave={(event) => {
                    event.preventDefault();
                    setIsDragActive(false);
                  }}
                  onDrop={(event) => {
                    event.preventDefault();
                    setIsDragActive(false);
                    applyImage(event.dataTransfer.files?.[0] ?? null);
                  }}
                  className={`flex flex-col items-center justify-center rounded-xl border border-dashed p-4 transition ${
                    isDragActive
                      ? "border-accent bg-accent/10"
                      : "border-border-secondary bg-bg-primary/50"
                  }`}
                >
                  <div className="mb-3 h-24 w-24 overflow-hidden rounded-2xl border border-border-primary bg-bg-light shadow-sm">
                    {value.productImageUrl ? (
                      <img
                        src={value.productImageUrl}
                        alt="Pré-visualização do produto"
                        className="h-full w-full object-cover"
                      />
                    ) : (
                      <div className="flex h-full w-full items-center justify-center text-center text-sm font-medium text-text-tertiary">
                        Sem imagem
                      </div>
                    )}
                  </div>
                  <input
                    ref={fileInputRef}
                    type="file"
                    accept="image/*"
                    className="hidden"
                    onChange={(event) => applyImage(event.target.files?.[0] ?? null)}
                  />
                  <button
                    type="button"
                    className="btn-outline-secondary"
                    onClick={() => fileInputRef.current?.click()}
                  >
                    Arraste e solte ou clique para enviar
                  </button>
                  {value.productImageName ? (
                    <span className="mt-2 text-xs text-text-secondary">
                      Arquivo: {value.productImageName}
                    </span>
                  ) : null}
                </div>
              </label>
              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">Descrição do Produto *</span>
                <input
                  value={value.productName}
                  onChange={(event) => setField("productName", event.target.value)}
                  className="input-field w-full"
                  placeholder="Ex.: Arroz branco 5 kg"
                />
              </label>
              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">Código do Produto</span>
                <input
                  value={value.productCode}
                  onChange={(event) => setField("productCode", event.target.value)}
                  className="input-field w-full"
                  placeholder="Gerado automaticamente se não informado"
                />
              </label>
              <SearchableSelectField
                label="Fornecedor (opcional)"
                value={value.productSupplier}
                options={supplierOptions}
                onChange={(nextValue) => setField("productSupplier", nextValue)}
                getOptionValue={(supplier) => supplier}
                getOptionLabel={(supplier) => supplier}
                placeholder="Pesquisar fornecedor"
                emptyMessage="Nenhum fornecedor encontrado."
                createActionLabel="Cadastrar fornecedor"
                onCreateOption={openSupplierModal}
                className="md:col-span-2"
              />
              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">Marca</span>
                <input
                  value={value.marca ?? ""}
                  onChange={(event) => setField("marca", event.target.value || null)}
                  className="input-field w-full"
                  placeholder="Ex.: Votorantim, Tigre"
                />
              </label>
              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">Fabricante</span>
                <input
                  value={value.fabricante ?? ""}
                  onChange={(event) => setField("fabricante", event.target.value || null)}
                  className="input-field w-full"
                  placeholder="Fabricante"
                />
              </label>
              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">Ref. Fabricante</span>
                <input
                  value={value.referenciaFabricante ?? ""}
                  onChange={(event) => setField("referenciaFabricante", event.target.value || null)}
                  className="input-field w-full"
                  placeholder="Código do fabricante"
                />
              </label>
              <label className="block md:col-span-2">
                <span className="mb-1.5 block text-sm text-text-secondary">
                  Descrição complementar (opcional)
                </span>
                <textarea
                  value={value.productDescription}
                  onChange={(event) => setField("productDescription", event.target.value)}
                  className="input-field min-h-[96px] w-full"
                  placeholder="Detalhes adicionais do produto"
                />
              </label>
            </div>
          </section>

          <section className="card rounded-2xl p-4">
            <h4 className="text-sm font-semibold text-text-secondary">Classificação</h4>
            <div className="mt-3 grid gap-3 md:grid-cols-3">
              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">Departamento</span>
                <select
                  value={selectedDepId}
                  onChange={(event) => handleDepartmentChange(event.target.value)}
                  className="input-field w-full"
                >
                  <option value="">Sem departamento</option>
                  {categories.map((dept) => (
                    <option key={dept.id} value={dept.id}>
                      {dept.nome}
                    </option>
                  ))}
                </select>
              </label>
              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">Grupo</span>
                <select
                  value={selectedSubId}
                  onChange={(event) => handleSubcategoryChange(event.target.value)}
                  disabled={!selectedDepId || currentSubcategories.length === 0}
                  className="input-field w-full disabled:opacity-50"
                >
                  <option value="">
                    {!selectedDepId
                      ? "Selecione um departamento"
                      : currentSubcategories.length === 0
                      ? "Nenhum grupo cadastrado"
                      : "Sem grupo"}
                  </option>
                  {currentSubcategories.map((sub) => (
                    <option key={sub.id} value={sub.id}>
                      {sub.nome}
                    </option>
                  ))}
                </select>
              </label>
              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">Subgrupo</span>
                <select
                  value={selectedSubSubId}
                  onChange={(event) => handleSubSubcategoryChange(event.target.value)}
                  disabled={!selectedSubId || currentSubSubcategories.length === 0}
                  className="input-field w-full disabled:opacity-50"
                >
                  <option value="">
                    {!selectedSubId
                      ? "Selecione um grupo"
                      : currentSubSubcategories.length === 0
                      ? "Nenhum subgrupo cadastrado"
                      : "Sem subgrupo"}
                  </option>
                  {currentSubSubcategories.map((subsub) => (
                    <option key={subsub.id} value={subsub.id}>
                      {subsub.nome}
                    </option>
                  ))}
                </select>
              </label>
            </div>
          </section>

          <section className="card rounded-2xl p-4">
            <h4 className="text-sm font-semibold text-text-secondary">Preço e estoque</h4>
            <div className="mt-3 grid gap-3 md:grid-cols-2">
              <label className="block">
                <span className="mb-1.5 block text-sm font-semibold text-text-primary">
                  Unidade de Medida (Venda / Estoque)
                </span>
                <select
                  value={value.unidadeComercial || "UN"}
                  onChange={(event) => handleUnidadeComercialChange(event.target.value)}
                  className="input-field w-full font-bold text-accent"
                >
                  {SEFAZ_UNITS.map((unit) => (
                    <option key={unit} value={unit}>
                      {unit} {unit === "UN" ? "(Unidade)" : unit === "KG" ? "(Quilograma)" : unit === "LT" ? "(Litro)" : unit === "MT" ? "(Metro)" : unit === "CX" ? "(Caixa)" : unit === "PC" ? "(Peça)" : ""}
                    </option>
                  ))}
                </select>
                <span className="mt-1 block text-xs text-text-secondary">
                  Unidade de controle do estoque e vendas (ex.: KG para peso, UN para unidade).
                </span>
              </label>

              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">
                  Quantidade do Produto ({value.unidadeComercial || "UN"})
                </span>
                <input
                  value={value.productQnt}
                  inputMode="decimal"
                  onChange={(event) => setQuantityField(event.target.value)}
                  className="input-field w-full font-semibold"
                  placeholder={quantityIsFractionable ? "Ex: 17,50" : "Quantidade"}
                />
              </label>

              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">
                  Estoque Mínimo ({value.unidadeComercial || "UN"})
                </span>
                <input
                  value={value.estoqueMinimo}
                  inputMode="decimal"
                  onChange={(event) => setEstoqueMinimoField(event.target.value)}
                  className="input-field w-full"
                  placeholder={quantityIsFractionable ? "0,000" : "0"}
                />
                <span className="mt-1 block text-xs text-text-secondary">
                  Alerta no sistema quando o estoque estiver igual ou abaixo deste valor.
                </span>
              </label>

              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">
                  Estoque Máximo ({value.unidadeComercial || "UN"})
                </span>
                <input
                  value={value.estoqueMaximo}
                  inputMode="decimal"
                  onChange={(event) => setEstoqueMaximoField(event.target.value)}
                  className="input-field w-full"
                  placeholder={quantityIsFractionable ? "0,000" : "0"}
                />
              </label>

              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">Localização no Estoque</span>
                <input
                  value={value.localizacaoEstoque ?? ""}
                  onChange={(event) => setField("localizacaoEstoque", event.target.value || null)}
                  className="input-field w-full"
                  placeholder="Ex.: Corredor 3, Prateleira A"
                />
              </label>

              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">Unidade de Compra (Fornecedor)</span>
                <select
                  value={value.unidadeCompra}
                  onChange={(event) => handleUnidadeCompraChange(event.target.value)}
                  className="input-field w-full"
                >
                  {SEFAZ_UNITS.map((unit) => (
                    <option key={unit} value={unit}>{unit}</option>
                  ))}
                </select>
                <span className="mt-1 block text-xs text-text-secondary">
                  Unidade que consta na nota do fornecedor (ex.: CX ou KG).
                </span>
              </label>

              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">Fator de Conversão</span>
                <input
                  value={value.fatorConversao}
                  inputMode="decimal"
                  onChange={(event) => setField("fatorConversao", sanitizeDecimalInput(event.target.value, 4).replace(".", ","))}
                  className="input-field w-full"
                  placeholder="1"
                />
                <span className="mt-1 block text-xs text-text-secondary">
                  Quantas unidades de venda por unidade de compra (ex.: 1 CX = 100 UN).
                </span>
              </label>

              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">Qtd por Embalagem</span>
                <input
                  value={value.qtdEmbalagem}
                  inputMode="decimal"
                  onChange={(event) => setField("qtdEmbalagem", sanitizeDecimalInput(event.target.value, 4).replace(".", ","))}
                  className="input-field w-full"
                  placeholder="1"
                />
              </label>
              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">
                  Valor de Custo *
                </span>
                <input
                  value={value.productUnitPrice}
                  inputMode="numeric"
                  pattern="[0-9,.]*"
                  onBeforeInput={preventNonDigitBeforeInput}
                  onPaste={(event) => pasteMoneyField(event, "productUnitPrice")}
                  onChange={(event) => setMoneyField("productUnitPrice", event.target.value)}
                  className="input-field w-full"
                  placeholder="0,00"
                />
              </label>
              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">
                  Margem de lucro desejada (%)
                </span>
                <input
                  value={value.margemDesejadaPercentual ?? ""}
                  inputMode="numeric"
                  pattern="[0-9,.]*"
                  onBeforeInput={preventNonDigitBeforeInput}
                  onPaste={(event) => pasteMoneyField(event, "margemDesejadaPercentual")}
                  onChange={(event) => setMoneyField("margemDesejadaPercentual", event.target.value)}
                  className="input-field w-full"
                  placeholder="Ex.: 30,00"
                />
                <span className="mt-1 block text-xs text-text-secondary">
                  Opcional. Calcula o preço de venda a partir do custo e recalcula sozinho quando o
                  custo mudar (inclusive por importação de XML).
                </span>
              </label>
              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">
                  Valor de Venda *
                </span>
                <input
                  value={value.productSalePrice}
                  inputMode="numeric"
                  pattern="[0-9,.]*"
                  onBeforeInput={preventNonDigitBeforeInput}
                  onPaste={(event) => pasteMoneyField(event, "productSalePrice")}
                  onChange={(event) => setMoneyField("productSalePrice", event.target.value)}
                  className="input-field w-full"
                  placeholder="0,00"
                />
              </label>
              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">
                  Valor Total em Estoque (calculado)
                </span>
                <input
                  value={value.totalPriceOnProduct}
                  className="input-field w-full"
                  placeholder="0,00"
                  disabled
                />
              </label>
            </div>
          </section>

          <section className="card rounded-2xl p-4">
            <button
              type="button"
              className="flex w-full items-center justify-between"
              onClick={() => setShowPesoDimensoes((prev) => !prev)}
            >
              <h4 className="flex items-center gap-2 text-sm font-semibold text-text-secondary">
                <Scale className="h-4 w-4 text-primary" />
                Peso e Dimensões
              </h4>
              <ChevronDown
                className={`h-4 w-4 text-text-secondary transition-transform ${showPesoDimensoes ? "rotate-180" : ""}`}
              />
            </button>
            {showPesoDimensoes ? (
              <div className="mt-3 grid gap-3 border-t border-border-secondary pt-3 md:grid-cols-3">
                <label className="block">
                  <span className="mb-1.5 block text-sm text-text-secondary">Peso Líquido (kg)</span>
                  <input
                    value={value.pesoLiquidoKg}
                    inputMode="decimal"
                    onChange={(event) => setField("pesoLiquidoKg", sanitizeDecimalInput(event.target.value, 3).replace(".", ","))}
                    className="input-field w-full"
                    placeholder="0,000"
                  />
                </label>
                <label className="block">
                  <span className="mb-1.5 block text-sm text-text-secondary">Peso Bruto (kg)</span>
                  <input
                    value={value.pesoBrutoKg}
                    inputMode="decimal"
                    onChange={(event) => setField("pesoBrutoKg", sanitizeDecimalInput(event.target.value, 3).replace(".", ","))}
                    className="input-field w-full"
                    placeholder="0,000"
                  />
                </label>
                <label className="block">
                  <span className="mb-1.5 block text-sm text-text-secondary">Largura (cm)</span>
                  <input
                    value={value.larguraCm}
                    inputMode="decimal"
                    onChange={(event) => setField("larguraCm", sanitizeDecimalInput(event.target.value, 2).replace(".", ","))}
                    className="input-field w-full"
                    placeholder="0,00"
                  />
                </label>
                <label className="block">
                  <span className="mb-1.5 block text-sm text-text-secondary">Altura (cm)</span>
                  <input
                    value={value.alturaCm}
                    inputMode="decimal"
                    onChange={(event) => setField("alturaCm", sanitizeDecimalInput(event.target.value, 2).replace(".", ","))}
                    className="input-field w-full"
                    placeholder="0,00"
                  />
                </label>
                <label className="block">
                  <span className="mb-1.5 block text-sm text-text-secondary">Comprimento (cm)</span>
                  <input
                    value={value.comprimentoCm}
                    inputMode="decimal"
                    onChange={(event) => setField("comprimentoCm", sanitizeDecimalInput(event.target.value, 2).replace(".", ","))}
                    className="input-field w-full"
                    placeholder="0,00"
                  />
                </label>
              </div>
            ) : null}
          </section>

          <section className="card rounded-2xl p-4">
            <button
              type="button"
              className="flex w-full items-center justify-between"
              onClick={() => setShowDadosComerciais((prev) => !prev)}
            >
              <h4 className="flex items-center gap-2 text-sm font-semibold text-text-secondary">
                <Tag className="h-4 w-4 text-primary" />
                Dados Comerciais
              </h4>
              <ChevronDown
                className={`h-4 w-4 text-text-secondary transition-transform ${showDadosComerciais ? "rotate-180" : ""}`}
              />
            </button>
            {showDadosComerciais ? (
              <div className="mt-3 grid gap-3 border-t border-border-secondary pt-3 md:grid-cols-2">
                <label className="block">
                  <span className="mb-1.5 block text-sm text-text-secondary">Custo Médio</span>
                  <input
                    value={value.custoMedio}
                    inputMode="numeric"
                    pattern="[0-9,.]*"
                    onBeforeInput={preventNonDigitBeforeInput}
                    onChange={(event) => setField("custoMedio", maskMoneyBr(event.target.value))}
                    className="input-field w-full"
                    placeholder="0,00"
                  />
                  <span className="mt-1 block text-xs text-text-secondary">
                    Calculado automaticamente se não informado.
                  </span>
                </label>
                <label className="block">
                  <span className="mb-1.5 block text-sm text-text-secondary">Custo c/ Imposto</span>
                  <input
                    value={value.custoComImposto}
                    inputMode="numeric"
                    pattern="[0-9,.]*"
                    onBeforeInput={preventNonDigitBeforeInput}
                    onChange={(event) => setField("custoComImposto", maskMoneyBr(event.target.value))}
                    className="input-field w-full"
                    placeholder="0,00"
                  />
                </label>
                <label className="block">
                  <span className="mb-1.5 block text-sm text-text-secondary">Custo s/ Imposto</span>
                  <input
                    value={value.custoSemImposto}
                    inputMode="numeric"
                    pattern="[0-9,.]*"
                    onBeforeInput={preventNonDigitBeforeInput}
                    onChange={(event) => setField("custoSemImposto", maskMoneyBr(event.target.value))}
                    className="input-field w-full"
                    placeholder="0,00"
                  />
                </label>
                <label className="block">
                  <span className="mb-1.5 block text-sm text-text-secondary">Markup Cadastrado (%)</span>
                  <input
                    value={value.markupCadastrado}
                    inputMode="decimal"
                    onChange={(event) => setField("markupCadastrado", sanitizeDecimalInput(event.target.value, 2).replace(".", ","))}
                    className="input-field w-full"
                    placeholder="0,00"
                  />
                </label>
                <label className="block">
                  <span className="mb-1.5 block text-sm text-text-secondary">Markup Praticado (%)</span>
                  <input
                    value={value.markupPraticado}
                    className="input-field w-full bg-bg-primary/50"
                    disabled
                  />
                  <span className="mt-1 block text-xs text-text-secondary">
                    Calculado pelo sistema: (Venda - Custo Médio) / Custo Médio.
                  </span>
                </label>
                <label className="block">
                  <span className="mb-1.5 block text-sm text-text-secondary">Desconto Máximo (%)</span>
                  <input
                    value={value.descontoMaximoPercentual}
                    inputMode="decimal"
                    onChange={(event) => setField("descontoMaximoPercentual", sanitizeDecimalInput(event.target.value, 2).replace(".", ","))}
                    className="input-field w-full"
                    placeholder="0,00"
                  />
                </label>
                <label className="block">
                  <span className="mb-1.5 block text-sm text-text-secondary">Comissão (%)</span>
                  <input
                    value={value.comissaoPercentual}
                    inputMode="decimal"
                    onChange={(event) => setField("comissaoPercentual", sanitizeDecimalInput(event.target.value, 2).replace(".", ","))}
                    className="input-field w-full"
                    placeholder="0,00"
                  />
                </label>
              </div>
            ) : null}
          </section>

          <section className="card rounded-2xl p-4">
            <div className="flex items-center justify-between">
              <h4 className="flex items-center gap-2 text-sm font-semibold text-text-secondary">
                <Calendar className="h-4 w-4 text-primary" />
                Controle de Validade
              </h4>
              <label className="relative inline-flex cursor-pointer items-center gap-2">
                <input
                  type="checkbox"
                  checked={value.controlaValidade ?? false}
                  onChange={(e) =>
                    onChange({
                      ...value,
                      controlaValidade: e.target.checked,
                      diasAlertaValidade: value.diasAlertaValidade || 15,
                    })
                  }
                  className="h-4 w-4 rounded border-border-secondary text-primary focus:ring-primary"
                />
                <span className="text-sm font-medium text-text-primary">Controlar validade</span>
              </label>
            </div>

            {value.controlaValidade ? (
              <div className="mt-4 space-y-3 border-t border-border-secondary pt-3">
                <div className="grid gap-3 md:grid-cols-2">
                  <label className="block">
                    <span className="mb-1.5 block text-sm text-text-secondary">
                      {isEditMode ? "Data de Validade (lote mais próximo)" : "Data de Validade do lote inicial"}
                    </span>
                    <input
                      type="date"
                      value={value.dataValidade ?? ""}
                      onChange={(e) =>
                        onChange({
                          ...value,
                          dataValidade: e.target.value,
                        })
                      }
                      className="input-field w-full"
                    />
                  </label>
                  <label className="block">
                    <span className="mb-1.5 block text-sm text-text-secondary">
                      Dias de Antecedência para Alerta
                    </span>
                    <input
                      type="number"
                      min={1}
                      max={365}
                      value={value.diasAlertaValidade ?? 15}
                      onChange={(e) =>
                        onChange({
                          ...value,
                          diasAlertaValidade: Number(e.target.value) || 15,
                        })
                      }
                      className="input-field w-full"
                      placeholder="15"
                    />
                    <span className="mt-1 block text-xs text-text-secondary">
                      Alerta quando a validade estiver a X dias do vencimento.
                    </span>
                  </label>
                </div>

                {!isEditMode ? (
                  <label className="block">
                    <span className="mb-1.5 block text-sm text-text-secondary">Nº do lote (opcional)</span>
                    <input
                      type="text"
                      maxLength={60}
                      value={value.numeroLote ?? ""}
                      onChange={(e) => onChange({ ...value, numeroLote: e.target.value })}
                      className="input-field w-full"
                      placeholder="Número do lote impresso na embalagem"
                    />
                    <span className="mt-1 block text-xs text-text-secondary">
                      A quantidade informada no cadastro vira o primeiro lote deste produto.
                    </span>
                  </label>
                ) : null}

                {/* Indicador visual de status semafórico */}
                {value.dataValidade ? (() => {
                  const today = new Date();
                  today.setHours(0, 0, 0, 0);
                  const valDate = new Date(value.dataValidade + "T00:00:00");
                  const diffTime = valDate.getTime() - today.getTime();
                  const diffDays = Math.ceil(diffTime / (1000 * 60 * 60 * 24));
                  const alertDays = value.diasAlertaValidade || 15;

                  if (diffDays < 0) {
                    return (
                      <div className="flex items-center gap-2 rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-2 text-sm text-red-600 dark:text-red-400">
                        <AlertTriangle className="h-4 w-4 shrink-0" />
                        <span><strong className="font-semibold">PRODUTO VENCIDO:</strong> Venceu há {Math.abs(diffDays)} dia(s) (em {valDate.toLocaleDateString("pt-BR")}).</span>
                      </div>
                    );
                  } else if (diffDays <= alertDays) {
                    return (
                      <div className="flex items-center gap-2 rounded-lg border border-amber-500/30 bg-amber-500/10 px-3 py-2 text-sm text-amber-600 dark:text-amber-400">
                        <AlertTriangle className="h-4 w-4 shrink-0" />
                        <span><strong className="font-semibold">ATENÇÃO - PRÓXIMO DO VENCIMENTO:</strong> Vence em {diffDays} dia(s) (em {valDate.toLocaleDateString("pt-BR")}).</span>
                      </div>
                    );
                  } else {
                    return (
                      <div className="flex items-center gap-2 rounded-lg border border-emerald-500/30 bg-emerald-500/10 px-3 py-2 text-sm text-emerald-600 dark:text-emerald-400">
                        <span className="h-2 w-2 rounded-full bg-emerald-500 shrink-0" />
                        <span><strong className="font-medium">Válido:</strong> Vence em {diffDays} dia(s) (em {valDate.toLocaleDateString("pt-BR")}).</span>
                      </div>
                    );
                  }
                })() : (
                  <p className="text-xs text-text-tertiary">
                    Informe a data de validade para monitorar alertas automáticos e evitar perdas.
                  </p>
                )}
              </div>
            ) : null}
          </section>

          <section className="card rounded-2xl p-4">
            <h4 className="text-sm font-semibold text-text-secondary">Dados fiscais (NFC-e)</h4>
            <div className="mt-3 grid gap-3 md:grid-cols-3">
              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">NCM</span>
                <input
                  value={value.ncm}
                  onChange={(event) =>
                    setField("ncm", sanitizeIntegerInput(event.target.value).slice(0, 8))
                  }
                  className="input-field w-full"
                  placeholder="00000000"
                />
              </label>
              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">CFOP</span>
                <input
                  value={value.cfop}
                  onChange={(event) =>
                    setField("cfop", sanitizeIntegerInput(event.target.value).slice(0, 4))
                  }
                  className="input-field w-full"
                  placeholder="5102"
                />
              </label>
              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">CEST</span>
                <input
                  value={value.cest ?? ""}
                  onChange={(event) =>
                    setField("cest", sanitizeIntegerInput(event.target.value).slice(0, 7) || null)
                  }
                  className="input-field w-full"
                  placeholder="Só produtos com ST"
                />
              </label>
              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">Origem da mercadoria</span>
                <select
                  value={value.origemMercadoria}
                  onChange={(event) => setField("origemMercadoria", Number(event.target.value))}
                  className="input-field w-full"
                >
                  <option value={0}>0 - Nacional</option>
                  <option value={1}>1 - Estrangeira (importação direta)</option>
                  <option value={2}>2 - Estrangeira (mercado interno)</option>
                  <option value={3}>3 - Nacional, +40% importado</option>
                  <option value={4}>4 - Nacional, produção conforme processos produtivos básicos</option>
                  <option value={5}>5 - Nacional, ≤40% importado</option>
                  <option value={6}>6 - Estrangeira (importação direta, sem similar nacional)</option>
                  <option value={7}>7 - Estrangeira (mercado interno, sem similar nacional)</option>
                  <option value={8}>8 - Nacional, +70% importado</option>
                </select>
              </label>
              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">Unidade comercial (venda)</span>
                <select
                  value={value.unidadeComercial}
                  onChange={(event) => handleUnidadeComercialChange(event.target.value)}
                  className="input-field w-full"
                >
                  {SEFAZ_UNITS.map((unit) => (
                    <option key={unit} value={unit}>{unit}</option>
                  ))}
                </select>
              </label>
              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">Unidade tributável</span>
                <select
                  value={value.unidadeTributavel}
                  onChange={(event) => setField("unidadeTributavel", event.target.value)}
                  className="input-field w-full"
                >
                  {SEFAZ_UNITS.map((unit) => (
                    <option key={unit} value={unit}>{unit}</option>
                  ))}
                </select>
              </label>
              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">GTIN / código de barras</span>
                <input
                  value={value.gtin}
                  onChange={(event) => setField("gtin", event.target.value || "SEM GTIN")}
                  className="input-field w-full"
                  placeholder="SEM GTIN"
                />
              </label>
              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">CSOSN (Simples/MEI)</span>
                <input
                  value={value.csosnIcms ?? ""}
                  onChange={(event) => setField("csosnIcms", event.target.value.trim() || null)}
                  className="input-field w-full"
                  placeholder="Ex.: 102, 500"
                />
              </label>
              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">CST ICMS (Regime Normal)</span>
                <input
                  value={value.cstIcms ?? ""}
                  onChange={(event) => setField("cstIcms", event.target.value.trim() || null)}
                  className="input-field w-full"
                  placeholder="Ex.: 00, 60"
                />
              </label>
              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">Alíquota ICMS (%)</span>
                <input
                  value={value.aliquotaIcms}
                  inputMode="decimal"
                  onChange={(event) => setField("aliquotaIcms", maskMoneyBr(event.target.value))}
                  className="input-field w-full"
                  placeholder="0,00"
                />
              </label>
              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">CST PIS</span>
                <input
                  value={value.cstPis}
                  onChange={(event) =>
                    setField("cstPis", sanitizeIntegerInput(event.target.value).slice(0, 2).padStart(2, "0"))
                  }
                  className="input-field w-full"
                  placeholder="07"
                />
              </label>
              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">CST COFINS</span>
                <input
                  value={value.cstCofins}
                  onChange={(event) =>
                    setField("cstCofins", sanitizeIntegerInput(event.target.value).slice(0, 2).padStart(2, "0"))
                  }
                  className="input-field w-full"
                  placeholder="07"
                />
              </label>
              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">CST IBS/CBS</span>
                <input value={value.cstIbsCbs ?? ""} inputMode="numeric" maxLength={3}
                  onChange={event => setField("cstIbsCbs", onlyDigits(event.target.value).slice(0, 3) || null)}
                  className="input-field w-full" placeholder="Ex.: 000, 200" />
              </label>
              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">cClassTrib IBS/CBS</span>
                <input value={value.cClassTrib ?? ""} inputMode="numeric" maxLength={6}
                  onChange={event => setField("cClassTrib", onlyDigits(event.target.value).slice(0, 6) || null)}
                  className="input-field w-full" placeholder="Ex.: 000001" />
              </label>
            </div>
            <p className="mt-3 text-xs text-text-secondary">
              Preencha CSOSN quando a empresa for Simples/MEI ou CST ICMS quando for Regime
              Normal — o emissor fiscal usa o que estiver preenchido no cadastro da empresa.
            </p>
          </section>
        </div>

        <div className="border-t border-border-primary p-4">
          <div className="grid grid-cols-2 gap-2 sm:flex sm:flex-wrap sm:items-center sm:justify-end">
            <button type="button" onClick={onClose} className="btn-cancel">
              Cancelar
            </button>
            <LoadingButton
              type="button"
              onClick={onSave}
              isLoading={isSaving}
              loadingLabel="Salvando..."
              className="btn-primary inline-flex min-h-10 items-center justify-center gap-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
            >
              {isEditMode ? "Salvar produto" : "Criar produto"}
            </LoadingButton>
          </div>
        </div>
      </aside>

      {supplierModalOpen ? (
        <div
          className="fixed inset-0 z-layer-dialog flex items-center justify-center bg-black/50 px-3 backdrop-blur-sm"
          onClick={(event) => event.stopPropagation()}
        >
          <form
            onSubmit={submitSupplier}
            className="w-full max-w-4xl rounded-2xl border border-border-primary bg-bg-light shadow-2xl"
          >
            <div className="flex items-center justify-between border-b border-border-primary px-4 py-3">
              <div>
                <h3 className="text-base font-semibold text-text-primary">
                  Cadastrar fornecedor
                </h3>
                <p className="text-sm text-text-secondary">
                  O fornecedor criado será selecionado neste produto.
                </p>
              </div>
              <button
                type="button"
                onClick={() => setSupplierModalOpen(false)}
                className="inline-flex h-9 w-9 items-center justify-center rounded-lg hover:bg-hover-light"
                aria-label="Fechar cadastro de fornecedor"
              >
                <X size={18} />
              </button>
            </div>

            <div className="max-h-[70vh] space-y-4 overflow-y-auto p-4">
              <section className="card rounded-2xl p-4">
                <h4 className="text-sm font-semibold text-text-secondary">Dados do fornecedor</h4>
                <div className="mt-3 grid gap-3 md:grid-cols-3">
                  <label className="block">
                    <span className="mb-1.5 block text-sm text-text-secondary">Razão social *</span>
                    <input
                      value={supplierDraft.companyName}
                      onChange={(event) => setSupplierField("companyName", event.target.value)}
                      className="input-field w-full"
                      placeholder="Razão social"
                    />
                  </label>
                  <label className="block">
                    <span className="mb-1.5 block text-sm text-text-secondary">Nome fantasia *</span>
                    <input
                      value={supplierDraft.fantasyName}
                      onChange={(event) => setSupplierField("fantasyName", event.target.value)}
                      className="input-field w-full"
                      placeholder="Nome fantasia"
                    />
                  </label>
                  <label className="block">
                    <span className="mb-1.5 block text-sm text-text-secondary">CNPJ *</span>
                    <input
                      value={supplierDraft.cnpj}
                      onChange={(event) => setSupplierField("cnpj", maskCnpj(event.target.value))}
                      className="input-field w-full"
                      placeholder="00.000.000/0000-00"
                    />
                  </label>
                </div>
              </section>

              <AddressContactFields
                value={{
                  cep: supplierDraft.cep,
                  city: supplierDraft.city,
                  state: supplierDraft.state,
                  address: supplierDraft.address,
                  neighborhood: supplierDraft.neighborhood,
                  streetComplement: supplierDraft.streetComplement,
                  number: supplierDraft.number,
                  referencePoint: supplierDraft.referencePoint,
                  telephone: supplierDraft.telephone,
                  cellphone: supplierDraft.cellphone,
                  email: supplierDraft.email,
                }}
                loadingCep={loadingSupplierCep}
                onFillAddressFromCep={fillSupplierAddressFromCep}
                onChange={(field, fieldValue) => setSupplierField(field, fieldValue)}
              />
            </div>

            <div className="flex flex-col-reverse gap-2 border-t border-border-primary px-4 py-3 sm:flex-row sm:justify-end">
              <button
                type="button"
                onClick={() => setSupplierModalOpen(false)}
                className="btn-cancel"
              >
                Cancelar
              </button>
              <LoadingButton
                type="submit"
                isLoading={savingSupplier}
                loadingLabel="Salvando..."
                className="btn-primary inline-flex min-h-10 items-center justify-center gap-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
              >
                Salvar fornecedor
              </LoadingButton>
            </div>
          </form>
        </div>
      ) : null}
    </div>
  );
}

export default function ProductRegisterPage() {
  const { parseMoneyBr, formatMoneyBr, maskMoneyBr, sanitizeDecimalInput } = useInputMasks();
  const statusDialog = useStatusDialog();
  const { items: products, setItems: setProducts, isLoading: loadingProducts, error: productsError, reload: loadProducts } = useRemoteList<Product>(productService.list, "Não foi possível carregar os produtos.");
  const [supplierOptions, setSupplierOptions] = useState<string[]>([]);
  const [search, setSearch] = useState("");
  const [currentPage, setCurrentPage] = useState(1);
  const [itemsPerPage, setItemsPerPage] = useState(10);
  const [selectedProductIds, setSelectedProductIds] = useState<Set<string>>(() => new Set());
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [notasEntradaOpen, setNotasEntradaOpen] = useState(false);
  const [fiscalProduct, setFiscalProduct] = useState<Product | null>(null);
  const [importModalOpen, setImportModalOpen] = useState(false);
  const [balancaModalOpen, setBalancaModalOpen] = useState(false);
  const [isImportingMercado, setIsImportingMercado] = useState(false);

  type PriceFilter = "todos" | "sem-preco-venda" | "com-preco-venda" | "sem-preco-custo" | "margem-zerada-negativa";
  type StockFilter = "todos" | "estoque-baixo" | "sem-estoque" | "com-estoque";
  type FiscalFilter = "todos" | "sem-gtin" | "sem-ncm" | "balanca" | "unidade";
  type ValidityFilter = "todos" | "com-controle" | "vencidos-alerta";

  const [advancedFiltersOpen, setAdvancedFiltersOpen] = useState(false);
  const [filterPrice, setFilterPrice] = useState<PriceFilter>("todos");
  const [filterMinPrice, setFilterMinPrice] = useState<string>("");
  const [filterMaxPrice, setFilterMaxPrice] = useState<string>("");
  const [filterStock, setFilterStock] = useState<StockFilter>("todos");
  const [filterFiscal, setFilterFiscal] = useState<FiscalFilter>("todos");
  const [filterCategory, setFilterCategory] = useState<string>("todas");
  const [filterSupplier, setFilterSupplier] = useState<string>("todos");
  const [filterLocation, setFilterLocation] = useState<string>("");
  const [filterValidity, setFilterValidity] = useState<ValidityFilter>("todos");

  const locationOptions = useMemo(() => {
    const locs = new Set<string>();
    for (const p of products) {
      if (p.localizacaoEstoque) locs.add(p.localizacaoEstoque);
    }
    return Array.from(locs).sort();
  }, [products]);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [cloneSourceName, setCloneSourceName] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [bulkDeleting, setBulkDeleting] = useState(false);
  const [deletingProductIds, setDeletingProductIds] = useState<Set<string>>(() => new Set());
  const [form, setForm] = useState<ProductFormData>(EMPTY_FORM);

  // Inline table editing
  const [inlineEditCell, setInlineEditCell] = useState<{ id: string; field: string } | null>(null);
  const [inlineEditValue, setInlineEditValue] = useState("");
  const [inlineSaving, setInlineSaving] = useState(false);
  const inlineInputRef = useRef<HTMLInputElement | null>(null);

  const [gondolaModalOpen, setGondolaModalOpen] = useState(false);
  const [gondolaInitialProducts, setGondolaInitialProducts] = useState<Product[]>([]);
  const [importMenuOpen, setImportMenuOpen] = useState(false);
  const importMenuRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!importMenuOpen) return;
    const handleClickOutside = (event: MouseEvent) => {
      if (importMenuRef.current && !importMenuRef.current.contains(event.target as Node)) {
        setImportMenuOpen(false);
      }
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") setImportMenuOpen(false);
    };
    document.addEventListener("mousedown", handleClickOutside);
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("mousedown", handleClickOutside);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [importMenuOpen]);

  const [categories, setCategories] = useState<CategoriaArvore[]>([]);

  const loadSuppliers = () => {
    supplierService
      .list()
      .then((items) =>
        setSupplierOptions(
          items
            .map((item) => item.fantasyName || item.companyName)
            .filter((supplierName) => supplierName.trim().length > 0),
        ),
      )
      .catch(() => setSupplierOptions([]));
  };

  const loadCategories = () => {
    categoriaService
      .list()
      .then(setCategories)
      .catch(() => {
        setCategories([]);
      });
  };

  useEffect(() => {
    loadSuppliers();
    loadCategories();
  }, []);

  const flatCategoryOptions = useMemo(() => {
    const result: { id: string; nome: string }[] = [];
    function traverse(list: CategoriaArvore[], prefix = "") {
      for (const item of list) {
        const fullLabel = prefix ? `${prefix} > ${item.nome}` : item.nome;
        result.push({ id: item.id, nome: fullLabel });
        if (item.subcategorias && item.subcategorias.length > 0) {
          traverse(item.subcategorias, fullLabel);
        }
      }
    }
    traverse(categories);
    return result;
  }, [categories]);

  const metrics = useMemo(() => {
    let noSalePrice = 0;
    let noCostPrice = 0;
    let lowStock = 0;
    let zeroStock = 0;
    let noGtin = 0;
    let noCategory = 0;
    let noNcm = 0;

    for (const p of products) {
      const sale = parseMoneyBr(p.productSalePrice || "0");
      const cost = parseMoneyBr(p.productUnitPrice || "0");
      const current = parseMoneyBr(p.productQnt || "0");
      const min = parseMoneyBr(p.estoqueMinimo || "0");
      const gtin = (p.gtin || "").trim().toUpperCase();
      const ncm = (p.ncm || "").trim();

      if (sale <= 0) noSalePrice++;
      if (cost <= 0) noCostPrice++;
      if (min > 0 && current <= min) lowStock++;
      if (current <= 0) zeroStock++;
      if (!gtin || gtin === "SEM GTIN") noGtin++;
      if (!p.categoriaId && !p.categoriaNome) noCategory++;
      if (!ncm || ncm === "00000000") noNcm++;
    }

    return {
      noSalePrice,
      noCostPrice,
      lowStock,
      zeroStock,
      noGtin,
      noCategory,
      noNcm,
    };
  }, [products, parseMoneyBr]);

  const activeFiltersCount = useMemo(() => {
    let count = 0;
    if (filterPrice !== "todos") count++;
    if (filterMinPrice.trim() !== "") count++;
    if (filterMaxPrice.trim() !== "") count++;
    if (filterStock !== "todos") count++;
    if (filterFiscal !== "todos") count++;
    if (filterCategory !== "todas") count++;
    if (filterSupplier !== "todos") count++;
    if (filterLocation.trim() !== "") count++;
    if (filterValidity !== "todos") count++;
    return count;
  }, [
    filterPrice,
    filterMinPrice,
    filterMaxPrice,
    filterStock,
    filterFiscal,
    filterCategory,
    filterSupplier,
    filterLocation,
    filterValidity,
  ]);

  const clearAllFilters = () => {
    setSearch("");
    setFilterPrice("todos");
    setFilterMinPrice("");
    setFilterMaxPrice("");
    setFilterStock("todos");
    setFilterFiscal("todos");
    setFilterCategory("todas");
    setFilterSupplier("todos");
    setFilterLocation("");
    setFilterValidity("todos");
    setCurrentPage(1);
    setSelectedProductIds(new Set());
  };

  const filteredProducts = useMemo(() => {
    const normalized = search.trim().toLowerCase();
    const minPriceNum = filterMinPrice.trim() ? parseMoneyBr(filterMinPrice) : null;
    const maxPriceNum = filterMaxPrice.trim() ? parseMoneyBr(filterMaxPrice) : null;

    return products.filter((product) => {
      // 1. Busca textual ampla
      if (normalized) {
        const matchName = product.productName.toLowerCase().includes(normalized);
        const matchCode = product.productCode.toLowerCase().includes(normalized);
        const matchGtin = (product.gtin || "").toLowerCase().includes(normalized);
        const matchSupplier = (product.productSupplier || "").toLowerCase().includes(normalized);
        const matchCategory = (product.categoriaNome || "").toLowerCase().includes(normalized);
        if (!matchName && !matchCode && !matchGtin && !matchSupplier && !matchCategory) {
          return false;
        }
      }

      // 2. Preço de venda e custo
      const salePrice = parseMoneyBr(product.productSalePrice || "0");
      const costPrice = parseMoneyBr(product.productUnitPrice || "0");
      const lucro = parseMoneyBr(product.lucro || "0");

      if (filterPrice === "sem-preco-venda" && salePrice > 0) return false;
      if (filterPrice === "com-preco-venda" && salePrice <= 0) return false;
      if (filterPrice === "sem-preco-custo" && costPrice > 0) return false;
      if (filterPrice === "margem-zerada-negativa" && lucro > 0) return false;

      // Faixa de preço de venda
      if (minPriceNum !== null && salePrice < minPriceNum) return false;
      if (maxPriceNum !== null && salePrice > maxPriceNum) return false;

      // 3. Estoque
      const minStock = parseMoneyBr(product.estoqueMinimo || "0");
      const currentStock = parseMoneyBr(product.productQnt || "0");

      if (filterStock === "estoque-baixo") {
        if (minStock <= 0 || currentStock > minStock) return false;
      } else if (filterStock === "sem-estoque") {
        if (currentStock > 0) return false;
      } else if (filterStock === "com-estoque") {
        if (currentStock <= 0) return false;
      }

      // 4. Fiscal / Cadastro
      const gtinVal = (product.gtin || "").trim().toUpperCase();
      const hasGtin = gtinVal.length > 0 && gtinVal !== "SEM GTIN";
      const ncmVal = (product.ncm || "").trim();
      const hasNcm = ncmVal.length > 0 && ncmVal !== "00000000";
      const isWeight = (product.unidadeComercial || "").toUpperCase() === "KG" || isFractionableUnit(product.unidadeComercial || "");

      if (filterFiscal === "sem-gtin" && hasGtin) return false;
      if (filterFiscal === "sem-ncm" && hasNcm) return false;
      if (filterFiscal === "balanca" && !isWeight) return false;
      if (filterFiscal === "unidade" && isWeight) return false;

      // 5. Categoria
      if (filterCategory === "sem-categoria") {
        if (product.categoriaId || product.categoriaNome) return false;
      } else if (filterCategory !== "todas") {
        if (product.categoriaId !== filterCategory && product.categoriaNome !== filterCategory) {
          return false;
        }
      }

      // 6. Fornecedor
      if (filterSupplier === "sem-fornecedor") {
        if (product.productSupplier && product.productSupplier.trim() !== "") return false;
      } else if (filterSupplier !== "todos") {
        if (product.productSupplier !== filterSupplier) return false;
      }

      // 7. Localização
      if (filterLocation && product.localizacaoEstoque !== filterLocation) {
        return false;
      }

      // 8. Validade
      if (filterValidity === "com-controle" && !product.controlaValidade) {
        return false;
      }
      if (filterValidity === "vencidos-alerta") {
        if (!product.controlaValidade) return false;
        const alertDays = product.diasAlertaValidade ?? 15;
        if (product.diasRestantes === null || product.diasRestantes === undefined || product.diasRestantes > alertDays) {
          return false;
        }
      }

      return true;
    });
  }, [
    products,
    search,
    filterPrice,
    filterMinPrice,
    filterMaxPrice,
    filterStock,
    filterFiscal,
    filterCategory,
    filterSupplier,
    filterLocation,
    filterValidity,
    parseMoneyBr,
  ]);

  const totalPages = Math.max(1, Math.ceil(filteredProducts.length / itemsPerPage));
  const safeCurrentPage = Math.min(currentPage, totalPages);
  const paginatedProducts = useMemo(() => {
    const start = (safeCurrentPage - 1) * itemsPerPage;
    return filteredProducts.slice(start, start + itemsPerPage);
  }, [filteredProducts, itemsPerPage, safeCurrentPage]);
  const selectedProductsOnPage = paginatedProducts.filter((product) =>
    selectedProductIds.has(product.id),
  );
  const allProductsOnPageSelected =
    paginatedProducts.length > 0 && selectedProductsOnPage.length === paginatedProducts.length;

  const toggleProductSelection = (productId: string) => {
    setSelectedProductIds((current) => {
      const next = new Set(current);
      if (next.has(productId)) {
        next.delete(productId);
      } else {
        next.add(productId);
      }
      return next;
    });
  };

  const toggleCurrentPageSelection = () => {
    setSelectedProductIds((current) => {
      const next = new Set(current);
      if (allProductsOnPageSelected) {
        paginatedProducts.forEach((product) => next.delete(product.id));
      } else {
        paginatedProducts.forEach((product) => next.add(product.id));
      }
      return next;
    });
  };

  const openCreateDrawer = () => {
    setEditingId(null);
    setCloneSourceName(null);
    setForm(EMPTY_FORM);
    setDrawerOpen(true);
  };

  // ---------------------------------------------------------------------------
  // Inline cell editing (click-to-edit on table)
  // ---------------------------------------------------------------------------

  const startInlineEdit = (product: Product, field: string) => {
    const value =
      field === "productQnt" ? product.productQnt
      : field === "productSalePrice" ? product.productSalePrice
      : field === "productUnitPrice" ? product.productUnitPrice
      : field === "margemDesejadaPercentual" ? (product.margemDesejadaPercentual ?? "")
      : "";
    setInlineEditCell({ id: product.id, field });
    setInlineEditValue(value);
    setTimeout(() => inlineInputRef.current?.select(), 0);
  };

  const cancelInlineEdit = () => {
    setInlineEditCell(null);
    setInlineEditValue("");
  };

  const saveInlineEdit = async () => {
    if (!inlineEditCell || inlineSaving) return;
    const product = products.find((p) => p.id === inlineEditCell.id);
    if (!product) return;

    const { field } = inlineEditCell;
    const currentValue =
      field === "productQnt" ? product.productQnt
      : field === "productSalePrice" ? product.productSalePrice
      : field === "productUnitPrice" ? product.productUnitPrice
      : field === "margemDesejadaPercentual" ? (product.margemDesejadaPercentual ?? "")
      : "";

    // No change — just close
    if (inlineEditValue === currentValue) {
      cancelInlineEdit();
      return;
    }

    setInlineSaving(true);
    try {
      const payload: ProductFormData = { ...product };
      if (field === "productQnt") {
        payload.productQnt = inlineEditValue;
      } else if (field === "productSalePrice") {
        payload.productSalePrice = inlineEditValue;
      } else if (field === "productUnitPrice") {
        payload.productUnitPrice = inlineEditValue;
        // Se tem margem desejada, recalcula preço de venda a partir do novo custo
        const margem = parseMoneyBr(product.margemDesejadaPercentual ?? "0");
        if (margem > 0) {
          const novoCusto = parseMoneyBr(inlineEditValue);
          payload.productSalePrice = formatMoneyBr(novoCusto * (1 + margem / 100));
        }
      } else if (field === "margemDesejadaPercentual") {
        payload.margemDesejadaPercentual = inlineEditValue || null;
        // Recalcula preço de venda = custo * (1 + margem/100)
        const margem = parseMoneyBr(inlineEditValue || "0");
        if (margem > 0) {
          const custo = parseMoneyBr(product.productUnitPrice);
          payload.productSalePrice = formatMoneyBr(custo * (1 + margem / 100));
        }
      }

      const updated = await productService.update(product.id, payload);
      if (updated) {
        setProducts((cur) => cur.map((p) => (p.id === product.id ? updated : p)));
        Toast.success("Atualizado!");
      }
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao salvar.");
    } finally {
      setInlineSaving(false);
      cancelInlineEdit();
    }
  };

  const handleInlineKeyDown = (e: ReactKeyboardEvent) => {
    if (e.key === "Enter") { e.preventDefault(); void saveInlineEdit(); }
    else if (e.key === "Escape") cancelInlineEdit();
  };

  const openEditDrawer = (product: Product) => {
    setEditingId(product.id);
    setCloneSourceName(null);
    setForm({ ...product });
    setDrawerOpen(true);
  };

  // Clona o produto como um cadastro novo, mantendo tributação fiscal, categoria, fornecedor, preços etc.
  // Só limpa o que identifica o item (código, GTIN) ou é específico do lote (quantidade, validade).
  const openCloneDrawer = (product: Product) => {
    setEditingId(null);
    setCloneSourceName(product.productName);
    const cloned: Partial<Product> = { ...product };
    delete cloned.id; // não pode ir no payload de criação
    setForm({
      ...(cloned as ProductFormData),
      productCode: "",
      gtin: "SEM GTIN",
      productQnt: "0",
      totalPriceOnProduct: "",
      dataValidade: "",
      diasRestantes: null,
    });
    setDrawerOpen(true);
  };

  const handleOpenGondolaModal = (selectedOnly = false) => {
    if (selectedOnly && selectedProductIds.size > 0) {
      const selectedItems = products.filter((p) => selectedProductIds.has(p.id));
      setGondolaInitialProducts(selectedItems);
    } else {
      setGondolaInitialProducts([]);
    }
    setGondolaModalOpen(true);
  };

  const handlePrintSingleGondolaLabel = (product: Product) => {
    setGondolaInitialProducts([product]);
    setGondolaModalOpen(true);
  };

  const handleDelete = async (product: Product) => {
    const confirmed = await statusDialog.confirm(
      `Deseja excluir o produto "${product.productName}"?`,
    );
    if (!confirmed) return;
    setDeletingProductIds((current) => new Set(current).add(product.id));
    try {
      await productService.remove(product.id);
      setProducts((current) => current.filter((item) => item.id !== product.id));
      setSelectedProductIds((current) => {
        const next = new Set(current);
        next.delete(product.id);
        return next;
      });
      statusDialog.success("Produto excluído com sucesso.");
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao excluir produto.");
    } finally {
      setDeletingProductIds((current) => {
        const next = new Set(current);
        next.delete(product.id);
        return next;
      });
    }
  };

  const handleBulkDelete = async () => {
    const selectedIds = Array.from(selectedProductIds);
    if (selectedIds.length === 0) return;

    const confirmed = await statusDialog.confirm(
      `Excluir ${selectedIds.length} produto(s) selecionado(s)?`,
    );
    if (!confirmed) return;

    setBulkDeleting(true);
    setDeletingProductIds((current) => new Set([...current, ...selectedIds]));
    const results = await Promise.allSettled(
      selectedIds.map(async (productId) => {
        await productService.remove(productId);
        return productId;
      }),
    );
    setBulkDeleting(false);
    setDeletingProductIds((current) => {
      const next = new Set(current);
      selectedIds.forEach((productId) => next.delete(productId));
      return next;
    });
    const removedIds = results
      .filter((result): result is PromiseFulfilledResult<string> => result.status === "fulfilled")
      .map((result) => result.value);

    if (removedIds.length > 0) {
      const removedIdSet = new Set(removedIds);
      setProducts((current) => current.filter((product) => !removedIdSet.has(product.id)));
      setSelectedProductIds((current) => {
        const next = new Set(current);
        removedIds.forEach((productId) => next.delete(productId));
        return next;
      });
    }

    const failedCount = selectedIds.length - removedIds.length;
    if (failedCount > 0) {
      Toast.error(`${failedCount} produto(s) não puderam ser excluído(s).`);
      return;
    }

    Toast.success("Produtos selecionados excluídos com sucesso.");
  };

  const handleCreateSupplier = async (draft: QuickSupplierDraft) => {
    const payload: SupplierPayload = {
      companyName: draft.companyName.trim(),
      fantasyName: draft.fantasyName.trim(),
      cnpj: draft.cnpj.trim(),
      cep: draft.cep.trim(),
      city: draft.city.trim(),
      state: draft.state.trim(),
      address: draft.address.trim(),
      neighborhood: draft.neighborhood.trim(),
      streetComplement: draft.streetComplement.trim(),
      number: draft.number.trim(),
      referencePoint: draft.referencePoint.trim(),
      telephone: draft.telephone.trim(),
      cellphone: draft.cellphone.trim(),
      email: draft.email.trim(),
    };

    try {
      const created = await supplierService.create(payload);
      if (!created) return null;

      const supplierName = created.fantasyName || created.companyName;
      setSupplierOptions((current) =>
        current.includes(supplierName) ? current : [supplierName, ...current],
      );
      Toast.success("Fornecedor cadastrado com sucesso.");
      return supplierName;
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao cadastrar fornecedor.");
      return null;
    }
  };

  const validateForm = () => {
    const requiredFields: Array<keyof ProductFormData> = [
      "productName",
      "productUnitPrice",
      "productSalePrice",
    ];

    const missing = requiredFields.some((field) => !String(form[field]).trim());
    if (missing) {
      Toast.error("Preencha a descrição, o valor de custo e o valor de venda.");
      return false;
    }

    if (parseMoneyBr(form.productUnitPrice) <= 0) {
      Toast.error("O valor de custo deve ser maior que zero.");
      return false;
    }

    if (parseMoneyBr(form.productSalePrice) <= 0) {
      Toast.error("O valor de venda deve ser maior que zero.");
      return false;
    }

    if (parseMoneyBr(form.productQnt || "0") < 0) {
      Toast.error("A quantidade do produto não pode ser negativa.");
      return false;
    }

    return true;
  };

  const handleSave = async () => {
    if (!validateForm()) return;

    setSaving(true);
    try {
      if (editingId) {
        const updated = await productService.update(editingId, form);
        if (!updated) return;
        setProducts((current) =>
          current.map((product) => (product.id === editingId ? updated : product)),
        );
        Toast.success("Produto atualizado com sucesso.");
      } else {
        const created = await productService.create(form);
        if (!created) return;
        setProducts((current) => [created, ...current]);
        Toast.success("Produto cadastrado com sucesso.");
      }
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao salvar produto.");
      return;
    } finally {
      setSaving(false);
    }

    setDrawerOpen(false);
    setEditingId(null);
    setCloneSourceName(null);
    setForm(EMPTY_FORM);
  };

  const handleImportarCargaMercado = async () => {
    if (
      !window.confirm(
        "Deseja sincronizar/importar todos os 4.714 produtos do mercado (base exp_cadprodutos.cds)?\n\nTodos os códigos de barras (EAN-13), descrições, unidades e preços de venda serão sincronizados no sistema."
      )
    ) {
      return;
    }

    setIsImportingMercado(true);
    try {
      const res = await productService.importarLegado();
      Toast.success(res?.message || "Carga de 4.714 produtos importada com sucesso no sistema!");
      loadProducts();
    } catch (err) {
      Toast.error(err instanceof Error ? err.message : "Erro ao importar carga de produtos.");
    } finally {
      setIsImportingMercado(false);
    }
  };

  if (fiscalProduct) return (
    <PageLayout className="space-y-4 py-4 md:py-6 lg:py-8">
      <ProductFiscalPanel product={fiscalProduct} onBack={() => setFiscalProduct(null)}
        onEdit={() => { openEditDrawer(fiscalProduct); setFiscalProduct(null); }} />
    </PageLayout>
  );

  if (notasEntradaOpen) return (
    <PageLayout className="space-y-4 py-4 md:space-y-6 md:py-6 lg:py-8">
      <button className="btn-outline-secondary inline-flex min-h-10 items-center justify-center gap-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent disabled:cursor-not-allowed disabled:opacity-50" type="button" onClick={() => setNotasEntradaOpen(false)}>Voltar ao cadastro de produtos</button>
      <NotasEntradaPanel />
    </PageLayout>
  );

  return (
    <PageLayout className="space-y-4 py-4 md:space-y-6 md:py-6 lg:py-8">
      <PageHeader
        title="Cadastro de Produto"
        description="Gerencie o catálogo de produtos, estoque, preços de venda, códigos de barras e tributação fiscal."
        action={
          <div className="flex flex-wrap items-center gap-2.5">
            {/* Menu Dropdown: Importar & Cargas */}
            <div className="relative" ref={importMenuRef}>
              <button
                type="button"
                onClick={() => setImportMenuOpen((prev) => !prev)}
                className={`btn-secondary inline-flex items-center gap-2 font-medium transition-all ${
                  importMenuOpen ? "border-accent text-accent shadow-sm" : ""
                }`}
                aria-expanded={importMenuOpen}
                aria-haspopup="true"
                title="Opções de importação de notas fiscais e cargas em lote"
              >
                <UploadCloud size={16} className={importMenuOpen ? "text-accent" : "text-text-secondary"} />
                <span>Importar / Cargas</span>
                <ChevronDown
                  size={14}
                  className={`transition-transform duration-200 ${importMenuOpen ? "rotate-180 text-accent" : "text-text-tertiary"}`}
                />
              </button>

              {importMenuOpen && (
                <div className="absolute right-0 top-full z-50 mt-1.5 w-80 rounded-xl border border-border-primary bg-bg-light p-2 shadow-2xl backdrop-blur-md animate-in fade-in zoom-in-95">
                  <div className="px-2.5 py-1.5 text-[11px] font-semibold uppercase tracking-wider text-text-tertiary">
                    Entrada & Importação
                  </div>

                  <button type="button" className="flex w-full items-start gap-3 rounded-lg p-2.5 text-left transition hover:bg-accent/10"
                    onClick={() => { setImportMenuOpen(false); setNotasEntradaOpen(true); }}>
                    <FileUp size={18} className="mt-1 shrink-0 text-accent" />
                    <div><div className="text-sm font-semibold text-text-primary">Notas de entrada</div><div className="text-xs text-text-secondary">Histórico, itens recebidos e XMLs armazenados</div></div>
                  </button>
                  {/* Entrada de NF-e */}
                  <button
                    type="button"
                    onClick={() => {
                      setImportMenuOpen(false);
                      setImportModalOpen(true);
                    }}
                    className="flex w-full items-start gap-3 rounded-lg p-2.5 text-left transition hover:bg-accent/10"
                  >
                    <span className="mt-0.5 inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-accent/15 text-accent">
                      <FileUp size={16} />
                    </span>
                    <div className="min-w-0 flex-1">
                      <div className="text-xs font-semibold text-text-primary">
                        Entrada de NF-e / NFC-e
                      </div>
                      <div className="text-[11px] text-text-secondary">
                        NF-e por chave; NFC-e por XML ou itens do cupom
                      </div>
                    </div>
                  </button>

                  <div className="my-1.5 border-t border-border-primary/60" />

                  <div className="px-2.5 py-1 text-[11px] font-semibold uppercase tracking-wider text-text-tertiary">
                    Cargas Rápidas em Lote
                  </div>

                  {/* Carga Balança */}
                  <button
                    type="button"
                    onClick={() => {
                      setImportMenuOpen(false);
                      setBalancaModalOpen(true);
                    }}
                    className="flex w-full items-start gap-3 rounded-lg p-2.5 text-left transition hover:bg-hover-light"
                  >
                    <span className="mt-0.5 inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-secondary/15 text-secondary">
                      <Scale size={16} />
                    </span>
                    <div className="min-w-0 flex-1">
                      <div className="text-xs font-semibold text-text-primary">
                        Carga Balança Toledo (133 produtos)
                      </div>
                      <div className="text-[11px] text-text-secondary">
                        Sincronizar produtos pesáveis e códigos PLU
                      </div>
                    </div>
                  </button>

                  {/* Carga Mercado */}
                  <button
                    type="button"
                    onClick={() => {
                      setImportMenuOpen(false);
                      void handleImportarCargaMercado();
                    }}
                    disabled={isImportingMercado}
                    className="flex w-full items-start gap-3 rounded-lg p-2.5 text-left transition hover:bg-hover-light disabled:opacity-60"
                  >
                    <span className="mt-0.5 inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-primary/15 text-primary">
                      {isImportingMercado ? (
                        <Loader2 size={16} className="animate-spin text-primary" />
                      ) : (
                        <Database size={16} />
                      )}
                    </span>
                    <div className="min-w-0 flex-1">
                      <div className="text-xs font-semibold text-text-primary">
                        {isImportingMercado ? "Importando base..." : "Carga Mercado (4.714 produtos)"}
                      </div>
                      <div className="text-[11px] text-text-secondary">
                        Sincronizar catálogo da base exp_cadprodutos
                      </div>
                    </div>
                  </button>
                </div>
              )}
            </div>

            {/* Etiquetas de Gôndola */}
            <button
              type="button"
              onClick={() => handleOpenGondolaModal(selectedProductIds.size > 0)}
              className="btn-outline-secondary inline-flex min-h-10 items-center justify-center gap-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent disabled:cursor-not-allowed disabled:opacity-50 inline-flex items-center gap-2 font-medium"
              title="Gerar e imprimir etiquetas de gôndola/prateleira (A4 Pimaco ou Bobina Térmica)"
            >
              <Tag size={16} className="text-text-secondary" />
              <span>Etiquetas de Gôndola</span>
              {selectedProductIds.size > 0 && (
                <span className="inline-flex h-5 items-center justify-center rounded-full bg-accent/20 px-2 text-xs font-bold text-accent">
                  {selectedProductIds.size}
                </span>
              )}
            </button>

            {/* Novo Produto (Ação Primária) */}
            <button
              type="button"
              onClick={openCreateDrawer}
              className="btn-primary inline-flex items-center gap-2 font-semibold shadow-md"
            >
              <Plus size={16} />
              <span>Novo produto</span>
            </button>
          </div>
        }
      />

      {balancaModalOpen ? (
        <BalancaImportModal
          existingProducts={products}
          onClose={() => setBalancaModalOpen(false)}
          onImported={() => {
            loadProducts();
          }}
        />
      ) : null}

      {importModalOpen ? (
        <NfeImportModal
          onClose={() => setImportModalOpen(false)}
          onImported={() => {
            loadProducts();
            loadSuppliers();
          }}
        />
      ) : null}

      {gondolaModalOpen ? (
        <GondolaLabelModal
          initialProducts={gondolaInitialProducts}
          allProducts={products}
          onClose={() => setGondolaModalOpen(false)}
        />
      ) : null}

      <section className="card p-4 md:p-5 space-y-3.5">
        {/* Linha superior: Input de busca + Botão de Filtros Avançados + Botão Limpar */}
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div className="relative flex-1 max-w-xl">
            <Search
              size={16}
              className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-text-tertiary"
            />
            <input
              value={search}
              onChange={(event) => {
                setSearch(event.target.value);
                setCurrentPage(1);
                setSelectedProductIds(new Set());
              }}
              className="input-field w-full pl-9 pr-8"
              placeholder="Pesquise por nome, código, barras/GTIN ou categoria..."
              aria-label="Buscar produtos por descrição, código ou categoria"
            />
            {search && (
              <button
                type="button"
                onClick={() => {
                  setSearch("");
                  setCurrentPage(1);
                }}
                className="absolute right-2.5 top-1/2 -translate-y-1/2 text-text-tertiary hover:text-text-primary transition"
                title="Limpar busca"
              >
                <X size={14} />
              </button>
            )}
          </div>

          <div className="flex items-center gap-2 shrink-0">
            <button
              type="button"
              onClick={() => setAdvancedFiltersOpen(!advancedFiltersOpen)}
              className={`inline-flex items-center gap-2 rounded-xl border px-3.5 py-2 text-xs font-semibold transition-all shadow-sm ${
                advancedFiltersOpen || activeFiltersCount > 0
                  ? "border-primary bg-primary/10 text-primary dark:text-accent shadow-primary/10"
                  : "border-border-primary bg-bg-surface text-text-secondary hover:text-text-primary hover:border-border-secondary"
              }`}
              title="Abrir opções de filtros avançados"
            >
              <SlidersHorizontal size={14} className={advancedFiltersOpen || activeFiltersCount > 0 ? "text-primary dark:text-accent" : "text-text-tertiary"} />
              <span>Filtros Avançados</span>
              {activeFiltersCount > 0 && (
                <span className="inline-flex items-center justify-center rounded-full bg-primary text-white text-[10px] font-bold h-4.5 min-w-[18px] px-1">
                  {activeFiltersCount}
                </span>
              )}
              {advancedFiltersOpen ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
            </button>

            {(activeFiltersCount > 0 || search) && (
              <button
                type="button"
                onClick={clearAllFilters}
                className="inline-flex items-center gap-1.5 rounded-xl border border-red-500/20 bg-red-500/10 px-3 py-2 text-xs font-semibold text-red-600 dark:text-red-400 hover:bg-red-500/20 transition-all shrink-0"
                title="Limpar todos os filtros e busca"
              >
                <RotateCcw size={13} />
                <span className="hidden sm:inline">Limpar</span>
              </button>
            )}
          </div>
        </div>

        {/* Chips de Filtros Rápidos (Acesso com 1 clique) */}
        <div className="flex flex-wrap items-center gap-2 pt-1 border-t border-border-primary/60 text-xs">
          <span className="text-[11px] font-medium text-text-tertiary uppercase tracking-wider shrink-0 mr-1 flex items-center gap-1">
            <Filter size={12} />
            Filtros Rápidos:
          </span>

          {/* 1. Sem Preço de Venda */}
          <button
            type="button"
            onClick={() => {
              setFilterPrice((cur) => (cur === "sem-preco-venda" ? "todos" : "sem-preco-venda"));
              setCurrentPage(1);
            }}
            className={`inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1 text-xs font-medium transition-all ${
              filterPrice === "sem-preco-venda"
                ? "border-red-500 bg-red-500/20 text-red-600 dark:text-red-400 font-semibold ring-2 ring-red-500/20"
                : metrics.noSalePrice > 0
                ? "border-red-500/30 bg-red-500/10 text-red-600 dark:text-red-400 hover:bg-red-500/15"
                : "border-border-primary bg-bg-surface text-text-secondary hover:text-text-primary"
            }`}
            title="Mostrar produtos que ainda não possuem preço de venda cadastrado"
          >
            <DollarSign size={13} className={filterPrice === "sem-preco-venda" || metrics.noSalePrice > 0 ? "text-red-500" : "text-text-tertiary"} />
            Sem Preço de Venda ({metrics.noSalePrice})
          </button>

          {/* 2. Sem Preço de Custo */}
          <button
            type="button"
            onClick={() => {
              setFilterPrice((cur) => (cur === "sem-preco-custo" ? "todos" : "sem-preco-custo"));
              setCurrentPage(1);
            }}
            className={`inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1 text-xs font-medium transition-all ${
              filterPrice === "sem-preco-custo"
                ? "border-amber-500 bg-amber-500/20 text-amber-600 dark:text-amber-400 font-semibold ring-2 ring-amber-500/20"
                : "border-border-primary bg-bg-surface text-text-secondary hover:text-text-primary"
            }`}
            title="Mostrar produtos que não possuem preço de custo informado"
          >
            Sem Preço de Custo ({metrics.noCostPrice})
          </button>

          {/* 3. Estoque Baixo */}
          <button
            type="button"
            onClick={() => {
              setFilterStock((cur) => (cur === "estoque-baixo" ? "todos" : "estoque-baixo"));
              setCurrentPage(1);
            }}
            className={`inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1 text-xs font-medium transition-all ${
              filterStock === "estoque-baixo"
                ? "border-amber-500 bg-amber-500/20 text-amber-600 dark:text-amber-400 font-semibold ring-2 ring-amber-500/20"
                : "border-border-primary bg-bg-surface text-text-secondary hover:text-text-primary"
            }`}
            title="Mostrar produtos com estoque atual menor ou igual ao estoque mínimo"
          >
            <AlertTriangle size={13} className={filterStock === "estoque-baixo" ? "text-amber-500" : "text-text-tertiary"} />
            Estoque Baixo ({metrics.lowStock})
          </button>

          {/* 4. Zerados no Estoque */}
          <button
            type="button"
            onClick={() => {
              setFilterStock((cur) => (cur === "sem-estoque" ? "todos" : "sem-estoque"));
              setCurrentPage(1);
            }}
            className={`inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1 text-xs font-medium transition-all ${
              filterStock === "sem-estoque"
                ? "border-orange-500 bg-orange-500/20 text-orange-600 dark:text-orange-400 font-semibold ring-2 ring-orange-500/20"
                : "border-border-primary bg-bg-surface text-text-secondary hover:text-text-primary"
            }`}
            title="Mostrar produtos com estoque zerado ou negativo"
          >
            <PackageX size={13} className={filterStock === "sem-estoque" ? "text-orange-500" : "text-text-tertiary"} />
            Sem Estoque ({metrics.zeroStock})
          </button>

          {/* 5. Sem Código de Barras / GTIN */}
          <button
            type="button"
            onClick={() => {
              setFilterFiscal((cur) => (cur === "sem-gtin" ? "todos" : "sem-gtin"));
              setCurrentPage(1);
            }}
            className={`inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1 text-xs font-medium transition-all ${
              filterFiscal === "sem-gtin"
                ? "border-sky-500 bg-sky-500/20 text-sky-600 dark:text-sky-400 font-semibold ring-2 ring-sky-500/20"
                : "border-border-primary bg-bg-surface text-text-secondary hover:text-text-primary"
            }`}
            title="Mostrar produtos sem código de barras (GTIN)"
          >
            <Barcode size={13} className={filterFiscal === "sem-gtin" ? "text-sky-500" : "text-text-tertiary"} />
            Sem GTIN ({metrics.noGtin})
          </button>

          {/* 6. Sem Categoria */}
          <button
            type="button"
            onClick={() => {
              setFilterCategory((cur) => (cur === "sem-categoria" ? "todas" : "sem-categoria"));
              setCurrentPage(1);
            }}
            className={`inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1 text-xs font-medium transition-all ${
              filterCategory === "sem-categoria"
                ? "border-purple-500 bg-purple-500/20 text-purple-600 dark:text-purple-400 font-semibold ring-2 ring-purple-500/20"
                : "border-border-primary bg-bg-surface text-text-secondary hover:text-text-primary"
            }`}
            title="Mostrar produtos sem categoria vinculada"
          >
            Sem Categoria ({metrics.noCategory})
          </button>
        </div>

        {/* Painel Expansível de Filtros Avançados */}
        {advancedFiltersOpen && (
          <div className="rounded-2xl border border-border-primary/80 bg-bg-secondary/40 p-4 space-y-4 animate-in fade-in duration-200">
            <div className="flex flex-col gap-1 sm:flex-row sm:items-center sm:justify-between border-b border-border-primary/60 pb-3">
              <div className="flex items-center gap-2">
                <SlidersHorizontal size={16} className="text-primary" />
                <h4 className="text-sm font-semibold text-text-primary">Filtros Avançados Detalhados</h4>
                <span className="text-xs text-text-tertiary">
                  ({filteredProducts.length} de {products.length} produtos exibidos)
                </span>
              </div>
              {activeFiltersCount > 0 && (
                <button
                  type="button"
                  onClick={clearAllFilters}
                  className="inline-flex items-center gap-1 text-xs text-red-500 hover:text-red-600 font-semibold transition"
                >
                  <RotateCcw size={12} />
                  Redefinir filtros
                </button>
              )}
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3.5">
              {/* 1. Preço de Venda / Custo */}
              <div className="space-y-1">
                <label className="text-xs font-medium text-text-secondary">Situação de Preço</label>
                <select
                  aria-label="Situação de preço"
                  value={filterPrice}
                  onChange={(e) => {
                    setFilterPrice(e.target.value as PriceFilter);
                    setCurrentPage(1);
                  }}
                  className="input-field h-9 text-xs w-full rounded-xl"
                >
                  <option value="todos">Todos os preços</option>
                  <option value="sem-preco-venda">⚠️ Sem Preço de Venda (R$ 0,00)</option>
                  <option value="com-preco-venda">✅ Com Preço de Venda (&gt; R$ 0,00)</option>
                  <option value="sem-preco-custo">Sem Preço de Custo (R$ 0,00)</option>
                  <option value="margem-zerada-negativa">Margem ou Lucro ≤ 0%</option>
                </select>
              </div>

              {/* 2. Faixa de Preço de Venda */}
              <div className="space-y-1">
                <label className="text-xs font-medium text-text-secondary">Faixa de Preço Venda (R$)</label>
                <div className="flex items-center gap-1.5">
                  <input
                    type="text"
                    inputMode="decimal"
                    placeholder="Mín 0,00"
                    aria-label="Preço de venda mínimo"
                    value={filterMinPrice}
                    onChange={(e) => {
                      setFilterMinPrice(maskMoneyBr(e.target.value));
                      setCurrentPage(1);
                    }}
                    className="input-field h-9 text-xs w-full rounded-xl"
                  />
                  <span className="text-xs text-text-tertiary">até</span>
                  <input
                    type="text"
                    inputMode="decimal"
                    placeholder="Máx 0,00"
                    aria-label="Preço de venda máximo"
                    value={filterMaxPrice}
                    onChange={(e) => {
                      setFilterMaxPrice(maskMoneyBr(e.target.value));
                      setCurrentPage(1);
                    }}
                    className="input-field h-9 text-xs w-full rounded-xl"
                  />
                </div>
              </div>

              {/* 3. Situação do Estoque */}
              <div className="space-y-1">
                <label className="text-xs font-medium text-text-secondary">Situação do Estoque</label>
                <select
                  aria-label="Situação do estoque"
                  value={filterStock}
                  onChange={(e) => {
                    setFilterStock(e.target.value as StockFilter);
                    setCurrentPage(1);
                  }}
                  className="input-field h-9 text-xs w-full rounded-xl"
                >
                  <option value="todos">Todos os estoques</option>
                  <option value="estoque-baixo">⚠️ Estoque Baixo (≤ Mínimo)</option>
                  <option value="sem-estoque">📦 Sem Estoque / Zerado (≤ 0)</option>
                  <option value="com-estoque">🟢 Com Estoque Positivo (&gt; 0)</option>
                </select>
              </div>

              {/* 4. Cadastro & Fiscal */}
              <div className="space-y-1">
                <label className="text-xs font-medium text-text-secondary">Cadastro & Fiscal</label>
                <select
                  aria-label="Cadastro e dados fiscais"
                  value={filterFiscal}
                  onChange={(e) => {
                    setFilterFiscal(e.target.value as FiscalFilter);
                    setCurrentPage(1);
                  }}
                  className="input-field h-9 text-xs w-full rounded-xl"
                >
                  <option value="todos">Todos os cadastros</option>
                  <option value="sem-gtin">🏷️ Sem Código de Barras (GTIN)</option>
                  <option value="sem-ncm">📄 Sem NCM cadastrado</option>
                  <option value="balanca">⚖️ Apenas Balança / Pesáveis (KG)</option>
                  <option value="unidade">📦 Apenas Unidade (UN)</option>
                </select>
              </div>

              {/* 5. Categoria / Departamento */}
              <div className="space-y-1">
                <label className="text-xs font-medium text-text-secondary">Categoria / Departamento</label>
                <select
                  aria-label="Categoria do produto"
                  value={filterCategory}
                  onChange={(e) => {
                    setFilterCategory(e.target.value);
                    setCurrentPage(1);
                  }}
                  className="input-field h-9 text-xs w-full rounded-xl"
                >
                  <option value="todas">Todas as categorias</option>
                  <option value="sem-categoria">⚠️ Sem Categoria Vinculada</option>
                  {flatCategoryOptions.map((cat) => (
                    <option key={cat.id} value={cat.id}>
                      {cat.nome}
                    </option>
                  ))}
                </select>
              </div>

              {/* 6. Fornecedor */}
              <div className="space-y-1">
                <label className="text-xs font-medium text-text-secondary">Fornecedor</label>
                <select
                  aria-label="Fornecedor do produto"
                  value={filterSupplier}
                  onChange={(e) => {
                    setFilterSupplier(e.target.value);
                    setCurrentPage(1);
                  }}
                  className="input-field h-9 text-xs w-full rounded-xl"
                >
                  <option value="todos">Todos os fornecedores</option>
                  <option value="sem-fornecedor">⚠️ Sem Fornecedor</option>
                  {supplierOptions.map((supp) => (
                    <option key={supp} value={supp}>
                      {supp}
                    </option>
                  ))}
                </select>
              </div>

              {/* 7. Localização de Estoque */}
              <div className="space-y-1">
                <label className="text-xs font-medium text-text-secondary">Localização / Gôndola</label>
                <select
                  aria-label="Localização do estoque"
                  value={filterLocation}
                  onChange={(e) => {
                    setFilterLocation(e.target.value);
                    setCurrentPage(1);
                  }}
                  className="input-field h-9 text-xs w-full rounded-xl"
                >
                  <option value="">Todas localizações</option>
                  {locationOptions.map((loc) => (
                    <option key={loc} value={loc}>
                      {loc}
                    </option>
                  ))}
                </select>
              </div>

              {/* 8. Validade */}
              <div className="space-y-1">
                <label className="text-xs font-medium text-text-secondary">Controle de Validade</label>
                <select
                  aria-label="Controle de validade"
                  value={filterValidity}
                  onChange={(e) => {
                    setFilterValidity(e.target.value as ValidityFilter);
                    setCurrentPage(1);
                  }}
                  className="input-field h-9 text-xs w-full rounded-xl"
                >
                  <option value="todos">Todas situações</option>
                  <option value="com-controle">Apenas com controle ativo</option>
                  <option value="vencidos-alerta">⚠️ Vencidos ou em Alerta</option>
                </select>
              </div>
            </div>
          </div>
        )}
      </section>

      <section className="card overflow-hidden">
        {selectedProductIds.size > 0 ? (
          <div className="flex flex-col gap-2 border-b border-border-primary bg-primary/8 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
            <p className="text-sm font-semibold text-text-primary">
              {selectedProductIds.size} produto(s) selecionado(s)
            </p>
            <div className="flex flex-wrap items-center gap-2">
              <button
                type="button"
                onClick={() => handleOpenGondolaModal(true)}
                className="btn-outline-secondary inline-flex min-h-10 items-center justify-center gap-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent disabled:cursor-not-allowed disabled:opacity-50 inline-flex items-center justify-center gap-2 text-xs"
                title="Imprimir etiquetas de gôndola para os produtos selecionados"
              >
                <Tag size={14} />
                Imprimir Etiquetas ({selectedProductIds.size})
              </button>
              <LoadingButton
                type="button"
                onClick={handleBulkDelete}
                isLoading={bulkDeleting}
                loadingLabel="Excluindo..."
                className="btn-cancel inline-flex items-center justify-center gap-2"
              >
                <Trash2 size={15} />
                Excluir selecionados
              </LoadingButton>
            </div>
          </div>
        ) : null}
        <TableScrollArea label="Produtos" className="overflow-x-auto">
          <table className="w-full min-w-[1200px] text-sm">
            <thead className="bg-bg-primary text-left text-text-secondary">
              <tr>
                <th className="w-12 px-4 py-3">
                  <input
                    type="checkbox"
                    checked={allProductsOnPageSelected}
                    onChange={toggleCurrentPageSelection}
                    aria-label="Selecionar produtos desta página"
                    className="h-4 w-4 rounded border-border-secondary accent-accent"
                  />
                </th>
                <th className="px-4 py-3">Imagem</th>
                <th className="px-4 py-3">Produto</th>
                <th className="px-4 py-3">Código</th>
                <th className="px-4 py-3">Fornecedor</th>
                <th className="px-4 py-3">Quantidade</th>
                <th className="px-4 py-3">Preço Custo</th>
                <th className="px-4 py-3">Preço Venda</th>
                <th className="px-4 py-3">Margem %</th>
                <th className="px-4 py-3">Lucro R$</th>
                <th className="px-4 py-3">Ações</th>
              </tr>
            </thead>
            <tbody>
              {loadingProducts || productsError || paginatedProducts.length === 0 ? (
                <tr>
                  <td colSpan={11}>
                    <ListState loading={loadingProducts} error={Boolean(productsError)}
                      title={loadingProducts ? "Carregando produtos…" : productsError || (activeFiltersCount > 0 || search ? "Nenhum produto encontrado" : "Nenhum produto cadastrado")}
                      description={productsError ? "Verifique a conexão e tente novamente." : loadingProducts ? undefined : activeFiltersCount > 0 || search ? "Tente outra pesquisa ou remova os filtros." : "Informe descrição, custo e venda para cadastrar o primeiro produto."}
                      actionLabel={loadingProducts ? undefined : productsError ? "Tentar novamente" : activeFiltersCount > 0 || search ? "Limpar todos os filtros" : "Cadastrar primeiro produto"}
                      onAction={loadingProducts ? undefined : productsError ? () => void loadProducts() : activeFiltersCount > 0 || search ? clearAllFilters : openCreateDrawer} />
                  </td>
                </tr>
              ) : (
                paginatedProducts.map((product) => (
                <tr key={product.id} className="border-t border-border-primary">
                  <td className="px-4 py-3">
                    <input
                      type="checkbox"
                      checked={selectedProductIds.has(product.id)}
                      onChange={() => toggleProductSelection(product.id)}
                      aria-label={`Selecionar ${product.productName}`}
                      className="h-4 w-4 rounded border-border-secondary accent-accent"
                    />
                  </td>
                  <td className="px-4 py-3">
                    <div className="h-12 w-12 overflow-hidden rounded-lg border border-border-primary bg-bg-light">
                      {product.productImageUrl ? (
                        <img
                          src={product.productImageUrl}
                          alt={product.productName}
                          className="h-full w-full object-cover"
                        />
                      ) : (
                        <div className="flex h-full w-full items-center justify-center text-[10px] text-text-tertiary">
                          Sem
                        </div>
                      )}
                    </div>
                  </td>
                  <td className="px-4 py-3">
                    <div className="font-medium text-text-primary">{product.productName}</div>
                    {product.categoriaNome ? (
                      <span className="mt-0.5 inline-block rounded border border-border-secondary bg-bg-surface px-1.5 py-0.5 text-[11px] text-text-tertiary">
                        {product.categoriaNome}
                      </span>
                    ) : null}
                    {product.controlaValidade && product.dataValidade ? (() => {
                      const diffDays = product.diasRestantes ?? 0;
                      if (diffDays < 0) {
                        return (
                          <span className="mt-0.5 ml-1 inline-block rounded bg-red-500/15 px-1.5 py-0.5 text-[10px] font-semibold text-red-600 dark:text-red-400">
                            Vencido
                          </span>
                        );
                      }
                      if (diffDays <= (product.diasAlertaValidade || 15)) {
                        return (
                          <span className="mt-0.5 ml-1 inline-block rounded bg-amber-500/15 px-1.5 py-0.5 text-[10px] font-medium text-amber-600 dark:text-amber-400">
                            Vence em {diffDays}d
                          </span>
                        );
                      }
                      return (
                        <span className="mt-0.5 ml-1 inline-block rounded bg-emerald-500/10 px-1.5 py-0.5 text-[10px] text-emerald-600 dark:text-emerald-400">
                          Val: {new Date(product.dataValidade + "T00:00:00").toLocaleDateString("pt-BR")}
                        </span>
                      );
                    })() : null}
                  </td>
                  <td className="px-4 py-3">{product.productCode}</td>
                  <td className="px-4 py-3">{product.productSupplier}</td>
                  {/* Quantidade — click-to-edit */}
                  <td className="px-4 py-3">
                    {inlineEditCell?.id === product.id && inlineEditCell.field === "productQnt" ? (
                      <input
                        ref={inlineInputRef}
                        type="text"
                        inputMode="decimal"
                        value={inlineEditValue}
                        onChange={(e) => setInlineEditValue(sanitizeDecimalInput(e.target.value, 4).replace(".", ","))}
                        onBlur={() => void saveInlineEdit()}
                        onKeyDown={handleInlineKeyDown}
                        disabled={inlineSaving}
                        className="input-field w-24 text-right text-sm font-semibold"
                        autoFocus
                      />
                    ) : (
                      <div
                        className="cursor-pointer rounded px-1 py-0.5 transition hover:bg-accent/10"
                        onClick={() => startInlineEdit(product, "productQnt")}
                        title="Clique para editar quantidade"
                      >
                        <span className="font-semibold text-text-primary">
                          {product.productQnt} {product.unidadeComercial}
                        </span>
                        {parseMoneyBr(product.estoqueMinimo || "0") > 0 &&
                        parseMoneyBr(product.productQnt || "0") <= parseMoneyBr(product.estoqueMinimo || "0") ? (
                          <span className="mt-1 block w-fit rounded bg-amber-500/15 px-1.5 py-0.5 text-[10px] font-medium text-amber-600 dark:text-amber-400">
                            Abaixo do mín.
                          </span>
                        ) : null}
                      </div>
                    )}
                  </td>
                  {/* Preço Custo — click-to-edit */}
                  <td className="px-4 py-3">
                    {inlineEditCell?.id === product.id && inlineEditCell.field === "productUnitPrice" ? (
                      <input
                        ref={inlineInputRef}
                        type="text"
                        inputMode="decimal"
                        value={inlineEditValue}
                        onChange={(e) => setInlineEditValue(maskMoneyBr(e.target.value))}
                        onBlur={() => void saveInlineEdit()}
                        onKeyDown={handleInlineKeyDown}
                        disabled={inlineSaving}
                        className="input-field w-24 text-right text-sm"
                        autoFocus
                      />
                    ) : (
                      <span
                        className="cursor-pointer rounded px-1 py-0.5 text-text-secondary transition hover:bg-accent/10"
                        onClick={() => startInlineEdit(product, "productUnitPrice")}
                        title="Clique para editar preço de custo"
                      >
                        {product.productUnitPrice}
                      </span>
                    )}
                  </td>
                  {/* Preço Venda — click-to-edit */}
                  <td className="px-4 py-3">
                    {inlineEditCell?.id === product.id && inlineEditCell.field === "productSalePrice" ? (
                      <input
                        ref={inlineInputRef}
                        type="text"
                        inputMode="decimal"
                        value={inlineEditValue}
                        onChange={(e) => setInlineEditValue(maskMoneyBr(e.target.value))}
                        onBlur={() => void saveInlineEdit()}
                        onKeyDown={handleInlineKeyDown}
                        disabled={inlineSaving}
                        className="input-field w-24 text-right text-sm font-semibold"
                        autoFocus
                      />
                    ) : (
                      <span
                        className="cursor-pointer rounded px-1 py-0.5 font-semibold text-text-primary transition hover:bg-accent/10"
                        onClick={() => startInlineEdit(product, "productSalePrice")}
                        title="Clique para editar preço de venda"
                      >
                        {parseMoneyBr(product.productSalePrice || "0") <= 0 ? (
                          <span
                            className="inline-flex items-center gap-1 rounded-md bg-red-500/15 border border-red-500/30 px-2 py-0.5 text-xs font-bold text-red-600 dark:text-red-400"
                            title="Produto sem preço de venda definido! Clique para cadastrar"
                          >
                            <AlertTriangle size={12} />
                            Sem Preço
                          </span>
                        ) : (
                          product.productSalePrice
                        )}
                      </span>
                    )}
                  </td>
                  {/* Margem Desejada — click-to-edit */}
                  <td className="px-4 py-3">
                    {inlineEditCell?.id === product.id && inlineEditCell.field === "margemDesejadaPercentual" ? (
                      <input
                        ref={inlineInputRef}
                        type="text"
                        inputMode="decimal"
                        value={inlineEditValue}
                        onChange={(e) => setInlineEditValue(sanitizeDecimalInput(e.target.value, 2).replace(".", ","))}
                        onBlur={() => void saveInlineEdit()}
                        onKeyDown={handleInlineKeyDown}
                        disabled={inlineSaving}
                        className="input-field w-20 text-right text-sm"
                        placeholder="—"
                        autoFocus
                      />
                    ) : (
                      <span
                        className="cursor-pointer rounded px-1 py-0.5 transition hover:bg-accent/10"
                        onClick={() => startInlineEdit(product, "margemDesejadaPercentual")}
                        title="Clique para editar margem desejada"
                      >
                        {product.margemDesejadaPercentual ? `${product.margemDesejadaPercentual}%` : (
                          <span className="text-text-tertiary">—</span>
                        )}
                      </span>
                    )}
                  </td>
                  {/* Lucro R$ (somente leitura — calculado pelo backend) */}
                  <td className="px-4 py-3">
                    <span className={`font-semibold ${parseMoneyBr(product.lucro || "0") > 0 ? "text-emerald-600 dark:text-emerald-400" : parseMoneyBr(product.lucro || "0") < 0 ? "text-red-500" : "text-text-secondary"}`}>
                      {product.lucro || "0,00"}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    <RowActionsMenu
                      items={[
                        {
                          key: "etiqueta",
                          label: "Imprimir Etiqueta",
                          icon: <Tag size={13} />,
                          onClick: () => handlePrintSingleGondolaLabel(product),
                        },
                        {
                          key: "fiscal",
                          label: "Verificar situação fiscal",
                          icon: <FileCheck2 size={13} />,
                          onClick: () => setFiscalProduct(product),
                        },
                        {
                          key: "edit",
                          label: "Editar",
                          icon: <Pencil size={13} />,
                          onClick: () => openEditDrawer(product),
                        },
                        {
                          key: "clone",
                          label: "Clonar produto",
                          icon: <Copy size={13} />,
                          onClick: () => openCloneDrawer(product),
                        },
                        {
                          key: "delete",
                          label: "Excluir",
                          icon: <Trash2 size={13} />,
                          onClick: () => handleDelete(product),
                          loading: deletingProductIds.has(product.id),
                          loadingLabel: "Excluindo...",
                          danger: true,
                        },
                      ]}
                    />
                  </td>
                </tr>
              ))
              )}
            </tbody>
          </table>
        </TableScrollArea>
        <div className="px-4 py-4">
          <TablePagination
            totalItems={filteredProducts.length}
            currentPage={safeCurrentPage}
            itemsPerPage={itemsPerPage}
            onPageChange={setCurrentPage}
            onItemsPerPageChange={(value) => {
              setItemsPerPage(value);
              setCurrentPage(1);
              setSelectedProductIds(new Set());
            }}
          />
        </div>
      </section>

      <ProductFormDrawer
        open={drawerOpen}
        isEditMode={editingId !== null}
        cloneSourceName={cloneSourceName}
        value={form}
        isSaving={saving}
        onClose={() => setDrawerOpen(false)}
        onChange={setForm}
        onSave={handleSave}
        supplierOptions={supplierOptions}
        onCreateSupplier={handleCreateSupplier}
        categories={categories}
      />
      {statusDialog.Dialog}
    </PageLayout>
  );
}
