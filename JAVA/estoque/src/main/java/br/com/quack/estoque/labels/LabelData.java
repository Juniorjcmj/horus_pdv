package br.com.quack.estoque.labels;

import br.com.quack.estoque.domain.Produto;
import java.math.BigDecimal;

/**
 * Dados impressos numa etiqueta. Em promoção de preço fixo, {@code precoPromocional} vira o preço em destaque e o
 * preço normal sai riscado, com o período ("até 30/11").
 */
public record LabelData(String produtoId, String codigo, String nome, String unidade, String gtin, BigDecimal preco,
        BigDecimal precoPromocional, String periodoPromocao) {

    public static LabelData de(Produto p) {
        return new LabelData(p.id(), p.codigo(), p.nome(), p.unidade(), p.gtin(), p.preco(), null, null);
    }

    public static LabelData promocao(Produto p, BigDecimal precoPromocional, String periodo) {
        return new LabelData(p.id(), p.codigo(), p.nome(), p.unidade(), p.gtin(), p.preco(), precoPromocional, periodo);
    }

    public boolean emPromocao() {
        return precoPromocional != null && precoPromocional.signum() > 0 && precoPromocional.compareTo(preco) < 0;
    }

    /** Preço que sai em destaque. */
    public BigDecimal precoFinal() {
        return emPromocao() ? precoPromocional : preco;
    }
}
