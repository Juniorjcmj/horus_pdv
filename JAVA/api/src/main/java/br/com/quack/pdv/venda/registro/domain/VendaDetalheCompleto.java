package br.com.quack.pdv.venda.registro.domain;

import java.util.List;

/** VendaDetalheCompletoAD: cabecalho da venda, itens (linhas do historico) e pagamentos. */
public record VendaDetalheCompleto(String vendaId, String saleNumber, String status, String customerName,
                                   String customerCpf, String paymentType, String totalAmount, String operatorName,
                                   String saleDate, String clientSaleId, String offlineReference,
                                   List<VendaHistoricoDto> items, List<VendaPagamentoDto> payments) {}
