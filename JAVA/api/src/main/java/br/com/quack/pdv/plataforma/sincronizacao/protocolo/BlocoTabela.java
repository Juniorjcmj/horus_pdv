package br.com.quack.pdv.plataforma.sincronizacao.protocolo;

import java.util.List;

/**
 * Linhas de uma tabela dentro de uma parte: {@code linhas} sao upserts (valores na ordem de {@code colunas}) e
 * {@code exclusoes} sao chaves primarias (valores na ordem de {@code colunasChave}). Valor nulo = NULL.
 */
public record BlocoTabela(String tabela, List<ColunaSync> colunas, List<List<String>> linhas,
                          List<ColunaSync> colunasChave, List<List<String>> exclusoes) {}
