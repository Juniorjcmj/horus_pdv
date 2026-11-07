package br.com.quack.pdv.plataforma.sincronizacao.nuvem;

import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Recursos que o servidor da loja anuncia (O9 da change api-java-operacoes-estoque-nuvem): a loja manda
 * {@code X-Quack-Recursos} no long-poll de comandos e o {@link ServidorLojaFilter} grava em
 * {@code SyncServidores.Recursos}. A nuvem so usa um recurso novo para a empresa cujo servidor ativo o anunciou.
 */
public final class RecursosLoja {

    public static final String CABECALHO = "X-Quack-Recursos";
    /** Recurso: a loja aplica o comando {@code operacao} (entrada, estorno e ajuste de estoque feitos na nuvem). */
    public static final String OPERACAO_ESTOQUE = "operacao-estoque";

    private RecursosLoja() {}

    /** O servidor ativo da empresa anunciou {@code recurso}? */
    public static boolean suporta(JdbcTemplate j, String companyId, String recurso) {
        List<String> r = j.query("SELECT Recursos FROM dbo.SyncServidores WHERE CompanyId = ? AND Status = N'ativo'",
                (rs, i) -> rs.getString(1), companyId);
        if (r.isEmpty() || r.getFirst() == null) return false;
        return Arrays.stream(r.getFirst().split(",")).map(String::trim).anyMatch(recurso::equals);
    }
}
