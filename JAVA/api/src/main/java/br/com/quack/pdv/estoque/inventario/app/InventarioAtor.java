package br.com.quack.pdv.estoque.inventario.app;

/** Quem esta operando (usuario logado) e de onde (IP/terminal): vai para a trilha. */
public record InventarioAtor(String id, String nome, String ip, String terminal) {}
