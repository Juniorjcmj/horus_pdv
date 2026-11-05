package br.com.quack.pdv.plataforma.sincronizacao.integracao;

import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.EMPRESA;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.esperar;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.SincronizacaoIT.contar;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.fiscal.fila.app.FilaFiscalMinima;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fila fiscal no modo loja (tarefa 5.1, D8): a rotina so retoma vendas sem nota, a nota pendente de venda cancelada nao
 * muda, e a retomada roda por conta do envio (a rotina propria fica com intervalo de 1 h, para provar que foi o envio).
 * Os testes dependem da ordem (estado compartilhado).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FilaFiscalLojaIT {

    static AmbienteSync amb;
    static JdbcTemplate loja;
    static JdbcTemplate nuvem;
    static FilaFiscalMinima fila;

    @BeforeAll
    static void subir() {
        amb = AmbienteSync.iniciarComExtrasNaLoja("fiscal", "--quack.fiscal.fila-minima.ativa=true",
                "--quack.fiscal.fila-minima.intervalo-ms=3600000");
        loja = amb.jdbcLoja;
        nuvem = amb.jdbcNuvem;
        fila = amb.loja.getBean(FilaFiscalMinima.class);
        amb.esperarConvergencia(60_000);
    }

    @AfterAll
    static void descer() {
        if (amb != null) amb.close();
    }

    private static List<Map<String, Object>> notas(JdbcTemplate j, String vendaId) {
        return j.queryForList("SELECT Id, Modelo, Serie, NumeroNf, Ambiente, Status, MotivoStatus, UltimoErro, Tentativas "
                + "FROM DocumentosFiscais WHERE VendaId = ? ORDER BY Id", vendaId);
    }

    @Test
    @Order(1)
    void cancelamentoNaLojaNaoMudaANotaPendente() {
        loja.update("INSERT INTO DocumentosFiscais (Id, CompanyId, VendaId, Modelo, Serie, NumeroNf, Ambiente, Status, TpEmis) "
                + "VALUES (N'doc-it-loja-canc', ?, N'vd-ct-canc', 65, 2, 7, 2, 1, 1)", EMPRESA);
        loja.update("UPDATE Vendas SET EmitirNfce = 1 WHERE CompanyId = ? AND Id = N'vd-ct-canc'", EMPRESA);
        List<Map<String, Object>> antes = notas(loja, "vd-ct-canc");

        fila.executarAgendado();
        fila.processarLote();
        fila.executarAgendado();

        assertThat(antes).hasSize(1);
        assertThat(notas(loja, "vd-ct-canc")).isEqualTo(antes);
        assertThat(contar(loja, "SELECT COUNT(*) FROM AuditLog WHERE EventType = N'NfceNaoEmitida'")).isZero();
    }

    @Test
    @Order(2)
    void vendaSemNotaGanhaPendenciaComOProximoNumeroAntesDeSubir() {
        assertThat(notas(loja, "vd-ct-fin")).isEmpty();
        List<Integer> sequenciaAntes = loja.queryForList(
                "SELECT ProximoNumero FROM FiscalSequencias WHERE CompanyId = ? AND Modelo = 65", Integer.class, EMPRESA);
        int esperado = sequenciaAntes.isEmpty() ? 1 : sequenciaAntes.getFirst();
        // a venda decide emitir e fica sem nota; a rotina propria (1 h) nao retoma: quem retoma e o envio
        loja.update("UPDATE Vendas SET EmitirNfce = 1 WHERE CompanyId = ? AND Id = N'vd-ct-fin'", EMPRESA);

        esperar("pendencia gravada na loja pelo envio", 30_000, () -> !notas(loja, "vd-ct-fin").isEmpty());
        amb.esperarConvergencia(60_000);

        List<Map<String, Object>> daLoja = notas(loja, "vd-ct-fin");
        assertThat(daLoja).hasSize(1);
        assertThat(((Number) daLoja.getFirst().get("NumeroNf")).intValue()).isEqualTo(esperado);
        assertThat(((Number) daLoja.getFirst().get("Status")).intValue()).isEqualTo(1);
        // a venda sobe junto com a nota e o numero reservado; a sequencia da loja avanca tambem na nuvem
        assertThat(notas(nuvem, "vd-ct-fin")).isEqualTo(daLoja);
        assertThat(nuvem.queryForObject("SELECT ProximoNumero FROM FiscalSequencias WHERE CompanyId = ? AND Modelo = 65",
                Integer.class, EMPRESA)).isEqualTo(esperado + 1);
    }

    @Test
    @Order(3)
    void rotinaDaLojaNaoTocaOutrosRamosDaFila() {
        // nota pendente sem emitente: no modo padrao a fila marcaria erro; na loja isso fica com a .NET da nuvem
        List<Map<String, Object>> antes = notas(loja, "vd-ct-fin");

        fila.executarAgendado();
        fila.processarLote();

        assertThat(notas(loja, "vd-ct-fin")).isEqualTo(antes);
        assertThat(contar(loja, "SELECT COUNT(*) FROM DocumentosFiscais WHERE UltimoErro IS NOT NULL")).isZero();
    }

    @Test
    @Order(4)
    void lojaAindaNaoAtivadaNaoRetomaVendaSemNota() {
        // achado do aceite 8.2: durante a carga inicial as vendas chegam antes das notas e a retomada criaria notas
        // com numeros locais que colidem com os que ainda vao chegar da nuvem
        SincronizacaoIT.vendas = amb.loja.getBean(br.com.quack.pdv.venda.registro.app.VendaRegistroService.class);
        String vendaId = SincronizacaoIT.vender("CT001", "1", "3", "3,00").vendaId();
        loja.update("UPDATE Vendas SET EmitirNfce = 1 WHERE Id = ?", vendaId);
        loja.update("UPDATE SyncEstado SET Ativada = 0 WHERE Id = 1");
        try {
            fila.processarLote();
            fila.executarAgendado();
            assertThat(notas(loja, vendaId)).as("sem ativacao a fila nao escreve").isEmpty();
        } finally {
            loja.update("UPDATE SyncEstado SET Ativada = 1 WHERE Id = 1");
        }

        fila.processarLote();

        assertThat(notas(loja, vendaId)).as("ativada, a retomada volta a funcionar").hasSize(1);
    }
}
