package br.com.quack.pdv.venda.cancelamento.domain;

import java.math.BigDecimal;

/** Uma linha da trilha a gravar (VendaCancelamentoRegistro da .NET). */
public record TrilhaRegistro(String companyId, String resultado, String via, String motivoRecusa, String saleNumber,
                             String operadorId, String operadorNome, String supervisorId, String supervisorNome,
                             String justificativa, String terminal, String ip, Integer itensEstornados,
                             BigDecimal fiadoEstornado, VendaSnapshot snapshot) {}
