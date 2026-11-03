package br.com.quack.caixa.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Porta de promotionEngine.ts: a promoção de maior desconto vence, sem empilhar. */
public final class PromotionEngine {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private PromotionEngine() {
    }

    public static List<CartItem> apply(List<CartItem> items, List<Promotion> promotions, Instant now) {
        List<CartItem> out = new ArrayList<>();
        List<Promotion> vigentes = promotions.stream().filter(p -> vigente(p, now)).toList();
        for (CartItem item : items) {
            BigDecimal best = BigDecimal.ZERO;
            Promotion bestPromo = null;
            for (Promotion p : vigentes) {
                if (applies(p, item)) {
                    BigDecimal d = discount(p, item);
                    if (d.compareTo(best) > 0) {
                        best = d;
                        bestPromo = p;
                    }
                }
            }
            BigDecimal eff = item.gross().min(best);
            if (eff.signum() > 0 && bestPromo != null) {
                out.add(item.withPromotion(Money.round2(eff), bestPromo.id(), bestPromo.nome()));
            } else {
                out.add(item.withPromotion(Money.ZERO, null, null));
            }
        }
        return out;
    }

    static boolean vigente(Promotion p, Instant now) {
        if (!p.ativa()) {
            return false;
        }
        if (p.inicio() != null && now.isBefore(p.inicio())) {
            return false;
        }
        return p.fim() == null || !now.isAfter(p.fim());
    }

    static boolean applies(Promotion p, CartItem item) {
        if (p.produtoIds() != null && !p.produtoIds().isEmpty()
                && (p.produtoIds().contains(item.productId()) || p.produtoIds().contains(item.code()))) {
            return true;
        }
        return p.categoriaId() != null && !p.categoriaId().isBlank() && item.categoriaId() != null
                && item.categoriaId().equals(p.categoriaId());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    static BigDecimal discount(Promotion p, CartItem item) {
        BigDecimal q = item.quantity();
        BigDecimal price = item.unitPrice();
        if (q.signum() <= 0 || price.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal gross = Money.productMoney(q, price);
        String tipo = p.tipo() == null ? "" : p.tipo();
        switch (tipo) {
            case "desconto_percentual": {
                BigDecimal pct = nz(p.valorDesconto());
                if (pct.signum() <= 0) {
                    return BigDecimal.ZERO;
                }
                return Money.productMoney(gross, pct.min(HUNDRED).divide(HUNDRED, 10, RoundingMode.HALF_UP));
            }
            case "desconto_valor": {
                BigDecimal v = nz(p.valorDesconto());
                if (v.signum() <= 0) {
                    return BigDecimal.ZERO;
                }
                return gross.min(Money.productMoney(q, v));
            }
            case "preco_fixo": {
                BigDecimal pf = nz(p.precoFixo());
                if (pf.signum() <= 0 || pf.compareTo(price) >= 0) {
                    return BigDecimal.ZERO;
                }
                return Money.round2(q.multiply(price.subtract(pf)));
            }
            case "leve_x_pague_y": {
                BigDecimal leva = nz(p.quantidadeLeva());
                BigDecimal paga = nz(p.quantidadePaga());
                if (leva.signum() <= 0 || paga.signum() <= 0 || leva.compareTo(paga) <= 0
                        || q.compareTo(leva) < 0) {
                    return BigDecimal.ZERO;
                }
                BigDecimal grupos = q.divideToIntegralValue(leva);
                return Money.round2(grupos.multiply(leva.subtract(paga)).multiply(price));
            }
            case "combo_quantidade": {
                BigDecimal min = nz(p.quantidadeMinima());
                if (min.compareTo(BigDecimal.ONE) <= 0 || q.compareTo(min) < 0) {
                    return BigDecimal.ZERO;
                }
                BigDecimal grupos = q.divideToIntegralValue(min);
                if (nz(p.precoFixo()).signum() > 0) {
                    BigDecimal normal = grupos.multiply(min).multiply(price);
                    BigDecimal promo = grupos.multiply(p.precoFixo());
                    return Money.round2(normal.subtract(promo).max(BigDecimal.ZERO));
                }
                if (nz(p.valorDesconto()).signum() > 0) {
                    return Money.round2(grupos.multiply(p.valorDesconto()));
                }
                return BigDecimal.ZERO;
            }
            case "preco_atacado": {
                BigDecimal min = nz(p.quantidadeMinima());
                if (min.compareTo(BigDecimal.ONE) <= 0 || q.compareTo(min) < 0) {
                    return BigDecimal.ZERO;
                }
                if (nz(p.precoFixo()).signum() > 0) {
                    if (p.precoFixo().compareTo(price) < 0) {
                        return Money.round2(q.multiply(price.subtract(p.precoFixo())));
                    }
                    return BigDecimal.ZERO;
                }
                if (nz(p.valorDesconto()).signum() > 0) {
                    return Money.round2(gross.min(q.multiply(p.valorDesconto())));
                }
                return BigDecimal.ZERO;
            }
            default:
                return BigDecimal.ZERO;
        }
    }
}
