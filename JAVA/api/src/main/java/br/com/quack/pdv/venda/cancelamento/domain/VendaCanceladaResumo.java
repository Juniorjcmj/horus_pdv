package br.com.quack.pdv.venda.cancelamento.domain;

import br.com.quack.pdv.venda.registro.domain.VendaHistoricoDto;
import java.math.BigDecimal;
import java.util.List;

/** VendaCanceladaResumoAD da .NET: venda cancelada com seus itens. */
public record VendaCanceladaResumo(String vendaId, String saleNumber, String customerName, String customerCpf,
                                   String paymentType, String totalAmount, String operatorName, String saleDate,
                                   String canceladoEm, String canceladoPorOperadorNome,
                                   String canceladoPorSupervisorNome, String canceladoJustificativa,
                                   int totalItens, BigDecimal totalQuantidadeItens, String itensResumo,
                                   List<VendaHistoricoDto> items) {}
