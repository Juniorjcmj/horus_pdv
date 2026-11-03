package br.com.quack.pdv.estoque.movimentacao.web;

import java.math.BigDecimal;

public record EstoqueMovimentacaoDto(
        String id,
        String produtoId,
        String produtoNome,
        String produtoCodigo,
        String tipo,
        BigDecimal quantidade,
        BigDecimal estoqueAnterior,
        BigDecimal estoqueNovo,
        String motivo,
        String usuarioId,
        String usuarioNome,
        String criadoEm) {}
