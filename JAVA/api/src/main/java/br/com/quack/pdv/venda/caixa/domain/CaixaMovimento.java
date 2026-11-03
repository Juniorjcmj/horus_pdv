package br.com.quack.pdv.venda.caixa.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Sangria ou reforco de um turno (CaixaMovimentoAD). {@code tipo} e o byte gravado em CaixaMovimentos.Tipo. */
public record CaixaMovimento(
        String id,
        String caixaSessaoId,
        int tipo,
        BigDecimal valor,
        String motivo,
        OffsetDateTime createdAt,
        String operatorId,
        String operatorName) {}
