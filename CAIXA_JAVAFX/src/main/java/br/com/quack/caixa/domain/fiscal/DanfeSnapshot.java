package br.com.quack.caixa.domain.fiscal;

import java.math.BigDecimal;
import java.util.List;

/** Retrato da NFC-e lido do XML: o DANFE não consulta cadastro nem refaz cálculos (porta de danfeXml.ts). */
public record DanfeSnapshot(String chave, String numero, String serie, String ambiente, String emitidoEm,
        boolean contingencia, boolean autorizado, String protocolo, String autorizadoEm, Emitente emitente,
        Consumidor consumidor, List<Item> itens, BigDecimal total, BigDecimal desconto, BigDecimal frete,
        BigDecimal seguro, BigDecimal outros, BigDecimal pagar, List<Pagamento> pagamentos, BigDecimal troco,
        BigDecimal tributos, String qrCode, String consulta, List<String> mensagens) {

    public record Emitente(String nome, String fantasia, String cnpj, String endereco) {
    }

    public record Consumidor(String tipo, String documento, String nome, String endereco) {
    }

    public record Item(String codigo, String descricao, BigDecimal quantidade, String unidade, BigDecimal unitario,
            BigDecimal total) {
    }

    public record Pagamento(String forma, BigDecimal valor) {
    }
}
