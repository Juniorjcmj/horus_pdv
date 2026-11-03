package br.com.quack.pdv.produto.produto.web;

public record VencimentoResumoModel(
        int vencidos,
        int venceEm7Dias,
        int venceEm15Dias,
        int venceEm30Dias,
        int totalControlados,
        int semDataInformada) {}
