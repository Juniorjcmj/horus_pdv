package br.com.quack.pdv.plataforma.sincronizacao.integracao;

import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.EMPRESA;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.esperar;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.SincronizacaoIT.contar;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.plataforma.sincronizacao.catalogo.EsquemaJdbc;
import br.com.quack.pdv.plataforma.sincronizacao.envio.ClienteNuvemHttp;
import br.com.quack.pdv.plataforma.sincronizacao.conferencia.ConferenciaSincronizacao;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
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
 * Operacoes de estoque lancadas na nuvem (change api-java-operacoes-estoque-nuvem): a nuvem grava como a .NET grava (uma
 * transacao por operacao, por SQL, sem contexto de tracking) e a loja aplica pela diferenca. Os testes dependem da ordem.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OperacoesNuvemIT {

    static final ObjectMapper JSON = new ObjectMapper();
    static AmbienteSync amb;
    static JdbcTemplate loja;
    static JdbcTemplate nuvem;
    static ClienteNuvemHttp internet;
    static String produtoId;
    static String notaEntrada;

    @BeforeAll
    static void subir() {
        amb = AmbienteSync.iniciar("opn");
        loja = amb.jdbcLoja;
        nuvem = amb.jdbcNuvem;
        internet = amb.cliente();
        loja.update("UPDATE Produtos SET ProductQnt = 1000 WHERE CompanyId = ? AND ProductCode IN ('CT001','CT002')", EMPRESA);
        amb.esperarConvergencia(60_000);
        produtoId = nuvem.queryForObject("SELECT Id FROM Produtos WHERE CompanyId = ? AND ProductCode = 'CT001'", String.class, EMPRESA);
    }

    @AfterAll
    static void descer() {
        if (amb != null) amb.close();
    }

    // ------------------------------------------------------------------ apoio

    static BigDecimal saldo(JdbcTemplate j, String id) {
        return j.queryForObject("SELECT ProductQnt FROM Produtos WHERE Id = ?", BigDecimal.class, id);
    }

    static long maiorSeq() {
        Long v = nuvem.queryForObject("SELECT ISNULL(MAX(Seq), 0) FROM SyncComandos", Long.class);
        return v == null ? 0 : v;
    }

    static Map<String, Object> operacao(long depoisDe) {
        return AmbienteSync.linha(nuvem, "SELECT TOP 1 * FROM SyncComandos WHERE Tipo = 'operacao' AND Seq > ? ORDER BY Seq", depoisDe);
    }

    static void esperarOperacao(long depoisDe, String status) {
        esperar("operacao em " + status, 60_000, () -> status.equals(operacao(depoisDe).get("Status")));
    }

    static int quarentenaAberta() {
        return contar(nuvem, "SELECT COUNT(*) FROM SyncQuarentena WHERE ResolvidoEm IS NULL");
    }

    static String identidade(String s) {
        return String.format("%64s", s).replace(' ', '0');
    }

    static String movimento(String id, String tipo, String qtd, BigDecimal anterior, BigDecimal novo, String origemTipo, String origemId) {
        return "INSERT INTO EstoqueMovimentacoes (Id, CompanyId, ProdutoId, Tipo, Quantidade, EstoqueAnterior, EstoqueNovo, Motivo, "
                + "UsuarioId, UsuarioNome, CriadoEm, CustoUnitario, OrigemTipo, OrigemId, DepositoId) VALUES ('" + id + "', '" + EMPRESA
                + "', '" + produtoId + "', '" + tipo + "', " + qtd + ", " + anterior.toPlainString() + ", " + novo.toPlainString()
                + ", N'Entrada de teste', 'u1', N'Painel', SYSUTCDATETIME(), 2.5, '" + origemTipo + "', '" + origemId + "', 'loja');";
    }

    static String nota(String id, String identidade, String numero, int itens) {
        return "INSERT INTO NotasEntradaArquivo (Id, CompanyId, Identidade, ChaveAcesso, Modelo, NumeroNota, Serie, FornecedorNome, "
                + "FornecedorCnpj, ValorNota, ValorEntrada, QuantidadeItens, Origem, EntradaJson, UsuarioId, UsuarioNome, CriadaEm, Estornada) "
                + "VALUES ('" + id + "', '" + EMPRESA + "', '" + identidade + "', NULL, 55, '" + numero + "', '1', N'Forn', '12345678000199', "
                + "100, 100, " + itens + ", 'xml', N'{}', 'u1', N'Painel', SYSUTCDATETIME(), 0);";
    }

    static String lote(String id, String qtd) {
        return "INSERT INTO ProdutoLotes (Id, CompanyId, ProdutoId, NumeroLote, DataValidade, QtdInicial, Origem, ValidadePadrao, CriadoEm, "
                + "CriadoPorNome, QtdAtual) VALUES ('" + id + "', '" + EMPRESA + "', '" + produtoId + "', 'L1', '2030-01-01', " + qtd
                + ", 'nfe', 0, SYSDATETIMEOFFSET(), N'Painel', " + qtd + ");";
    }

    /** Uma transacao so, como a .NET: entrada de {@code qtd} sobre o saldo que a nuvem enxerga. */
    static String entrada(String idNota, String identidade, String numero, int qtd, String sufixo) {
        BigDecimal ant = saldo(nuvem, produtoId);
        BigDecimal novo = ant.add(BigDecimal.valueOf(qtd));
        return "BEGIN TRAN; UPDATE Produtos SET ProductQnt = " + novo.toPlainString() + " WHERE Id = '" + produtoId + "'; "
                + movimento("mov-" + sufixo, "entrada", String.valueOf(qtd), ant, novo, "nfe", identidade)
                + lote("lote-" + sufixo, String.valueOf(qtd)) + nota(idNota, identidade, numero, 1) + " COMMIT;";
    }

    // ------------------------------------------------------------------ cenarios

    @Test
    @Order(1)
    void lojaAnunciaORecursoNoLongPoll() {
        esperar("recurso anunciado", 60_000, () -> "operacao-estoque".equals(nuvem.queryForObject(
                "SELECT Recursos FROM SyncServidores WHERE CompanyId = ?", String.class, EMPRESA)));
    }

    @Test
    @Order(2)
    void entradaLancadaNaNuvemSobeOEstoqueDaLojaEVoltaIgual() throws Exception {
        BigDecimal base = saldo(loja, produtoId);
        long antes = maiorSeq();
        int quarentena = quarentenaAberta();
        notaEntrada = "nota-nuv-1";
        nuvem.execute(entrada(notaEntrada, identidade("a1"), "1001", 10, "n1"));

        esperarOperacao(antes, "aplicado");
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM SyncComandos WHERE Tipo = 'operacao' AND Seq > ?", antes)).isEqualTo(1);
        JsonNode passos = JSON.readTree((String) operacao(antes).get("PayloadJson")).path("passos");
        assertThat(passos).hasSize(3);
        assertThat(passos.get(0).path("tipo").asText()).isEqualTo("movimento");
        assertThat(passos.get(1).path("tabela").asText()).isEqualTo("ProdutoLotes");
        assertThat(passos.get(2).path("tabela").asText()).isEqualTo("NotasEntradaArquivo");
        assertThat(saldo(loja, produtoId)).isEqualByComparingTo(base.add(BigDecimal.TEN));
        assertThat(contar(loja, "SELECT COUNT(*) FROM NotasEntradaArquivo WHERE Id = 'nota-nuv-1'")).isEqualTo(1);
        assertThat(contar(loja, "SELECT COUNT(*) FROM ProdutoLotes WHERE Id = 'lote-n1'")).isEqualTo(1);
        assertThat(contar(loja, "SELECT COUNT(*) FROM AuditLog WHERE EventType = 'SincronizacaoOperacao'")).isGreaterThanOrEqualTo(1);
        amb.esperarConvergencia(60_000);
        assertThat(saldo(nuvem, produtoId)).isEqualByComparingTo(base.add(BigDecimal.TEN));
        assertThat(quarentenaAberta()).as("nada foi para a quarentena").isEqualTo(quarentena);
    }

    @Test
    @Order(3)
    void vendaNaLojaEnquantoANotaEhLancadaNaNuvemSomaNoSaldoDaLoja() throws Exception {
        BigDecimal y = saldo(loja, produtoId);
        long antes = maiorSeq();
        internet.setSemInternet(true);
        try {
            loja.update("UPDATE Produtos SET ProductQnt = ProductQnt - 3 WHERE Id = ?", produtoId); // venda que a nuvem nao viu
            nuvem.execute(entrada("nota-nuv-2", identidade("a2"), "1002", 10, "n2"));
            esperar("operacao criada", 20_000, () -> operacao(antes).get("Seq") != null);
        } finally {
            internet.setSemInternet(false);
        }
        amb.zerarBackoff();
        esperarOperacao(antes, "aplicado");
        assertThat(saldo(loja, produtoId)).isEqualByComparingTo(y.subtract(BigDecimal.valueOf(3)).add(BigDecimal.TEN));
        Map<String, Object> mov = AmbienteSync.linha(loja, "SELECT * FROM EstoqueMovimentacoes WHERE Id = 'mov-n2'");
        assertThat((BigDecimal) mov.get("EstoqueAnterior")).isEqualByComparingTo(y.subtract(BigDecimal.valueOf(3)));
        assertThat((BigDecimal) mov.get("EstoqueNovo")).isEqualByComparingTo(y.add(BigDecimal.valueOf(7)));
        amb.esperarConvergencia(60_000);
        esperar("nuvem com o saldo da loja", 30_000, () -> saldo(nuvem, produtoId).compareTo(saldo(loja, produtoId)) == 0);
    }

    @Test
    @Order(4)
    void estornoPelaNuvemBaixaOSaldoEMarcaANota() throws Exception {
        BigDecimal base = saldo(loja, produtoId);
        BigDecimal ant = saldo(nuvem, produtoId);
        long antes = maiorSeq();
        // como a .NET: uma transacao por item e outra para a nota
        nuvem.execute("BEGIN TRAN; UPDATE Produtos SET ProductQnt = " + ant.subtract(BigDecimal.TEN).toPlainString() + " WHERE Id = '"
                + produtoId + "'; " + movimento("mov-est1", "estorno", "10", ant, ant.subtract(BigDecimal.TEN), "nota_entrada", notaEntrada)
                + " COMMIT;");
        nuvem.update("UPDATE NotasEntradaArquivo SET Estornada = 1, EstornadaEm = SYSUTCDATETIME(), EstornadaPorUsuarioId = 'u1', "
                + "EstornadaPorUsuarioNome = N'Painel', EstornoJustificativa = N'teste de estorno' WHERE Id = ?", notaEntrada);
        esperar("duas operacoes aplicadas", 60_000, () -> contar(nuvem,
                "SELECT COUNT(*) FROM SyncComandos WHERE Tipo = 'operacao' AND Seq > ? AND Status = 'aplicado'", antes) == 2);
        assertThat(saldo(loja, produtoId)).isEqualByComparingTo(base.subtract(BigDecimal.TEN));
        assertThat(loja.queryForObject("SELECT Estornada FROM NotasEntradaArquivo WHERE Id = ?", Boolean.class, notaEntrada)).isTrue();
        amb.esperarConvergencia(60_000);
    }

    @Test
    @Order(5)
    void notaEmDobroSobeUmaVezSoEViraConflitoComAlerta() throws Exception {
        BigDecimal base = saldo(loja, produtoId);
        long antes = maiorSeq();
        String ident = identidade("dd");
        internet.setSemInternet(true);
        try {
            BigDecimal ant = saldo(loja, produtoId);
            loja.execute("BEGIN TRAN; UPDATE Produtos SET ProductQnt = ProductQnt + 10 WHERE Id = '" + produtoId + "'; "
                    + movimento("mov-loja-dd", "entrada", "10", ant, ant.add(BigDecimal.TEN), "nfe", ident).replace("'" + produtoId + "'", "'" + produtoId + "'")
                    + nota("nota-loja-dd", ident, "1234", 1) + " COMMIT;");
            nuvem.execute(entrada("nota-nuv-dd", ident, "1234", 10, "dd"));
            esperar("operacao criada", 20_000, () -> operacao(antes).get("Seq") != null);
        } finally {
            internet.setSemInternet(false);
        }
        amb.zerarBackoff();
        esperarOperacao(antes, "conflito");
        JsonNode det = JSON.readTree((String) operacao(antes).get("ResultadoJson"));
        assertThat(det.path("motivo").asText()).isEqualTo("nota-ja-lancada");
        assertThat(det.path("numero").asText()).isEqualTo("1234");
        assertThat(saldo(loja, produtoId)).as("entrou so a nota da loja").isEqualByComparingTo(base.add(BigDecimal.TEN));
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM SyncAlertas WHERE Motivo = 'comando-conflito'")).isGreaterThanOrEqualTo(1);
        // a nuvem se ajusta a loja: a nota recusada sai, a da loja chega
        amb.esperarConvergencia(60_000);
        esperar("nota da nuvem fora", 30_000, () -> contar(nuvem, "SELECT COUNT(*) FROM NotasEntradaArquivo WHERE Id = 'nota-nuv-dd'") == 0);
        esperar("nota da loja na nuvem", 30_000, () -> contar(nuvem, "SELECT COUNT(*) FROM NotasEntradaArquivo WHERE Id = 'nota-loja-dd'") == 1);
        ConferenciaSincronizacao conferencia = amb.loja.getBean(ConferenciaSincronizacao.class);
        conferencia.executar("manual");
        amb.esperarConvergencia(60_000);
        assertThat(saldo(nuvem, produtoId)).isEqualByComparingTo(saldo(loja, produtoId));
    }

    @Test
    @Order(6)
    void produtoNovoPelaNotaChegaComSaldoZeroERecebePelaMovimentacao() throws Exception {
        long antes = maiorSeq();
        List<String> cols = new ArrayList<>(new EsquemaJdbc(nuvem).colunas().get("Produtos"));
        String insert = String.join(", ", cols.stream().map(c -> "[" + c + "]").toList());
        String select = String.join(", ", cols.stream().map(c -> switch (c) {
            case "Id" -> "'pr-nuv-novo'";
            case "ProductCode" -> "'NUVNOVO1'";
            case "ProductQnt" -> "5";
            case "TotalPriceOnProduct" -> "5";
            case "Gtin" -> "'SEM GTIN'";
            default -> "[" + c + "]";
        }).toList());
        String movimentoNovo = movimento("mov-novo", "entrada", "5", BigDecimal.ZERO, new BigDecimal("5"), "nfe", "novo")
                .replace("'" + produtoId + "'", "'pr-nuv-novo'");
        nuvem.execute("BEGIN TRAN; INSERT INTO dbo.Produtos (" + insert + ") SELECT " + select + " FROM dbo.Produtos WHERE Id = '"
                + produtoId + "'; " + movimentoNovo + " COMMIT;");
        esperarOperacao(antes, "aplicado");
        assertThat(contar(loja, "SELECT COUNT(*) FROM Produtos WHERE Id = 'pr-nuv-novo'")).isEqualTo(1);
        assertThat(saldo(loja, "pr-nuv-novo")).as("5 da movimentacao, nao 10").isEqualByComparingTo("5");
        amb.esperarConvergencia(60_000);
        assertThat(saldo(nuvem, "pr-nuv-novo")).isEqualByComparingTo("5");
    }

    @Test
    @Order(7)
    void ajusteDeSaldoSemMovimentoEAlteracaoDeMovimentoContinuamNaQuarentena() throws Exception {
        int antes = quarentenaAberta();
        nuvem.update("UPDATE Produtos SET ProductQnt = ProductQnt + 1 WHERE CompanyId = ? AND ProductCode = 'CT002'", EMPRESA);
        esperar("quarentena do saldo sem movimento", 30_000, () -> quarentenaAberta() > antes);
        int meio = quarentenaAberta();
        nuvem.update("UPDATE EstoqueMovimentacoes SET Quantidade = Quantidade + 1 WHERE Id = 'mov-n1'");
        esperar("quarentena da alteracao de movimento", 30_000, () -> quarentenaAberta() > meio);
    }

    @Test
    @Order(8)
    void notaDe300ItensAplicaTudoENaoPassaDoLimiteDeEntrega() throws Exception {
        BigDecimal base = saldo(loja, produtoId);
        BigDecimal ant = saldo(nuvem, produtoId);
        long antes = maiorSeq();
        StringBuilder sb = new StringBuilder("BEGIN TRAN; ");
        BigDecimal atual = ant;
        for (int i = 0; i < 300; i++) {
            BigDecimal novo = atual.add(BigDecimal.ONE);
            sb.append(movimento("mov-big-" + i, "entrada", "1", atual, novo, "nfe", "big")).append(lote("lote-big-" + i, "1"));
            atual = novo;
        }
        sb.append("UPDATE Produtos SET ProductQnt = ").append(atual.toPlainString()).append(" WHERE Id = '").append(produtoId).append("'; ")
                .append(nota("nota-big", identidade("bb"), "9999", 300)).append(" COMMIT;");
        nuvem.execute(sb.toString());
        esperarOperacao(antes, "aplicado");
        String payload = (String) operacao(antes).get("PayloadJson");
        System.out.println("TAMANHO_OPERACAO_300_ITENS=" + payload.length());
        assertThat(payload.length()).isLessThan(8_000_000);
        assertThat(saldo(loja, produtoId)).isEqualByComparingTo(base.add(BigDecimal.valueOf(300)));
        assertThat(contar(loja, "SELECT COUNT(*) FROM ProdutoLotes WHERE Id LIKE 'lote-big-%'")).isEqualTo(300);
    }

    @Test
    @Order(9)
    void conferenciaComALojaOfflineMantemANotaDaNuvem() throws Exception {
        long antes = maiorSeq();
        internet.setSemInternet(true);
        try {
            nuvem.execute(entrada("nota-nuv-conf", identidade("cf"), "5005", 4, "cf"));
            esperar("operacao criada", 20_000, () -> operacao(antes).get("Seq") != null);
            var conf = amb.nuvem.getBean(br.com.quack.pdv.plataforma.sincronizacao.conferencia.ServicoConferencia.class);
            conf.excluir(EMPRESA, List.of(
                    new br.com.quack.pdv.plataforma.sincronizacao.conferencia.ServicoConferencia.ChavesDaTabela("NotasEntradaArquivo", List.of("nota-nuv-conf")),
                    new br.com.quack.pdv.plataforma.sincronizacao.conferencia.ServicoConferencia.ChavesDaTabela("EstoqueMovimentacoes", List.of("mov-cf"))));
            assertThat(contar(nuvem, "SELECT COUNT(*) FROM NotasEntradaArquivo WHERE Id = 'nota-nuv-conf'")).isEqualTo(1);
            assertThat(contar(nuvem, "SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE Id = 'mov-cf'")).isEqualTo(1);
        } finally {
            internet.setSemInternet(false);
        }
        amb.zerarBackoff();
        esperarOperacao(antes, "aplicado");
        assertThat(contar(loja, "SELECT COUNT(*) FROM NotasEntradaArquivo WHERE Id = 'nota-nuv-conf'")).isEqualTo(1);
    }
}
