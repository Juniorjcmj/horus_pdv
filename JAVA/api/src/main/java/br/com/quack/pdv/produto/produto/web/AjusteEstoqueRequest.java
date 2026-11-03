package br.com.quack.pdv.produto.produto.web;

import java.math.BigDecimal;

public record AjusteEstoqueRequest(
        String tipo,
        BigDecimal quantidade,
        String motivo,
        String dataValidade,
        String numeroLote) {}
