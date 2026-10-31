package br.com.quack.estoque.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

/**
 * Produto do catálogo em memória. {@code raw} guarda o JSON completo da API: a edição reenvia o produto inteiro
 * (PUT /api/Produto/{id}) trocando só os campos alterados, sem perder os dados fiscais que o app não mostra.
 */
public record Produto(String id, String codigo, String nome, String gtin, String unidade, BigDecimal preco,
        BigDecimal custo, BigDecimal estoque, BigDecimal minimo, BigDecimal maximo, String categoriaId,
        String categoriaNome, String fornecedor, boolean controlaValidade, String localizacao, String versaoPreco,
        JsonNode raw) {

    public static final String SEM_GTIN = "SEM GTIN";

    public static Produto de(JsonNode n) {
        String gtin = text(n, "gtin");
        if (gtin.equalsIgnoreCase(SEM_GTIN)) {
            gtin = "";
        }
        String un = text(n, "unidadeComercial");
        return new Produto(text(n, "id"), text(n, "productCode"), text(n, "productName"), gtin,
                un.isBlank() ? "UN" : un, Fmt.parseOrZero(text(n, "productSalePrice")),
                Fmt.parseOrZero(text(n, "productUnitPrice")), Fmt.parseOrZero(text(n, "productQnt")),
                Fmt.parseOrZero(text(n, "estoqueMinimo")), Fmt.parseOrZero(text(n, "estoqueMaximo")),
                text(n, "categoriaId"), text(n, "categoriaNome"), text(n, "productSupplier"),
                n.path("controlaValidade").asBoolean(false), text(n, "localizacaoEstoque"), text(n, "versaoPreco"), n);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText("").trim();
    }

    public boolean fracionavel() {
        return !"UN".equalsIgnoreCase(unidade);
    }

    /** Situação do estoque para o indicador da lista. */
    public enum Nivel { SEM_ESTOQUE, BAIXO, NORMAL, ACIMA_DO_MAXIMO }

    public Nivel nivel() {
        if (estoque.signum() <= 0) {
            return Nivel.SEM_ESTOQUE;
        }
        if (minimo.signum() > 0 && estoque.compareTo(minimo) < 0) {
            return Nivel.BAIXO;
        }
        if (maximo.signum() > 0 && estoque.compareTo(maximo) > 0) {
            return Nivel.ACIMA_DO_MAXIMO;
        }
        return Nivel.NORMAL;
    }

    public static String rotulo(Nivel n) {
        return switch (n) {
            case SEM_ESTOQUE -> "Sem estoque";
            case BAIXO -> "Baixo";
            case NORMAL -> "Normal";
            case ACIMA_DO_MAXIMO -> "Acima do máximo";
        };
    }
}
