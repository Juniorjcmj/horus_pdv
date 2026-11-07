package br.com.quack.pdv.plataforma.sincronizacao.integracao;

import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.EMPRESA;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.esperar;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.SincronizacaoIT.contar;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.SincronizacaoIT.vender;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.plataforma.sincronizacao.conferencia.ConferenciaSincronizacao;
import br.com.quack.pdv.plataforma.sincronizacao.envio.ClienteNuvemHttp;
import br.com.quack.pdv.plataforma.sincronizacao.nuvem.DetectorNuvem;
import br.com.quack.pdv.venda.registro.app.VendaRegistroService;
import br.com.quack.pdv.venda.registro.domain.VendaRegistroResultado;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Conferencia loja x nuvem (tarefa 6.1, D10). Divergencias "silenciosas" na nuvem sao feitas com o tracking desligado
 * (DDL) ou com o contexto {@code sync-loja} (o detector as ignora); o resultado e lido de {@code SyncConferencias}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConferenciaIT {

    static final ObjectMapper JSON = new ObjectMapper();
    /** Contexto do Change Tracking do aplicador de lotes: o detector e a deteccao inline ignoram a escrita. */
    static final String SILENCIO = "WITH CHANGE_TRACKING_CONTEXT (0x53510001) ";

    static AmbienteSync amb;
    static JdbcTemplate loja;
    static JdbcTemplate nuvem;
    static ConferenciaSincronizacao conferencia;
    static VendaRegistroResultado venda;

    @BeforeAll
    static void subir() {
        // jar antigo na loja (sem operacao-estoque): a gravacao de movimentacao na nuvem segue para a quarentena, que e o que este teste exerce
        amb = AmbienteSync.iniciarComExtrasNaLoja("conf", "--quack.sync.recursos=");
        loja = amb.jdbcLoja;
        nuvem = amb.jdbcNuvem;
        conferencia = amb.loja.getBean(ConferenciaSincronizacao.class);
        SincronizacaoIT.vendas = amb.loja.getBean(VendaRegistroService.class);
        // o seed nem sempre fecha saldo x movimentos; normaliza para que so a adulteracao de cada teste apareca
        loja.update("UPDATE Produtos SET ProductQnt = 1000 WHERE CompanyId = ? AND ProductCode IN ('CT001','CT002')", EMPRESA);
        venda = vender("CT001", "1", "3", "3,00");
        loja.update("""
                UPDATE p SET ProductQnt = x.EstoqueNovo FROM Produtos p
                  CROSS APPLY (SELECT TOP 1 EstoqueNovo FROM EstoqueMovimentacoes m WHERE m.ProdutoId = p.Id ORDER BY m.CriadoEm DESC) x
                 WHERE p.CompanyId = ?
                """, EMPRESA);
        loja.update("""
                UPDATE c SET SaldoDevedor = x.SaldoAtual FROM Clientes c
                  CROSS APPLY (SELECT TOP 1 SaldoAtual FROM FiadoMovimentos f WHERE f.ClienteId = c.Id ORDER BY f.CriadoEm DESC) x
                 WHERE c.CompanyId = ?
                """, EMPRESA);
        amb.esperarConvergencia(60_000);
    }

    @AfterAll
    static void descer() {
        if (amb != null) amb.close();
    }

    private static JsonNode conferir() throws Exception {
        amb.esperarConvergencia(60_000);
        ConferenciaSincronizacao.Relatorio r = conferencia.executar("manual");
        return JSON.readTree(JSON.writeValueAsString(r));
    }

    private static int reenviadas(JsonNode r, String tabela) {
        for (JsonNode t : r.path("detalhe").path("tabelas")) if (tabela.equals(t.path("tabela").asText())) return t.path("reenviadas").asInt();
        return 0;
    }

    private static int excluidas(JsonNode r, String tabela) {
        for (JsonNode t : r.path("detalhe").path("tabelas")) if (tabela.equals(t.path("tabela").asText())) return t.path("excluidas").asInt();
        return 0;
    }

    private static void clonarSilencioso(JdbcTemplate j, String tabela, String idOrigem, String novoId) {
        List<String> cols = new ArrayList<>(new br.com.quack.pdv.plataforma.sincronizacao.catalogo.EsquemaJdbc(j).colunas().get(tabela));
        String insert = String.join(", ", cols.stream().map(c -> "[" + c + "]").toList());
        String select = String.join(", ", cols.stream().map(c -> c.equals("Id") ? "?" : "[" + c + "]").toList());
        j.update(SILENCIO + "INSERT INTO dbo.[" + tabela + "] (" + insert + ") SELECT " + select + " FROM dbo.[" + tabela
                + "] WHERE Id = ?", novoId, idOrigem);
    }

    // ------------------------------------------------------------------ 6.1: tabelas

    @Test
    @Order(1)
    void replicaIgualFechaSemCorrecaoEGravaOResultadoNasDuasPontas() throws Exception {
        JsonNode r = conferir();
        assertThat(r.path("resultado").asText()).as(r.toString()).isEqualTo("ok");
        assertThat(r.path("detalhe").path("tabelas")).isEmpty();
        assertThat(loja.queryForObject("SELECT TOP 1 Resultado FROM SyncConferencias ORDER BY Id DESC", String.class)).isEqualTo("ok");
        esperar("resultado na nuvem", 10_000, () -> "ok".equals(nuvem.queryForObject(
                "SELECT TOP 1 Resultado FROM SyncConferencias WHERE CompanyId = ? ORDER BY Id DESC", String.class, EMPRESA)));
    }

    @Test
    @Order(2)
    void linhaMudadaNoBancoDaNuvemSemRegistroEAcusadaReenviadaEFecha() throws Exception {
        DetectorNuvem detector = amb.nuvem.getBean(DetectorNuvem.class);
        detector.setPausado(true);
        try {
            // tracking desligado so na tabela: a mudanca nao deixa rastro nenhum
            nuvem.execute("ALTER TABLE dbo.Vendas DISABLE CHANGE_TRACKING");
            nuvem.update("UPDATE Vendas SET CustomerName = 'ADULTERADO NA NUVEM' WHERE Id = ?", venda.vendaId());
            nuvem.execute("ALTER TABLE dbo.Vendas ENABLE CHANGE_TRACKING WITH (TRACK_COLUMNS_UPDATED = OFF)");
        } finally {
            detector.setPausado(false);
        }
        assertThat(amb.diferencas()).as("a divergencia existe antes da conferencia").isNotEmpty();

        JsonNode r = conferir();

        assertThat(r.path("resultado").asText()).as(r.toString()).isEqualTo("ok");
        assertThat(r.path("voltas").asInt()).isBetween(1, 3);
        assertThat(reenviadas(r, "Vendas")).isGreaterThanOrEqualTo(1);
        assertThat(nuvem.queryForObject("SELECT CustomerName FROM Vendas WHERE Id = ?", String.class, venda.vendaId()))
                .isEqualTo(loja.queryForObject("SELECT CustomerName FROM Vendas WHERE Id = ?", String.class, venda.vendaId()));
        assertThat(amb.diferencas()).isEmpty();
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM SyncQuarentena WHERE CompanyId = ?", EMPRESA)).isZero();
    }

    @Test
    @Order(3)
    void exclusaoFeitaNaNuvemEmTabelaLojaNaoTemEmpresaMasAConferenciaCorrige() throws Exception {
        int antes = contar(nuvem, "SELECT COUNT(*) FROM VendaPagamentos WHERE VendaId = ?", venda.vendaId());
        assertThat(antes).isGreaterThanOrEqualTo(1);
        nuvem.update("DELETE FROM VendaPagamentos WHERE VendaId = ?", venda.vendaId()); // com tracking, sem empresa conhecida
        Thread.sleep(5_000); // o detector (2 s) passa e nao gera comando nem quarentena
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM VendaPagamentos WHERE VendaId = ?", venda.vendaId())).isZero();
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM SyncComandos WHERE CompanyId = ? AND Tabela = 'VendaPagamentos'", EMPRESA)).isZero();
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM SyncQuarentena WHERE CompanyId = ? AND Tabela = 'VendaPagamentos'", EMPRESA)).isZero();

        JsonNode r = conferir();

        assertThat(r.path("resultado").asText()).as(r.toString()).isEqualTo("ok");
        assertThat(reenviadas(r, "VendaPagamentos")).isGreaterThanOrEqualTo(1);
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM VendaPagamentos WHERE VendaId = ?", venda.vendaId())).isEqualTo(antes);
        assertThat(amb.diferencas()).isEmpty();
    }

    @Test
    @Order(4)
    void colunaLojaDivergenteEmTabelaSemEspelhoEEmTabelaComEspelhoVoltaAoValorDaLoja() throws Exception {
        String movimento = loja.queryForObject("SELECT TOP 1 Id FROM EstoqueMovimentacoes WHERE CompanyId = ? AND ProdutoId = 'pr-ct-1' "
                + "ORDER BY CriadoEm DESC", String.class, EMPRESA);
        String motivo = loja.queryForObject("SELECT Motivo FROM EstoqueMovimentacoes WHERE Id = ?", String.class, movimento);
        java.math.BigDecimal qnt = loja.queryForObject("SELECT ProductQnt FROM Produtos WHERE Id = 'pr-ct-1'", java.math.BigDecimal.class);
        // EstoqueMovimentacoes e LOJA e nao tem espelho; Produtos.ProductQnt e coluna LOJA de tabela EDITAVEL (com espelho)
        nuvem.update(SILENCIO + "UPDATE EstoqueMovimentacoes SET Motivo = 'ADULTERADO' WHERE Id = ?", movimento);
        nuvem.update(SILENCIO + "UPDATE Produtos SET ProductQnt = ProductQnt + 5 WHERE Id = 'pr-ct-1'");
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM SyncEspelho WHERE Tabela = 'EstoqueMovimentacoes'")).isZero();

        JsonNode r = conferir();

        assertThat(r.path("resultado").asText()).as(r.toString()).isEqualTo("ok");
        assertThat(reenviadas(r, "EstoqueMovimentacoes")).isGreaterThanOrEqualTo(1);
        assertThat(reenviadas(r, "Produtos")).isGreaterThanOrEqualTo(1);
        assertThat(nuvem.queryForObject("SELECT Motivo FROM EstoqueMovimentacoes WHERE Id = ?", String.class, movimento)).isEqualTo(motivo);
        assertThat(nuvem.queryForObject("SELECT ProductQnt FROM Produtos WHERE Id = 'pr-ct-1'", java.math.BigDecimal.class))
                .isEqualByComparingTo(qnt);
        assertThat(amb.diferencas()).isEmpty();
    }

    @Test
    @Order(5)
    void linhaQueSoExisteNaNuvemEExcluidaMasAEmQuarentenaFicaAteSerResolvida() throws Exception {
        String modelo = loja.queryForObject("SELECT TOP 1 Id FROM EstoqueMovimentacoes WHERE CompanyId = ?", String.class, EMPRESA);
        // sobra silenciosa (sem rastro): a conferencia exclui
        clonarSilencioso(nuvem, "EstoqueMovimentacoes", modelo, "mov-sobra-silenciosa");
        // sobra detectada (o detector a poe na quarentena): a spec manda manter ate um administrador resolver
        QuarentenaFiscalIT.clonar(nuvem, "EstoqueMovimentacoes", modelo, QuarentenaFiscalIT.mapa("Id", "mov-sobra-quarentena"));
        esperar("quarentena da sobra detectada", 30_000, () -> contar(nuvem, "SELECT COUNT(*) FROM SyncQuarentena WHERE Tabela = 'EstoqueMovimentacoes' AND Chave = 'mov-sobra-quarentena' AND ResolvidoEm IS NULL") >= 1);

        JsonNode r = conferir();

        assertThat(r.path("resultado").asText()).as(r.toString()).isEqualTo("ok");
        assertThat(excluidas(r, "EstoqueMovimentacoes")).isEqualTo(1);
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE Id = 'mov-sobra-silenciosa'")).isZero();
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE Id = 'mov-sobra-quarentena'")).isEqualTo(1);
        assertThat(r.path("detalhe").path("pendencias").asInt()).isGreaterThanOrEqualTo(1);

        // resolvida a quarentena, a proxima conferencia exclui a sobra
        nuvem.update("UPDATE SyncQuarentena SET ResolvidoEm = SYSDATETIMEOFFSET(), ResolvidoPor = 'usr-ct-admin' WHERE Tabela = 'EstoqueMovimentacoes'");
        JsonNode r2 = conferir();
        assertThat(r2.path("resultado").asText()).as(r2.toString()).isEqualTo("ok");
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE Id = 'mov-sobra-quarentena'")).isZero();
        assertThat(amb.diferencas()).isEmpty();
    }

    // ------------------------------------------------------------------ 6.1: saldos

    @Test
    @Order(6)
    void saldoDeProdutoDivergenteELogadoSemCorrecaoEGeraAlerta() throws Exception {
        java.math.BigDecimal certo = loja.queryForObject("SELECT ProductQnt FROM Produtos WHERE Id = 'pr-ct-1'", java.math.BigDecimal.class);
        loja.update("UPDATE Produtos SET ProductQnt = ProductQnt + 7 WHERE Id = 'pr-ct-1'");
        nuvem.update("DELETE FROM SyncAlertas WHERE Motivo = 'conferencia-divergente'");

        JsonNode r = conferir();

        assertThat(r.path("resultado").asText()).as(r.toString()).isEqualTo("divergente");
        assertThat(r.path("detalhe").path("tabelasFecharam").asBoolean()).isTrue();
        JsonNode produtos = r.path("detalhe").path("saldos").path("produtos");
        assertThat(produtos).hasSize(1);
        assertThat(produtos.get(0).path("id").asText()).isEqualTo("pr-ct-1");
        assertThat(new java.math.BigDecimal(produtos.get(0).path("saldoNoCadastro").asText())).isEqualByComparingTo(certo.add(java.math.BigDecimal.valueOf(7)));
        assertThat(new java.math.BigDecimal(produtos.get(0).path("saldoDasMovimentacoes").asText())).isEqualByComparingTo(certo);
        assertThat(loja.queryForObject("SELECT ProductQnt FROM Produtos WHERE Id = 'pr-ct-1'", java.math.BigDecimal.class))
                .as("nao corrige sozinha").isEqualByComparingTo(certo.add(java.math.BigDecimal.valueOf(7)));
        assertThat(contar(loja, "SELECT COUNT(*) FROM SyncAlertas WHERE Motivo = 'conferencia-divergente' AND CompanyId = ? "
                + "AND Detalhe LIKE '%pr-ct-1%'", EMPRESA)).isEqualTo(1);

        loja.update("UPDATE Produtos SET ProductQnt = ? WHERE Id = 'pr-ct-1'", certo);
        assertThat(conferir().path("resultado").asText()).isEqualTo("ok");
    }

    @Test
    @Order(7)
    void saldoDeFiadoDivergenteELogadoSemCorrecao() throws Exception {
        String cliente = loja.queryForObject("SELECT TOP 1 Id FROM Clientes WHERE CompanyId = ? ORDER BY Id", String.class, EMPRESA);
        java.math.BigDecimal saldo = loja.queryForObject("SELECT SaldoDevedor FROM Clientes WHERE Id = ?", java.math.BigDecimal.class, cliente);
        loja.update("""
                INSERT INTO FiadoMovimentos (Id, CompanyId, ClienteId, Tipo, Valor, SaldoAnterior, SaldoAtual, VendaId, FormaPagamento,
                                             Observacao, OperadorNome, CriadoEm)
                VALUES ('fiado-conf-1', ?, ?, 1, 11.11, ?, ?, NULL, NULL, 'IT', 'IT', SYSDATETIMEOFFSET())
                """, EMPRESA, cliente, saldo, saldo.add(new java.math.BigDecimal("11.11")));

        JsonNode r = conferir();

        assertThat(r.path("resultado").asText()).as(r.toString()).isEqualTo("divergente");
        JsonNode clientes = r.path("detalhe").path("saldos").path("clientes");
        assertThat(clientes).hasSize(1);
        assertThat(clientes.get(0).path("id").asText()).isEqualTo(cliente);
        assertThat(new java.math.BigDecimal(clientes.get(0).path("saldoNoCadastro").asText())).isEqualByComparingTo(saldo);
        assertThat(new java.math.BigDecimal(clientes.get(0).path("saldoDosMovimentos").asText()))
                .isEqualByComparingTo(saldo.add(new java.math.BigDecimal("11.11")));
        assertThat(loja.queryForObject("SELECT SaldoDevedor FROM Clientes WHERE Id = ?", java.math.BigDecimal.class, cliente))
                .as("nao corrige sozinha").isEqualByComparingTo(saldo);

        loja.update("DELETE FROM FiadoMovimentos WHERE Id = 'fiado-conf-1'");
        assertThat(conferir().path("resultado").asText()).isEqualTo("ok");
    }

    // ------------------------------------------------------------------ 6.1: rota de pedido e sem internet

    @Test
    @Order(8)
    void rotaDePedidoExigeAdministradorEDevolveOResultadoGravado() throws Exception {
        String admin = amb.tokenLoja("usr-ct-admin", EMPRESA, "Ana Administradora", "admin@contrato.quack", "administrador");
        String caixa = amb.tokenLoja("usr-ct-caixa", EMPRESA, "Cai Caixa", "caixa@contrato.quack", "caixa");
        amb.esperarConvergencia(60_000);
        HttpResponse<String> semPermissao = post("/api/loja-sync/conferencia", caixa);
        assertThat(semPermissao.statusCode()).isEqualTo(403);
        HttpResponse<String> ok = post("/api/loja-sync/conferencia", admin);
        assertThat(ok.statusCode()).isEqualTo(200);
        JsonNode corpo = JSON.readTree(ok.body()).path("data");
        assertThat(corpo.path("resultado").asText()).isEqualTo("ok");
        assertThat(corpo.path("origem").asText()).isEqualTo("manual");
        assertThat(loja.queryForObject("SELECT COUNT(*) FROM SyncConferencias WHERE Id = ?", Integer.class, corpo.path("id").asLong())).isEqualTo(1);
    }

    @Test
    @Order(9)
    void semInternetAConferenciaTerminaEmErroSemMexerNosDados() throws Exception {
        ClienteNuvemHttp cliente = amb.cliente();
        cliente.setSemInternet(true);
        try {
            ConferenciaSincronizacao.Relatorio r = conferencia.executar("manual");
            assertThat(r.resultado()).isEqualTo("erro");
            assertThat(r.detalhe().path("erro").asText()).contains("inacessivel");
        } finally {
            cliente.setSemInternet(false);
        }
    }

    private static HttpResponse<String> post(String caminho, String token) throws Exception {
        return amb.http.send(HttpRequest.newBuilder(URI.create(amb.urlBaseLoja() + caminho))
                .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
