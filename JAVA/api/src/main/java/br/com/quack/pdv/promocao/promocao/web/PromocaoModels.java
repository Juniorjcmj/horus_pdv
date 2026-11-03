package br.com.quack.pdv.promocao.promocao.web;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public class PromocaoModels {
    public record Promocao(
            String id,
            String companyId,
            String nome,
            String tipo,
            BigDecimal valorDesconto,
            BigDecimal precoFixo,
            Integer quantidadeLeva,
            Integer quantidadePaga,
            Integer quantidadeMinima,
            OffsetDateTime inicioVigencia,
            OffsetDateTime fimVigencia,
            boolean ativa,
            String categoriaId,
            String categoriaNome,
            String criadoPor,
            OffsetDateTime criadoEm,
            List<String> produtoIds) {}

    public record Resultado(
            String promocaoId,
            String nome,
            int quantidadeVendas,
            BigDecimal receitaBruta,
            BigDecimal receitaLiquida,
            BigDecimal descontoTotal,
            BigDecimal margemLiquida) {}
}
