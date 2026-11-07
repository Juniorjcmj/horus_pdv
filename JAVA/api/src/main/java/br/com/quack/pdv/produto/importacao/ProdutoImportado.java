package br.com.quack.pdv.produto.importacao;

import java.math.BigDecimal;

/**
 * Uma linha da planilha ja lida e validada, com os campos que o cadastro de produtos recebe (I3). Sem banco.
 * {@code saldoInicial} e sempre maior ou igual a zero (saldo negativo do arquivo entra como zero, com aviso).
 */
public record ProdutoImportado(
        int linha,
        String codigo,
        String descricao,
        String gtin,
        String unidade,
        String unidadeCompra,
        BigDecimal qtdEmbalagem,
        String marca,
        String fabricante,
        String referenciaFabricante,
        String fornecedor,
        String ncm,
        String cest,
        String cfop,
        int origemMercadoria,
        String cstIcms,
        String csosnIcms,
        BigDecimal aliquotaIcms,
        String cstPis,
        String cstCofins,
        BigDecimal precoVenda,
        BigDecimal custoCompra,
        BigDecimal custoMedio,
        BigDecimal custoSemImposto,
        BigDecimal estoqueMinimo,
        BigDecimal estoqueMaximo,
        String localizacao,
        BigDecimal comissao,
        BigDecimal descontoMaximo,
        BigDecimal markupCadastrado,
        BigDecimal markupPraticado,
        BigDecimal pesoLiquido,
        BigDecimal pesoBruto,
        BigDecimal largura,
        BigDecimal altura,
        BigDecimal comprimento,
        BigDecimal saldoInicial,
        String classe,
        String grupo,
        String subGrupo) {}
