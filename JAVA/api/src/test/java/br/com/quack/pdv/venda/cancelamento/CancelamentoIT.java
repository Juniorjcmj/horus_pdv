package br.com.quack.pdv.venda.cancelamento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.quack.pdv.BancoIT;
import br.com.quack.pdv.contrato.EstadoDoBanco;
import br.com.quack.pdv.venda.cancelamento.app.CancelamentoVendaService;
import br.com.quack.pdv.venda.cancelamento.app.CancelamentoVendaService.Pedido;
import br.com.quack.pdv.venda.cancelamento.domain.CancelamentoResultado;
import br.com.quack.pdv.venda.registro.VendaRegistroIT;
import br.com.quack.pdv.venda.registro.app.VendaRegistroService;
import br.com.quack.pdv.venda.registro.domain.VendaRegistroResultado;
import br.com.quack.pdv.venda.registro.web.VendaRequests;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Cancelamento de venda (5.2) com SQL Server real: estorno de estoque pelo registrador (tipo devolucao), fiado, lotes,
 * trilha, recusa gravando "negada" + AuditLog, cancelamento simultaneo (uma vez so) e cancelamentos cruzados sem
 * deadlock (travas de produto em ordem de codigo, D13).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
class CancelamentoIT {

    static final String EMPRESA = "empresa-principal";
    static final String SENHA = "Senha#Quack2026";
    private static final ObjectMapper JSON = new ObjectMapper();

