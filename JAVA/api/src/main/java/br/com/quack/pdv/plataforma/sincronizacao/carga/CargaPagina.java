package br.com.quack.pdv.plataforma.sincronizacao.carga;

import br.com.quack.pdv.plataforma.sincronizacao.protocolo.ColunaSync;
import java.util.List;

/**
 * Uma pagina da carga inicial (D5): linhas da empresa de uma tabela em ordem crescente de chave. As colunas CIFRADA vem
 * decifradas (o transporte e TLS, D9) e a loja as cifra com a chave dela. {@code ultimaChave} e o cursor da proxima
 * pagina (nulo no fim); {@code fim} diz que a tabela acabou.
 */
public record CargaPagina(String tabela, long versao, List<ColunaSync> colunas, List<List<String>> linhas,
                          List<String> ultimaChave, boolean fim) {}
