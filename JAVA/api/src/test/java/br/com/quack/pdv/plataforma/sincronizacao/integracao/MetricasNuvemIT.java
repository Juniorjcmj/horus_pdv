package br.com.quack.pdv.plataforma.sincronizacao.integracao;

import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.EMPRESA;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.SincronizacaoIT.vender;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.config.QuackProperties;
import br.com.quack.pdv.plataforma.monitoramento.MetricasSyncNuvem;
import br.com.quack.pdv.venda.registro.app.VendaRegistroService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Metricas Prometheus do receptor da nuvem (change api-java-monitoramento): protecao por token, histograma por rota,
 * metricas da sincronizacao por loja e metricas do relato de estado. O {@code MetricasSyncNuvem} e atualizado a mao
 * ({@code atualizar()}), com o agendamento afastado por um intervalo longo.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MetricasNuvemIT {

    static final String TOKEN_METRICAS = "tok-metricas-" + UUID.randomUUID();

    static AmbienteSync amb;
    static JdbcTemplate nuvem;
    static MetricasSyncNuvem metricas;
    /** Empresa so de comandos e quarentena: a loja do teste faz long-poll da dela e executaria os comandos de mentira. */
    static final String OUTRA = "empresa-cmd";
    static final String TOKEN_OUTRA = "tok-outra-" + UUID.randomUUID();

    @BeforeAll
    static void subir() {
        String[] semAgenda = {"--quack.metrics.intervalo-ms=3600000", "--quack.sync.alertas-ativo=false",
                "--quack.sync.conferencia-ativa=false"};
        String[] nuvemExtras = {"--quack.metrics.token=" + TOKEN_METRICAS, "--quack.metrics.intervalo-ms=3600000",
                "--quack.sync.alertas-ativo=false", "--quack.sync.conferencia-ativa=false"};
        amb = AmbienteSync.iniciarComExtras("met", nuvemExtras, semAgenda);
        nuvem = amb.jdbcNuvem;
        metricas = amb.nuvem.getBean(MetricasSyncNuvem.class);
        SincronizacaoIT.vendas = amb.loja.getBean(VendaRegistroService.class);
        amb.jdbcLoja.update("UPDATE Produtos SET ProductQnt = 1000 WHERE CompanyId = ? AND ProductCode IN ('CT001','CT002')", EMPRESA);
        amb.esperarConvergencia(60_000);
        amb.registrarServidor("srv-cmd", OUTRA, TOKEN_OUTRA, "ativo");
    }

    @AfterAll
    static void descer() {
        if (amb != null) amb.close();
    }

    // ------------------------------------------------------------------ apoio

    static HttpResponse<String> get(String caminho, String autorizacao) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(amb.urlBaseNuvem() + caminho)).GET();
        if (autorizacao != null) b.header("Authorization", autorizacao);
        return amb.http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String raspar() throws Exception {
        HttpResponse<String> r = get("/actuator/prometheus", "Bearer " + TOKEN_METRICAS);
        assertThat(r.statusCode()).isEqualTo(200);
        return r.body();
    }

    /** Linhas da raspagem de uma metrica cujos rotulos contem todos os trechos dados (ex.: {@code empresa="x"}). */
    static List<String> linhas(String corpo, String metrica, String... trechos) {
        return corpo.lines().filter(l -> l.startsWith(metrica + "{") || l.startsWith(metrica + " "))
                .filter(l -> java.util.Arrays.stream(trechos).allMatch(l::contains)).toList();
    }

    static double valor(String linha) {
        Matcher m = Pattern.compile("\\s([-+0-9.eE]+|NaN)$").matcher(linha);
        assertThat(m.find()).as("valor em " + linha).isTrue();
        return Double.parseDouble(m.group(1));
    }

    static double unico(String corpo, String metrica, String... trechos) {
        List<String> l = linhas(corpo, metrica, trechos);
        assertThat(l).as(metrica + " " + String.join(",", trechos)).hasSize(1);
        return valor(l.getFirst());
    }

    static String empresa() {
        return "empresa=\"" + EMPRESA + "\"";
    }

    static String outra() {
        return "empresa=\"" + OUTRA + "\"";
    }

    static void comando(String empresa, String status, int horasAtras) {
        nuvem.update("INSERT INTO SyncComandos (CompanyId, Tipo, Tabela, Chave, Status, AtualizadoEm) VALUES (?, N'alterar', N'Produtos', ?, ?, "
                + "DATEADD(HOUR, ?, SYSDATETIMEOFFSET()))", empresa, "k-" + UUID.randomUUID(), status, -horasAtras);
    }

    // ------------------------------------------------------------------ 1. rota protegida

    @Test
    @Order(1)
    void tokenCorretoDevolveMetricasEmTextoPrometheus() throws Exception {
        HttpResponse<String> r = get("/actuator/prometheus", "Bearer " + TOKEN_METRICAS);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("text/plain");
        assertThat(r.body()).contains("jvm_memory_used_bytes");
    }

    @Test
    @Order(2)
    void semTokenOuTokenErradoDa401SemMetricas() throws Exception {
        for (String auth : new String[] {null, "Bearer outro-token", "Basic abc", "Bearer "}) {
            HttpResponse<String> r = get("/actuator/prometheus", auth);
            assertThat(r.statusCode()).as("auth=" + auth).isEqualTo(401);
            assertThat(r.body()).doesNotContain("jvm_memory_used_bytes").doesNotContain("# HELP");
        }
    }

    @Test
    @Order(3)
    void healthContinuaAbertoEOutrasRotasDoActuatorNaoExistem() throws Exception {
        HttpResponse<String> health = get("/actuator/health", null);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");

        assertThat(get("/actuator/metrics", "Bearer " + TOKEN_METRICAS).statusCode()).isEqualTo(404);
        assertThat(get("/actuator/env", "Bearer " + TOKEN_METRICAS).statusCode()).isEqualTo(404);
        assertThat(get("/actuator/metrics", null).statusCode()).isEqualTo(404);
    }

    @Test
    @Order(4)
    void tokenConfiguradoNaPropriedade() {
        assertThat(amb.nuvem.getBean(QuackProperties.class).getMetrics().getToken()).isEqualTo(TOKEN_METRICAS);
    }

    // ------------------------------------------------------------------ 2. histograma por rota

    @Test
    @Order(5)
    void histogramaTemUmaSerieUnicaPorRotaSemOsParametros() throws Exception {
        String servidor = "LojaServidor " + amb.token;
        assertThat(get("/api/loja-sync/comandos?apos=10", servidor).statusCode()).isEqualTo(200);
        assertThat(get("/api/loja-sync/comandos?apos=20", servidor).statusCode()).isEqualTo(200);

        List<String> series = linhas(raspar(), "http_server_requests_seconds_bucket", "uri=\"/api/loja-sync/comandos\"", "le=\"+Inf\"");

        assertThat(series).hasSize(1);
        assertThat(series.getFirst()).contains("application=\"nuvem\"");
        assertThat(valor(series.getFirst())).isGreaterThanOrEqualTo(2);
    }

    // ------------------------------------------------------------------ 3. metricas da sincronizacao

    @Test
    @Order(10)
    void lojaSemContatoMostraOsSegundosDesdeOUltimoContato() throws Exception {
        nuvem.update("UPDATE SyncServidores SET UltimoContato = DATEADD(MINUTE, -10, SYSDATETIMEOFFSET()) WHERE Id = 'srv-it'");
        metricas.atualizar();

        assertThat(unico(raspar(), "quack_sync_ultimo_contato_segundos", empresa())).isGreaterThanOrEqualTo(600);
        assertThat(unico(raspar(), "quack_sync_servidor_ativo", empresa())).isEqualTo(1);
    }

    @Test
    @Order(11)
    void comandosEQuarentenaSaoContadosPorEmpresa() throws Exception {
        comando(OUTRA, "pendente", 0);
        comando(OUTRA, "pendente", 0);
        comando(OUTRA, "parado", 0);
        for (int i = 0; i < 3; i++) {
            nuvem.update("INSERT INTO SyncQuarentena (CompanyId, Tabela, Chave, Origem, Motivo) VALUES (?, N'Produtos', ?, N'edicao-nuvem', N'teste')",
                    OUTRA, "q-" + i);
        }
        metricas.atualizar();
        String corpo = raspar();

        assertThat(unico(corpo, "quack_sync_comandos", outra(), "status=\"pendente\"")).isEqualTo(2);
        assertThat(unico(corpo, "quack_sync_comandos", outra(), "status=\"parado\"")).isEqualTo(1);
        assertThat(unico(corpo, "quack_sync_quarentena_aberta", outra())).isEqualTo(3);
    }

    @Test
    @Order(12)
    void soConflitosDasUltimas24HorasContam() throws Exception {
        comando(OUTRA, "conflito", 2);
        comando(OUTRA, "conflito", 30);
        metricas.atualizar();

        assertThat(unico(raspar(), "quack_sync_comandos", outra(), "status=\"conflito_24h\"")).isEqualTo(1);
    }

    @Test
    @Order(13)
    void alertasDasUltimas24HorasPorMotivo() throws Exception {
        nuvem.update("INSERT INTO SyncAlertas (Motivo, CompanyId, Detalhe) VALUES (N'comando-parado', ?, N'teste')", OUTRA);
        metricas.atualizar();

        assertThat(unico(raspar(), "quack_sync_alertas", outra(), "motivo=\"comando-parado\"")).isEqualTo(1);
    }

    @Test
    @Order(14)
    void servidorRevogadoPerdeAsSeries() throws Exception {
        String extra = "empresa-extra-" + UUID.randomUUID().toString().substring(0, 8);
        amb.registrarServidor("srv-" + extra, extra, "tok-" + UUID.randomUUID(), "ativo");
        nuvem.update("UPDATE SyncServidores SET UltimoContato = SYSDATETIMEOFFSET() WHERE Id = ?", "srv-" + extra);
        metricas.atualizar();
        assertThat(linhas(raspar(), "quack_sync_ultimo_contato_segundos", "empresa=\"" + extra + "\"")).hasSize(1);

        nuvem.update("UPDATE SyncServidores SET Status = N'revogado', RevogadoEm = SYSDATETIMEOFFSET() WHERE Id = ?", "srv-" + extra);
        metricas.atualizar();
        String corpo = raspar();

        assertThat(corpo).doesNotContain("empresa=\"" + extra + "\"");
        assertThat(linhas(corpo, "quack_sync_ultimo_contato_segundos", empresa())).hasSize(1);
    }

    // ------------------------------------------------------------------ 4. contadores no codigo

    @Test
    @Order(20)
    void loteAplicadoIncrementaOContadorDeLotes() throws Exception {
        vender("CT001", "1", "3", "3,00");
        amb.esperarConvergencia(60_000);

        String corpo = raspar();

        assertThat(unico(corpo, "quack_sync_lotes_total", empresa(), "resultado=\"aplicado\"")).isGreaterThanOrEqualTo(1);
        assertThat(unico(corpo, "quack_sync_detector_ciclo_seconds_count")).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(22)
    void comandosEntreguesNoLongPollSaoContados() throws Exception {
        HttpResponse<String> r = get("/api/loja-sync/comandos?apos=0", "LojaServidor " + TOKEN_OUTRA);
        assertThat(r.statusCode()).isEqualTo(200);

        assertThat(unico(raspar(), "quack_sync_comandos_entregues_total", outra())).isGreaterThanOrEqualTo(2);
    }

    @Test
    @Order(21)
    void loteIlegivelIncrementaORecusado() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(amb.urlBaseNuvem() + "/api/loja-sync/lotes"))
                .header("Authorization", "LojaServidor " + amb.token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("lixo")).build();
        assertThat(amb.http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);

        assertThat(unico(raspar(), "quack_sync_lotes_total", empresa(), "resultado=\"recusado\"")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 5. metricas do relato

    private static void relato(String empresa, int horasBackup, boolean sucesso) {
        nuvem.update("DELETE FROM SyncEstadoLoja WHERE CompanyId = ?", empresa);
        nuvem.update("""
                INSERT INTO SyncEstadoLoja (CompanyId, RecebidoEm, GeradoEm, AtrasoSegundos, Pausada, UltimoBackupEm, BackupSucesso,
                                            BackupErro, NfcePendentes, VersaoPrograma)
                VALUES (?, SYSDATETIMEOFFSET(), SYSDATETIMEOFFSET(), 42, 0, DATEADD(HOUR, ?, SYSDATETIMEOFFSET()), ?, ?, 3, N'1.2.3-it')
                """, empresa, -horasBackup, sucesso, sucesso ? null : "erro 1844");
    }

    @Test
    @Order(30)
    void backupAtrasadoFicaVisivelComSucessoDaUltimaTentativa() throws Exception {
        relato(EMPRESA, 30, true);
        metricas.atualizar();
        String corpo = raspar();

        assertThat(unico(corpo, "quack_loja_backup_idade_segundos", empresa())).isGreaterThanOrEqualTo(108_000);
        assertThat(unico(corpo, "quack_loja_backup_sucesso", empresa())).isEqualTo(1);
        assertThat(unico(corpo, "quack_loja_atraso_envio_segundos", empresa())).isEqualTo(42);
        assertThat(unico(corpo, "quack_loja_nfce_pendentes", empresa())).isEqualTo(3);
        assertThat(unico(corpo, "quack_loja_pausada", empresa())).isEqualTo(0);
        assertThat(unico(corpo, "quack_loja_versao_programa", empresa(), "versao=\"1.2.3-it\"")).isEqualTo(1);
        assertThat(unico(corpo, "quack_loja_relato_idade_segundos", empresa())).isLessThan(120);
    }

    @Test
    @Order(31)
    void backupQueFalhouMostraSucessoZero() throws Exception {
        relato(EMPRESA, 30, false);
        metricas.atualizar();

        assertThat(unico(raspar(), "quack_loja_backup_sucesso", empresa())).isEqualTo(0);
    }

    @Test
    @Order(32)
    void lojaComProgramaAntigoNaoTemSeriesDeRelatoMasTemAsDeSincronizacao() throws Exception {
        nuvem.update("DELETE FROM SyncEstadoLoja WHERE CompanyId = ?", EMPRESA);
        metricas.atualizar();
        String corpo = raspar();

        assertThat(corpo.lines().filter(l -> l.startsWith("quack_loja_") && l.contains(empresa()))).isEmpty();
        assertThat(linhas(corpo, "quack_sync_ultimo_contato_segundos", empresa())).hasSize(1);
        assertThat(linhas(corpo, "quack_sync_comandos", empresa())).hasSize(4);
    }

    // ------------------------------------------------------------------ 6. falha de consulta

    @Test
    @Order(40)
    void erroDeConsultaMantemORetratoAnteriorEAMetricaSegueResponde() throws Exception {
        AtomicBoolean falhar = new AtomicBoolean();
        JdbcTemplate instavel = new JdbcTemplate(amb.nuvem.getBean(DataSource.class)) {
            @Override
            public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
                if (falhar.get()) throw new org.springframework.jdbc.CannotGetJdbcConnectionException("banco fora (teste)");
                return super.query(sql, rowMapper);
            }
        };
        SimpleMeterRegistry registro = new SimpleMeterRegistry();
        var fabrica = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        fabrica.addBean("jdbc", instavel);
        MetricasSyncNuvem isolada = new MetricasSyncNuvem(fabrica.getBeanProvider(JdbcTemplate.class), registro, Clock.systemUTC());

        isolada.atualizar();
        double antes = registro.get("quack_sync_quarentena_aberta").tag("empresa", OUTRA).gauge().value();
        falhar.set(true);
        isolada.atualizar(); // nao lanca

        assertThat(antes).isEqualTo(3);
        assertThat(registro.get("quack_sync_quarentena_aberta").tag("empresa", OUTRA).gauge().value()).isEqualTo(antes);
        assertThat(get("/actuator/prometheus", "Bearer " + TOKEN_METRICAS).statusCode()).isEqualTo(200);
    }

    @Test
    @Order(41)
    void cicloDasMetricasEMedido() throws Exception {
        assertThat(unico(raspar(), "quack_sync_metricas_ciclo_seconds_count")).isGreaterThanOrEqualTo(1);
    }
}
