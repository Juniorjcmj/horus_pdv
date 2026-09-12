/**
 * Arquivo: src/pages/Admin/SalesStartPage.tsx
 * Objetivo: implementa a frente de caixa com busca de produto, carrinho, fechamento e pagamento.
 * Entradas esperadas: recebe flag opcional de modo standalone para ajustar comportamento da aba PDV.
 */

import {
  Image as ImageIcon,
  Loader2,
  Maximize2,
  Minimize2,
  Plus,
  Printer,
  Search,
  Trash2,
  X,
} from "lucide-react";
import {
  type ClipboardEvent,
  type FormEvent,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { SearchableSelectField } from "@/components/Form";
import { Toast, useStatusDialog } from "@/hooks/Dialog";
import useInputMasks from "@/hooks/InputMasks/useInputMasks";
import {
  cashRegisterService,
  type CashRegisterStatusDto,
} from "@/services/api/cashRegisterService";
import ReceiptPreviewModal, {
  type PaymentType,
  type SaleReceipt,
} from "@/components/Admin/ReceiptPreviewModal";
import { companyService, type CompanyDto } from "@/services/api/companyService";
import {
  FISCAL_STATUS,
  fiscalService,
  type FiscalDocumentDetailDto,
} from "@/services/api/fiscalService";
import { pedidoService, type PedidoDto } from "@/services/api/pedidoService";
import { productService } from "@/services/api/productService";
import { salesHistoryService } from "@/services/api/salesHistoryService";
import { parseBalancaBarcode } from "@/utils/balancaBarcode";
import { getPrintPreviewEnabled } from "@/utils/pdvPreferences";

type SalesStartPageProps = {
  onExit?: () => void;
  standalone?: boolean;
  operatorName?: string;
};

type Product = {
  id: string;
  name: string;
  code: string;
  stock: number;
  salePrice: number;
  imageUrl?: string;
  unit: string;
};

// Produtos vendidos por peso/volume aceitam quantidade fracionada na NFC-e (ex.: 0,452 kg).
// "UN" (unidade) continua com o stepper inteiro de sempre.
function isFractionableUnit(unit: string) {
  return unit.trim().toUpperCase() !== "UN";
}

// Formata quantidade pt-BR sem casas decimais desnecessárias (10 -> "10", 0.452 -> "0,452").
function formatQuantityDisplay(value: number) {
  return value.toFixed(4).replace(/0+$/, "").replace(/\.$/, "").replace(".", ",") || "0";
}

type CartItem = {
  id: string;
  code: string;
  name: string;
  quantity: number;
  unitPrice: number;
};

const PAYMENT_OPTIONS: Array<{ value: PaymentType; label: string }> = [
  { value: "dinheiro", label: "Dinheiro" },
  { value: "pix", label: "PIX" },
  { value: "debito", label: "Cartão Débito" },
  { value: "credito", label: "Cartão Crédito" },
];

const LAST_RECEIPT_STORAGE_KEY = "horus-pdv-last-receipt";

function formatDateTime(date: Date) {
  return {
    dateLabel: date.toLocaleDateString("pt-BR", {
      day: "2-digit",
      month: "long",
      year: "numeric",
    }),
    timeLabel: date.toLocaleTimeString("pt-BR", {
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    }),
  };
}

function preventNonDigitBeforeInput(event: FormEvent<HTMLInputElement>) {
  const data = (event.nativeEvent as InputEvent).data ?? "";
  if (data && /\D/.test(data)) {
    event.preventDefault();
  }
}

function getPaymentLabel(paymentType: PaymentType) {
  return PAYMENT_OPTIONS.find((option) => option.value === paymentType)?.label ?? paymentType;
}

function formatCashElapsed(minutes?: number) {
  if (!minutes || minutes < 1) return "menos de 1 min";
  const hours = Math.floor(minutes / 60);
  const remainingMinutes = minutes % 60;
  if (hours === 0) return `${remainingMinutes} min`;
  return `${hours}h ${String(remainingMinutes).padStart(2, "0")}min`;
}

type SplitPayment = {
  id: string;
  paymentType: PaymentType;
  amount: number;
  cashGiven?: number;
  changeAmount?: number;
};

export default function SalesStartPage({
  standalone = false,
  operatorName = "Operador",
}: SalesStartPageProps) {
  const { formatMoneyBr, maskMoneyBr, parseMoneyBr, sanitizeIntegerInput, sanitizeDecimalInput } =
    useInputMasks();
  const statusDialog = useStatusDialog();
  const productInputRef = useRef<HTMLInputElement | null>(null);
  const qtyInputRef = useRef<HTMLInputElement | null>(null);

  const [now, setNow] = useState(new Date());
  const [productSearch, setProductSearch] = useState("");
  const [products, setProducts] = useState<Product[]>([]);
  const [company, setCompany] = useState<CompanyDto | null>(null);
  const [cashStatus, setCashStatus] = useState<CashRegisterStatusDto | null>(null);
  const [selectedProductId, setSelectedProductId] = useState("");
  const [showProductOptions, setShowProductOptions] = useState(false);
  const [highlightedProductIndex, setHighlightedProductIndex] = useState(0);
  const [quantityInput, setQuantityInput] = useState("1");
  const [cart, setCart] = useState<CartItem[]>([]);

  // Pedido montado pelo vendedor (ver NovoPedidoPage) e localizado aqui pelo número — o
  // carrinho fica travado (preço e itens vêm congelados do pedido) até finalizar ou soltar.
  const [activePedido, setActivePedido] = useState<PedidoDto | null>(null);
  const [pedidoNumberInput, setPedidoNumberInput] = useState("");
  const [loadingPedido, setLoadingPedido] = useState(false);
  const cartLocked = activePedido !== null;

  const [checkoutOpen, setCheckoutOpen] = useState(false);
  const [payments, setPayments] = useState<SplitPayment[]>([]);
  const [currentPaymentType, setCurrentPaymentType] = useState<PaymentType>("dinheiro");
  const [currentPaymentAmount, setCurrentPaymentAmount] = useState("");
  const [currentCashGiven, setCurrentCashGiven] = useState("");
  const [cpfNota, setCpfNota] = useState("");
  const [lastReceipt, setLastReceipt] = useState<SaleReceipt | null>(null);
  const [receiptPreview, setReceiptPreview] = useState<SaleReceipt | null>(null);
  const [printPreviewEnabled, setPrintPreviewEnabled] = useState(() =>
    getPrintPreviewEnabled(),
  );
  const [isConfirmingSale, setIsConfirmingSale] = useState(false);

  const pasteCurrentCashGiven = (event: ClipboardEvent<HTMLInputElement>) => {
    event.preventDefault();
    setCurrentCashGiven(maskMoneyBr(event.clipboardData.getData("text")));
  };

  const selectedProduct = useMemo(
    () => products.find((item) => item.id === selectedProductId) ?? null,
    [products, selectedProductId],
  );

  const quantityUnit = selectedProduct?.unit ?? "UN";
  const quantityIsFractionable = isFractionableUnit(quantityUnit);

  const quantity = useMemo(() => {
    const parsed = Number(quantityInput);
    if (!Number.isFinite(parsed) || parsed <= 0) return quantityIsFractionable ? 0 : 1;
    return quantityIsFractionable ? parsed : Math.floor(parsed);
  }, [quantityInput, quantityIsFractionable]);

  const filteredProducts = useMemo(() => {
    const normalized = productSearch.trim().toLowerCase();
    if (!normalized) return products;
    return products.filter(
      (item) =>
        item.name.toLowerCase().includes(normalized) ||
        item.code.toLowerCase().includes(normalized),
    );
  }, [products, productSearch]);

  const subtotal = useMemo(
    () => cart.reduce((sum, item) => sum + item.unitPrice * item.quantity, 0),
    [cart],
  );
  const totalVolumes = useMemo(
    () => cart.reduce((sum, item) => sum + item.quantity, 0),
    [cart],
  );

  const totalPaid = useMemo(
    () => payments.reduce((sum, item) => sum + item.amount, 0),
    [payments],
  );
  const remainingToPay = useMemo(
    () => Math.max(0, subtotal - totalPaid),
    [subtotal, totalPaid],
  );
  const currentPaymentAmountValue = parseMoneyBr(currentPaymentAmount || "0");
  const currentCashGivenValue = parseMoneyBr(currentCashGiven || "0");
  const currentChangeValue =
    currentPaymentType === "dinheiro"
      ? Math.max(0, currentCashGivenValue - currentPaymentAmountValue)
      : 0;

  const currentCoversRemaining = useMemo(() => {
    if (remainingToPay <= 0.001) return false;
    return (
      currentPaymentAmountValue > 0 &&
      Math.abs(currentPaymentAmountValue - remainingToPay) <= 0.01 &&
      (currentPaymentType !== "dinheiro" || currentCashGivenValue >= currentPaymentAmountValue - 0.009)
    );
  }, [
    remainingToPay,
    currentPaymentAmountValue,
    currentPaymentType,
    currentCashGivenValue,
  ]);

  const canConfirmPayment = useMemo(() => {
    if (cart.length === 0) return false;
    if (payments.length === 0) {
      if (currentPaymentAmountValue < subtotal - 0.009) return false;
      if (currentPaymentType === "dinheiro" && currentCashGivenValue < subtotal - 0.009) return false;
      return true;
    }
    if (remainingToPay <= 0.001) return true;
    return currentCoversRemaining;
  }, [
    cart.length,
    payments.length,
    remainingToPay,
    currentCoversRemaining,
    currentPaymentAmountValue,
    currentCashGivenValue,
    currentPaymentType,
    subtotal,
  ]);

  const effectiveTotalPaid = useMemo(() => {
    if (payments.length === 0) {
      return Math.min(subtotal, currentPaymentAmountValue);
    }
    if (remainingToPay <= 0.001) return totalPaid;
    return totalPaid + Math.min(remainingToPay, currentPaymentAmountValue);
  }, [payments.length, subtotal, currentPaymentAmountValue, remainingToPay, totalPaid]);

  const effectiveRemainingToPay = useMemo(() => {
    return Math.max(0, subtotal - effectiveTotalPaid);
  }, [subtotal, effectiveTotalPaid]);

  const totalChangeValue = useMemo(() => {
    const fromList = payments.reduce((sum, item) => sum + (item.changeAmount ?? 0), 0);
    if (currentPaymentType === "dinheiro") {
      if (payments.length === 0) {
        return Math.max(0, currentCashGivenValue - subtotal);
      }
      if (remainingToPay > 0.001) {
        return fromList + Math.max(0, currentCashGivenValue - currentPaymentAmountValue);
      }
    }
    return fromList;
  }, [payments, currentPaymentType, currentCashGivenValue, currentPaymentAmountValue, remainingToPay, subtotal]);

  const activeProductName =
    cart.length > 0 ? cart[cart.length - 1].name : selectedProduct?.name ?? "";

  const previewProduct = useMemo(() => {
    if (selectedProduct) return selectedProduct;
    const lastItem = cart[cart.length - 1];
    if (!lastItem) return null;
    return products.find((item) => item.id === lastItem.id) ?? null;
  }, [selectedProduct, products, cart]);

  const loadProducts = useCallback(async () => {
    const items = await productService.list();
    setProducts(
      items.map((item) => ({
        id: item.id,
        name: item.productName,
        code: item.productCode,
        stock: parseMoneyBr(item.productQnt || "0"),
        salePrice: parseMoneyBr(item.productSalePrice || "0"),
        imageUrl: item.productImageUrl,
        unit: item.unidadeComercial || "UN",
      })),
    );
  }, [parseMoneyBr]);

  const loadCashStatus = useCallback(async () => {
    const status = await cashRegisterService.status();
    setCashStatus(status ?? null);
    return status ?? null;
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    loadProducts().catch(() => {
      Toast.error("Não foi possível carregar produtos da API no PDV.");
    });
  }, [loadProducts]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    loadCashStatus().catch(() => {
      setCashStatus(null);
      Toast.error("Não foi possível validar a abertura de caixa.");
    });
  }, [loadCashStatus]);

  useEffect(() => {
    companyService
      .get()
      .then((data) => {
        if (data) setCompany(data);
      })
      .catch(() => {
        Toast.error("Não foi possível carregar dados da empresa no PDV.");
      });
  }, []);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    try {
      const storedReceipt = window.localStorage.getItem(LAST_RECEIPT_STORAGE_KEY);
      if (!storedReceipt) return;
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setLastReceipt(JSON.parse(storedReceipt) as SaleReceipt);
    } catch {
      window.localStorage.removeItem(LAST_RECEIPT_STORAGE_KEY);
    }
  }, []);

  useEffect(() => {
    const syncPrintPreviewPreference = () => setPrintPreviewEnabled(getPrintPreviewEnabled());
    const onCustomPreferenceChange = (event: Event) => {
      const enabled = (event as CustomEvent<{ enabled?: boolean }>).detail?.enabled;
      setPrintPreviewEnabled(typeof enabled === "boolean" ? enabled : getPrintPreviewEnabled());
    };

    window.addEventListener("focus", syncPrintPreviewPreference);
    window.addEventListener("storage", syncPrintPreviewPreference);
    window.addEventListener("horus-pdv-print-preview-change", onCustomPreferenceChange);

    return () => {
      window.removeEventListener("focus", syncPrintPreviewPreference);
      window.removeEventListener("storage", syncPrintPreviewPreference);
      window.removeEventListener("horus-pdv-print-preview-change", onCustomPreferenceChange);
    };
  }, []);

  useEffect(() => {
    if (!standalone) return;
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", onBeforeUnload);
    return () => window.removeEventListener("beforeunload", onBeforeUnload);
  }, [standalone]);

  const [isFullscreen, setIsFullscreen] = useState(
    typeof document !== "undefined" ? Boolean(document.fullscreenElement) : false,
  );

  useEffect(() => {
    const handleFullscreenChange = () => {
      setIsFullscreen(Boolean(document.fullscreenElement));
    };
    document.addEventListener("fullscreenchange", handleFullscreenChange);
    return () => {
      document.removeEventListener("fullscreenchange", handleFullscreenChange);
    };
  }, []);

  const toggleFullscreen = useCallback(async () => {
    try {
      if (!document.fullscreenElement) {
        await document.documentElement.requestFullscreen();
      } else {
        await document.exitFullscreen();
      }
    } catch (err) {
      console.warn("Fullscreen toggle error:", err);
    }
  }, []);

  useEffect(() => {
    const handleF11 = (event: KeyboardEvent) => {
      if (event.key === "F11") {
        event.preventDefault();
        void toggleFullscreen();
      }
    };
    window.addEventListener("keydown", handleF11);
    return () => window.removeEventListener("keydown", handleF11);
  }, [toggleFullscreen]);

  // Ao abrir a frente de caixa, no primeiro clique ou tecla do operador,
  // expande automaticamente para tela cheia caso o navegador ainda não esteja nela.
  useEffect(() => {
    const enterFullscreenOnFirstGesture = () => {
      if (!document.fullscreenElement) {
        document.documentElement.requestFullscreen().catch(() => {});
      }
    };

    window.addEventListener("click", enterFullscreenOnFirstGesture, { once: true });
    window.addEventListener("keydown", enterFullscreenOnFirstGesture, { once: true });

    return () => {
      window.removeEventListener("click", enterFullscreenOnFirstGesture);
      window.removeEventListener("keydown", enterFullscreenOnFirstGesture);
    };
  }, []);

  useEffect(() => {
    if (filteredProducts.length === 0) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setHighlightedProductIndex(-1);
      return;
    }
    setHighlightedProductIndex((current) => {
      if (current < 0) return 0;
      if (current >= filteredProducts.length) return filteredProducts.length - 1;
      return current;
    });
  }, [filteredProducts]);

  // Compartilhada entre "adicionar item" manual e a leitura de código de barras de balança
  // (peso variável) — ambos os fluxos acabam no mesmo carrinho, com a mesma checagem de estoque.
  const addProductToCart = useCallback((product: Product, quantityToAdd: number) => {
    if (quantityToAdd <= 0) {
      Toast.error("Informe uma quantidade maior que zero.");
      return false;
    }
    if (quantityToAdd > product.stock) {
      Toast.error(`Estoque insuficiente. Disponível: ${formatQuantityDisplay(product.stock)}.`);
      return false;
    }

    let added = true;
    setCart((current) => {
      const existing = current.find((item) => item.id === product.id);
      if (!existing) {
        return [
          ...current,
          {
            id: product.id,
            code: product.code,
            name: product.name,
            quantity: quantityToAdd,
            unitPrice: product.salePrice,
          },
        ];
      }
      const nextQuantity = existing.quantity + quantityToAdd;
      if (nextQuantity > product.stock) {
        Toast.error(`Estoque insuficiente para ${product.name}.`);
        added = false;
        return current;
      }
      return current.map((item) =>
        item.id === product.id ? { ...item, quantity: nextQuantity } : item,
      );
    });

    return added;
  }, []);

  const selectProductOption = useCallback(
    (product: Product) => {
      if (cartLocked) {
        Toast.error("Este carrinho veio de um pedido — solte o pedido para adicionar itens à mão.");
        return;
      }

      const qty = quantity > 0 ? quantity : 1;
      if (!addProductToCart(product, qty)) return;

      setSelectedProductId("");
      setProductSearch("");
      setShowProductOptions(false);
      setHighlightedProductIndex(0);
      setQuantityInput("1");
      productInputRef.current?.focus();
    },
    [addProductToCart, cartLocked, quantity],
  );

  const addItem = useCallback(() => {
    if (cartLocked) {
      Toast.error("Este carrinho veio de um pedido — solte o pedido para adicionar itens à mão.");
      return;
    }

    const matchedFromSearch =
      selectedProduct ??
      filteredProducts.find(
        (item) =>
          item.name.toLowerCase() === productSearch.trim().toLowerCase() ||
          item.code.toLowerCase() === productSearch.trim().toLowerCase(),
      ) ??
      filteredProducts[0];

    if (!matchedFromSearch) {
      Toast.error("Selecione um produto.");
      return;
    }

    if (!addProductToCart(matchedFromSearch, quantity)) return;

    setSelectedProductId("");
    setProductSearch("");
    setShowProductOptions(false);
    setQuantityInput("1");
    productInputRef.current?.focus();
  }, [addProductToCart, cartLocked, filteredProducts, productSearch, quantity, selectedProduct]);

  // Leitura de etiqueta de balança (código de barras EAN-13 de peso variável): decodifica o
  // PLU + peso e adiciona direto ao carrinho, sem passar pelos campos de quantidade manual.
  const addFromBalancaBarcode = useCallback(
    (code: string) => {
      const decoded = parseBalancaBarcode(code, products);
      if (!decoded) return false;
      if (cartLocked) {
        Toast.error("Este carrinho veio de um pedido — solte o pedido para adicionar itens à mão.");
        return true;
      }

      const product =
        products.find((item) => item.code === decoded.productCode) ??
        products.find((item) => Number(item.code) === Number(decoded.productCode));

      if (!product) {
        Toast.error(`Produto com código de balança "${decoded.productCode}" não encontrado.`);
        return true; // era um código de balança válido — não deve cair na busca de texto
      }

      if (addProductToCart(product, decoded.weightKg)) {
        if (decoded.mode === "price" && decoded.totalPrice) {
          Toast.success(
            `${product.name} — ${formatQuantityDisplay(decoded.weightKg)} kg (R$ ${formatMoneyBr(decoded.totalPrice)}) adicionado.`,
          );
        } else {
          Toast.success(`${product.name} — ${formatQuantityDisplay(decoded.weightKg)} kg adicionado.`);
        }
        setProductSearch("");
        setSelectedProductId("");
        setShowProductOptions(false);
        productInputRef.current?.focus();
      }
      return true;
    },
    [addProductToCart, cartLocked, formatMoneyBr, products],
  );

  const removeItem = (id: string) => {
    if (cartLocked) {
      Toast.error("Este carrinho veio de um pedido — solte o pedido para remover itens.");
      return;
    }
    setCart((current) => current.filter((item) => item.id !== id));
  };

  const loadPedido = async () => {
    const orderNumber = pedidoNumberInput.trim();
    if (!orderNumber) {
      Toast.error("Informe o número do pedido.");
      return;
    }

    setLoadingPedido(true);
    try {
      const pedido = await pedidoService.getByOrderNumber(orderNumber);
      if (!pedido) {
        Toast.error(`Pedido ${orderNumber} não encontrado.`);
        return;
      }
      if (pedido.status !== "aberto") {
        Toast.error(
          pedido.status === "finalizado" ? "Este pedido já foi pago." : "Este pedido foi cancelado.",
        );
        return;
      }

      setCart(
        pedido.itens.map((item) => ({
          id: item.productCode,
          code: item.productCode,
          name: item.productName,
          quantity: item.quantity,
          unitPrice: parseMoneyBr(item.unitPrice),
        })),
      );
      setCpfNota(pedido.customerCpf === "-" ? "" : pedido.customerCpf);
      setActivePedido(pedido);
      setPedidoNumberInput("");
      Toast.success(`Pedido ${pedido.orderNumber} carregado — ${pedido.customerName}.`);
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao buscar pedido.");
    } finally {
      setLoadingPedido(false);
    }
  };

  const clearPedido = () => {
    setActivePedido(null);
    setCart([]);
    setCpfNota("");
  };

  const saveLastReceipt = (receipt: SaleReceipt) => {
    setLastReceipt(receipt);
    try {
      window.localStorage.setItem(LAST_RECEIPT_STORAGE_KEY, JSON.stringify(receipt));
    } catch {
      // Mantem apenas em memoria caso o navegador bloqueie o armazenamento local.
    }
  };

  const printLastSale = () => {
    if (!lastReceipt) {
      Toast.info("Nenhuma venda finalizada nesta estação.");
      return;
    }
    setReceiptPreview(lastReceipt);
  };

  const cancelSale = useCallback(async () => {
    if (cart.length === 0) return;
    const confirmed = await statusDialog.confirm(
      activePedido ? `Soltar o pedido ${activePedido.orderNumber} sem pagar?` : "Cancelar venda atual?",
    );
    if (!confirmed) return;
    setCart([]);
    setActivePedido(null);
    setCpfNota("");
    setCurrentCashGiven("");
    setCheckoutOpen(false);
    Toast.info(activePedido ? "Pedido solto do caixa." : "Venda cancelada.");
  }, [activePedido, cart.length, statusDialog]);

  const openPayment = useCallback(async () => {
    if (cart.length === 0) {
      Toast.error("Adicione ao menos um item.");
      return;
    }

    try {
      const latestCashStatus = await loadCashStatus();
      if (!latestCashStatus?.canSell) {
        Toast.error(
          latestCashStatus?.blockReason || "Abra o caixa antes de iniciar uma venda.",
        );
        return;
      }
    } catch {
      Toast.error("Não foi possível validar a abertura de caixa.");
      return;
    }

    setPayments([]);
    setCurrentPaymentType("dinheiro");
    setCurrentPaymentAmount(formatMoneyBr(subtotal));
    setCurrentCashGiven(formatMoneyBr(subtotal));
    setCheckoutOpen(true);
  }, [cart.length, formatMoneyBr, loadCashStatus, subtotal]);

  const handleAddPayment = () => {
    const amountVal = parseMoneyBr(currentPaymentAmount || "0");
    if (amountVal <= 0) {
      Toast.error("Informe um valor maior que zero para o pagamento.");
      return;
    }

    if (amountVal > remainingToPay + 0.009) {
      Toast.error(
        `Valor de R$ ${formatMoneyBr(amountVal)} excede o saldo restante de R$ ${formatMoneyBr(remainingToPay)}.`,
      );
      return;
    }

    const cashGivenVal =
      currentPaymentType === "dinheiro" ? parseMoneyBr(currentCashGiven || "0") : amountVal;
    if (currentPaymentType === "dinheiro" && cashGivenVal < amountVal) {
      Toast.error("Valor recebido em dinheiro é menor que o valor desta parcela.");
      return;
    }

    const changeVal = currentPaymentType === "dinheiro" ? Math.max(0, cashGivenVal - amountVal) : 0;

    const newPayment: SplitPayment = {
      id: `pay-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
      paymentType: currentPaymentType,
      amount: amountVal,
      cashGiven: cashGivenVal,
      changeAmount: changeVal,
    };

    const nextPayments = [...payments, newPayment];
    setPayments(nextPayments);

    const nextRemaining = Math.max(
      0,
      subtotal - nextPayments.reduce((sum, p) => sum + p.amount, 0),
    );
    setCurrentPaymentAmount(formatMoneyBr(nextRemaining));
    setCurrentCashGiven(formatMoneyBr(nextRemaining));
  };

  const handleRemovePayment = (id: string) => {
    const nextPayments = payments.filter((p) => p.id !== id);
    setPayments(nextPayments);
    const nextRemaining = Math.max(
      0,
      subtotal - nextPayments.reduce((sum, p) => sum + p.amount, 0),
    );
    setCurrentPaymentAmount(formatMoneyBr(nextRemaining));
    setCurrentCashGiven(formatMoneyBr(nextRemaining));
  };

  const confirmPayment = async () => {
    let finalPayments: SplitPayment[] = [...payments];

    // Se nenhuma parcela foi adicionada manualmente à lista, usamos a forma corrente (1 clique ágil)
    if (finalPayments.length === 0) {
      const amountVal = parseMoneyBr(currentPaymentAmount || "0");
      if (amountVal < subtotal - 0.009) {
        Toast.error("Adicione os pagamentos para cobrir o total da venda.");
        return;
      }
      const cashGivenVal =
        currentPaymentType === "dinheiro" ? parseMoneyBr(currentCashGiven || "0") : subtotal;
      if (currentPaymentType === "dinheiro" && cashGivenVal < subtotal) {
        Toast.error("Valor recebido em dinheiro menor que o total da venda.");
        return;
      }
      const changeVal = currentPaymentType === "dinheiro" ? Math.max(0, cashGivenVal - subtotal) : 0;
      finalPayments = [
        {
          id: `pay-${Date.now()}`,
          paymentType: currentPaymentType,
          amount: subtotal,
          cashGiven: cashGivenVal,
          changeAmount: changeVal,
        },
      ];
    } else {
      const currentAmountVal = parseMoneyBr(currentPaymentAmount || "0");
      if (remainingToPay > 0.001 && Math.abs(currentAmountVal - remainingToPay) <= 0.01) {
        const cashGivenVal =
          currentPaymentType === "dinheiro" ? parseMoneyBr(currentCashGiven || "0") : currentAmountVal;
        if (currentPaymentType === "dinheiro" && cashGivenVal < currentAmountVal - 0.009) {
          Toast.error("Valor recebido em dinheiro é menor que o valor desta parcela.");
          return;
        }
        const changeVal = currentPaymentType === "dinheiro" ? Math.max(0, cashGivenVal - currentAmountVal) : 0;
        finalPayments.push({
          id: `pay-${Date.now()}`,
          paymentType: currentPaymentType,
          amount: currentAmountVal,
          cashGiven: cashGivenVal,
          changeAmount: changeVal,
        });
      }

      const totalPaidVal = finalPayments.reduce((sum, p) => sum + p.amount, 0);
      if (Math.abs(totalPaidVal - subtotal) > 0.01) {
        Toast.error(
          `Faltam R$ ${formatMoneyBr(Math.max(0, subtotal - totalPaidVal))} para liquidar a venda.`,
        );
        return;
      }
    }

    if (isConfirmingSale) return;
    setIsConfirmingSale(true);
    try {
      const latestCashStatus = await loadCashStatus();
      if (!latestCashStatus?.canSell) {
        Toast.error(
          latestCashStatus?.blockReason || "Abra o caixa antes de confirmar a venda.",
        );
        return;
      }

      const primaryPaymentType =
        finalPayments.length === 1 ? finalPayments[0].paymentType : "Múltiplo";
      const totalCashGiven = finalPayments.reduce((sum, p) => sum + (p.cashGiven ?? p.amount), 0);
      const totalChange = finalPayments.reduce((sum, p) => sum + (p.changeAmount ?? 0), 0);

      const payloadPayments = finalPayments.map((p) => ({
        paymentType: p.paymentType,
        amount: p.amount,
        cashGiven: p.cashGiven,
        changeAmount: p.changeAmount,
      }));

      const result = activePedido
        ? await pedidoService.finalize(activePedido.orderNumber, primaryPaymentType, payloadPayments)
        : await salesHistoryService.register({
            customerName: "Consumidor",
            customerCpf: cpfNota || "-",
            paymentType: primaryPaymentType,
            totalAmount: formatMoneyBr(subtotal),
            operatorName,
            items: cart.map((item) => ({
              productCode: item.code,
              productName: item.name,
              quantity: item.quantity,
            })),
            payments: payloadPayments,
          });

      const saleNumber = result?.saleNumber || `PDV-${Date.now()}`;

      // Aguarda brevemente a autorização da SEFAZ pelo outbox worker (polling ágil de até 1.5s)
      // Caso ainda não tenha retornado, o ReceiptPreviewModal continuará o polling ao vivo sem travar o operador.
      let fiscalDetail: FiscalDocumentDetailDto | null = null;
      if (result?.saleNumber) {
        for (let attempt = 0; attempt < 3; attempt++) {
          try {
            const doc = await fiscalService.getBySaleNumber(result.saleNumber);
            if (
              doc &&
              (doc.status === FISCAL_STATUS.Autorizado ||
                doc.status === FISCAL_STATUS.ContingenciaPendente)
            ) {
              fiscalDetail = doc;
              break;
            }
          } catch {
            // Ignora falha transitória de rede durante o processamento da nota
          }
          await new Promise((resolve) => setTimeout(resolve, 500));
        }
      }

      const receipt: SaleReceipt = {
        saleNumber,
        issuedAt: new Date().toISOString(),
        company: company
          ? {
              fantasyName: company.fantasyName,
              corporateName: company.corporateName,
              cnpj: company.cnpj,
              stateRegistration: company.stateRegistration,
              address: company.address,
              number: company.number,
              neighborhood: company.neighborhood,
              city: company.city,
              uf: company.uf,
              phone: company.phone,
              sacPhone: company.sacPhone,
              ambienteFiscal: company.ambienteFiscal,
            }
          : null,
        customerCpf: cpfNota || "-",
        paymentType: finalPayments.length === 1 ? finalPayments[0].paymentType : "dinheiro",
        paymentLabel:
          finalPayments.length === 1
            ? getPaymentLabel(finalPayments[0].paymentType)
            : `Múltiplos (${finalPayments.map((p) => getPaymentLabel(p.paymentType)).join(" + ")})`,
        operatorName,
        subtotal,
        cashGiven: totalCashGiven,
        change: totalChange,
        items: cart.map((item) => ({
          ...item,
          total: item.quantity * item.unitPrice,
        })),
        payments: finalPayments.map((p) => ({
          paymentType: p.paymentType,
          paymentLabel: getPaymentLabel(p.paymentType),
          amount: p.amount,
          cashGiven: p.cashGiven,
          changeAmount: p.changeAmount,
        })),
        fiscalDetail,
      };

      setCheckoutOpen(false);
      await loadProducts();
      saveLastReceipt(receipt);
      if (printPreviewEnabled) {
        setReceiptPreview(receipt);
      }
      if (fiscalDetail?.status === FISCAL_STATUS.Autorizado) {
        Toast.success(`Venda ${receipt.saleNumber} confirmada e NFC-e autorizada!`);
      } else {
        Toast.success(`Pagamento confirmado. Venda ${receipt.saleNumber} registrada.`);
      }
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao registrar venda.");
      return;
    } finally {
      setIsConfirmingSale(false);
    }

    setCart([]);
    setActivePedido(null);
    setSelectedProductId("");
    setProductSearch("");
    setShowProductOptions(false);
    setQuantityInput("1");
    setPayments([]);
    setCurrentPaymentType("dinheiro");
    setCurrentPaymentAmount("");
    setCurrentCashGiven("");
    setCpfNota("");
    window.setTimeout(() => productInputRef.current?.focus(), 0);
  };

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.defaultPrevented) return;
      const target = event.target as HTMLElement | null;
      const isInput = target?.tagName === "INPUT";

      if (event.key === "F2") {
        event.preventDefault();
        productInputRef.current?.focus();
      }
      if (event.key === "F4") {
        event.preventDefault();
        qtyInputRef.current?.focus();
      }
      if (event.key === "F8") {
        event.preventDefault();
        void cancelSale();
      }
      if (event.key === "F12") {
        event.preventDefault();
        if (!checkoutOpen) openPayment();
      }
      if (event.key === "Enter" && isInput && !checkoutOpen) {
        if (target === productInputRef.current && showProductOptions) {
          return;
        }
        if (target === productInputRef.current || target === qtyInputRef.current) {
          event.preventDefault();
          addItem();
        }
      }
      if (event.key === "Escape" && checkoutOpen) {
        event.preventDefault();
        setCheckoutOpen(false);
      }
    };

    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [addItem, cancelSale, checkoutOpen, openPayment, quantity, selectedProductId, cart.length, showProductOptions]);

  const { dateLabel, timeLabel } = formatDateTime(now);
  const cashCanSell = cashStatus?.canSell === true;
  const cashLabel = cashStatus
    ? cashCanSell
      ? `Caixa aberto por ${formatCashElapsed(cashStatus.currentSession?.elapsedMinutes)}`
      : cashStatus.blockReason || "Caixa fechado"
    : "Validando caixa...";

  return (
    <div
      className={`h-[100dvh] overflow-y-auto bg-bg-primary ${
        isFullscreen ? "p-0" : "p-1.5 md:overflow-hidden md:p-2"
      }`}
    >
      <div
        className={`mx-auto flex min-h-full w-full ${
          isFullscreen
            ? "max-w-none rounded-none border-0"
            : "max-w-[1600px] rounded-2xl border border-border-primary"
        } flex-col overflow-visible bg-bg-light shadow-md md:h-full md:overflow-hidden`}
      >
        <header className="relative border-b border-border-secondary bg-accent px-4 py-3 text-text-light">
          <div className="flex items-center justify-between">
            <div>
              <h1 className="font-display text-2xl font-bold italic leading-none md:text-4xl">Quack PDV</h1>
              <p className="text-sm italic leading-none md:text-lg">Frente de Caixa</p>
            </div>
            <div className="flex items-center gap-2.5 md:gap-4">
              <button
                type="button"
                onClick={toggleFullscreen}
                title={isFullscreen ? "Sair da tela cheia (F11 ou Esc)" : "Entrar em tela cheia (F11)"}
                className="inline-flex items-center gap-1.5 rounded-lg border border-white/20 bg-white/10 px-3 py-1.5 text-xs font-semibold text-white shadow-sm transition hover:bg-white/20 active:scale-95 focus:outline-none"
              >
                {isFullscreen ? <Minimize2 size={16} /> : <Maximize2 size={16} />}
                <span className="hidden sm:inline">{isFullscreen ? "Sair Tela Cheia" : "Tela Cheia (F11)"}</span>
              </button>
              <div className="text-right text-xs md:text-sm">
                <p className="capitalize">{dateLabel}</p>
                <p className="text-base font-semibold md:text-lg">{timeLabel}</p>
              </div>
            </div>
          </div>
        </header>

        <main className="flex min-h-0 flex-1 flex-col lg:grid lg:grid-cols-[280px_minmax(0,1fr)]">
          <aside className="shrink-0 border-b border-border-primary bg-bg-gray-theme p-3.5 text-text-primary lg:overflow-y-auto lg:border-b-0 lg:border-r">
            {activePedido ? (
              <div className="mb-3 rounded-xl border border-accent/30 bg-accent/10 p-3 text-xs">
                <p className="font-semibold text-text-primary">
                  Pedido {activePedido.orderNumber} — {activePedido.customerName}
                </p>
                <p className="mt-0.5 text-text-secondary">Itens travados: vieram do pedido do vendedor.</p>
                <button
                  type="button"
                  onClick={clearPedido}
                  className="btn-cancel mt-2 h-8 w-full text-xs"
                >
                  Soltar pedido
                </button>
              </div>
            ) : (
              <div className="mb-3 flex gap-2">
                <input
                  value={pedidoNumberInput}
                  onChange={(event) => setPedidoNumberInput(event.target.value.replace(/\D/g, ""))}
                  onKeyDown={(event) => {
                    if (event.key === "Enter") {
                      event.preventDefault();
                      void loadPedido();
                    }
                  }}
                  placeholder="Nº do pedido"
                  inputMode="numeric"
                  className="input-field h-9 flex-1 text-xs"
                />
                <button
                  type="button"
                  onClick={() => void loadPedido()}
                  disabled={loadingPedido}
                  className="btn-outline-secondary h-9 shrink-0 px-3 text-xs disabled:cursor-not-allowed disabled:opacity-60"
                >
                  {loadingPedido ? "..." : "Buscar"}
                </button>
              </div>
            )}

            <label className="mb-2 block">
              <span className="mb-1 block text-xs font-semibold uppercase">Produto:</span>
              <div className="relative">
                <Search
                  size={14}
                  className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-text-tertiary"
                />
                <input
                  ref={productInputRef}
                  value={productSearch}
                  onChange={(event) => {
                    setProductSearch(event.target.value);
                    setSelectedProductId("");
                    setShowProductOptions(true);
                    setHighlightedProductIndex(0);
                  }}
                  onFocus={() => {
                    // Evita reabrir o autocomplete automaticamente após adicionar item no mobile.
                    const hasSearch = productSearch.trim().length > 0;
                    setShowProductOptions(hasSearch);
                    if (hasSearch && filteredProducts.length > 0) setHighlightedProductIndex(0);
                  }}
                  onBlur={() => window.setTimeout(() => setShowProductOptions(false), 120)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter") {
                      event.preventDefault();
                      if (addFromBalancaBarcode(productSearch)) {
                        return;
                      }

                      const product =
                        filteredProducts[highlightedProductIndex] ??
                        filteredProducts.find(
                          (item) =>
                            item.code.toLowerCase() === productSearch.trim().toLowerCase() ||
                            item.name.toLowerCase() === productSearch.trim().toLowerCase(),
                        ) ??
                        products.find(
                          (item) =>
                            item.code.toLowerCase() === productSearch.trim().toLowerCase() ||
                            item.name.toLowerCase() === productSearch.trim().toLowerCase(),
                        ) ??
                        filteredProducts[0];

                      if (product) {
                        selectProductOption(product);
                      } else if (productSearch.trim().length > 0) {
                        Toast.error("Produto não encontrado.");
                      }
                      return;
                    }

                    if (!showProductOptions) return;

                    if (event.key === "ArrowDown") {
                      event.preventDefault();
                      if (filteredProducts.length === 0) return;
                      setHighlightedProductIndex((current) =>
                        current >= filteredProducts.length - 1 ? 0 : current + 1,
                      );
                    }

                    if (event.key === "ArrowUp") {
                      event.preventDefault();
                      if (filteredProducts.length === 0) return;
                      setHighlightedProductIndex((current) =>
                        current <= 0 ? filteredProducts.length - 1 : current - 1,
                      );
                    }

                    if (event.key === "Escape") {
                      event.preventDefault();
                      setShowProductOptions(false);
                    }
                  }}
                  className="input-field h-10 w-full pl-9 text-sm"
                  autoComplete="off"
                />
                {showProductOptions && (
                  <ul className="absolute left-0 right-0 top-full z-layer-popover mt-1 max-h-44 overflow-y-auto rounded-xl border border-border-secondary bg-bg-light text-text-primary shadow-lg">
                    {filteredProducts.length > 0 ? (
                      filteredProducts.map((item, index) => (
                        <li
                          key={item.id}
                          className={`cursor-pointer border-b border-border-primary px-3 py-2 text-xs ${
                            highlightedProductIndex === index ? "bg-hover-light" : "hover:bg-hover-light"
                          }`}
                          onMouseEnter={() => setHighlightedProductIndex(index)}
                          onMouseDown={() => {
                            selectProductOption(item);
                          }}
                        >
                          <p className="font-semibold">{item.name}</p>
                          <p className="text-[11px] text-text-secondary">{item.code}</p>
                        </li>
                      ))
                    ) : (
                      <li className="px-2 py-2 text-xs text-text-secondary">Nenhum produto encontrado.</li>
                    )}
                  </ul>
                )}
              </div>
            </label>

            <label className="mb-2 block">
              <span className="mb-1 block text-xs font-semibold uppercase">
                Quantidade{quantityIsFractionable ? ` (${quantityUnit.toLowerCase()})` : " (volume)"}:
              </span>
              <input
                ref={qtyInputRef}
                value={quantityInput}
                inputMode="decimal"
                onFocus={(event) => event.target.select()}
                onChange={(event) =>
                  setQuantityInput(
                    quantityIsFractionable
                      ? sanitizeDecimalInput(event.target.value, 4).slice(0, 9)
                      : sanitizeIntegerInput(event.target.value).slice(0, 4),
                  )
                }
                onBlur={() => {
                  if (!quantityInput || Number(quantityInput) <= 0) {
                    setQuantityInput(quantityIsFractionable ? "0" : "1");
                  }
                }}
                className="input-field h-10 w-full text-lg font-semibold"
              />
            </label>

            <label className="mb-2 block">
              <span className="mb-1 block text-xs font-semibold uppercase">Preço unitário:</span>
              <input
                value={selectedProduct ? formatMoneyBr(selectedProduct.salePrice) : "0,00"}
                className="input-field h-10 w-full text-lg font-semibold"
                disabled
              />
            </label>

            <label className="mb-2 block">
              <span className="mb-1 block text-xs font-semibold uppercase">Preço total:</span>
              <input
                value={selectedProduct ? formatMoneyBr(selectedProduct.salePrice * quantity) : "0,00"}
                className="input-field h-10 w-full text-lg font-semibold"
                disabled
              />
            </label>

            <button
              type="button"
              onClick={addItem}
              disabled={cartLocked}
              className="btn-success h-10 w-full rounded-xl disabled:cursor-not-allowed disabled:opacity-40"
            >
              ADICIONAR ITEM (ENTER)
            </button>

            <div className="mt-2 border-t border-border-primary pt-2 text-sm">
              <p className="font-semibold">Total volumes: {String(totalVolumes).padStart(4, "0")}</p>
            </div>

            <div className="mt-3 hidden rounded-xl border border-border-primary bg-bg-light p-3 sm:block">
              <div className="mx-auto flex h-28 w-full max-w-[220px] items-center justify-center overflow-hidden rounded-xl border-2 border-border-secondary bg-bg-primary text-text-tertiary">
                {previewProduct?.imageUrl ? (
                  <img
                    src={previewProduct.imageUrl}
                    alt={previewProduct.name}
                    className="h-full w-full object-cover"
                  />
                ) : (
                  <div className="flex flex-col items-center gap-1 text-center">
                    <ImageIcon size={28} />
                    <span className="text-xs font-medium">Sem imagem</span>
                  </div>
                )}
              </div>
            </div>
          </aside>

          <section className="flex min-h-[55vh] flex-col bg-bg-light lg:min-h-0">
            <div className="grid grid-cols-1 gap-1 border-b border-border-primary bg-bg-gray-theme px-3 py-2 text-xs text-text-primary sm:grid-cols-[1fr_200px] sm:gap-0">
              <p>
                <span className="font-semibold">Empresa:</span>{" "}
                {company?.fantasyName || "Quack PDV"}
              </p>
              <p className="sm:text-right">
                <span className="font-semibold">CNPJ:</span> {company?.cnpj || "-"}
              </p>
            </div>

            <div
              className={`border-b px-3 py-2 text-xs font-semibold ${
                cashCanSell
                  ? "border-success/20 bg-success/10 text-success"
                  : "border-primary/20 bg-primary/10 text-primary"
              }`}
            >
              {cashLabel}
            </div>

            <div className="border-b border-border-primary px-3 py-3">
              <p className="text-xs font-semibold">Nome produto:</p>
              <h2 className="text-center font-display text-xl font-semibold leading-none tracking-tight text-text-primary md:text-3xl">
                {activeProductName || "AGUARDANDO PRODUTO"}
              </h2>
            </div>

            <div className="flex min-h-0 flex-1 flex-col px-3 py-3">
              <p className="mb-1 text-sm font-semibold">Lista de itens:</p>
              <div className="min-h-[180px] flex-1 overflow-auto rounded-xl border border-dashed border-border-secondary bg-bg-primary md:min-h-0">
                {cart.length === 0 ? (
                  <div className="px-2 py-6 text-center text-sm text-text-secondary">
                    Nenhum item no cupom.
                  </div>
                ) : (
                  <>
                    <div className="space-y-2 p-2 md:hidden">
                      {cart.map((item, index) => {
                        const total = item.quantity * item.unitPrice;
                        return (
                          <article
                            key={item.id}
                            className="rounded-xl border border-border-primary bg-bg-light p-2.5"
                          >
                            <div className="flex items-start justify-between gap-2">
                              <div className="min-w-0">
                                <p className="text-xs font-semibold text-text-secondary">
                                  Item #{index + 1} • {item.code}
                                </p>
                                <p className="truncate text-sm font-semibold text-text-primary">
                                  {item.name}
                                </p>
                              </div>
                              <button
                                type="button"
                                onClick={() => removeItem(item.id)}
                                disabled={cartLocked}
                                className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-primary text-white disabled:cursor-not-allowed disabled:opacity-40"
                                aria-label={`Remover ${item.name}`}
                              >
                                <Trash2 size={12} />
                              </button>
                            </div>
                            <div className="mt-2 grid grid-cols-3 gap-2 text-xs">
                              <div>
                                <p className="text-text-secondary">Qtd</p>
                                <p className="font-semibold text-text-primary">{item.quantity}</p>
                              </div>
                              <div>
                                <p className="text-text-secondary">Vl. Unit</p>
                                <p className="font-semibold text-text-primary">
                                  {formatMoneyBr(item.unitPrice)}
                                </p>
                              </div>
                              <div>
                                <p className="text-text-secondary">Vl. Total</p>
                                <p className="font-semibold text-text-primary">
                                  {formatMoneyBr(total)}
                                </p>
                              </div>
                            </div>
                          </article>
                        );
                      })}
                    </div>

                    <table className="hidden min-w-[720px] w-full text-sm leading-[1.25] md:table md:text-base">
                      <thead>
                        <tr className="border-b border-border-primary bg-bg-gray-theme text-[11px] uppercase text-text-secondary md:text-xs">
                          <th className="w-12 px-2 py-1 text-center">#</th>
                          <th className="w-28 px-2 py-1 text-left">Código</th>
                          <th className="px-2 py-1 text-left">Produto</th>
                          <th className="w-16 px-2 py-1 text-center">Qtd</th>
                          <th className="w-32 px-2 py-1 text-right">Vl. Unit</th>
                          <th className="w-32 px-2 py-1 text-right">Vl. Total</th>
                          <th className="w-12 px-1 py-1 text-center"></th>
                        </tr>
                      </thead>
                      <tbody>
                        {cart.map((item, index) => {
                          const total = item.quantity * item.unitPrice;
                          return (
                            <tr key={item.id} className="border-b border-border-primary">
                              <td className="w-12 px-2 py-1 text-center">{index + 1}</td>
                              <td className="w-28 px-2 py-1">{item.code}</td>
                              <td className="px-2 py-1">{item.name}</td>
                              <td className="w-16 px-2 py-1 text-center">{item.quantity}</td>
                              <td className="w-32 px-2 py-1 text-right">{formatMoneyBr(item.unitPrice)}</td>
                              <td className="w-32 px-2 py-1 text-right">{formatMoneyBr(total)}</td>
                              <td className="w-12 px-1 py-1 text-center">
                                <button
                                  type="button"
                                  onClick={() => removeItem(item.id)}
                                  disabled={cartLocked}
                                  className="inline-flex h-7 w-7 items-center justify-center rounded-sm bg-primary text-white disabled:cursor-not-allowed disabled:opacity-40"
                                >
                                  <Trash2 size={12} />
                                </button>
                              </td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </>
                )}
              </div>
            </div>

            <div className="md:sticky md:bottom-0 md:z-10 md:shadow-[0_-8px_18px_rgba(15,23,42,0.08)]">
              <div className="border-t border-border-primary bg-bg-gray-theme px-3 py-1.5 text-sm text-text-primary">00 - Ajuda</div>

              <div className="grid grid-cols-[1fr_160px] border-t border-border-primary md:grid-cols-[1fr_220px]">
                <div className="bg-bg-gray-theme px-3 py-2 text-right text-sm font-semibold uppercase text-text-primary">
                  SUB TOTAL:
                </div>
                <div className="bg-accent px-3 py-2 text-right font-display text-3xl font-bold text-text-light md:text-4xl">
                  R$ {formatMoneyBr(subtotal)}
                </div>
              </div>

              <div className="grid grid-cols-1 gap-2 border-t border-border-primary px-3 py-3 sm:grid-cols-3">
                <button
                  type="button"
                  onClick={cancelSale}
                  className="btn-cancel h-11 w-full rounded-xl"
                >
                  ✖ CANCELAR (F8)
                </button>
                <button
                  type="button"
                  onClick={printLastSale}
                  className="inline-flex h-11 w-full items-center justify-center gap-2 rounded-xl border border-border-secondary px-4 py-2 text-sm font-semibold text-text-secondary transition hover:bg-hover-light hover:text-text-primary"
                >
                  <Printer size={16} />
                  Imprimir última venda
                </button>
                <button
                  type="button"
                  onClick={openPayment}
                  className="btn-success h-11 w-full rounded-xl disabled:cursor-not-allowed disabled:opacity-60"
                  disabled={!cashCanSell}
                >
                  PAGAMENTO (F12)
                </button>
              </div>

              <footer className="space-y-0.5 border-t border-border-primary bg-bg-primary px-3 py-2 text-[11px] text-text-secondary sm:grid sm:grid-cols-3 sm:items-center sm:space-y-0 sm:text-xs">
                <p>Usuário: {operatorName}</p>
                <p className="sm:text-center">
                  Estabelecimento: {company?.fantasyName || "Quack PDV"}
                </p>
                <p className="sm:text-right">
                  Prévia impressão: {printPreviewEnabled ? "Sim" : "Não"} • Caixa:{" "}
                  {cashCanSell ? "PDV01 aberto" : "bloqueado"}
                </p>
              </footer>
            </div>
          </section>
        </main>
      </div>

      {checkoutOpen && (
        <div className="fixed inset-0 z-layer-modal flex items-end bg-black/45 md:items-center md:justify-center">
          <div className="w-full rounded-t-2xl border border-border-primary bg-bg-light p-4 md:max-w-xl md:rounded-2xl">
            <div className="mb-3 flex items-center justify-between">
              <h2 className="text-lg font-semibold text-text-primary">Pagamento</h2>
              <button
                type="button"
                onClick={() => setCheckoutOpen(false)}
                className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-border-primary text-text-secondary"
              >
                <X size={14} />
              </button>
            </div>

            <div className="space-y-3">
              <label className="block">
                <span className="mb-1.5 block text-sm text-text-secondary">CPF na nota (opcional)</span>
                <input
                  value={cpfNota}
                  onChange={(event) => setCpfNota(event.target.value)}
                  className="input-field w-full"
                  placeholder="Somente se cliente pedir"
                />
              </label>

              {/* Painel de Resumo dos Valores */}
              <div className="grid grid-cols-2 gap-2 rounded-xl border border-border-primary bg-bg-primary p-3 text-xs sm:grid-cols-4 sm:text-sm">
                <div>
                  <span className="block text-[11px] text-text-secondary">Total da Venda</span>
                  <span className="font-bold text-text-primary">R$ {formatMoneyBr(subtotal)}</span>
                </div>
                <div>
                  <span className="block text-[11px] text-text-secondary">Total Informado</span>
                  <span className="font-semibold text-text-primary">R$ {formatMoneyBr(effectiveTotalPaid)}</span>
                </div>
                <div>
                  <span className="block text-[11px] text-text-secondary">Saldo Restante</span>
                  <span className={`font-bold ${effectiveRemainingToPay <= 0.001 ? "text-success" : "text-amber-500"}`}>
                    R$ {formatMoneyBr(effectiveRemainingToPay)}
                  </span>
                </div>
                <div>
                  <span className="block text-[11px] text-text-secondary">Troco Total</span>
                  <span className="font-semibold text-success">R$ {formatMoneyBr(totalChangeValue)}</span>
                </div>
              </div>

              {/* Lista de Pagamentos já inseridos */}
              {payments.length > 0 && (
                <div className="space-y-1.5">
                  <div className="flex items-center justify-between text-xs font-semibold text-text-secondary uppercase">
                    <span>Pagamentos Adicionados ({payments.length})</span>
                    <span className="text-text-tertiary">
                      R$ {formatMoneyBr(totalPaid)} de R$ {formatMoneyBr(subtotal)}
                    </span>
                  </div>
                  <div className="max-h-36 overflow-y-auto space-y-1.5 rounded-xl border border-border-primary bg-bg-primary/40 p-2">
                    {payments.map((p, idx) => (
                      <div
                        key={p.id}
                        className="flex items-center justify-between rounded-lg border border-border-primary bg-bg-light px-3 py-2 text-xs"
                      >
                        <div className="flex flex-wrap items-center gap-1.5 sm:gap-2">
                          <span className="font-semibold text-text-primary">
                            {idx + 1}. {getPaymentLabel(p.paymentType)}:
                          </span>
                          <span className="font-bold text-success">R$ {formatMoneyBr(p.amount)}</span>
                          {p.paymentType === "dinheiro" && p.changeAmount && p.changeAmount > 0 ? (
                            <span className="text-[11px] text-text-secondary">
                              (Recebido: R$ {formatMoneyBr(p.cashGiven ?? p.amount)} | Troco: R$ {formatMoneyBr(p.changeAmount)})
                            </span>
                          ) : null}
                        </div>
                        <button
                          type="button"
                          onClick={() => handleRemovePayment(p.id)}
                          className="rounded p-1 text-danger hover:bg-danger/10 transition"
                          title="Remover esta parcela"
                        >
                          <Trash2 size={13} />
                        </button>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* Formulário para adicionar pagamento (exibido enquanto houver saldo restante ou lista vazia) */}
              {remainingToPay > 0.001 && (
                <div className="rounded-xl border border-border-primary bg-bg-primary/30 p-3 space-y-3">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-semibold text-text-secondary uppercase">
                      {payments.length > 0 ? "Adicionar outra forma" : "Forma de pagamento"}
                    </span>
                    <span
                      className={`text-xs font-medium ${
                        effectiveRemainingToPay <= 0.001 ? "text-success font-semibold" : "text-amber-500"
                      }`}
                    >
                      {effectiveRemainingToPay <= 0.001
                        ? "✓ Saldo quitado"
                        : `Faltam: R$ ${formatMoneyBr(effectiveRemainingToPay)}`}
                    </span>
                  </div>

                  <div className="grid gap-2 sm:grid-cols-2">
                    <SearchableSelectField
                      label="Forma"
                      value={currentPaymentType}
                      options={PAYMENT_OPTIONS}
                      onChange={(nextValue) => {
                        const tipo = nextValue as PaymentType;
                        setCurrentPaymentType(tipo);
                        if (tipo === "dinheiro") {
                          setCurrentCashGiven(currentPaymentAmount);
                        }
                      }}
                      getOptionValue={(option) => option.value}
                      getOptionLabel={(option) => option.label}
                      placeholder="Selecione a forma"
                      emptyMessage="Forma de pagamento não encontrada."
                    />

                    <label className="block">
                      <span className="mb-1.5 block text-sm text-text-secondary">Valor a pagar (R$)</span>
                      <input
                        value={currentPaymentAmount}
                        inputMode="numeric"
                        pattern="[0-9,.]*"
                        onBeforeInput={preventNonDigitBeforeInput}
                        onKeyDown={(event) => {
                          if (event.key === "Enter") {
                            event.preventDefault();
                            if (canConfirmPayment) {
                              void confirmPayment();
                            } else {
                              handleAddPayment();
                            }
                          }
                        }}
                        onChange={(event) => {
                          const val = maskMoneyBr(event.target.value);
                          setCurrentPaymentAmount(val);
                          if (currentPaymentType === "dinheiro") {
                            setCurrentCashGiven(val);
                          }
                        }}
                        className="input-field w-full"
                        placeholder="0,00"
                      />
                    </label>
                  </div>

                  {currentPaymentType === "dinheiro" && (
                    <div className="grid gap-2 sm:grid-cols-2">
                      <label className="block">
                        <span className="mb-1.5 block text-sm text-text-secondary">Valor entregue em dinheiro</span>
                        <input
                          value={currentCashGiven}
                          inputMode="numeric"
                          pattern="[0-9,.]*"
                          onBeforeInput={preventNonDigitBeforeInput}
                          onPaste={pasteCurrentCashGiven}
                          onKeyDown={(event) => {
                            if (event.key === "Enter") {
                              event.preventDefault();
                              if (canConfirmPayment) {
                                void confirmPayment();
                              } else {
                                handleAddPayment();
                              }
                            }
                          }}
                          onChange={(event) => setCurrentCashGiven(maskMoneyBr(event.target.value))}
                          className="input-field w-full"
                          placeholder="0,00"
                        />
                      </label>
                      <div className="flex flex-col justify-end">
                        <span className="mb-1.5 block text-sm text-text-secondary">Troco desta parcela</span>
                        <div className="flex h-10 items-center rounded-xl border border-border-primary bg-bg-primary px-3 text-sm font-semibold text-success">
                          R$ {formatMoneyBr(currentChangeValue)}
                        </div>
                      </div>
                    </div>
                  )}

                  <button
                    type="button"
                    onClick={handleAddPayment}
                    className="btn-secondary w-full py-2 text-xs font-semibold flex items-center justify-center gap-1.5"
                  >
                    <Plus size={14} /> Adicionar à lista de parcelas
                  </button>
                </div>
              )}
            </div>

            <div className="mt-4 grid gap-2 sm:grid-cols-2">
              <button
                type="button"
                onClick={() => setCheckoutOpen(false)}
                disabled={isConfirmingSale}
                className="btn-cancel disabled:cursor-not-allowed disabled:opacity-50"
              >
                Voltar
              </button>
              <button
                type="button"
                onClick={confirmPayment}
                className="btn-success flex items-center justify-center gap-2 disabled:cursor-not-allowed disabled:opacity-50"
                disabled={!canConfirmPayment || isConfirmingSale}
              >
                {isConfirmingSale ? (
                  <>
                    <Loader2 size={16} className="animate-spin" />
                    Processando venda...
                  </>
                ) : (
                  "Confirmar Venda"
                )}
              </button>
            </div>
          </div>
        </div>
      )}

      {isConfirmingSale && (
        <div className="fixed inset-0 z-layer-dialog flex flex-col items-center justify-center bg-black/65 px-4 backdrop-blur-sm">
          <div className="flex flex-col items-center gap-4 rounded-2xl border border-border-primary bg-bg-light p-6 text-center shadow-2xl">
            <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-accent/10 text-accent">
              <Loader2 size={32} className="animate-spin" />
            </div>
            <div>
              <h3 className="text-base font-semibold text-text-primary">Processando Venda</h3>
              <p className="mt-1 text-xs text-text-secondary">
                Registrando pagamento e preparando cupom fiscal...
              </p>
            </div>
          </div>
        </div>
      )}

      {receiptPreview ? (
        <ReceiptPreviewModal
          receipt={receiptPreview}
          formatMoney={formatMoneyBr}
          onClose={() => setReceiptPreview(null)}
        />
      ) : null}

      {statusDialog.Dialog}
    </div>
  );
}
