package br.com.quack.pdv.venda.caixa.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Turno de caixa (CaixaSessionAD da API .NET). Datas no offset em que foram gravadas. */
public record CaixaSessao(
        String id,
        OffsetDateTime openedAt,
        OffsetDateTime closedAt,
        BigDecimal openingAmount,
        BigDecimal closingAmount,
        String operatorId,
        String operatorName,
        String closedById,
        String closedByName,
        String note,
        BigDecimal expectedCashAmount,
        BigDecimal differenceAmount,
        String differenceReason) {}
