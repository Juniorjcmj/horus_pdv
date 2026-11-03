package br.com.quack.pdv.venda.caixa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.quack.pdv.BancoIT;
import br.com.quack.pdv.contrato.EstadoDoBanco;
import br.com.quack.pdv.security.UsuarioAutenticado;
import br.com.quack.pdv.shared.NegocioException;
import br.com.quack.pdv.venda.caixa.app.CaixaComandos;
import br.com.quack.pdv.venda.caixa.app.CaixaService;
import br.com.quack.pdv.venda.caixa.domain.CaixaStatusDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("it")
class CaixaIT {

    private static final String EMPRESA = "empresa-principal";
    private static final UsuarioAutenticado CAIXA =
            new UsuarioAutenticado("usr-ct-caixa", EMPRESA, "s1", "Cai Caixa", "caixa@x", "caixa", List.of());
    private static final UsuarioAutenticado ATENDENTE =
            new UsuarioAutenticado("usr-ct-atend", EMPRESA, "s2", "Ari Atendente", "a@x", "atendente", List.of());
    private static final UsuarioAutenticado GERENTE =
            new UsuarioAutenticado("usr-ct-gerente", EMPRESA, "s3", "Gil Gerente", "g@x", "gerente", List.of());
    private static final ObjectMapper JSON = new ObjectMapper();

