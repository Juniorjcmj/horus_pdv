package br.com.quack.pdv.plataforma.sincronizacao.integracao;

import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.EMPRESA;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.SincronizacaoIT.contar;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.SincronizacaoIT.vender;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.plataforma.monitoramento.MetricasLoja;
import br.com.quack.pdv.plataforma.monitoramento.RelatorEstadoLoja;
import br.com.quack.pdv.venda.registro.app.VendaRegistroService;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Relato de estado da loja a nuvem (tarefas 3.2 e 3.4) e metricas locais da loja (4.1). A loja do teste tem um
 * {@code backup-estado.json} de 30 h atras e o interruptor de "sem internet" comanda as falhas.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RelatoEstadoIT {

    static final String TOKEN_METRICAS_LOJA = "tok-metricas-loja-" + UUID.randomUUID();

    static AmbienteSync amb;
    static JdbcTemplate loja;
    static JdbcTemplate nuvem;
    static Path arquivoBackup;
    static OffsetDateTime backupEm;

    @BeforeAll
    static void subir() throws Exception {
        backupEm = OffsetDateTime.now().minusHours(30).withNano(0);
        arquivoBackup = Files.createTempFile("backup-estado-relato", ".json");
        Files.writeString(arquivoBackup, "{\"ultimoBackupEm\":\"" + backupEm + "\",\"sucesso\":true,\"erro\":null}");
        String[] nuvemExtras = {"--quack.sync.alertas-ativo=false", "--quack.sync.conferencia-ativa=false",
                "--quack.metrics.intervalo-ms=3600000"};
        String[] lojaExtras = {"--quack.sync.alertas-ativo=false", "--quack.sync.conferencia-ativa=false",
                "--quack.metrics.intervalo-ms=3600000", "--quack.metrics.relato-intervalo-ms=3600000",
                "--quack.metrics.token=" + TOKEN_METRICAS_LOJA, "--quack.sync.backup-estado=" + arquivoBackup};
        amb = AmbienteSync.iniciarComExtras("rel", nuvemExtras, lojaExtras);
        loja = amb.jdbcLoja;
        nuvem = amb.jdbcNuvem;
        SincronizacaoIT.vendas = amb.loja.getBean(VendaRegistroService.class);
        loja.update("UPDATE Produtos SET ProductQnt = 1000 WHERE CompanyId = ? AND ProductCode IN ('CT001','CT002')", EMPRESA);
        amb.esperarConvergencia(60_000);
    }

    @AfterAll
    static void descer() throws Exception {
        amb.cliente().setSemInternet(false);
        if (amb != null) amb.close();
        if (arquivoBackup != null) Files.deleteIfExists(arquivoBackup);
    }

    // ------------------------------------------------------------------ apoio

    static HttpResponse<String> postEstado(String autorizacao, String corpo) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(amb.urlBaseNuvem() + "/api/loja-sync/estado"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(corpo));
        if (autorizacao != null) b.header("Authorization", autorizacao);
        return amb.http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static Map<String, Object> relatoGravado() {
        List<Map<String, Object>> l = nuvem.queryForList("SELECT * FROM SyncEstadoLoja WHERE CompanyId = ?", EMPRESA);
        return l.isEmpty() ? null : l.getFirst();
    }

    static int alertasDaLoja() {
        return contar(loja, "SELECT COUNT(*) FROM SyncAlertas");
    }

    static int alertasDaNuvem() {
        return contar(nuvem, "SELECT COUNT(*) FROM SyncAlertas");
    }

    // ------------------------------------------------------------------ nuvem: a rota

    @Test
    @Order(1)
    void relatoAceitoSubstituiOAnterior() throws Exception {
        String auth = "LojaServidor " + amb.token;
        String primeiro = "{\"geradoEm\":\"2026-11-07T12:00:00Z\",\"atrasoSegundos\":5,\"pausada\":false,"
                + "\"ultimoBackupEm\":\"2026-11-07T02:00:00-03:00\",\"backupSucesso\":true,\"backupErro\":null,"
                + "\"nfcePendentes\":2,\"versaoPrograma\":\"1.0.0\"}";
        HttpResponse<String> r1 = postEstado(auth, primeiro);
        assertThat(r1.statusCode()).isEqualTo(200);
        assertThat(relatoGravado()).containsEntry("AtrasoSegundos", 5L).containsEntry("NfcePendentes", 2)
                .containsEntry("VersaoPrograma", "1.0.0");
        OffsetDateTime recebido1 = nuvem.queryForObject("SELECT RecebidoEm FROM SyncEstadoLoja WHERE CompanyId = ?",
                OffsetDateTime.class, EMPRESA);

        String segundo = primeiro.replace("\"atrasoSegundos\":5", "\"atrasoSegundos\":99").replace("\"backupSucesso\":true", "\"backupSucesso\":false")
                .replace("\"backupErro\":null", "\"backupErro\":\"" + "x".repeat(900) + "\"");
        HttpResponse<String> r2 = postEstado(auth, segundo);

        assertThat(r2.statusCode()).isEqualTo(200);
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM SyncEstadoLoja WHERE CompanyId = ?", EMPRESA)).isEqualTo(1);
        Map<String, Object> g = relatoGravado();
        assertThat(g).containsEntry("AtrasoSegundos", 99L).containsEntry("BackupSucesso", false);
        assertThat((String) g.get("BackupErro")).hasSize(500);
        OffsetDateTime recebido2 = nuvem.queryForObject("SELECT RecebidoEm FROM SyncEstadoLoja WHERE CompanyId = ?",
                OffsetDateTime.class, EMPRESA);
        assertThat(recebido2).isAfterOrEqualTo(recebido1);
    }

    @Test
    @Order(0)
    void migracaoV3ReexecutadaNaoFalhaENaoApagaORelato() throws Exception {
        String sql = new String(getClass().getResourceAsStream("/db/nuvem/V3__estado_loja.sql").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        int antes = contar(nuvem, "SELECT COUNT(*) FROM SyncEstadoLoja");

        nuvem.execute(sql);
        nuvem.execute(sql);

        assertThat(contar(nuvem, "SELECT COUNT(*) FROM SyncEstadoLoja")).isEqualTo(antes);
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM flyway_quack_sync WHERE version = '3' AND success = 1")).isEqualTo(1);
    }

    @Test
    @Order(2)
    void tokenInvalidoDa401ENadaEGravado() throws Exception {
        nuvem.update("DELETE FROM SyncEstadoLoja WHERE CompanyId = ?", EMPRESA);

        assertThat(postEstado("LojaServidor token-que-nao-existe", "{\"atrasoSegundos\":1}").statusCode()).isEqualTo(401);
        assertThat(postEstado(null, "{\"atrasoSegundos\":1}").statusCode()).isEqualTo(401);

        assertThat(contar(nuvem, "SELECT COUNT(*) FROM SyncEstadoLoja")).isZero();
    }

    @Test
    @Order(3)
    void corpoMaiorQue4KbDa413() throws Exception {
        String grande = "{\"backupErro\":\"" + "y".repeat(5000) + "\"}";

        assertThat(postEstado("LojaServidor " + amb.token, grande).statusCode()).isEqualTo(413);
        assertThat(relatoGravado()).isNull();
    }

    @Test
    @Order(4)
    void corpoQueNaoEJsonDa400() throws Exception {
        assertThat(postEstado("LojaServidor " + amb.token, "lixo{").statusCode()).isEqualTo(400);
        assertThat(relatoGravado()).isNull();
    }

    // ------------------------------------------------------------------ loja: o relato

    @Test
    @Order(10)
    void lojaRelataENuvemGuardaBackupAtrasoEVersao() {
        RelatorEstadoLoja relator = amb.loja.getBean(RelatorEstadoLoja.class);

        assertThat(relator.relatar()).isEqualTo(RelatorEstadoLoja.Resultado.ENVIADO);

        Map<String, Object> g = relatoGravado();
        assertThat(g).isNotNull();
        assertThat(g.get("BackupSucesso")).isEqualTo(true);
        assertThat(g.get("AtrasoSegundos")).isNotNull();
        assertThat(((OffsetDateTime) nuvem.queryForObject("SELECT UltimoBackupEm FROM SyncEstadoLoja WHERE CompanyId = ?",
                OffsetDateTime.class, EMPRESA)).toInstant()).isEqualTo(backupEm.toInstant());
        assertThat((String) g.get("VersaoPrograma")).isNotBlank();
        assertThat(g.get("Pausada")).isEqualTo(false);
    }

    @Test
    @Order(11)
    void semInternetORelatoEDescartadoSemAlertaNemFila() {
        RelatorEstadoLoja relator = amb.loja.getBean(RelatorEstadoLoja.class);
        int alertasLoja = alertasDaLoja();
        int alertasNuvem = alertasDaNuvem();
        OffsetDateTime antes = nuvem.queryForObject("SELECT RecebidoEm FROM SyncEstadoLoja WHERE CompanyId = ?", OffsetDateTime.class, EMPRESA);

        amb.cliente().setSemInternet(true);
        try {
            assertThat(relator.relatar()).isEqualTo(RelatorEstadoLoja.Resultado.DESCARTADO);
        } finally {
            amb.cliente().setSemInternet(false);
        }

        assertThat(alertasDaLoja()).isEqualTo(alertasLoja);
        assertThat(alertasDaNuvem()).isEqualTo(alertasNuvem);
        assertThat(nuvem.queryForObject("SELECT RecebidoEm FROM SyncEstadoLoja WHERE CompanyId = ?", OffsetDateTime.class, EMPRESA))
                .isEqualTo(antes);
    }

    @Test
    @Order(12)
    void rotaDeEstadoComDefeitoNaoAtrapalhaOEnvioDeLotes() throws Exception {
        RelatorEstadoLoja relator = amb.loja.getBean(RelatorEstadoLoja.class);
        int alertasLoja = alertasDaLoja();
        int alertasNuvem = alertasDaNuvem();
        nuvem.execute("EXEC sp_rename N'dbo.SyncEstadoLoja', N'SyncEstadoLoja_fora'");
        try {
            assertThat(relator.relatar()).isEqualTo(RelatorEstadoLoja.Resultado.DESCARTADO); // a nuvem responde 500
            vender("CT001", "1", "3", "3,00");
            amb.esperarConvergencia(60_000);
        } finally {
            nuvem.execute("EXEC sp_rename N'dbo.SyncEstadoLoja_fora', N'SyncEstadoLoja'");
        }

        assertThat(amb.diferencas(true)).isEmpty();
        assertThat(alertasDaLoja()).isEqualTo(alertasLoja);
        assertThat(alertasDaNuvem()).isEqualTo(alertasNuvem);
        assertThat(amb.loja.getBean(RelatorEstadoLoja.class).relatar()).isEqualTo(RelatorEstadoLoja.Resultado.ENVIADO);
    }

    // ------------------------------------------------------------------ loja: metricas locais

    static double metricaDaLoja(String corpo, String nome) {
        Matcher m = Pattern.compile("(?m)^" + nome + "(\\{[^}]*\\})? ([-+0-9.eE]+)$").matcher(corpo);
        assertThat(m.find()).as(nome + " na raspagem da loja").isTrue();
        return Double.parseDouble(m.group(2));
    }

    @Test
    @Order(20)
    void semInternetHaQuinzeMinutosAsMetricasDaLojaMostramOAtraso() throws Exception {
        amb.cliente().setSemInternet(true);
        try {
            vender("CT001", "1", "3", "3,00");
            loja.update("UPDATE SyncEstado SET UltimaSincronizacaoEm = DATEADD(MINUTE, -20, SYSDATETIMEOFFSET()) WHERE Id = 1");
            amb.loja.getBean(MetricasLoja.class).atualizar();

            HttpRequest req = HttpRequest.newBuilder(URI.create(amb.urlBaseLoja() + "/actuator/prometheus"))
                    .header("Authorization", "Bearer " + TOKEN_METRICAS_LOJA).GET().build();
            HttpResponse<String> r = amb.http.send(req, HttpResponse.BodyHandlers.ofString());

            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(metricaDaLoja(r.body(), "quack_loja_atraso_envio_segundos")).isGreaterThanOrEqualTo(900);
            assertThat(metricaDaLoja(r.body(), "quack_loja_backup_idade_segundos")).isGreaterThanOrEqualTo(108_000);
            assertThat(metricaDaLoja(r.body(), "quack_loja_backup_sucesso")).isEqualTo(1);
            assertThat(metricaDaLoja(r.body(), "quack_loja_pausada")).isEqualTo(0);
            assertThat(r.body()).contains("application=\"loja\"").contains("quack_loja_versao_programa{");
        } finally {
            amb.cliente().setSemInternet(false);
        }
        amb.esperarConvergencia(60_000);
    }

    @Test
    @Order(21)
    void metricasDaLojaSemCabecalhoDa401() throws Exception {
        // a loja deste teste tem token; sem cabecalho e 401
        HttpRequest req = HttpRequest.newBuilder(URI.create(amb.urlBaseLoja() + "/actuator/prometheus")).GET().build();

        assertThat(amb.http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }
}
