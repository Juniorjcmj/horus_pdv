package br.com.quack.pdv.venda.cancelamento.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** VendaCancelamentoAuditoriaAD da .NET: uma tentativa da trilha, autorizada ou negada. */
public record TrilhaLinha(String id, OffsetDateTime ocorridoEm, String resultado, String via, String motivoRecusa,
                          String saleNumber, String operadorId, String operadorNome, String supervisorId,
                          String supervisorNome, String justificativa, String terminal, String ip,
                          String caixaSessaoId, String caixaSessaoStatus, BigDecimal totalVenda, String clienteNome,
                          String clienteCpf, Integer itensEstornados, BigDecimal fiadoEstornado, JsonNode snapshot) {}