    static {
        BancoIT.criarBanco("cancelamento");
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> BancoIT.urlDoBanco("cancelamento"));
        r.add("spring.datasource.username", BancoIT.SQL::getUsername);
        r.add("spring.datasource.password", BancoIT.SQL::getPassword);
        r.add("quack.lote.retentativa.ativa", () -> "false");
    }

    @Autowired VendaRegistroService vendas;
    @Autowired CancelamentoVendaService cancelamento;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @BeforeEach
    void seed() {
        EstadoDoBanco.por(BancoIT.urlDoBanco("cancelamento"), BancoIT.SQL.getUsername(), BancoIT.SQL.getPassword())
                .restaurar();
        jdbc.update("DELETE FROM LoteBaixaPendencias");
    }

    @AfterEach
    void limpar() {
        jdbc.execute("DROP TRIGGER IF EXISTS dbo.trg_falha_estorno_cancelamento");
    }

    // ---------------------------------------------------------------- helpers

    private VendaRegistroResultado vender(List<VendaRequests.Item> itens, String total) {
        return vendas.registrar(EMPRESA, VendaRegistroIT.venda(total, itens, VendaRegistroIT.pag("dinheiro", total.replace(',', '.'))),
                null);
    }

    private VendaRegistroResultado venderFiado(String total, String preco, String qtd) {
        VendaRequests.Venda v = VendaRegistroIT.venda(total, List.of(VendaRegistroIT.item("CT001", qtd, preco)),
                VendaRegistroIT.pag("fiado", total.replace(',', '.')));
        v.paymentType = "fiado";
        v.customerCpf = "111.444.777-35"; // Fabio Folgado (limite 1000)
        return vendas.registrar(EMPRESA, v, null);
    }

    private static Pedido pedido(String saleNumber) {
        return new Pedido(EMPRESA, saleNumber, "usr-ct-gerente", "Gil Gerente", "usr-ct-caixa", "Cai Caixa",
                "Cliente desistiu da compra.", "PDV-01", "127.0.0.1");
    }

    private BigDecimal estoque(String codigo) {
        return jdbc.queryForObject("SELECT ProductQnt FROM Produtos WHERE CompanyId = ? AND ProductCode = ?",
                BigDecimal.class, EMPRESA, codigo);
    }

    private int contar(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private String bearer(String email) throws Exception {
        MvcResult r = mvc.perform(post("/api/Auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("email", email, "password", SENHA)))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return "Bearer " + r.getResponse().getCookie("horuspdv.auth").getValue();
    }

    private MvcResult cancelarPelaApi(String email, String saleNumber, String senha, String justificativa)
            throws Exception {
        return mvc.perform(post("/api/HistoricoVendas/" + saleNumber + "/cancelar")
                .header("Authorization", bearer(email)).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("supervisorId", "usr-ct-gerente", "supervisorPassword", senha,
                        "justificativa", justificativa, "terminal", "PDV-07")))).andReturn();
    }

    // ---------------------------------------------------------------- estoque, fiado e lotes

    @Test
    void estoqueVoltaPeloRegistradorComTipoDevolucaoEOrigemDaVenda() {
        VendaRegistroResultado v = vender(List.of(VendaRegistroIT.item("CT001", "2", "3"),
                VendaRegistroIT.item("CT002", "1", "3")), "9,00");
        assertThat(estoque("CT001")).isEqualByComparingTo("8");

        CancelamentoResultado r = cancelamento.cancelar(pedido(v.saleNumber()));

        assertThat(r.sucesso()).isTrue();
        assertThat(r.itensEstornados()).isEqualTo(2);
        assertThat(r.mensagem()).isEqualTo("Venda cancelada e mercadorias estornadas ao estoque com sucesso.");
        assertThat(estoque("CT001")).isEqualByComparingTo("10");
        assertThat(estoque("CT002")).isEqualByComparingTo("10");
        Map<String, Object> mov = jdbc.queryForMap("""
                SELECT Tipo, Quantidade, OrigemTipo, OrigemId, Motivo, UsuarioId, UsuarioNome
                FROM EstoqueMovimentacoes WHERE Tipo = 'devolucao' AND ProdutoId = N'pr-ct-1'""");
        assertThat(mov).containsEntry("OrigemTipo", "venda").containsEntry("OrigemId", v.saleNumber())
                .containsEntry("Motivo", "Cancelamento da venda #" + v.saleNumber())
                .containsEntry("UsuarioId", "usr-ct-gerente").containsEntry("UsuarioNome", "Gil Gerente");
        assertThat(((Number) mov.get("Quantidade")).intValue()).isEqualTo(2);
        Map<String, Object> venda = jdbc.queryForMap("SELECT Status, CanceladoPorOperadorNome, "
                + "CanceladoPorSupervisorNome, CanceladoJustificativa FROM Vendas WHERE Id = ?", v.vendaId());
        assertThat(venda).containsEntry("Status", "cancelada").containsEntry("CanceladoPorOperadorNome", "Cai Caixa")
                .containsEntry("CanceladoPorSupervisorNome", "Gil Gerente")
                .containsEntry("CanceladoJustificativa", "Cliente desistiu da compra.");
    }

    @Test
    void cancelamentoAutorizadoGravaAuditLogETrilhaComAFotografiaDaVenda() {
        VendaRegistroResultado v = vender(List.of(VendaRegistroIT.item("CT001", "2", "3")), "6,00");

        cancelamento.cancelar(pedido(v.saleNumber()));

        Map<String, Object> trilha = jdbc.queryForMap("SELECT Resultado, Via, SaleNumber, OperadorId, SupervisorId, "
                + "Terminal, Ip, ItensEstornados, FiadoEstornado, TotalVenda, Snapshot FROM VendaCancelamentos "
                + "WHERE VendaId = ?", v.vendaId());
        assertThat(trilha).containsEntry("Resultado", "cancelada").containsEntry("Via", "venda")
                .containsEntry("SaleNumber", v.saleNumber()).containsEntry("OperadorId", "usr-ct-caixa")
                .containsEntry("SupervisorId", "usr-ct-gerente").containsEntry("Terminal", "PDV-01")
                .containsEntry("Ip", "127.0.0.1");
        assertThat(((Number) trilha.get("ItensEstornados")).intValue()).isEqualTo(1);
        assertThat((String) trilha.get("Snapshot")).startsWith("{\"itens\":[{\"codigo\":\"CT001\"")
                .contains("\"pagamentos\":[{\"forma\":\"dinheiro\"").contains("\"notas\":[]");
        Map<String, Object> audit = jdbc.queryForMap("SELECT UserId, UserName, EntityType, EntityId, Description, Ip "
                + "FROM AuditLog WHERE EventType = 'VendaCancelada'");
        assertThat(audit).containsEntry("UserId", "usr-ct-caixa").containsEntry("EntityType", "Vendas")
                .containsEntry("EntityId", v.vendaId()).containsEntry("Ip", "127.0.0.1");
        assertThat((String) audit.get("Description")).startsWith("Venda #" + v.saleNumber() + " cancelada. Total: R$ "
                + "6,00. Gerente: Gil Gerente (usr-ct-gerente). Motivo: Cliente desistiu da compra. ")
                .contains("Itens: ").endsWith("x Produto Contrato 1");
    }

    @Test
    void fiadoEDevolvidoEOMovimentoDeEstornoGravado() {
        VendaRegistroResultado v = venderFiado("6,00", "3", "2");
        assertThat(jdbc.queryForObject("SELECT SaldoDevedor FROM Clientes WHERE Id = N'cl-ct-folgado'",
                BigDecimal.class)).isEqualByComparingTo("6");

        CancelamentoResultado r = cancelamento.cancelar(pedido(v.saleNumber()));

        assertThat(r.sucesso()).isTrue();
        assertThat(r.fiadoEstornado()).isEqualByComparingTo("6");
        assertThat(jdbc.queryForObject("SELECT SaldoDevedor FROM Clientes WHERE Id = N'cl-ct-folgado'",
                BigDecimal.class)).isEqualByComparingTo("0");
        Map<String, Object> mov = jdbc.queryForMap("SELECT Valor, SaldoAnterior, SaldoAtual, OperadorNome, "
                + "FormaPagamento FROM FiadoMovimentos WHERE VendaId = ? AND Tipo = 2", v.vendaId());
        assertThat(mov).containsEntry("OperadorNome", "Cai Caixa").containsEntry("FormaPagamento", "Estorno");
        assertThat((BigDecimal) mov.get("SaldoAnterior")).isEqualByComparingTo("6");
        assertThat((BigDecimal) mov.get("SaldoAtual")).isEqualByComparingTo("0");
    }

    @Test
    void fiadoPagoEmParteRecusaComAMensagemDoFinanceiroENaoMudaNada() {
        VendaRegistroResultado v = venderFiado("6,00", "3", "2");
        jdbc.update("UPDATE Clientes SET SaldoDevedor = 2 WHERE Id = N'cl-ct-folgado'");

        CancelamentoResultado r = cancelamento.cancelar(pedido(v.saleNumber()));

        assertThat(r.sucesso()).isFalse();
        assertThat(r.mensagem()).isEqualTo("O cliente já pagou parte deste fiado: o saldo devedor é menor que o "
                + "valor da venda. Faça o estorno pelo financeiro.");
        assertThat(r.codigo()).isEqualTo("ERRO");
        assertThat(jdbc.queryForObject("SELECT Status FROM Vendas WHERE Id = ?", String.class, v.vendaId()))
                .isEqualTo("finalizada");
        assertThat(estoque("CT001")).isEqualByComparingTo("8");
        assertThat(contar("SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE Tipo = 'devolucao'")).isZero();
        assertThat(contar("SELECT COUNT(*) FROM VendaCancelamentos WHERE VendaId = ?", v.vendaId())).isZero();
        assertThat(contar("SELECT COUNT(*) FROM FiadoMovimentos WHERE VendaId = ? AND Tipo = 2", v.vendaId()))
                .isZero();
    }

    @Test
    void lotesDeOrigemRecebemDeVoltaOQueAVendaConsumiu() {
        Map<String, Object> p = jdbc.queryForMap(
                "SELECT ProductCode, ProductSalePrice, ProductUnitPrice FROM Produtos WHERE Id = N'pr-ct-val'");
        String codigo = (String) p.get("ProductCode");
        BigDecimal preco = ((BigDecimal) p.get("ProductSalePrice")).signum() > 0 ? (BigDecimal) p.get("ProductSalePrice")
                : (BigDecimal) p.get("ProductUnitPrice");
        String total = preco.multiply(new BigDecimal("5")).setScale(2).toPlainString().replace('.', ',');
        VendaRegistroResultado v = vender(List.of(VendaRegistroIT.item(codigo, "5", preco.toPlainString())), total);
        assertThat(saldoLote("lote-ct-vencido")).isEqualByComparingTo("0");
        assertThat(saldoLote("lote-ct-alerta")).isEqualByComparingTo("2");

        cancelamento.cancelar(pedido(v.saleNumber()));

        assertThat(saldoLote("lote-ct-vencido")).isEqualByComparingTo("3");
        assertThat(saldoLote("lote-ct-alerta")).isEqualByComparingTo("4");
        assertThat(contar("SELECT COUNT(*) FROM LoteConsumos WHERE RefId = ? AND Estornado = 0", v.vendaId()))
                .isZero();
    }

    @Test
    void falhaNoEstornoDeLotesNaoDesfazOCancelamentoEViraPendenciaDeNovaTentativa() {
        Map<String, Object> p = jdbc.queryForMap(
                "SELECT ProductCode, ProductSalePrice, ProductUnitPrice FROM Produtos WHERE Id = N'pr-ct-val'");
        BigDecimal preco = ((BigDecimal) p.get("ProductSalePrice")).signum() > 0 ? (BigDecimal) p.get("ProductSalePrice")
                : (BigDecimal) p.get("ProductUnitPrice");
        String total = preco.multiply(new BigDecimal("2")).setScale(2).toPlainString().replace('.', ',');
        VendaRegistroResultado v = vender(List.of(VendaRegistroIT.item((String) p.get("ProductCode"), "2",
                preco.toPlainString())), total);
        jdbc.execute("CREATE TRIGGER dbo.trg_falha_estorno_cancelamento ON dbo.LoteConsumos AFTER UPDATE AS"
                + " THROW 50002, N'falha simulada no estorno', 1;");

        CancelamentoResultado r = cancelamento.cancelar(pedido(v.saleNumber()));

        assertThat(r.sucesso()).isTrue();
        assertThat(jdbc.queryForObject("SELECT Status FROM Vendas WHERE Id = ?", String.class, v.vendaId()))
                .isEqualTo("cancelada");
        assertThat(jdbc.queryForObject("SELECT Estado FROM LoteBaixaPendencias WHERE VendaId = ? AND Tipo = 'estorno'",
                String.class, v.vendaId())).isEqualTo("pendente");
    }

    private BigDecimal saldoLote(String id) {
        return jdbc.queryForObject("SELECT QtdAtual FROM ProdutoLotes WHERE Id = ?", BigDecimal.class, id);
    }

    // ---------------------------------------------------------------- concorrencia

    @Test
    void doisCancelamentosSimultaneosDaMesmaVendaEstornamUmaVezSo() throws Exception {
        VendaRegistroResultado v = vender(List.of(VendaRegistroIT.item("CT001", "2", "3")), "6,00");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch largada = new CountDownLatch(1);
        try {
            Callable<CancelamentoResultado> tarefa = () -> {
                largada.await();
                return cancelamento.cancelar(pedido(v.saleNumber()));
            };
            Future<CancelamentoResultado> a = pool.submit(tarefa);
            Future<CancelamentoResultado> b = pool.submit(tarefa);
            largada.countDown();
            List<CancelamentoResultado> rs = List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS));

            assertThat(rs.stream().filter(CancelamentoResultado::sucesso)).hasSize(1);
            assertThat(rs.stream().filter(r -> !r.sucesso()).map(CancelamentoResultado::codigo))
                    .containsExactly("JA_CANCELADA");
        } finally {
            pool.shutdownNow();
        }
        assertThat(estoque("CT001")).isEqualByComparingTo("10");
        assertThat(contar("SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE Tipo = 'devolucao' AND OrigemId = ?",
                v.saleNumber())).isEqualTo(1);
        assertThat(contar("SELECT COUNT(*) FROM VendaCancelamentos WHERE VendaId = ? AND Resultado = 'cancelada'",
                v.vendaId())).isEqualTo(1);
    }

    @Test
    void cancelamentosCruzadosComOsMesmosProdutosEmOrdemInversaNaoDaoDeadlock() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int rodada = 0; rodada < 12; rodada++) {
                VendaRegistroResultado ab = vender(List.of(VendaRegistroIT.item("CT001", "1", "3"),
                        VendaRegistroIT.item("CT002", "1", "3")), "6,00");
                VendaRegistroResultado ba = vender(List.of(VendaRegistroIT.item("CT002", "1", "3"),
                        VendaRegistroIT.item("CT001", "1", "3")), "6,00");
                CountDownLatch largada = new CountDownLatch(1);
                Future<CancelamentoResultado> fa = pool.submit(() -> {
                    largada.await();
                    return cancelamento.cancelar(pedido(ab.saleNumber()));
                });
                Future<CancelamentoResultado> fb = pool.submit(() -> {
                    largada.await();
                    return cancelamento.cancelar(pedido(ba.saleNumber()));
                });
                largada.countDown();

                CancelamentoResultado ra = fa.get(60, TimeUnit.SECONDS);
                CancelamentoResultado rb = fb.get(60, TimeUnit.SECONDS);
                assertThat(ra.sucesso()).as("rodada %d: %s", rodada, ra.mensagem()).isTrue();
                assertThat(rb.sucesso()).as("rodada %d: %s", rodada, rb.mensagem()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(estoque("CT001")).isEqualByComparingTo("10");
        assertThat(estoque("CT002")).isEqualByComparingTo("10");
    }

    // ---------------------------------------------------------------- recusas pela API

    @Test
    void senhaErradaRecusaComForbiddenEGravaNegadaNaTrilhaEOAuditLog() throws Exception {
        VendaRegistroResultado v = vender(List.of(VendaRegistroIT.item("CT001", "2", "3")), "6,00");

        MvcResult r = cancelarPelaApi("caixa@contrato.quack", v.saleNumber(), "errada", "Cliente desistiu da compra.");

        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(r.getResponse().getContentAsString()).contains("Senha do gerente incorreta.");
        assertThat(jdbc.queryForObject("SELECT Status FROM Vendas WHERE Id = ?", String.class, v.vendaId()))
                .isEqualTo("finalizada");
        assertThat(estoque("CT001")).isEqualByComparingTo("8");
        Map<String, Object> trilha = jdbc.queryForMap("SELECT Resultado, MotivoRecusa, SupervisorId, SupervisorNome, "
                + "Terminal, TotalVenda, Snapshot FROM VendaCancelamentos WHERE VendaId = ?", v.vendaId());
        assertThat(trilha).containsEntry("Resultado", "negada").containsEntry("MotivoRecusa",
                "Senha do gerente incorreta.").containsEntry("SupervisorId", "usr-ct-gerente")
                .containsEntry("SupervisorNome", "Gil Gerente").containsEntry("Terminal", "PDV-07");
        assertThat((String) trilha.get("Snapshot")).contains("\"codigo\":\"CT001\"");
        Map<String, Object> audit = jdbc.queryForMap("SELECT UserId, EntityId, Description FROM AuditLog "
                + "WHERE EventType = 'CancelamentoVendaNegado'");
        assertThat(audit).containsEntry("UserId", "usr-ct-caixa").containsEntry("EntityId", v.vendaId());
        assertThat((String) audit.get("Description")).isEqualTo("Cancelamento da venda #" + v.saleNumber()
                + " negado: Senha do gerente incorreta. Gerente informado: Gil Gerente. Terminal: PDV-07.");
    }

    @Test
    void justificativaCurtaEVendaInexistenteTambemEntramNaTrilhaComoNegadas() throws Exception {
        VendaRegistroResultado v = vender(List.of(VendaRegistroIT.item("CT001", "2", "3")), "6,00");

        MvcResult curta = cancelarPelaApi("caixa@contrato.quack", v.saleNumber(), SENHA, "abc");
        MvcResult inexistente = cancelarPelaApi("caixa@contrato.quack", "99999", SENHA, "Cliente desistiu.");

        assertThat(curta.getResponse().getStatus()).isEqualTo(400);
        assertThat(inexistente.getResponse().getStatus()).isEqualTo(404);
        assertThat(inexistente.getResponse().getContentAsString()).contains("\"codigo\":\"NAO_ENCONTRADA\"");
        assertThat(contar("SELECT COUNT(*) FROM VendaCancelamentos WHERE Resultado = 'negada' AND SaleNumber = ?",
                v.saleNumber())).isEqualTo(1);
        assertThat(contar("SELECT COUNT(*) FROM VendaCancelamentos WHERE Resultado = 'negada' AND SaleNumber = '99999'"
                + " AND VendaId IS NULL AND Snapshot IS NULL")).isEqualTo(1);
        assertThat(contar("SELECT COUNT(*) FROM AuditLog WHERE EventType = 'CancelamentoVendaNegado'")).isEqualTo(2);
    }

    @Test
    void notaAutorizadaRecusaComConflitoEGravaNegadaComAsNotasNaFotografia() throws Exception {
        MvcResult r = cancelarPelaApi("caixa@contrato.quack", "15042", SENHA, "Cliente desistiu da compra.");

        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        mvc.perform(post("/api/HistoricoVendas/15042/cancelar").header("Authorization", bearer("caixa@contrato.quack"))
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("supervisorId",
                        "usr-ct-gerente", "supervisorPassword", SENHA, "justificativa", "Cliente desistiu da compra."))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.data.codigo").value("NOTA_AUTORIZADA"))
                .andExpect(jsonPath("$.data.documentoFiscalId").value("df-ct-nota"))
                .andExpect(jsonPath("$.data.documentoModelo").value(65));
        String snapshot = jdbc.queryForObject("SELECT TOP 1 Snapshot FROM VendaCancelamentos WHERE SaleNumber = "
                + "'15042' AND Resultado = 'negada'", String.class);
        assertThat(snapshot).contains("\"notas\":[{\"id\":\"df-ct-nota\",\"modelo\":65");
        assertThat(contar("SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE Tipo = 'devolucao'")).isZero();
    }
}
