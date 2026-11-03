package br.com.quack.caixa.domain;

import java.math.BigDecimal;

/**
 * Item do carrinho. desconto/promoção só vem preenchido após PromotionEngine.apply.
 * Preço alterado com senha de gerente: unitPrice é o preço autorizado, precoCadastro guarda o original (para
 * restaurar), autorizacaoPrecoId vai no item da venda e precoAutorizadoPor é o nome do gerente.
 */
public record CartItem(String productId, String code, String name, String unit, String categoriaId,
        BigDecimal quantity, BigDecimal unitPrice, BigDecimal desconto, String promocaoId, String promocaoNome,
        BigDecimal precoCadastro, String autorizacaoPrecoId, String precoAutorizadoPor) {

    public CartItem(String productId, String code, String name, String unit, String categoriaId,
            BigDecimal quantity, BigDecimal unitPrice, BigDecimal desconto, String promocaoId, String promocaoNome) {
        this(productId, code, name, unit, categoriaId, quantity, unitPrice, desconto, promocaoId, promocaoNome, null,
                null, null);
    }

    public static CartItem raw(Product p, BigDecimal qty) {
        return new CartItem(p.id(), p.code(), p.name(), p.unit(), p.categoriaId(), qty, p.price(), Money.ZERO,
                null, null);
    }

    public BigDecimal gross() {
        return Money.productMoney(quantity, unitPrice);
    }

    public BigDecimal total() {
        return Money.round2(gross().subtract(desconto).max(BigDecimal.ZERO));
    }

    /** Preço alterado com autorização de gerente. */
    public boolean priceOverridden() {
        return autorizacaoPrecoId != null && !autorizacaoPrecoId.isBlank();
    }

    public CartItem withQuantity(BigDecimal q) {
        return new CartItem(productId, code, name, unit, categoriaId, q, unitPrice, desconto, promocaoId,
                promocaoNome, precoCadastro, autorizacaoPrecoId, precoAutorizadoPor);
    }

    public CartItem withPromotion(BigDecimal d, String promoId, String promoNome) {
        return new CartItem(productId, code, name, unit, categoriaId, quantity, unitPrice, d, promoId, promoNome,
                precoCadastro, autorizacaoPrecoId, precoAutorizadoPor);
    }

    /** Aplica o preço autorizado pelo gerente (guarda o preço cadastrado para poder restaurar). */
    public CartItem withAuthorizedPrice(BigDecimal novoPreco, String autorizacaoId, String supervisorNome) {
        BigDecimal original = precoCadastro != null ? precoCadastro : unitPrice;
        return new CartItem(productId, code, name, unit, categoriaId, quantity, novoPreco, Money.ZERO, null, null,
                original, autorizacaoId, supervisorNome);
    }

    /** Volta ao preço cadastrado. */
    public CartItem withCatalogPrice() {
        BigDecimal original = precoCadastro != null ? precoCadastro : unitPrice;
        return new CartItem(productId, code, name, unit, categoriaId, quantity, original, Money.ZERO, null, null,
                null, null, null);
    }
}
