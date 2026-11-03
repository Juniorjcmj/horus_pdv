package br.com.quack.pdv.produto.produto.app;

import java.math.BigDecimal;
import java.time.LocalDate;

/**Projecao interna equivalente ao ProdutoAD da API .NET (valores numericos nativos lidos/gravados no SQL Server). */
public record ProdutoDados(
        String id,
        BigDecimal estoqueConsultado,
        String productImageUrl,
        String productImageName,
        String productName,
        String productCode,
        String productSupplier,
        String productDescription,
        BigDecimal productQnt,
        BigDecimal estoqueMinimo,
        BigDecimal productUnitPrice,
        BigDecimal productSalePrice,
        BigDecimal totalPriceOnProduct,
        BigDecimal lucro,
        BigDecimal margemDesejadaPercentual,
        String categoriaId,
        String categoriaNome,
        LocalDate dataValidade,
        boolean controlaValidade,
        int diasAlertaValidade,
        String unidadeCompra,
        BigDecimal fatorConversao,
        BigDecimal qtdEmbalagem,
        String marca,
        String fabricante,
        String referenciaFabricante,
        BigDecimal pesoLiquidoKg,
        BigDecimal pesoBrutoKg,
        BigDecimal larguraCm,
        BigDecimal alturaCm,
        BigDecimal comprimentoCm,
        BigDecimal estoqueMaximo,
        String localizacaoEstoque,
        BigDecimal custoMedio,
        BigDecimal custoComImposto,
        BigDecimal custoSemImposto,
        BigDecimal descontoMaximoPercentual,
        BigDecimal comissaoPercentual,
        BigDecimal markupCadastrado,
        BigDecimal markupPraticado,
        String ncm,
        String cest,
        String cfop,
        int origemMercadoria,
        String unidadeComercial,
        String unidadeTributavel,
        String gtin,
        String csosnIcms,
        String cstIcms,
        BigDecimal aliquotaIcms,
        String cstPis,
        String cstCofins,
        String cstIbsCbs,
        String cClassTrib) {

    public ProdutoDados comQuantidade(BigDecimal novaQuantidade) {
        return new ProdutoDados(
                id, estoqueConsultado, productImageUrl, productImageName, productName, productCode, productSupplier,
                productDescription, novaQuantidade, estoqueMinimo, productUnitPrice, productSalePrice,
                totalPriceOnProduct, lucro, margemDesejadaPercentual, categoriaId, categoriaNome, dataValidade,
                controlaValidade, diasAlertaValidade, unidadeCompra, fatorConversao, qtdEmbalagem, marca, fabricante,
                referenciaFabricante, pesoLiquidoKg, pesoBrutoKg, larguraCm, alturaCm, comprimentoCm, estoqueMaximo,
                localizacaoEstoque, custoMedio, custoComImposto, custoSemImposto, descontoMaximoPercentual,
                comissaoPercentual, markupCadastrado, markupPraticado, ncm, cest, cfop, origemMercadoria,
                unidadeComercial, unidadeTributavel, gtin, csosnIcms, cstIcms, aliquotaIcms, cstPis, cstCofins,
                cstIbsCbs, cClassTrib);
    }

    public ProdutoDados comPrecoVenda(BigDecimal novoPrecoVenda, BigDecimal novoLucro, BigDecimal novoMarkupPraticado) {
        return new ProdutoDados(
                id, estoqueConsultado, productImageUrl, productImageName, productName, productCode, productSupplier,
                productDescription, productQnt, estoqueMinimo, productUnitPrice, novoPrecoVenda,
                totalPriceOnProduct, novoLucro, margemDesejadaPercentual, categoriaId, categoriaNome, dataValidade,
                controlaValidade, diasAlertaValidade, unidadeCompra, fatorConversao, qtdEmbalagem, marca, fabricante,
                referenciaFabricante, pesoLiquidoKg, pesoBrutoKg, larguraCm, alturaCm, comprimentoCm, estoqueMaximo,
                localizacaoEstoque, custoMedio, custoComImposto, custoSemImposto, descontoMaximoPercentual,
                comissaoPercentual, markupCadastrado, novoMarkupPraticado, ncm, cest, cfop, origemMercadoria,
                unidadeComercial, unidadeTributavel, gtin, csosnIcms, cstIcms, aliquotaIcms, cstPis, cstCofins,
                cstIbsCbs, cClassTrib);
    }
}
