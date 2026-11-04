package br.com.quack.pdv.fiscal.fila;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.BancoIT;
import br.com.quack.pdv.contrato.EstadoDoBanco;
import br.com.quack.pdv.fiscal.fila.app.FilaFiscalMinima;
import br.com.quack.pdv.security.SecretProtector;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Fila fiscal minima (4.2) com o relogio fixo: cada ramo do ciclo, a rotina agendada ligada e (no outro IT) desligada.
 * O seed de contrato nao tem emitente (a empresa-principal nao tem certificado nem CSC).
 */
@SpringBootTest
@ActiveProfiles("it")
class FilaFiscalMinimaIT {

    static final String EMPRESA = "empresa-principal";
    static final Instant AGORA = Instant.parse("2026-06-01T12:00:00Z");
    private static final String MOTIVO_SEM_EMITENTE = "Empresa sem certificado ou CSC configurado.";

    static {
        BancoIT.criarBanco("filafiscal");
    }

    @TestConfiguration
    static class RelogioFixo {
        @Bean
        @Primary
        Clock relogioFixoDaFila() {
            return Clock.fixed(AGORA, ZoneOffset.UTC);
        }
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> BancoIT.urlDoBanco("filafiscal"));
        r.add("spring.datasource.username", BancoIT.SQL::getUsername);
        r.add("spring.datasource.password", BancoIT.SQL::getPassword);
        r.add("quack.fiscal.fila-minima.ativa", () -> "true");
    }

    @Autowired FilaFiscalMinima fila;
    @Autowired SecretProtector segredos;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        EstadoDoBanco.por(BancoIT.urlDoBanco("filafiscal"), BancoIT.SQL.getUsername(), BancoIT.SQL.getPassword())
                .restaurar();
    }

    // ---------------------------------------------------------------- helpers

    private void inserirPendente(String id, String vendaId, int numero, String xmlAssinado) {
        jdbc.update("""
                INSERT INTO DocumentosFiscais (Id, CompanyId, VendaId, Modelo, Serie, NumeroNf, Ambiente, Status,
                                               TpEmis, XmlAssinado)
                VALUES (?, ?, ?, 65, 2, ?, 2, 1, 1, ?);
                """, id, EMPRESA, vendaId, numero, xmlAssinado);
    }

    private void configurarEmitente() {
        jdbc.update("UPDATE Empresas SET CertificadoPfxCifrado = ?, CertificadoSenhaCifrada = ?, CscId = N'000001',"
                        + " CscCifrado = ? WHERE Id = ?", segredos.protect("QUFBQQ=="), segredos.protect("senha"),
                segredos.protect("csc-de-teste"), EMPRESA);
    }

    private Map<String, Object> doc(String id) {
        return jdbc.queryForMap("SELECT Status, MotivoStatus, UltimoErro, Tentativas, ProximaTentativaEm, AtualizadoEm"
                + " FROM DocumentosFiscais WHERE Id = ?", id);
    }

    private List<Map<String, Object>> auditorias(String evento) {
        return jdbc.queryForList("SELECT UserId, UserName, EntityType, EntityId, Description, Ip FROM AuditLog"
                + " WHERE EventType = ?", evento);
    }

    // ---------------------------------------------------------------- ramos

    @Test
    void retomaVendaQueDecidiuEmitirEFicouSemDocumento() {
        jdbc.update("UPDATE Vendas SET EmitirNfce = 1 WHERE Id = N'vd-ct-fin'");

        fila.processarLote();

        List<Map<String, Object>> docs = jdbc.queryForList(
                "SELECT Id, Modelo, Serie, NumeroNf, Status FROM DocumentosFiscais WHERE VendaId = N'vd-ct-fin'");
        assertThat(docs).hasSize(1);
        assertThat(docs.getFirst().get("Modelo")).isEqualTo((short) 65);
        assertThat(((Number) docs.getFirst().get("NumeroNf")).intValue()).isEqualTo(2); // o seed ja usou o numero 1
        assertThat(((Number) docs.getFirst().get("Status")).intValue()).isEqualTo(1);

        fila.processarLote(); // idempotente: nao cria segunda nota
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM DocumentosFiscais WHERE VendaId = N'vd-ct-fin'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void naoRetomaVendaCanceladaNemQueNaoDecidiuEmitir() {
        jdbc.update("UPDATE Vendas SET EmitirNfce = 1 WHERE Id = N'vd-ct-canc'");

        fila.processarLote();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM DocumentosFiscais WHERE VendaId IN (N'vd-ct-canc',"
                + " N'vd-ct-fin')", Integer.class)).isZero();
    }

    @Test
    void notaDeVendaCanceladaSemEmitenteViraNaoEmitidaComOMotivoDaDotNetEAuditoria() {
        inserirPendente("doc-it-canc", "vd-ct-canc", 7, null);

        fila.processarLote();

        Map<String, Object> d = doc("doc-it-canc");
        assertThat(((Number) d.get("Status")).intValue()).isEqualTo(4);
        assertThat(d.get("MotivoStatus")).isEqualTo("NFC-e não emitida: a venda #15041 foi cancelada antes da emissão. "
                + "Inutilize o número 7 (série 2) na SEFAZ (empresa sem certificado configurado).");
        assertThat(d.get("UltimoErro")).isEqualTo(d.get("MotivoStatus"));
        assertThat(d.get("ProximaTentativaEm")).isNull();
        assertThat(jdbc.queryForObject("SELECT AtualizadoEm FROM DocumentosFiscais WHERE Id = N'doc-it-canc'",
                OffsetDateTime.class).toInstant()).isEqualTo(AGORA);

        List<Map<String, Object>> trilha = auditorias("NfceNaoEmitida");
        assertThat(trilha).hasSize(1);
        assertThat(trilha.getFirst()).containsEntry("UserId", "usr-ct-caixa").containsEntry("UserName", "Cai Caixa")
                .containsEntry("EntityType", "DocumentosFiscais").containsEntry("EntityId", "doc-it-canc")
                .containsEntry("Ip", "fila fiscal");
        assertThat((String) trilha.getFirst().get("Description")).isEqualTo(d.get("MotivoStatus"));

        fila.processarLote(); // ja saiu da fila: nao audita de novo
        assertThat(auditorias("NfceNaoEmitida")).hasSize(1);
    }

    @Test
    void notaDeVendaCanceladaComEmitenteTemOMotivoSemOSufixoDeCertificado() {
        configurarEmitente();
        inserirPendente("doc-it-canc", "vd-ct-canc", 7, null);

        fila.processarLote();

        Map<String, Object> d = doc("doc-it-canc");
        assertThat(((Number) d.get("Status")).intValue()).isEqualTo(4);
        assertThat(d.get("MotivoStatus")).isEqualTo("NFC-e não emitida: a venda #15041 foi cancelada antes da emissão. "
                + "Inutilize o número 7 (série 2) na SEFAZ.");
    }

    @Test
    void notaJaAssinadaDeVendaCanceladaNaoEEncerradaPelaFilaMinima() {
        inserirPendente("doc-it-assinada", "vd-ct-canc", 8, "<NFe/>");

        fila.processarLote();

        // sem emitente cai no ramo do erro; o status continua pendente (1), sem virar "nao emitida"
        Map<String, Object> d = doc("doc-it-assinada");
        assertThat(((Number) d.get("Status")).intValue()).isEqualTo(1);
        assertThat(d.get("UltimoErro")).isEqualTo(MOTIVO_SEM_EMITENTE);
        assertThat(auditorias("NfceNaoEmitida")).isEmpty();
    }

    @Test
    void notaPendenteDeEmpresaSemEmitenteRecebeOErroSemNovaTentativaMarcada() {
        inserirPendente("doc-it-sem", "vd-ct-fin", 9, null);

        fila.processarLote();

        Map<String, Object> d = doc("doc-it-sem");
        assertThat(((Number) d.get("Status")).intValue()).isEqualTo(1);
        assertThat(d.get("UltimoErro")).isEqualTo(MOTIVO_SEM_EMITENTE);
        assertThat(((Number) d.get("Tentativas")).intValue()).isEqualTo(1);
        assertThat(d.get("ProximaTentativaEm")).isNull();
    }

    @Test
    void notaPendenteDeEmpresaComEmitenteContinuaParadaSemTransmissao() {
        configurarEmitente();
        inserirPendente("doc-it-com", "vd-ct-fin", 9, null);

        fila.processarLote();
        fila.processarLote();

        Map<String, Object> d = doc("doc-it-com");
        assertThat(((Number) d.get("Status")).intValue()).isEqualTo(1);
        assertThat(d.get("UltimoErro")).isNull();
        assertThat(((Number) d.get("Tentativas")).intValue()).isZero();
        assertThat(d.get("MotivoStatus")).isNull();
    }

    @Test
    void notaComProximaTentativaNoFuturoNaoEProcessadaEORelogioFixoDefineOVencimento() {
        inserirPendente("doc-it-futura", "vd-ct-fin", 10, null);
        jdbc.update("UPDATE DocumentosFiscais SET ProximaTentativaEm = ? WHERE Id = N'doc-it-futura'",
                OffsetDateTime.ofInstant(AGORA.plusSeconds(3600), ZoneOffset.UTC));

        fila.processarLote();
        assertThat(((Number) doc("doc-it-futura").get("Tentativas")).intValue()).isZero();

        jdbc.update("UPDATE DocumentosFiscais SET ProximaTentativaEm = ? WHERE Id = N'doc-it-futura'",
                OffsetDateTime.ofInstant(AGORA.minusSeconds(1), ZoneOffset.UTC));
        fila.processarLote();
        assertThat(((Number) doc("doc-it-futura").get("Tentativas")).intValue()).isEqualTo(1);
    }

    @Test
    void rotinaAgendadaLigadaProcessaOLote() {
        inserirPendente("doc-it-agendada", "vd-ct-canc", 11, null);

        fila.executarAgendado();

        assertThat(((Number) doc("doc-it-agendada").get("Status")).intValue()).isEqualTo(4);
    }
}
