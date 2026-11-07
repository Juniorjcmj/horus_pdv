package br.com.quack.pdv.produto.importacao;

import br.com.quack.pdv.produto.produto.app.ProdutoDados;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Monta o {@link ProdutoDados} gravado pelo cadastro a partir de uma linha importada. Produto existente: os campos que a
 * planilha nao traz (imagem, validade, margem desejada, IBS/CBS...) e o saldo vem do cadastro atual; novo: padroes do
 * cadastro normal.
 */
final class ProdutoDadosImportacao {

    private static final BigDecimal CEM = new BigDecimal("100");
    private static final BigDecimal LIMITE_MARKUP = new BigDecimal("99999.9999");

    private ProdutoDadosImportacao() {}

    static ProdutoDados montar(String id, ProdutoImportado p, String categoriaId, ProdutoDados atual) {
        BigDecimal custoMedio = p.custoMedio().signum() == 0 ? p.custoCompra() : p.custoMedio();
        BigDecimal saldo = atual == null ? BigDecimal.ZERO : atual.productQnt();
        BigDecimal markupPraticado = custoMedio.signum() > 0
                ? p.precoVenda().subtract(custoMedio).divide(custoMedio, MathContext.DECIMAL128)
                        .multiply(CEM).setScale(4, RoundingMode.HALF_UP).min(LIMITE_MARKUP)
                : BigDecimal.ZERO;
        String categoria = categoriaId != null ? categoriaId : atual == null ? null : atual.categoriaId();
        return new ProdutoDados(
                id,
                null,
                atual == null ? "" : atual.productImageUrl(),
                atual == null ? "" : atual.productImageName(),
                p.descricao(),
                p.codigo(),
                p.fornecedor() == null ? "" : p.fornecedor(),
                p.descricao(),
                saldo,
                p.estoqueMinimo(),
                p.custoCompra(),
                p.precoVenda(),
                p.custoCompra().multiply(saldo),
                p.precoVenda().subtract(p.custoCompra()),
                atual == null ? null : atual.margemDesejadaPercentual(),
                categoria,
                null,
                atual == null ? null : atual.dataValidade(),
                atual != null && atual.controlaValidade(),
                atual == null ? 15 : atual.diasAlertaValidade(),
                p.unidadeCompra(),
                p.qtdEmbalagem(),
                p.qtdEmbalagem(),
                p.marca(),
                p.fabricante(),
                p.referenciaFabricante(),
                p.pesoLiquido(),
                p.pesoBruto(),
                p.largura(),
                p.altura(),
                p.comprimento(),
                p.estoqueMaximo(),
                p.localizacao(),
                custoMedio,
                p.custoCompra(),
                p.custoSemImposto(),
                p.descontoMaximo(),
                p.comissao(),
                p.markupCadastrado(),
                markupPraticado,
                p.ncm(),
                p.cest(),
                p.cfop(),
                p.origemMercadoria(),
                p.unidade(),
                p.unidade(),
                p.gtin(),
                p.csosnIcms(),
                p.cstIcms(),
                p.aliquotaIcms(),
                p.cstPis(),
                p.cstCofins(),
                atual == null ? null : atual.cstIbsCbs(),
                atual == null ? null : atual.cClassTrib());
    }
}
