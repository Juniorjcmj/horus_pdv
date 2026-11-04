package br.com.quack.pdv.venda.fiado.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** DTOs da conta corrente do fiado (FiadoMovimentoAD, FiadoDevedorAD e FiadoResumoAD da API .NET). */
public final class FiadoDtos {

    private FiadoDtos() {}

    /** Movimento: tipo 1 = debito (compra a prazo), 2 = credito (recebimento ou estorno). */
    public record Movimento(
            @JsonProperty("id") String id,
            @JsonProperty("companyId") String companyId,
            @JsonProperty("clienteId") String clienteId,
            @JsonProperty("clienteNome") String clienteNome,
            @JsonProperty("clienteDocument") String clienteDocument,
            @JsonProperty("tipo") int tipo,
            @JsonProperty("valor") BigDecimal valor,
            @JsonProperty("saldoAnterior") BigDecimal saldoAnterior,
            @JsonProperty("saldoAtual") BigDecimal saldoAtual,
            @JsonProperty("vendaId") String vendaId,
            @JsonProperty("formaPagamento") String formaPagamento,
            @JsonProperty("observacao") String observacao,
            @JsonProperty("operadorNome") String operadorNome,
            @JsonProperty("criadoEm") OffsetDateTime criadoEm) {}

    public record Devedor(
            @JsonProperty("clienteId") String clienteId,
            @JsonProperty("clienteNome") String clienteNome,
            @JsonProperty("document") String document,
            @JsonProperty("telephone") String telephone,
            @JsonProperty("cellphone") String cellphone,
            @JsonProperty("limiteCredito") BigDecimal limiteCredito,
            @JsonProperty("saldoDevedor") BigDecimal saldoDevedor,
            @JsonProperty("ultimaCompra") OffsetDateTime ultimaCompra,
            @JsonProperty("diasSemPagamento") Integer diasSemPagamento) {}

    public record Resumo(
            @JsonProperty("totalAReceber") BigDecimal totalAReceber,
            @JsonProperty("quantidadeDevedores") int quantidadeDevedores,
            @JsonProperty("inadimplencia30Dias") BigDecimal inadimplencia30Dias,
            @JsonProperty("inadimplencia60Dias") BigDecimal inadimplencia60Dias,
            @JsonProperty("maiorDebito") BigDecimal maiorDebito) {}
}
