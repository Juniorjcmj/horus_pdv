/**
 * Arquivo: src/pages/Admin/NovoPedidoPage.tsx
 * Objetivo: tela do vendedor para montar um pedido (orçamento com preço congelado) e gerar um
 *           número curto que o cliente leva até o caixa para pagar.
 * Entradas esperadas: não recebe props; busca produtos da API e registra o pedido ao finalizar.
 */
import { CheckCircle2, ClipboardList, Plus, Search, Trash2 } from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";
import PageHeader from "@/components/Admin/PageHeader";
import { Toast } from "@/hooks/Dialog";
import useInputMasks from "@/hooks/InputMasks/useInputMasks";
import PageLayout from "@/layout/PageLayout";
import { pedidoService, type PedidoDto } from "@/services/api/pedidoService";
import { productService } from "@/services/api/productService";

type Product = {
  id: string;
  name: string;
  code: string;
  stock: number;
  salePrice: number;
  unit: string;
};

type CartItem = {
  code: string;
  name: string;
  quantity: number;
  unitPrice: number;
};

function isFractionableUnit(unit: string) {
  return unit.trim().toUpperCase() !== "UN";
}

function formatQuantityDisplay(value: number) {
  return value.toFixed(4).replace(/0+$/, "").replace(/\.$/, "").replace(".", ",") || "0";
}

