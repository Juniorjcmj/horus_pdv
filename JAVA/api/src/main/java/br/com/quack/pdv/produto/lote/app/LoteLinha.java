package br.com.quack.pdv.produto.lote.app;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Lote + dados do produto/categoria necessarios para estimar saldo FEFO e montar alertas (porta de LoteLinha). */
public record LoteLinha(
        String id,
        String produtoId,
        String numeroLote,
        LocalDate dataValidade,
        BigDecimal qtdInicial,
        BigDecimal qtdAtual,
        String origem,
        boolean validadePadrao,
        OffsetDateTime criadoEm,
        String productCode,
        String productName,
        BigDecimal estoque,
        BigDecimal custoUnitario,
        BigDecimal precoVenda,
        int diasAlertaProduto,
        String categoriaNome,
        String categoriaId,
        String categoriaPaiId,
        Integer diasAlertaCategoria) {}
