package br.com.quack.pdv.plataforma.sincronizacao.integracao;

import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.EMPRESA;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.esperar;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.SincronizacaoIT.contar;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.SincronizacaoIT.vender;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.plataforma.sincronizacao.envio.ClienteNuvemHttp;
import br.com.quack.pdv.plataforma.sincronizacao.nuvem.DetectorNuvem;
import br.com.quack.pdv.venda.registro.app.VendaRegistroService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
 * Comandos nuvem -> loja (tarefas 4.1 e 4.2): cada cenario da spec comandos-nuvem-loja contra dois bancos e dois
 * contextos. As edicoes "da nuvem" sao UPDATE/INSERT por SQL no banco da nuvem, sem contexto de tracking (e o que a .NET
 * faz). Os testes dependem da ordem (estado compartilhado).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ComandosIT {

    static final ObjectMapper JSON = new ObjectMapper();
    static AmbienteSync amb;
    static JdbcTemplate loja;
    static JdbcTemplate nuvem;
    static ClienteNuvemHttp internet;
    static DetectorNuvem detector;

    @BeforeAll
    static void subir() {
        amb = AmbienteSync.iniciar("cmd");
        loja = amb.jdbcLoja;
        nuvem = amb.jdbcNuvem;
        internet = amb.cliente();
        detector = amb.nuvem.getBean(DetectorNuvem.class);
        SincronizacaoIT.vendas = amb.loja.getBean(VendaRegistroService.class);
        loja.update("UPDATE Produtos SET ProductQnt = 1000 WHERE CompanyId = ? AND ProductCode IN ('CT001','CT002')", EMPRESA);
        amb.esperarConvergencia(60_000);
    }

    @AfterAll
    static void descer() {
        if (amb != null) amb.close();
    }

    // ------------------------------------------------------------------ apoio

    static String idProduto(String codigo) {
        return nuvem.queryForObject("SELECT Id FROM Produtos WHERE CompanyId = ? AND ProductCode = ?", String.class, EMPRESA, codigo);
    }

    static BigDecimal preco(JdbcTemplate j, String codigo) {
        return j.queryForObject("SELECT ProductSalePrice FROM Produtos WHERE CompanyId = ? AND ProductCode = ?", BigDecimal.class,
                EMPRESA, codigo);
    }

    static BigDecimal estoque(JdbcTemplate j, String codigo) {
        return j.queryForObject("SELECT ProductQnt FROM Produtos WHERE CompanyId = ? AND ProductCode = ?", BigDecimal.class,
                EMPRESA, codigo);
    }

    static long maiorSeq() {
        Long v = nuvem.queryForObject("SELECT ISNULL(MAX(Seq), 0) FROM SyncComandos", Long.class);
        return v == null ? 0 : v;
    }

    static Map<String, Object> comando(String tabela, String chave, String tipo, long depoisDe) {
        return AmbienteSync.linha(nuvem, "SELECT TOP 1 * FROM SyncComandos WHERE Tabela = ? AND Chave = ? AND Tipo = ? AND Seq > ? "
                + "ORDER BY Seq DESC", tabela, chave, tipo, depoisDe);
    }

    static void esperarStatus(String tabela, String chave, String tipo, long depoisDe, String status) {
        esperar("comando " + tipo + " " + tabela + " em " + status, 40_000, () ->
                status.equals(comando(tabela, chave, tipo, depoisDe).get("Status")));
    }

    static void inserirCliente(JdbcTemplate j, String id, String nome, String documento) {
        j.update("""
                INSERT INTO Clientes (Id, CompanyId, CustomerName, Document, BirthDate, Age, Cep, City, State, Address, Neighborhood,
                       StreetComplement, Number, ReferencePoint, Telephone, Cellphone, Email, IndIeDest, LimiteCredito, SaldoDevedor)
                VALUES (?, ?, ?, ?, '', '', '', '', 'SP', '', '', '', '', '', '', '', '', 9, 0, 0)
                """, id, EMPRESA, nome, documento);
    }

    // ------------------------------------------------------------------ 4.1: sincronizacao nao volta como comando

    @Test
    @Order(1)
    void loteAplicadoNaoGeraComando() throws Exception {
        long antes = maiorSeq();
        SincronizacaoIT.vendas = amb.loja.getBean(VendaRegistroService.class);
        vender("CT001", "1", "3", "3,00");
        loja.update("INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES ('cat-cmd-1', ?, 'Cmd', NULL, 98, 1)", EMPRESA);
        loja.update("UPDATE Fornecedores SET FantasyName = FantasyName WHERE CompanyId = ?", EMPRESA);
        amb.esperarConvergencia(60_000);
        esperar("categoria na nuvem", 30_000, () -> contar(nuvem, "SELECT COUNT(*) FROM Categorias WHERE Id = 'cat-cmd-1'") == 1);
        Thread.sleep(6_000); // tres ciclos do detector
        assertThat(maiorSeq()).as("nenhum comando por causa de linhas que a propria sincronizacao aplicou").isEqualTo(antes);
    }

    // ------------------------------------------------------------------ cenarios 4.2

    @Test
    @Order(2)
    void precoNovoValeNoProximoCaixaEOComandoTemABase() throws Exception {
        BigDecimal antigo = preco(nuvem, "CT001");
        long antes = maiorSeq();
        BigDecimal novo = new BigDecimal("7.7700");
        nuvem.update("UPDATE Produtos SET ProductSalePrice = ? WHERE CompanyId = ? AND ProductCode = 'CT001'", novo, EMPRESA);
        String id = idProduto("CT001");
        esperarStatus("Produtos", id, "alterar", antes, "aplicado");
        assertThat(preco(loja, "CT001")).isEqualByComparingTo(novo);

        JsonNode payload = JSON.readTree((String) comando("Produtos", id, "alterar", antes).get("PayloadJson"));
        assertThat(payload.path("colunas")).hasSize(1);
        assertThat(payload.path("colunas").get(0).path("nome").asText()).isEqualTo("ProductSalePrice");
        assertThat(new BigDecimal(payload.path("colunas").get(0).path("base").asText())).isEqualByComparingTo(antigo);
        assertThat(new BigDecimal(payload.path("colunas").get(0).path("novo").asText())).isEqualByComparingTo(novo);

        // o proximo caixa vende pelo preco do cadastro da loja
        BigDecimal naLoja = loja.queryForObject("SELECT ProductSalePrice FROM Produtos WHERE Id = ?", BigDecimal.class, id);
        assertThat(naLoja).isEqualByComparingTo("7.77");
        amb.esperarConvergencia(60_000);
        assertThat(preco(nuvem, "CT001")).as("a nuvem continua com o preco novo").isEqualByComparingTo(novo);
        assertThat(loja.queryForObject("SELECT COUNT(*) FROM AuditLog WHERE EventType = 'SincronizacaoComando' AND EntityId = ?",
                Integer.class, id)).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(3)
    void clienteNovoPelaNuvemExisteNaLoja() {
        long antes = maiorSeq();
        inserirCliente(nuvem, "cli-nuvem-1", "Cliente da Nuvem", "11122233344");
        esperarStatus("Clientes", "cli-nuvem-1", "inserir", antes, "aplicado");
        assertThat(contar(loja, "SELECT COUNT(*) FROM Clientes WHERE Id = 'cli-nuvem-1' AND CustomerName = 'Cliente da Nuvem'")).isEqualTo(1);
        amb.esperarConvergencia(60_000);
    }

    @Test
    @Order(4)
    void edicaoSimultaneaNaLojaENaNuvemViraConflitoELojaVence() throws Exception {
        String id = idProduto("CT001");
        long antes = maiorSeq();
        internet.setSemInternet(true);
        try {
            loja.update("UPDATE Produtos SET ProductSalePrice = 8.1000 WHERE Id = ?", id);
            nuvem.update("UPDATE Produtos SET ProductSalePrice = 9.2000 WHERE Id = ?", id);
            esperar("comando criado na nuvem", 20_000, () -> comando("Produtos", id, "alterar", antes).get("Seq") != null);
        } finally {
            internet.setSemInternet(false);
        }
        amb.zerarBackoff();
        esperarStatus("Produtos", id, "alterar", antes, "conflito");
        amb.esperarConvergencia(60_000);
        esperar("nuvem com o preco da loja", 30_000, () -> preco(nuvem, "CT001").compareTo(new BigDecimal("8.1")) == 0);
        assertThat(preco(loja, "CT001")).isEqualByComparingTo("8.1");

        JsonNode det = JSON.readTree((String) comando("Produtos", id, "alterar", antes).get("ResultadoJson"));
        JsonNode conflito = det.path("conflitos").get(0);
        assertThat(conflito.path("coluna").asText()).isEqualTo("ProductSalePrice");
        assertThat(new BigDecimal(conflito.path("loja").asText())).isEqualByComparingTo("8.1");
        assertThat(new BigDecimal(conflito.path("novo").asText())).isEqualByComparingTo("9.2");
    }

    @Test
    @Order(5)
    void colunasDiferentesValemAsDuas() {
        String id = idProduto("CT002");
        long antes = maiorSeq();
        internet.setSemInternet(true);
        try {
            loja.update("UPDATE Produtos SET ProductQnt = ProductQnt - 7 WHERE Id = ?", id);
            nuvem.update("UPDATE Produtos SET ProductDescription = N'Descricao da nuvem' WHERE Id = ?", id);
            esperar("comando criado na nuvem", 20_000, () -> comando("Produtos", id, "alterar", antes).get("Seq") != null);
        } finally {
            internet.setSemInternet(false);
        }
        amb.zerarBackoff();
        esperarStatus("Produtos", id, "alterar", antes, "aplicado");
        amb.esperarConvergencia(60_000);
        BigDecimal saldoLoja = estoque(loja, "CT002");
        esperar("saldo da loja na nuvem", 30_000, () -> estoque(nuvem, "CT002").compareTo(saldoLoja) == 0);
        assertThat(loja.queryForObject("SELECT ProductDescription FROM Produtos WHERE Id = ?", String.class, id))
                .isEqualTo("Descricao da nuvem");
        assertThat(nuvem.queryForObject("SELECT ProductDescription FROM Produtos WHERE Id = ?", String.class, id))
                .isEqualTo("Descricao da nuvem");
    }

    @Test
    @Order(6)
    void vendaLogoDepoisDaEdicaoDePrecoNaoApagaOPreco() throws Exception {
        String id = idProduto("CT001");
        long antes = maiorSeq();
        detector.setPausado(true); // a venda chega antes de o detector periodico ver a edicao
        try {
            Thread.sleep(300);
            nuvem.update("UPDATE Produtos SET ProductSalePrice = 6.5000 WHERE Id = ?", id);
            vender("CT001", "2", "8.1", "16,20"); // a loja ainda vende pelo preco dela (8,10)
            esperar("lote da venda aplicado", 40_000, () -> amb.convergiu());
            assertThat(preco(nuvem, "CT001")).as("a nuvem manteve o preco novo").isEqualByComparingTo("6.5");
            assertThat(comando("Produtos", id, "alterar", antes).get("Seq")).as("comando criado pela deteccao inline").isNotNull();
        } finally {
            detector.setPausado(false);
        }
        esperarStatus("Produtos", id, "alterar", antes, "aplicado");
        amb.esperarConvergencia(60_000);
        assertThat(preco(loja, "CT001")).isEqualByComparingTo("6.5");
        assertThat(preco(nuvem, "CT001")).isEqualByComparingTo("6.5");
        assertThat(estoque(nuvem, "CT001")).isEqualByComparingTo(estoque(loja, "CT001"));
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM SyncComandos WHERE Tabela = 'Produtos' AND Chave = ? AND Tipo = 'alterar' AND Seq > ?",
                id, antes)).as("um unico comando, sem duplicata").isEqualTo(1);
    }

    @Test
    @Order(7)
    void comandosGuardadosSemInternetSaoAplicadosEmOrdem() {
        long antes = maiorSeq();
        String id1 = idProduto("CT001");
        String id2 = idProduto("CT002");
        internet.setSemInternet(true);
        try {
            nuvem.update("UPDATE Produtos SET ProductSalePrice = 1.1100 WHERE Id = ?", id1);
            esperar("1o comando", 20_000, () -> maiorSeq() > antes);
            nuvem.update("UPDATE Produtos SET ProductSalePrice = 2.2200 WHERE Id = ?", id2);
            esperar("2o comando", 20_000, () -> maiorSeq() > antes + 1);
            inserirCliente(nuvem, "cli-nuvem-2", "Cliente Offline", "55566677788");
            esperar("3o comando", 20_000, () -> maiorSeq() > antes + 2);
            assertThat(preco(loja, "CT001")).as("nada chegou enquanto sem internet").isNotEqualByComparingTo("1.11");
        } finally {
            internet.setSemInternet(false);
        }
        amb.zerarBackoff();
        esperar("os tres aplicados", 40_000, () -> contar(nuvem, "SELECT COUNT(*) FROM SyncComandos WHERE Seq > ? AND Status = 'aplicado'", antes) >= 3);
        List<Long> naOrdemDeAplicacao = loja.queryForList(
                "SELECT Seq FROM SyncComandosAplicados WHERE Seq > ? ORDER BY AplicadoEm, Seq", Long.class, antes);
        assertThat(naOrdemDeAplicacao).isSorted();
        assertThat(preco(loja, "CT001")).isEqualByComparingTo("1.11");
        assertThat(preco(loja, "CT002")).isEqualByComparingTo("2.22");
        assertThat(contar(loja, "SELECT COUNT(*) FROM Clientes WHERE Id = 'cli-nuvem-2'")).isEqualTo(1);
        amb.esperarConvergencia(60_000);
    }

    @Test
    @Order(8)
    void comandoQueFalhaCincoVezesParaSemTravarOsSeguintes() {
        loja.execute("ALTER TABLE Clientes ADD CONSTRAINT CK_it_falha CHECK (CustomerName <> N'FALHA')");
        long antes = maiorSeq();
        try {
            inserirCliente(nuvem, "cli-falha", "FALHA", "99900011122");
            esperar("comando do cliente", 20_000, () -> maiorSeq() > antes);
            nuvem.update("UPDATE Produtos SET ProductSalePrice = 3.3300 WHERE Id = ?", idProduto("CT002"));
            esperarStatus("Clientes", "cli-falha", "inserir", antes, "parado");
            // o seguinte nao ficou preso atras dele
            esperar("preco aplicado apesar do parado", 30_000, () -> preco(loja, "CT002").compareTo(new BigDecimal("3.33")) == 0);
            Map<String, Object> parado = comando("Clientes", "cli-falha", "inserir", antes);
            assertThat(((Number) parado.get("Tentativas")).intValue()).isEqualTo(5);
            assertThat((String) parado.get("ResultadoJson")).contains("erro");
            assertThat(contar(nuvem, "SELECT COUNT(*) FROM SyncAlertas WHERE Motivo = 'comando-parado' AND CompanyId = ?", EMPRESA)).isEqualTo(1);
            assertThat(contar(loja, "SELECT COUNT(*) FROM Clientes WHERE Id = 'cli-falha'")).isZero();
        } finally {
            loja.execute("ALTER TABLE Clientes DROP CONSTRAINT CK_it_falha");
        }
    }
}
