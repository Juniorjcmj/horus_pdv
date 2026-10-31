package br.com.quack.caixa.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Carrinho mutável: guarda itens crus e recalcula promoções a cada leitura. */
public final class Cart {
    private final List<CartItem> raw = new ArrayList<>();
    private final Supplier<Instant> clock;
    private List<Promotion> promotions = List.of();
    /** Estoque por id de produto no momento da inclusão (para validar soma). */
    private final java.util.Map<String, BigDecimal> stockById = new java.util.HashMap<>();

    public Cart() {
        this(Instant::now);
    }

    public Cart(Supplier<Instant> clock) {
        this.clock = clock;
    }

    public void setPromotions(List<Promotion> promotions) {
        this.promotions = promotions == null ? List.of() : List.copyOf(promotions);
    }

    /**
     * Adiciona o produto. Devolve um aviso opcional (validade) ou null.
     * Lança DomainException quando a quantidade é inválida ou excede o estoque.
     */
    public String add(Product product, BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new DomainException("Quantidade inválida.");
        }
        BigDecimal q = Money.round4(quantity);
        if (!product.fractional() && q.stripTrailingZeros().scale() > 0) {
            throw new DomainException("Este produto é vendido por unidade; informe uma quantidade inteira.");
        }
        int idx = indexOf(product.id());
        BigDecimal total = idx >= 0 ? raw.get(idx).quantity().add(q) : q;
        if (total.compareTo(product.stock()) > 0) {
            throw new DomainException("Estoque insuficiente. Disponível: " + Money.formatQty(product.stock()) + ".");
        }
        if (idx >= 0) {
            raw.set(idx, raw.get(idx).withQuantity(total));
        } else {
            raw.add(CartItem.raw(product, total));
        }
        stockById.put(product.id(), product.stock());
        return validityWarning(product);
    }

    private String validityWarning(Product p) {
        if (!p.controlaValidade() || p.dataValidade() == null) {
            return null;
        }
        LocalDate today = clock.get().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        if (p.dataValidade().isBefore(today)) {
            return "Atenção: produto vencido em " + p.dataValidade().format(
                    java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) + ".";
        }
        long days = ChronoUnit.DAYS.between(today, p.dataValidade());
        if (days <= p.diasAlertaValidade()) {
            return "Produto vence em " + days + " dia(s).";
        }
        return null;
    }

    private int indexOf(String productId) {
        for (int i = 0; i < raw.size(); i++) {
            if (raw.get(i).productId().equals(productId)) {
                return i;
            }
        }
        return -1;
    }

    public void removeAt(int index) {
        if (index >= 0 && index < raw.size()) {
            raw.remove(index);
        }
    }

    public void clear() {
        raw.clear();
        stockById.clear();
    }

    public boolean isEmpty() {
        return raw.isEmpty();
    }

    /** Item com preço alterado por gerente vale pelo preço autorizado: não recebe promoção por cima. */
    public List<CartItem> pricedItems() {
        List<CartItem> priced = PromotionEngine.apply(raw, promotions, clock.get());
        List<CartItem> out = new ArrayList<>(priced.size());
        for (CartItem i : priced) {
            out.add(i.priceOverridden() ? i.withPromotion(Money.ZERO, null, null) : i);
        }
        return out;
    }

    /** Aplica ao item da posição index o preço autorizado pelo gerente. */
    public void applyAuthorizedPrice(int index, BigDecimal novoPreco, String autorizacaoId, String supervisorNome) {
        if (index < 0 || index >= raw.size()) {
            throw new DomainException("Item não encontrado no carrinho.");
        }
        raw.set(index, raw.get(index).withAuthorizedPrice(Money.round2(novoPreco), autorizacaoId, supervisorNome));
    }

    /** Volta o item ao preço cadastrado (descarta a autorização). */
    public void restoreCatalogPrice(int index) {
        if (index >= 0 && index < raw.size()) {
            raw.set(index, raw.get(index).withCatalogPrice());
        }
    }

    /** Item cru (preço cadastrado ou autorizado, sem promoção) da posição index. */
    public CartItem rawAt(int index) {
        return raw.get(index);
    }

    public BigDecimal total() {
        BigDecimal sum = BigDecimal.ZERO;
        for (CartItem i : pricedItems()) {
            sum = sum.add(i.total());
        }
        return Money.round2(sum);
    }
}