export default function NovoPedidoPage() {
  const { formatMoneyBr, parseMoneyBr, sanitizeIntegerInput, sanitizeDecimalInput } = useInputMasks();

  const [products, setProducts] = useState<Product[]>([]);
  const [productSearch, setProductSearch] = useState("");
  const [showOptions, setShowOptions] = useState(false);
  const [selectedProductId, setSelectedProductId] = useState("");
  const [quantityInput, setQuantityInput] = useState("1");
  const [cart, setCart] = useState<CartItem[]>([]);
  const [customerName, setCustomerName] = useState("");
  const [customerCpf, setCustomerCpf] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [confirmedOrder, setConfirmedOrder] = useState<PedidoDto | null>(null);

  useEffect(() => {
    productService
      .list()
      .then((items) =>
        setProducts(
          items.map((item) => ({
            id: item.id,
            name: item.productName,
            code: item.productCode,
            stock: parseMoneyBr(item.productQnt || "0"),
            salePrice: parseMoneyBr(item.productSalePrice || "0"),
            unit: item.unidadeComercial || "UN",
          })),
        ),
      )
      .catch(() => Toast.error("Não foi possível carregar produtos."));
  }, [parseMoneyBr]);

  const selectedProduct = useMemo(
    () => products.find((item) => item.id === selectedProductId) ?? null,
    [products, selectedProductId],
  );
  const quantityIsFractionable = isFractionableUnit(selectedProduct?.unit ?? "UN");

  const quantity = useMemo(() => {
    const parsed = Number(quantityInput);
    if (!Number.isFinite(parsed) || parsed <= 0) return quantityIsFractionable ? 0 : 1;
    return quantityIsFractionable ? parsed : Math.floor(parsed);
  }, [quantityInput, quantityIsFractionable]);

  const filteredProducts = useMemo(() => {
    const normalized = productSearch.trim().toLowerCase();
    if (!normalized) return [];
    return products.filter(
      (item) =>
        item.name.toLowerCase().includes(normalized) || item.code.toLowerCase().includes(normalized),
    );
  }, [products, productSearch]);

  const total = useMemo(
    () => cart.reduce((sum, item) => sum + item.unitPrice * item.quantity, 0),
    [cart],
  );

  const addItem = useCallback(() => {
    if (!selectedProduct) {
      Toast.error("Selecione um produto.");
      return;
    }
    if (quantity <= 0) {
      Toast.error("Informe uma quantidade maior que zero.");
      return;
    }

    setCart((current) => {
      const existing = current.find((item) => item.code === selectedProduct.code);
      if (!existing) {
        return [
          ...current,
          {
            code: selectedProduct.code,
            name: selectedProduct.name,
            quantity,
            unitPrice: selectedProduct.salePrice,
          },
        ];
      }
      return current.map((item) =>
        item.code === selectedProduct.code
          ? { ...item, quantity: item.quantity + quantity }
          : item,
      );
    });

    setSelectedProductId("");
    setProductSearch("");
    setShowOptions(false);
    setQuantityInput("1");
  }, [quantity, selectedProduct]);

  const removeItem = (code: string) => {
    setCart((current) => current.filter((item) => item.code !== code));
  };

  const resetForm = () => {
    setCart([]);
    setCustomerName("");
    setCustomerCpf("");
    setConfirmedOrder(null);
  };

  const submitOrder = async () => {
    if (cart.length === 0) {
      Toast.error("Adicione ao menos um item ao pedido.");
      return;
    }

    setSubmitting(true);
    try {
      const pedido = await pedidoService.create({
        customerName: customerName || "Consumidor",
        customerCpf: customerCpf || "-",
        items: cart.map((item) => ({
          productCode: item.code,
          productName: item.name,
          quantity: item.quantity,
        })),
      });

      if (!pedido) {
        Toast.error("Não foi possível gerar o pedido.");
        return;
      }

      setConfirmedOrder(pedido);
      setCart([]);
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao gerar pedido.");
    } finally {
      setSubmitting(false);
    }
  };

  if (confirmedOrder) {
    return (
      <PageLayout className="space-y-4 py-4 md:space-y-6 md:py-6 lg:py-8">
        <PageHeader title="Pedido gerado" description="Peça para o cliente levar este número até o caixa." />

        <section className="card mx-auto max-w-xl space-y-4 p-6 text-center">
          <CheckCircle2 size={40} className="mx-auto text-success" />
          <div>
            <p className="text-sm text-text-secondary">Número do pedido</p>
            <p className="text-5xl font-bold text-text-primary">{confirmedOrder.orderNumber}</p>
          </div>
          <div className="rounded-xl border border-border-secondary bg-bg-primary/50 p-4 text-left text-sm">
            {confirmedOrder.itens.map((item) => (
              <div key={item.productCode} className="flex justify-between border-b border-border-primary py-1.5 last:border-0">
                <span>
                  {item.productName} × {formatQuantityDisplay(item.quantity)}
                </span>
                <span className="font-semibold">R$ {item.itemTotal}</span>
              </div>
            ))}
            <div className="flex justify-between pt-2 text-base font-bold">
              <span>Total</span>
              <span>R$ {confirmedOrder.totalAmount}</span>
            </div>
          </div>
          <button type="button" onClick={resetForm} className="btn-primary w-full">
            Novo pedido
          </button>
        </section>
      </PageLayout>
    );
  }

  return (
    <PageLayout className="space-y-4 py-4 md:space-y-6 md:py-6 lg:py-8">
      <PageHeader
        title="Novo Pedido"
        description="Monte o pedido do cliente e gere um número para ele pagar no caixa."
      />

      <section className="card grid gap-4 p-4 md:grid-cols-2 md:p-5">
        <label className="block">
          <span className="mb-1.5 block text-sm text-text-secondary">Nome do cliente</span>
          <input
            className="input-field w-full"
            value={customerName}
            onChange={(event) => setCustomerName(event.target.value)}
            placeholder="Consumidor"
          />
        </label>
        <label className="block">
          <span className="mb-1.5 block text-sm text-text-secondary">CPF/CNPJ (opcional)</span>
          <input
            className="input-field w-full"
            value={customerCpf}
            onChange={(event) => setCustomerCpf(event.target.value)}
            placeholder="Para a nota fiscal, se quiser"
          />
        </label>
      </section>

      <section className="card p-4 md:p-5">
        <div className="grid gap-3 md:grid-cols-[1fr_180px_auto]">
          <label className="relative block">
            <span className="mb-1.5 block text-sm text-text-secondary">Produto</span>
            <Search size={14} className="pointer-events-none absolute left-3 top-[38px] text-text-tertiary" />
            <input
              value={productSearch}
              onChange={(event) => {
                setProductSearch(event.target.value);
                setSelectedProductId("");
                setShowOptions(true);
              }}
              onKeyDown={(event) => {
                if (event.key === "Enter" && filteredProducts.length > 0) {
                  event.preventDefault();
                  const first = filteredProducts[0];
                  setSelectedProductId(first.id);
                  setProductSearch(first.name);
                  setShowOptions(false);
                }
              }}
              className="input-field w-full pl-9"
              placeholder="Busque por nome ou código"
              autoComplete="off"
            />
            {showOptions && filteredProducts.length > 0 ? (
              <ul className="absolute left-0 right-0 top-full z-layer-popover mt-1 max-h-44 overflow-y-auto rounded-xl border border-border-secondary bg-bg-light shadow-lg">
                {filteredProducts.map((item) => (
                  <li
                    key={item.id}
                    className="cursor-pointer border-b border-border-primary px-3 py-2 text-xs last:border-0 hover:bg-hover-light"
                    onMouseDown={() => {
                      setSelectedProductId(item.id);
                      setProductSearch(item.name);
                      setShowOptions(false);
                    }}
                  >
                    <p className="font-semibold">{item.name}</p>
                    <p className="text-[11px] text-text-secondary">
                      {item.code} — R$ {formatMoneyBr(item.salePrice)} / {item.unit}
                    </p>
                  </li>
                ))}
              </ul>
            ) : null}
          </label>

          <label className="block">
            <span className="mb-1.5 block text-sm text-text-secondary">
              Quantidade {quantityIsFractionable ? `(${selectedProduct?.unit})` : ""}
            </span>
            <input
              value={quantityInput}
              inputMode="decimal"
              onChange={(event) =>
                setQuantityInput(
                  quantityIsFractionable
                    ? sanitizeDecimalInput(event.target.value, 4).slice(0, 9)
                    : sanitizeIntegerInput(event.target.value).slice(0, 4),
                )
              }
              className="input-field w-full"
            />
          </label>

          <div className="flex items-end">
            <button type="button" onClick={addItem} className="btn-success inline-flex h-10 w-full items-center justify-center gap-2 md:w-auto md:px-6">
              <Plus size={16} />
              Adicionar
            </button>
          </div>
        </div>
      </section>

      <section className="card overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[600px] text-sm">
            <thead className="bg-bg-primary text-left text-text-secondary">
              <tr>
                <th className="px-3 py-3">Produto</th>
                <th className="px-3 py-3 text-center">Qtd.</th>
                <th className="px-3 py-3 text-right">Vl. Unit.</th>
                <th className="px-3 py-3 text-right">Total</th>
                <th className="px-3 py-3 text-center">Ações</th>
              </tr>
            </thead>
            <tbody>
              {cart.length === 0 ? (
                <tr>
                  <td colSpan={5} className="px-3 py-6 text-center text-text-secondary">
                    Nenhum item adicionado.
                  </td>
                </tr>
              ) : null}
              {cart.map((item) => (
                <tr key={item.code} className="border-t border-border-primary">
                  <td className="px-3 py-3">{item.name}</td>
                  <td className="px-3 py-3 text-center">{formatQuantityDisplay(item.quantity)}</td>
                  <td className="px-3 py-3 text-right">R$ {formatMoneyBr(item.unitPrice)}</td>
                  <td className="px-3 py-3 text-right font-semibold">
                    R$ {formatMoneyBr(item.unitPrice * item.quantity)}
                  </td>
                  <td className="px-3 py-3 text-center">
                    <button
                      type="button"
                      onClick={() => removeItem(item.code)}
                      className="inline-flex h-8 w-8 items-center justify-center rounded-lg text-primary hover:bg-primary/10"
                      aria-label={`Remover ${item.name}`}
                    >
                      <Trash2 size={14} />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <div className="flex flex-col gap-3 border-t border-border-primary p-4 sm:flex-row sm:items-center sm:justify-between">
          <p className="text-lg font-bold text-text-primary">Total: R$ {formatMoneyBr(total)}</p>
          <button
            type="button"
            onClick={submitOrder}
            disabled={submitting || cart.length === 0}
            className="btn-primary inline-flex items-center justify-center gap-2 disabled:cursor-not-allowed disabled:opacity-60"
          >
            <ClipboardList size={16} />
            {submitting ? "Gerando pedido..." : "Gerar pedido"}
          </button>
        </div>
      </section>
    </PageLayout>
  );
}
