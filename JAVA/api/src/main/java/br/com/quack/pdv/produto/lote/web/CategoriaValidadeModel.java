package br.com.quack.pdv.produto.lote.web;

/** Categoria com configuracao de prazo padrao e alerta de validade (porta de CategoriaValidadeModel). */
public record CategoriaValidadeModel(
        String categoriaId,
        String nome,
        String categoriaPaiId,
        Integer prazoPadraoDias,
        Integer diasAlerta) {}
