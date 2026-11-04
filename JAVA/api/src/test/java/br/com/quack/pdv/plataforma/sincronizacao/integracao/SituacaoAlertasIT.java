package br.com.quack.pdv.plataforma.sincronizacao.integracao;

import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.EMPRESA;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.esperar;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.SincronizacaoIT.contar;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.SincronizacaoIT.vender;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.config.QuackProperties;
import br.com.quack.pdv.fiscal.fila.app.PendenciaFiscal;
import br.com.quack.pdv.plataforma.sincronizacao.alertas.AlertasSync;
import br.com.quack.pdv.plataforma.sincronizacao.alertas.EnviadorAlertas;
import br.com.quack.pdv.plataforma.sincronizacao.alertas.VerificadorAlertasLoja;
import br.com.quack.pdv.plataforma.sincronizacao.envio.EnvioSincronizacao;
import br.com.quack.pdv.plataforma.sincronizacao.situacao.SituacaoLoja;
import br.com.quack.pdv.plataforma.sincronizacao.situacao.SituacaoNuvem;
import br.com.quack.pdv.venda.registro.app.VendaRegistroService;
import br.com.quack.pdv.venda.registro.domain.VendaRegistroResultado;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Situacao e alertas (tarefa 6.2, D11). As rotinas agendadas de alerta ficam desligadas ({@code alertas-ativo=false}) e o
 * teste as chama com o "agora" que quer (relogio fixo). O SMTP e um GreenMail local; o e-mail sai pelo EmailService.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SituacaoAlertasIT {

    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    static GreenMail smtp;

    static AmbienteSync amb;
    static JdbcTemplate loja;
    static JdbcTemplate nuvem;
    static Path arquivoBackup;
    static int administradores;

    @BeforeAll
    static void subir() throws Exception {
        smtp = new GreenMail(ServerSetupTest.SMTP.dynamicPort());
        smtp.setUser("robo", "segredo123");
        smtp.start();
        arquivoBackup = Files.createTempFile("backup-estado", ".json");
        Files.writeString(arquivoBackup, "{\"ultimoBackupEm\":\"2026-11-04T02:00:00Z\",\"sucesso\":true,\"erro\":null}");
        String[] comuns = {"--quack.sync.alertas-ativo=false", "--quack.sync.conferencia-ativa=false", "--quack.email.enabled=true",
                "--quack.email.host=localhost", "--quack.email.port=" + smtp.getSmtp().getPort(), "--quack.email.enable-ssl=false",
                "--quack.email.user=robo", "--quack.email.password=segredo123", "--quack.email.from-email=naoresponder@quack.com"};
        String[] extrasLoja = java.util.stream.Stream.concat(java.util.Arrays.stream(comuns),
                java.util.stream.Stream.of("--quack.sync.backup-estado=" + arquivoBackup)).toArray(String[]::new);
        amb = AmbienteSync.iniciarComExtras("sit", comuns, extrasLoja);
        loja = amb.jdbcLoja;
        nuvem = amb.jdbcNuvem;
        SincronizacaoIT.vendas = amb.loja.getBean(VendaRegistroService.class);
        loja.update("UPDATE Produtos SET ProductQnt = 1000 WHERE CompanyId = ? AND ProductCode IN ('CT001','CT002')", EMPRESA);
        amb.esperarConvergencia(60_000);
        administradores = contar(loja, "SELECT COUNT(*) FROM Usuarios WHERE CompanyId = ? AND Role = N'administrador' AND Status = N'ativo'", EMPRESA);
        assertThat(administradores).isGreaterThanOrEqualTo(1);
    }

    @AfterAll
    static void descer() throws Exception {
        if (amb != null) amb.close();
        if (smtp != null) smtp.stop();
        if (arquivoBackup != null) Files.deleteIfExists(arquivoBackup);
    }

    // ------------------------------------------------------------------ helpers

    static HttpResponse<String> get(String base, String caminho, String autorizacao) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + caminho)).GET();
        if (autorizacao != null) b.header("Authorization", autorizacao);
        return amb.http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static int emailsDe(String motivo) throws Exception {
        int n = 0;
        for (MimeMessage m : smtp.getReceivedMessages()) if (m.getSubject().contains(motivo)) n++;
        return n;
    }

    // ------------------------------------------------------------------ situacao (D11)

    @Test
    @Order(1)
    void situacaoDaNuvemAdministrador200CaixaOuSemCredencial403Ou401TokenDoServidor200() throws Exception {
        String admin = amb.tokenNuvem("usr-ct-admin", EMPRESA, "Ana Administradora", "admin@contrato.quack", "administrador");
        String caixa = amb.tokenNuvem("usr-ct-caixa", EMPRESA, "Cai Caixa", "caixa@contrato.quack", "caixa");

        HttpResponse<String> comAdmin = get(amb.urlBaseNuvem(), "/api/loja-sync/situacao", "Bearer " + admin);
        assertThat(comAdmin.statusCode()).isEqualTo(200);
        JsonNode d = JSON.readTree(comAdmin.body()).path("data");
        assertThat(d.path("origem").asText()).isEqualTo("nuvem");
        assertThat(d.path("companyId").asText()).isEqualTo(EMPRESA);
        assertThat(d.path("versaoConfirmada").asLong()).isPositive();
        assertThat(d.path("comandosPendentes").asInt()).isZero();
        assertThat(d.path("comandosParados").asInt()).isZero();
        assertThat(d.has("quarentenaAberta")).isTrue();
        assertThat(d.path("servidorStatus").asText()).isEqualTo("ativo");

        assertThat(get(amb.urlBaseNuvem(), "/api/loja-sync/situacao", "Bearer " + caixa).statusCode()).isEqualTo(403);
        assertThat(get(amb.urlBaseNuvem(), "/api/loja-sync/situacao", null).statusCode()).isEqualTo(401);
        assertThat(get(amb.urlBaseNuvem(), "/api/loja-sync/situacao", "LojaServidor " + amb.token).statusCode()).isEqualTo(200);
        assertThat(get(amb.urlBaseNuvem(), "/api/loja-sync/situacao", "LojaServidor token-errado").statusCode()).isEqualTo(401);
    }

    @Test
    @Order(2)
    void situacaoDaLojaMostraAtrasoComandosEConferenciaEBloqueiaCaixa() throws Exception {
        String admin = amb.tokenLoja("usr-ct-admin", EMPRESA, "Ana Administradora", "admin@contrato.quack", "administrador");
        String caixa = amb.tokenLoja("usr-ct-caixa", EMPRESA, "Cai Caixa", "caixa@contrato.quack", "caixa");
        amb.loja.getBean(br.com.quack.pdv.plataforma.sincronizacao.conferencia.ConferenciaSincronizacao.class).executar("manual");

        HttpResponse<String> r = get(amb.urlBaseLoja(), "/api/loja-sync/situacao", "Bearer " + admin);
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode d = JSON.readTree(r.body()).path("data");
        assertThat(d.path("origem").asText()).isEqualTo("loja");
        assertThat(d.path("atrasoSegundos").asLong()).isZero();
        assertThat(d.path("pausada").asBoolean()).isFalse();
        assertThat(d.path("versaoLocal").asLong()).isEqualTo(d.path("versaoConfirmada").asLong());
        assertThat(d.path("nuvemAcessivel").asBoolean()).isTrue();
        assertThat(d.path("comandosPendentes").asInt()).isZero();
        assertThat(d.path("ultimaConferencia").path("resultado").asText()).isNotBlank();
        assertThat(d.path("ultimoBackup").path("sucesso").asBoolean()).isTrue();

        assertThat(get(amb.urlBaseLoja(), "/api/loja-sync/situacao", "Bearer " + caixa).statusCode()).isEqualTo(403);

        // sem internet a loja responde igual e avisa que nao alcanca a nuvem
        amb.cliente().setSemInternet(true);
        try {
            JsonNode off = JSON.readTree(get(amb.urlBaseLoja(), "/api/loja-sync/situacao", "Bearer " + admin).body()).path("data");
            assertThat(off.path("nuvemAcessivel").asBoolean()).isFalse();
            assertThat(off.has("comandosPendentes")).isFalse();
        } finally {
            amb.cliente().setSemInternet(false);
        }
    }

    // ------------------------------------------------------------------ alertas por e-mail

    @Test
    @Order(3)
    void sincronizacaoPausadaGeraUmEmailENaoOutroNaMesmaHora() throws Exception {
        EnvioSincronizacao envio = amb.loja.getBean(EnvioSincronizacao.class);
        EnviadorAlertas enviador = amb.loja.getBean(EnviadorAlertas.class);
        VerificadorAlertasLoja verificador = amb.loja.getBean(VerificadorAlertasLoja.class);
        smtp.purgeEmailFromAllMailboxes();
        loja.update("DELETE FROM SyncAlertas");

        // erro definitivo (token revogado = 401): a loja pausa
        nuvem.update("UPDATE SyncServidores SET Status = 'revogado' WHERE Id = 'srv-it'");
        loja.update("INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES ('cat-pausa', ?, 'Pausa', NULL, 91, 1)", EMPRESA);
        esperar("pausa da loja", 30_000, () -> loja.queryForObject("SELECT PausadaMotivo FROM SyncEstado WHERE Id = 1", String.class) != null);

        OffsetDateTime t0 = OffsetDateTime.now();
        assertThat(enviador.enviarPendentes(t0)).isEqualTo(1);
        smtp.waitForIncomingEmail(5_000, administradores);
        assertThat(emailsDe(AlertasSync.PAUSADA)).isEqualTo(administradores);
        MimeMessage m = smtp.getReceivedMessages()[0];
        assertThat(com.icegreen.greenmail.util.GreenMailUtil.getBody(m)).contains("Motivo: " + AlertasSync.PAUSADA).contains("401");

        // a pausa continua: o verificador registra de novo, mas dentro da hora nao sai outro e-mail
        assertThat(verificador.verificar(t0.plusMinutes(10))).contains(AlertasSync.PAUSADA);
        assertThat(enviador.enviarPendentes(t0.plusMinutes(10))).isZero();
        assertThat(enviador.enviarPendentes(t0.plusMinutes(59))).isZero();
        assertThat(emailsDe(AlertasSync.PAUSADA)).isEqualTo(administradores);

        // passada a hora e continuando a pausa, sai outro
        assertThat(verificador.verificar(t0.plusMinutes(61))).contains(AlertasSync.PAUSADA);
        assertThat(enviador.enviarPendentes(t0.plusMinutes(61))).isEqualTo(1);
        smtp.waitForIncomingEmail(5_000, 2 * administradores);
        assertThat(emailsDe(AlertasSync.PAUSADA)).isEqualTo(2 * administradores);

        // corrige e retoma: converge e a condicao some
        nuvem.update("UPDATE SyncServidores SET Status = 'ativo' WHERE Id = 'srv-it'");
        envio.retomar();
        amb.zerarBackoff();
        amb.esperarConvergencia(60_000);
        assertThat(verificador.verificar(OffsetDateTime.now())).doesNotContain(AlertasSync.PAUSADA);
    }

    @Test
    @Order(4)
    void semSmtpOAlertaFicaGuardadoSoNoLogESaiQuandoOSmtpVolta() throws Exception {
        EnviadorAlertas enviador = amb.loja.getBean(EnviadorAlertas.class);
        QuackProperties props = amb.loja.getBean(QuackProperties.class);
        smtp.purgeEmailFromAllMailboxes();
        loja.update("DELETE FROM SyncAlertas");
        AlertasSync.registrar(loja, OffsetDateTime.now(), AlertasSync.CONFERENCIA, EMPRESA, "diferenca de teste");
        props.getEmail().setEnabled(false);
        try {
            assertThat(enviador.enviarPendentes(OffsetDateTime.now())).as("SMTP desligado: so log").isZero();
            assertThat(loja.queryForObject("SELECT UltimoEnvio FROM SyncAlertas WHERE Motivo = ?", Object.class, AlertasSync.CONFERENCIA)).isNull();
        } finally {
            props.getEmail().setEnabled(true);
        }
        assertThat(enviador.enviarPendentes(OffsetDateTime.now())).as("SMTP de volta: sai o que ficou guardado").isEqualTo(1);
    }

    @Test
    @Order(5)
    void novaEntradaNaQuarentenaDaNuvemGeraEmailDoReceptor() throws Exception {
        EnviadorAlertas enviador = amb.nuvem.getBean(EnviadorAlertas.class);
        smtp.purgeEmailFromAllMailboxes();
        nuvem.update("DELETE FROM SyncAlertas");
        String modelo = loja.queryForObject("SELECT TOP 1 Id FROM EstoqueMovimentacoes WHERE CompanyId = ?", String.class, EMPRESA);
        QuarentenaFiscalIT.clonar(nuvem, "EstoqueMovimentacoes", modelo, QuarentenaFiscalIT.mapa("Id", "mov-quarentena-alerta"));
        esperar("alerta de quarentena na nuvem", 30_000, () ->
                contar(nuvem, "SELECT COUNT(*) FROM SyncAlertas WHERE Motivo = 'quarentena'") == 1);

        assertThat(enviador.enviarPendentes(OffsetDateTime.now())).isEqualTo(1);
        smtp.waitForIncomingEmail(5_000, administradores);
        assertThat(emailsDe("quarentena")).isEqualTo(administradores);
        assertThat(enviador.enviarPendentes(OffsetDateTime.now().plusMinutes(5))).isZero();
    }

    @Test
    @Order(6)
    void notaPendenteHaMaisDe30MinutosGeraAlertaEHaMaisDe10AparecemNaSituacao() throws Exception {
        VerificadorAlertasLoja verificador = amb.loja.getBean(VerificadorAlertasLoja.class);
        PendenciaFiscal pendencia = amb.loja.getBean(PendenciaFiscal.class);
        TransactionTemplate tx = new TransactionTemplate(amb.loja.getBean(PlatformTransactionManager.class));
        VendaRegistroResultado v = vender("CT002", "1", "3", "3,00");
        tx.executeWithoutResult(s -> {
            loja.update("UPDATE Vendas SET EmitirNfce = 1 WHERE CompanyId = ? AND Id = ?", EMPRESA, v.vendaId());
            pendencia.enfileirar(EMPRESA, v.vendaId());
        });
        assertThat(contar(loja, "SELECT COUNT(*) FROM DocumentosFiscais WHERE VendaId = ? AND Status IN (1, 2, 8)", v.vendaId())).isEqualTo(1);
        amb.esperarConvergencia(60_000);
        loja.update("DELETE FROM SyncAlertas");

        OffsetDateTime agora = OffsetDateTime.now();
        assertThat(verificador.verificar(agora.plusMinutes(25))).doesNotContain(AlertasSync.NFCE_PENDENTE);
        assertThat(verificador.verificar(agora.plusMinutes(31))).contains(AlertasSync.NFCE_PENDENTE);
        assertThat(loja.queryForObject("SELECT Detalhe FROM SyncAlertas WHERE Motivo = ?", String.class, AlertasSync.NFCE_PENDENTE))
                .contains("1 NFC-e");

        // a situacao conta as notas com mais de 10 minutos, nas duas pontas
        assertThat(amb.loja.getBean(SituacaoLoja.class).consultar(agora.plusMinutes(5)).nfcePendentesHaMaisDe10Min()).isZero();
        assertThat(amb.loja.getBean(SituacaoLoja.class).consultar(agora.plusMinutes(11)).nfcePendentesHaMaisDe10Min()).isEqualTo(1);
        assertThat(amb.nuvem.getBean(SituacaoNuvem.class).consultar(EMPRESA, agora.plusMinutes(11)).nfcePendentesHaMaisDe10Min()).isEqualTo(1);
        // a nota some da contagem quando e autorizada
        loja.update("UPDATE DocumentosFiscais SET Status = 100 WHERE VendaId = ?", v.vendaId());
        assertThat(verificador.verificar(agora.plusMinutes(32))).doesNotContain(AlertasSync.NFCE_PENDENTE);
    }

    @Test
    @Order(7)
    void atrasoAcimaDe15MinutosComInternetGeraAlertaMasSemInternetNao() throws Exception {
        VerificadorAlertasLoja verificador = amb.loja.getBean(VerificadorAlertasLoja.class);
        QuackProperties props = amb.loja.getBean(QuackProperties.class);
        amb.esperarConvergencia(60_000);
        loja.update("DELETE FROM SyncAlertas");
        props.getSync().setEnvioAtivo(false); // mudancas ficam na loja sem erro de rede
        try {
            loja.update("INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES ('cat-atraso', ?, 'Atraso', NULL, 92, 1)", EMPRESA);
            OffsetDateTime ultima = loja.queryForObject("SELECT UltimaSincronizacaoEm FROM SyncEstado WHERE Id = 1", OffsetDateTime.class);
            assertThat(verificador.verificar(ultima.plusMinutes(14))).doesNotContain(AlertasSync.ATRASO);
            assertThat(verificador.verificar(ultima.plusMinutes(16))).contains(AlertasSync.ATRASO);
            assertThat(amb.loja.getBean(SituacaoLoja.class).consultar(ultima.plusMinutes(16)).atrasoSegundos()).isGreaterThanOrEqualTo(16 * 60L);

            // com falha de rede (sem internet) o atraso nao e alertado: o problema e outro
            loja.update("DELETE FROM SyncAlertas");
            loja.update("UPDATE SyncEstado SET TentativasSeguidas = 3 WHERE Id = 1");
            assertThat(verificador.verificar(ultima.plusMinutes(16))).doesNotContain(AlertasSync.ATRASO);
        } finally {
            loja.update("UPDATE SyncEstado SET TentativasSeguidas = 0, ProximaTentativaEm = NULL WHERE Id = 1");
            props.getSync().setEnvioAtivo(true);
        }
        amb.esperarConvergencia(60_000);
    }

    @Test
    @Order(8)
    void falhaNoBackupGeraAlertaELeituraDoEstadoApareceNaSituacao() throws Exception {
        VerificadorAlertasLoja verificador = amb.loja.getBean(VerificadorAlertasLoja.class);
        loja.update("DELETE FROM SyncAlertas");
        assertThat(verificador.verificar(OffsetDateTime.now())).doesNotContain(AlertasSync.BACKUP);

        Files.writeString(arquivoBackup, "{\"ultimoBackupEm\":\"2026-11-04T02:00:00Z\",\"sucesso\":false,\"erro\":\"disco cheio\"}");
        assertThat(verificador.verificar(OffsetDateTime.now())).contains(AlertasSync.BACKUP);
        assertThat(loja.queryForObject("SELECT Detalhe FROM SyncAlertas WHERE Motivo = ?", String.class, AlertasSync.BACKUP))
                .contains("disco cheio");
        JsonNode situacao = JSON.valueToTree(amb.loja.getBean(SituacaoLoja.class).consultar(OffsetDateTime.now()));
        assertThat(situacao.path("ultimoBackup").path("sucesso").asBoolean()).isFalse();
        assertThat(situacao.path("ultimoBackup").path("erro").asText()).isEqualTo("disco cheio");
        List<String> motivos = loja.queryForList("SELECT Motivo FROM SyncAlertas", String.class);
        assertThat(motivos).contains(AlertasSync.BACKUP);
    }
}
