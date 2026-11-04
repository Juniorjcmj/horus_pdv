package br.com.quack.pdv.venda.cancelamento.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;

/** Fotografia da venda no momento do pedido de cancelamento (VendaCancelamentoSnapshot da .NET). */
public record VendaSnapshot(String vendaId, String saleNumber, String status, BigDecimal totalVenda,
                            String clienteNome, String clienteCpf, String caixaSessaoId, String caixaSessaoStatus,
                            List<Item> itens, List<Pagamento> pagamentos, List<Nota> notas) {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(com.fasterxml.jackson.core.JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);

    public record Item(String codigo, String nome, BigDecimal quantidade, BigDecimal precoUnitario, BigDecimal total) {}

    public record Pagamento(String forma, BigDecimal valor, BigDecimal recebido, BigDecimal troco) {}

    public record Nota(String id, int modelo, String numero, String serie, int status, String chave) {}

    /** JSON gravado em VendaCancelamentos.Snapshot: {"itens":[...],"pagamentos":[...],"notas":[...]} em camelCase. */
    public String toJson() {
        ObjectNode raiz = JSON.createObjectNode();
        ArrayNode listaItens = raiz.putArray("itens");
        for (Item i : itens) {
            listaItens.addObject().put("codigo", i.codigo()).put("nome", i.nome())
                    .put("quantidade", i.quantidade()).put("precoUnitario", i.precoUnitario())
                    .put("total", i.total());
        }
        ArrayNode listaPagamentos = raiz.putArray("pagamentos");
        for (Pagamento p : pagamentos) {
            listaPagamentos.addObject().put("forma", p.forma()).put("valor", p.valor())
                    .put("recebido", p.recebido()).put("troco", p.troco());
        }
        ArrayNode listaNotas = raiz.putArray("notas");
        for (Nota n : notas) {
            listaNotas.addObject().put("id", n.id()).put("modelo", n.modelo()).put("numero", n.numero())
                    .put("serie", n.serie()).put("status", n.status()).put("chave", n.chave());
        }
        try {
            return JSON.writeValueAsString(raiz);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
