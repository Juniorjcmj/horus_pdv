package br.com.quack.pdv.plataforma.sincronizacao.protocolo;

/** Nome e tipo de uma coluna no lote. */
public record ColunaSync(String nome, TipoColuna tipo) {}