    static {
        BancoIT.criarBanco("caixa");
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> BancoIT.urlDoBanco("caixa"));
        r.add("spring.datasource.username", BancoIT.SQL::getUsername);
        r.add("spring.datasource.password", BancoIT.SQL::getPassword);
    }

    @Autowired CaixaService caixa;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        EstadoDoBanco.por(BancoIT.urlDoBanco("caixa"), BancoIT.SQL.getUsername(), BancoIT.SQL.getPassword())
                .restaurar();
        // O seed deixa um turno do gerente aberto desde janeiro; os testes partem de um dia sem turnos abertos.
        jdbc.update("DELETE FROM VendaPagamentos");
        jdbc.update("DELETE FROM VendaItens");
        jdbc.update("DELETE FROM VendaCancelamentos");
        jdbc.update("DELETE FROM DocumentosFiscais");
        jdbc.update("DELETE FROM Vendas");
        jdbc.update("DELETE FROM CaixaMovimentos");
        jdbc.update("DELETE FROM CaixaSessoes");
    }

    private static CaixaComandos.Abrir abrirCmd(String valor, String eventId, String hash) {
        return new CaixaComandos.Abrir(eventId, hash, valor, null, null);
    }

    private static CaixaComandos.Movimento movCmd(String tipo, String valor, String eventId) {
        return new CaixaComandos.Movimento(eventId, null, tipo, valor, "Motivo valido");
    }

    private static CaixaComandos.Fechar fecharCmd(String valor, String motivo) {
        return new CaixaComandos.Fechar(null, null, valor, "", motivo, null, null);
    }

    private int contar(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private String sessaoAberta(UsuarioAutenticado u) {
        return caixa.sessaoAbertaDoOperador(EMPRESA, u.id()).orElseThrow().id();
    }

    private void venda(String id, String sessaoId, String forma, String valor, String status) {
        jdbc.update("INSERT INTO Vendas (Id, CompanyId, SaleNumber, CustomerName, CustomerCpf, PaymentType,"
                + " OperatorName, SaleDate, CaixaSessaoId, TotalAmount, Status)"
                + " VALUES (?, ?, ?, N'Consumidor', N'', ?, N'Teste', SYSDATETIMEOFFSET(), ?, ?, ?)",
                id, EMPRESA, id, forma, sessaoId, new BigDecimal(valor), status);
        jdbc.update("INSERT INTO VendaPagamentos (Id, CompanyId, VendaId, PaymentType, Amount, CreatedAt)"
                + " VALUES (?, ?, ?, ?, ?, SYSDATETIMEOFFSET())", id + "-p", EMPRESA, id, forma, new BigDecimal(valor));
    }

    @Test
    void doisOperadoresTemTurnosAbertosAoMesmoTempo() {
        caixa.abrir(abrirCmd("100,00", null, null), CAIXA, "10.0.0.1");
        CaixaStatusDto doAtendente = caixa.abrir(abrirCmd("50,00", null, null), ATENDENTE, "10.0.0.2");

        assertThat(contar("SELECT COUNT(*) FROM CaixaSessoes WHERE ClosedAt IS NULL")).isEqualTo(2);
        assertThat(doAtendente.canSell()).isTrue();
        assertThat(doAtendente.currentSession().operatorId()).isEqualTo(ATENDENTE.id());
        assertThat(sessaoAberta(CAIXA)).isNotEqualTo(sessaoAberta(ATENDENTE));
        // Gerente ve os dois; o atendente so o dele.
        assertThat(caixa.status(GERENTE).openSessions()).hasSize(2);
        assertThat(caixa.status(ATENDENTE).openSessions()).hasSize(1);
        assertThat(contar("SELECT COUNT(*) FROM AuditLog WHERE EventType = N'CaixaAbertura'")).isEqualTo(2);
    }

    @Test
    void abrirDeNovoComTurnoAbertoRecusa() {
        caixa.abrir(abrirCmd("100,00", null, null), CAIXA, null);
        assertThatThrownBy(() -> caixa.abrir(abrirCmd("10,00", null, null), CAIXA, null))
                .isInstanceOf(NegocioException.class)
                .hasMessage("Você já possui um caixa aberto. Feche o caixa atual antes de abrir outro.");
    }

    @Test
    void fechamentoUsaFundoMaisVendasEmDinheiroMaisReforcoMenosSangria() {
        caixa.abrir(abrirCmd("100,00", null, null), ATENDENTE, null);
        String sessao = sessaoAberta(ATENDENTE);
        venda("vd-it-1", sessao, "Dinheiro", "40.00", "finalizada");
        venda("vd-it-2", sessao, "Pix", "25.00", "finalizada");
        venda("vd-it-3", sessao, "Dinheiro", "9.00", "cancelada");
        caixa.registrarMovimento(movCmd("Reforco", "10,00", null), ATENDENTE, null);
        caixa.registrarMovimento(movCmd("Sangria", "5,00", null), ATENDENTE, null);

        assertThatThrownBy(() -> caixa.fechar(fecharCmd("140,00", null), ATENDENTE, null))
                .isInstanceOf(NegocioException.class)
                .hasMessage("Há uma diferença de 5,00 (falta) em relação ao esperado (145,00). "
                        + "Informe uma justificativa antes de fechar o caixa.");

        CaixaStatusDto fechado = caixa.fechar(fecharCmd("140,00", "Moeda perdida"), ATENDENTE, "10.0.0.2");

        assertThat(fechado.currentSession()).isNull();
        assertThat(fechado.lastSession().expectedCashAmount()).isEqualTo("145,00");
        assertThat(fechado.lastSession().differenceAmount()).isEqualTo("-5,00");
        assertThat(jdbc.queryForObject("SELECT ExpectedCashAmount FROM CaixaSessoes WHERE Id = ?", BigDecimal.class,
                        sessao)).isEqualByComparingTo("145.00");
        assertThat(jdbc.queryForObject("SELECT DifferenceReason FROM CaixaSessoes WHERE Id = ?", String.class, sessao))
                .isEqualTo("Moeda perdida");
        assertThat(contar("SELECT COUNT(*) FROM AuditLog WHERE EventType = N'CaixaFechamento'")).isEqualTo(1);
        assertThat(contar("SELECT COUNT(*) FROM AuditLog WHERE EventType IN (N'CaixaSangria', N'CaixaReforco')"))
                .isEqualTo(2);
    }

    @Test
    void sangriaMaiorQueOCaixaERecusada() {
        caixa.abrir(abrirCmd("20,00", null, null), CAIXA, null);
        assertThatThrownBy(() -> caixa.registrarMovimento(movCmd("Sangria", "20,01", null), CAIXA, null))
                .isInstanceOf(NegocioException.class)
                .hasMessage("Sangria maior que o dinheiro em caixa (disponível: 20,00).");
    }

    @Test
    void reenvioDaAberturaNaoCriaSegundoTurno() {
        CaixaStatusDto primeira = caixa.abrir(abrirCmd("100,00", "evt-it-1", null), CAIXA, null);
        CaixaStatusDto segunda = caixa.abrir(abrirCmd("100,00", "evt-it-1", null), CAIXA, null);

        assertThat(primeira.isReplay()).isFalse();
        assertThat(segunda.isReplay()).isTrue();
        assertThat(segunda.currentSession().id()).isEqualTo(primeira.currentSession().id());
        assertThat(contar("SELECT COUNT(*) FROM CaixaSessoes")).isEqualTo(1);
        assertThat(contar("SELECT COUNT(*) FROM AuditLog WHERE EventType = N'CaixaAbertura'")).isEqualTo(1);
    }

    @Test
    void mesmoEventoComOutroConteudoDa409ENadaEGravado() {
        caixa.abrir(abrirCmd("100,00", "evt-it-2", null), CAIXA, null);
        caixa.registrarMovimento(movCmd("Reforco", "10,00", "evt-mov-it"), CAIXA, null);

        assertThatThrownBy(() -> caixa.abrir(abrirCmd("200,00", "evt-it-2", null), CAIXA, null))
                .isInstanceOfSatisfying(NegocioException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> caixa.registrarMovimento(movCmd("Reforco", "99,00", "evt-mov-it"), CAIXA, null))
                .isInstanceOfSatisfying(NegocioException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("Conflito de idempotência: o EventId 'evt-mov-it' já foi "
                            + "processado anteriormente com um payload diferente.");
                });
        assertThat(contar("SELECT COUNT(*) FROM CaixaMovimentos")).isEqualTo(1);
        assertThat(contar("SELECT COUNT(*) FROM CaixaSessoes")).isEqualTo(1);
    }

    @Test
    void responsePayloadGravadoTemOFormatoDaDotNet() throws Exception {
        caixa.abrir(abrirCmd("100,00", "evt-it-3", null), CAIXA, null);
        String tipo = jdbc.queryForObject("SELECT EventType FROM ProcessedEvents WHERE EventId = N'evt-it-3'",
                String.class);
        String corpo = jdbc.queryForObject("SELECT ResponsePayload FROM ProcessedEvents WHERE EventId = N'evt-it-3'",
                String.class);
        JsonNode json = JSON.readTree(corpo);

        assertThat(tipo).isEqualTo("CASH_OPEN");
        assertThat(json.fieldNames()).toIterable().containsExactly("state", "canSell", "blockReason", "serverNow",
                "currentSession", "lastSession", "history", "openSessions", "isReplay");
        assertThat(json.get("isReplay").asBoolean()).isFalse();
        assertThat(json.get("currentSession").get("openingAmount").asText()).isEqualTo("100,00");
        assertThat(json.get("currentSession").get("closedAt").isNull()).isTrue();
        assertThat(json.get("currentSession").get("serverNow")).isNull();
    }

    @Test
    void respostaGravadaPelaDotNetEReconhecidaNoReenvio() {
        // Linha copiada do formato que a API .NET grava (camelCase, nulos presentes, isReplay false).
        String hash = "ab".repeat(32);
        String payloadDotNet = "{\"state\":\"aberto\",\"canSell\":true,\"blockReason\":\"\","
                + "\"serverNow\":\"2026-11-03T10:00:00.1234567-03:00\",\"currentSession\":{\"id\":\"cx-1762174800000\","
                + "\"status\":\"Aberto\",\"openedAt\":\"2026-11-03T10:00:00.1234567-03:00\",\"closedAt\":null,"
                + "\"openingAmount\":\"100,00\",\"closingAmount\":\"0,00\",\"operatorId\":\"usr-ct-caixa\","
                + "\"operatorName\":\"Cai Caixa\",\"closedById\":\"\",\"closedByName\":\"\",\"note\":\"\","
                + "\"elapsedMinutes\":0,\"expectedCashAmount\":\"100,00\",\"differenceAmount\":null,"
                + "\"differenceReason\":null,\"movimentos\":[],\"paymentBreakdown\":[]},\"lastSession\":null,"
                + "\"history\":[],\"openSessions\":[],\"isReplay\":false}";
        jdbc.update("INSERT INTO ProcessedEvents (Id, CompanyId, EventId, EventType, ClientSaleId, PayloadHash,"
                + " ProcessedAt, ResponsePayload) VALUES (N'pe-dotnet', ?, N'evt-dotnet', N'CASH_OPEN', NULL, ?,"
                + " SYSDATETIMEOFFSET(), ?)", EMPRESA, hash, payloadDotNet);

        CaixaStatusDto reenvio = caixa.abrir(abrirCmd("999,00", "evt-dotnet", hash.toUpperCase()), CAIXA, null);

        assertThat(reenvio.isReplay()).isTrue();
        assertThat(reenvio.currentSession().id()).isEqualTo("cx-1762174800000");
        assertThat(reenvio.currentSession().openingAmount()).isEqualTo("100,00");
        assertThat(contar("SELECT COUNT(*) FROM CaixaSessoes")).isZero();
    }

    @Test
    void vendaSemCaixaAbertoEBloqueadaComAuditoria() {
        assertThatThrownBy(() -> caixa.garantirVendaPermitida(CAIXA, "10.0.0.1"))
                .isInstanceOf(NegocioException.class)
                .hasMessage("Abra o caixa do dia antes de iniciar vendas.");
        assertThat(jdbc.queryForObject("SELECT Description FROM AuditLog WHERE EventType = N'VendaBloqueada'",
                String.class)).isEqualTo("Tentou vender com o caixa fechado — Abra o caixa do dia antes de iniciar vendas.");

        caixa.abrir(abrirCmd("10,00", null, null), CAIXA, null);
        caixa.garantirVendaPermitida(CAIXA, "10.0.0.1");
        assertThat(contar("SELECT COUNT(*) FROM AuditLog WHERE EventType = N'VendaBloqueada'")).isEqualTo(1);
    }

    @Test
    void sessaoAbertaDoOperadorEPorOperador() {
        caixa.abrir(abrirCmd("10,00", null, null), CAIXA, null);
        assertThat(caixa.sessaoAbertaDoOperador(EMPRESA, CAIXA.id())).isPresent();
        assertThat(caixa.sessaoAbertaDoOperador(EMPRESA, ATENDENTE.id())).isEmpty();
        assertThat(caixa.sessaoAbertaDoOperador("outra-empresa", CAIXA.id())).isEmpty();
    }

    @Test
    void gerenteFechaTurnoDeOutroMasCaixaNaoFechaOdoAtendente() {
        caixa.abrir(new CaixaComandos.Abrir(null, null, "40,00", "cx-it-atend", null), ATENDENTE, null);
        CaixaComandos.Fechar alvo = new CaixaComandos.Fechar(null, null, "40,00", "", null, "cx-it-atend", null);

        assertThatThrownBy(() -> caixa.fechar(alvo, CAIXA, null)).isInstanceOf(NegocioException.class)
                .hasMessage("Esse caixa foi aberto por Ari Atendente — só ela ou um gerente/administrador podem "
                        + "mexer nele.");
        CaixaStatusDto fechado = caixa.fechar(alvo, GERENTE, null);
        assertThat(fechado.isReplay()).isFalse();
        assertThat(jdbc.queryForObject("SELECT ClosedById FROM CaixaSessoes WHERE Id = N'cx-it-atend'", String.class))
                .isEqualTo(GERENTE.id());
    }
}
