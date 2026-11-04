package br.com.quack.pdv.fiscal.fila;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.BancoIT;
import br.com.quack.pdv.contrato.EstadoDoBanco;
import br.com.quack.pdv.fiscal.fila.app.FilaFiscalMinima;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Com {@code quack.fiscal.fila-minima.ativa=false} (padrao do perfil "it") a rotina agendada nao muda nenhuma nota nem
 * grava trilha, mesmo com nota de venda cancelada e venda sem documento esperando na fila.
 */
@SpringBootTest
@ActiveProfiles("it")
class FilaFiscalDesligadaIT {

    static {
        BancoIT.criarBanco("filadesligada");
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> BancoIT.urlDoBanco("filadesligada"));
        r.add("spring.datasource.username", BancoIT.SQL::getUsername);
        r.add("spring.datasource.password", BancoIT.SQL::getPassword);
        r.add("quack.fiscal.fila-minima.ativa", () -> "false");
    }

    @Autowired FilaFiscalMinima fila;
    @Autowired JdbcTemplate jdbc;

    private List<Map<String, Object>> fotografia() {
        return jdbc.queryForList("SELECT Id, VendaId, Status, MotivoStatus, UltimoErro, Tentativas, "
                + "ProximaTentativaEm, AtualizadoEm FROM DocumentosFiscais ORDER BY Id");
    }

    @Test
    void rotinaDesligadaNaoMudaNenhumaNotaNemGravaTrilhaNemCriaPendencia() {
        EstadoDoBanco.por(BancoIT.urlDoBanco("filadesligada"), BancoIT.SQL.getUsername(), BancoIT.SQL.getPassword())
                .restaurar();
        jdbc.update("INSERT INTO DocumentosFiscais (Id, CompanyId, VendaId, Modelo, Serie, NumeroNf, Ambiente, Status,"
                + " TpEmis) VALUES (N'doc-it-desl', N'empresa-principal', N'vd-ct-canc', 65, 2, 7, 2, 1, 1)");
        jdbc.update("UPDATE Vendas SET EmitirNfce = 1 WHERE Id = N'vd-ct-fin'"); // sem documento: seria retomada
        List<Map<String, Object>> antes = fotografia();

        fila.executarAgendado();
        fila.executarAgendado();

        assertThat(fotografia()).isEqualTo(antes);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM DocumentosFiscais WHERE VendaId = N'vd-ct-fin'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AuditLog WHERE EventType = N'NfceNaoEmitida'",
                Integer.class)).isZero();
    }
}
