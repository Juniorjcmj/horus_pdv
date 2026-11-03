package br.com.quack.pdv.promocao.promocao.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public class PromocaoRequest {
    public String id;
    public String nome = "";
    public String tipo = "";
    public BigDecimal valorDesconto;
    public BigDecimal precoFixo;
    public Integer quantidadeLeva;
    public Integer quantidadePaga;
    public Integer quantidadeMinima;

    @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
    @JsonSetter(nulls = Nulls.FAIL)
    public OffsetDateTime inicioVigencia = OffsetDateTime.parse("0001-01-01T00:00:00Z");

    @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
    @JsonSetter(nulls = Nulls.FAIL)
    public OffsetDateTime fimVigencia = OffsetDateTime.parse("0001-01-01T00:00:00Z");

    public boolean ativa = true;
    public String categoriaId;

    @JsonSetter(nulls = Nulls.FAIL)
    public List<String> produtoIds = new ArrayList<>();

    public static class Status {
        public boolean ativa;
    }
}
