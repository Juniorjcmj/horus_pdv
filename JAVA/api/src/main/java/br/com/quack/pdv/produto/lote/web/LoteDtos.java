package br.com.quack.pdv.produto.lote.web;

import java.math.BigDecimal;
import java.util.List;

/** Modelos de resposta e de entrada do controle de validade por lote (portas de LoteModels.cs e LoteRequests.cs). */
public final class LoteDtos {

    private LoteDtos() {}

    /** Lote de um produto (porta de LoteModel). */
    public record LoteModel(
            String id,
            String produtoId,
            String numeroLote,
            String dataValidade,
            int diasParaVencer,
            BigDecimal qtdInicial,
            BigDecimal qtdEstimada,
            String origem,
            boolean validadePadrao,
            String criadoEm) {}

    /** Lote com dados do produto/categoria e faixa de alerta (porta de LoteAlertaModel). */
    public record LoteAlertaModel(
            String id,
            String produtoId,
            String numeroLote,
            String dataValidade,
            int diasParaVencer,
            BigDecimal qtdInicial,
            BigDecimal qtdEstimada,
            String origem,
            boolean validadePadrao,
            String criadoEm,
            String productCode,
            String productName,
            String categoriaNome,
            String categoriaId,
            String categoriaPaiId,
            int janelaAlertaDias,
            String faixa,
            BigDecimal custoUnitario,
            BigDecimal precoVenda,
            BigDecimal valorEmRisco) {}

    public record LoteAlertasResumoModel(
            int vencidos,
            int criticos,
            int atencao,
            BigDecimal valorEmRisco,
            int produtosSemLote,
            String modo,
            List<LoteAlertaModel> itens) {}

    public record LoteConsultaModel(
            int total,
            int pagina,
            int tamanhoPagina,
            BigDecimal valorEmRisco,
            String modo,
            List<LoteAlertaModel> itens) {}

    public record FefoDivergenciaModel(
            String produtoId,
            String productCode,
            String productName,
            String loteId,
            String numeroLote,
            String dataValidade,
            BigDecimal qtdEstimada,
            BigDecimal qtdReal,
            BigDecimal diferenca) {}

    public record FefoStatusModel(
            String modo,
            int lotesComSaldo,
            int lotesComDivergencia,
            int produtosComDivergencia,
            int produtosComEstoqueSemLote,
            List<FefoDivergenciaModel> itens) {}

    public record RegistrarLoteRequest(String produtoId, String dataValidade, BigDecimal quantidade, String numeroLote) {}

    public record FefoModoRequest(String modo) {}

    public record CategoriaValidadeRequest(Integer prazoPadraoDias, Integer diasAlerta) {}

    /** Filtros da consulta (query string de GET api/Produto/lotes/consulta). */
    public record LoteConsultaFiltro(
            String busca,
            String de,
            String ate,
            String categoriaId,
            String faixa,
            Boolean comSaldo,
            String origem,
            int pagina,
            int tamanhoPagina) {}
}
