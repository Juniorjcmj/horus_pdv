package br.com.quack.estoque;

import br.com.quack.estoque.domain.Produto;
import br.com.quack.estoque.persistence.JsonFiles;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Produtos de teste montados do mesmo JSON que a API devolve. */
public final class TestData {
    private TestData() {
    }

    public static Produto produto(String id, String codigo, String nome, String gtin, String preco, String unidade) {
        return produto(id, codigo, nome, gtin, preco, unidade, "10", "0", "0", "0");
    }

    public static Produto produto(String id, String codigo, String nome, String gtin, String preco, String unidade,
            String estoque, String custo, String minimo, String maximo) {
        ObjectNode n = JsonFiles.MAPPER.createObjectNode();
        n.put("id", id);
        n.put("productCode", codigo);
        n.put("productName", nome);
        n.put("gtin", gtin == null || gtin.isEmpty() ? "SEM GTIN" : gtin);
        n.put("productSalePrice", preco);
        n.put("productUnitPrice", custo);
        n.put("productQnt", estoque);
        n.put("estoqueMinimo", minimo);
        n.put("estoqueMaximo", maximo);
        n.put("unidadeComercial", unidade);
        n.put("productSupplier", "");
        return Produto.de(n);
    }

    /** Completa os 12 primeiros dígitos com o dígito verificador EAN-13. */
    public static String ean(String primeiros12) {
        int soma = 0;
        for (int i = 0; i < 12; i++) {
            int d = primeiros12.charAt(i) - '0';
            soma += i % 2 == 0 ? d : d * 3;
        }
        return primeiros12 + ((10 - soma % 10) % 10);
    }
}
