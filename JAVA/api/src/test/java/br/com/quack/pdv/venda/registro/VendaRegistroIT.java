package br.com.quack.pdv.venda.registro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.quack.pdv.BancoIT;
import br.com.quack.pdv.contrato.EstadoDoBanco;
import br.com.quack.pdv.shared.NegocioException;
import br.com.quack.pdv.venda.registro.app.VendaRegistroService;
import br.com.quack.pdv.venda.registro.domain.VendaRegistroResultado;
import br.com.quack.pdv.venda.registro.web.VendaRequests;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
public class VendaRegistroIT {

    static final String EMPRESA = "empresa-principal";
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    static {
        BancoIT.criarBanco("venda");
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> BancoIT.urlDoBanco("venda"));
        r.add("spring.datasource.username", BancoIT.SQL::getUsername);
        r.add("spring.datasource.password", BancoIT.SQL::getPassword);
    }

    @Autowired VendaRegistroService vendas;
    @Autowired br.com.quack.pdv.venda.registro.app.VendaConsultaService consulta;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        EstadoDoBanco.por(BancoIT.urlDoBanco("venda"), BancoIT.SQL.getUsername(), BancoIT.SQL.getPassword())
                .restaurar();
    }

    // ---------------------------------------------------------------- helpers

    public static VendaRequests.Item item(String codigo, String qtd, String preco) {
        VendaRequests.Item i = new VendaRequests.Item();
        i.productCode = codigo;
        i.productName = "";
        i.quantity = new BigDecimal(qtd);
        i.setUnitPrice(new BigDecimal(preco));
        return i;
    }

    public static VendaRequests.Pagamento pag(String tipo, String valor) {
        VendaRequests.Pagamento p = new VendaRequests.Pagamento();
        p.paymentType = tipo;
        p.amount = new BigDecimal(valor);
        return p;
    }

    public static VendaRequests.Venda venda(String total, List<VendaRequests.Item> itens, VendaRequests.Pagamento... pags) {
        VendaRequests.Venda v = new VendaRequests.Venda();
        v.customerName = "Consumidor";
        v.paymentType = "dinheiro";
        v.totalAmount = total;
        v.operatorName = "Cai Caixa";
        v.items = new ArrayList<>(itens);
        v.payments = new ArrayList<>(List.of(pags));
        return v;
    }

    private BigDecimal estoque(String codigo) {
        return jdbc.queryForObject("SELECT ProductQnt FROM Produtos WHERE CompanyId = ? AND ProductCode = ?",
                BigDecimal.class, EMPRESA, codigo);
    }

    private int contar(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    // ---------------------------------------------------------------- registro

    @Test
    void vendaBaixaEstoqueEGravaItensPagamentosEMovimentacaoComOrigemVendaNumero() {
        VendaRegistroResultado r = vendas.registrar(EMPRESA,
                venda("6,00", List.of(item("CT001", "2", "3")), pag("dinheiro", "6")), null);

        assertThat(r.saleNumber()).isEqualTo("15043");
        assertThat(r.vendaId()).isEqualTo("sale-empresa-principal-15043");
        assertThat(estoque("CT001")).isEqualByComparingTo("8");
        assertThat(contar("SELECT COUNT(*) FROM VendaItens WHERE VendaId = ?", r.vendaId())).isEqualTo(1);
        assertThat(contar("SELECT COUNT(*) FROM VendaPagamentos WHERE VendaId = ?", r.vendaId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT ItemTotal FROM VendaItens WHERE VendaId = ?", BigDecimal.class,
                r.vendaId())).isEqualByComparingTo("6.00");
        String motivo = jdbc.queryForObject("SELECT Motivo FROM EstoqueMovimentacoes WHERE OrigemTipo = 'venda' "
                + "AND OrigemId = ?", String.class, "15043");
        assertThat(motivo).isEqualTo("Venda #15043");
        assertThat(contar("SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE OrigemId LIKE 'pend-%'")).isZero();
    }

    @Test
    void vendaRecusadaNaoDeixaNada() {
        int vendasAntes = contar("SELECT COUNT(*) FROM Vendas");
        int movAntes = contar("SELECT COUNT(*) FROM EstoqueMovimentacoes");

        assertThatThrownBy(() -> vendas.registrar(EMPRESA,
                venda("5,00", List.of(item("CT001", "2", "3")), pag("dinheiro", "5")), null))
                .isInstanceOf(NegocioException.class)
                .hasMessage("Soma dos itens líquidos difere do total da venda.");
        // CT001 e baixado antes de CT002 faltar: o rollback tem de desfazer a baixa de CT001
        assertThatThrownBy(() -> vendas.registrar(EMPRESA,
                venda("102,00", List.of(item("CT001", "1", "3"), item("CT002", "33", "3")), pag("dinheiro", "102")),
                null)).isInstanceOf(NegocioException.class).hasMessageContaining("Estoque insuficiente");

        assertThat(contar("SELECT COUNT(*) FROM Vendas")).isEqualTo(vendasAntes);
        assertThat(contar("SELECT COUNT(*) FROM EstoqueMovimentacoes")).isEqualTo(movAntes);
        assertThat(estoque("CT001")).isEqualByComparingTo("10");
        assertThat(estoque("CT002")).isEqualByComparingTo("10");
    }

    @Test
    void replayNaoBaixaDeNovoEConteudoDiferenteDa409() {
        VendaRequests.Venda v = venda("6,00", List.of(item("CT001", "2", "3")), pag("dinheiro", "6"));
        v.eventId = "ev-it-1";
        v.clientSaleId = "cs-it-1";
        int antes = contar("SELECT COUNT(*) FROM Vendas WHERE CompanyId = ?", EMPRESA);
        VendaRegistroResultado primeira = vendas.registrar(EMPRESA, v, null);
        VendaRegistroResultado segunda = vendas.registrar(EMPRESA, v, null);

        assertThat(primeira.isReplay()).isFalse();
        assertThat(segunda.isReplay()).isTrue();
        assertThat(segunda.saleNumber()).isEqualTo(primeira.saleNumber());
        assertThat(segunda.rows()).isEqualTo(primeira.rows());
        assertThat(estoque("CT001")).isEqualByComparingTo("8");
        assertThat(contar("SELECT COUNT(*) FROM Vendas WHERE CompanyId = ?", EMPRESA)).isEqualTo(antes + 1);

        VendaRequests.Venda outra = venda("3,00", List.of(item("CT001", "1", "3")), pag("dinheiro", "3"));
        outra.eventId = "ev-it-1";
        assertThatThrownBy(() -> vendas.registrar(EMPRESA, outra, null)).isInstanceOfSatisfying(
                NegocioException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("Conflito de idempotência: o EventId 'ev-it-1' já foi "
                            + "processado anteriormente com um payload diferente.");
                });
    }

    @Test
    void responsePayloadGravadoPelaDotNetEhLidoEDevolvido() throws Exception {
        VendaRequests.Venda r = JSON.readValue(VendaPayloadTest.fixture("venda-dotnet-request.json"),
                VendaRequests.Venda.class);
        jdbc.update("INSERT INTO ProcessedEvents (Id, CompanyId, EventId, EventType, ClientSaleId, PayloadHash, "
                        + "ProcessedAt, ResponsePayload) VALUES ('pe-dotnet', ?, ?, 'SALE_CREATED', 'cs-fix-1', ?, "
                        + "SYSDATETIMEOFFSET(), ?)", EMPRESA, "ev-fix-1", VendaPayloadTest.HASH_DOTNET,
                VendaPayloadTest.fixture("response-payload-venda-dotnet.json"));

        VendaRegistroResultado devolvido = vendas.registrar(EMPRESA, r, null);

        assertThat(devolvido.isReplay()).isTrue();
        assertThat(devolvido.saleNumber()).isEqualTo("15045");
        assertThat(devolvido.rows().getFirst().productName()).isEqualTo("Pão de Açúcar");
        assertThat(devolvido.fiadoClienteId()).isEqualTo("cl-ct-folgado");
        assertThat(estoque("CT001")).isEqualByComparingTo("10");
        assertThat(contar("SELECT COUNT(*) FROM Vendas WHERE SaleNumber = '15045'")).isZero();
    }

    @Test
    void responsePayloadGravadoPelaJavaTemOFormatoDaDotNet() throws Exception {
        VendaRequests.Venda v = venda("6,00", List.of(item("CT001", "2", "3")), pag("dinheiro", "6"));
        v.eventId = "ev-it-fmt";
        vendas.registrar(EMPRESA, v, null);

        String json = jdbc.queryForObject("SELECT ResponsePayload FROM ProcessedEvents WHERE EventId = 'ev-it-fmt'",
                String.class);
        JsonNode no = JSON.readTree(json);
        assertThat(no.get("saleNumber").asText()).isEqualTo("15043");
        assertThat(no.get("isReplay").asBoolean()).isFalse();
        assertThat(no.get("rows").get(0).get("unitPrice").asText()).isEqualTo("3,00");
        assertThat(no.get("payments").get(0).get("createdAt").asText()).matches(
                "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,7})?-03:00");
        assertThat(no.get("fiadoSaldoAtual").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT PayloadHash FROM ProcessedEvents WHERE EventId = 'ev-it-fmt'",
                String.class)).hasSize(64);
    }

    @Test
    void ruptura_vendaOfflineAceitaComSaldoNegativoEAviso() {
        VendaRequests.Venda v = venda("36,00", List.of(item("CT001", "12", "3")), pag("dinheiro", "36"));
        v.eventId = "ev-rup";

        VendaRegistroResultado r = vendas.registrar(EMPRESA, v, null);

        assertThat(estoque("CT001")).isEqualByComparingTo("-2");
        assertThat(r.warnings()).hasSize(1);
        assertThat(r.warnings().getFirst().saldoResultante()).isEqualByComparingTo("-2");
        assertThat(contar("SELECT COUNT(*) FROM AuditLog WHERE EventType = 'EstoqueRuptura'")).isEqualTo(1);
    }

    @Test
    void fiado_debitaOSaldoEGravaAuditoriaERecusaAcimaDoLimite() {
        VendaRequests.Venda v = venda("6,00", List.of(item("CT001", "2", "3")), pag("fiado", "6"));
        v.customerCpf = "111.444.777-35";
        VendaRegistroResultado r = vendas.registrar(EMPRESA, v, null);

        assertThat(r.fiadoClienteId()).isEqualTo("cl-ct-folgado");
        assertThat(r.fiadoSaldoAtual()).isEqualByComparingTo("6");
        assertThat(jdbc.queryForObject("SELECT SaldoDevedor FROM Clientes WHERE Id = 'cl-ct-folgado'",
                BigDecimal.class)).isEqualByComparingTo("6");
        assertThat(contar("SELECT COUNT(*) FROM FiadoMovimentos WHERE VendaId = ?", r.vendaId())).isEqualTo(1);
        assertThat(contar("SELECT COUNT(*) FROM AuditLog WHERE EventType = 'FiadoDebito'")).isEqualTo(1);

        VendaRequests.Venda estoura = venda("6,00", List.of(item("CT001", "2", "3")), pag("fiado", "6"));
        estoura.customerCpf = "52998224725";
        assertThatThrownBy(() -> vendas.registrar(EMPRESA, estoura, null)).hasMessageStartingWith("Crédito insuficiente");
        assertThat(estoque("CT001")).isEqualByComparingTo("8");
    }

    @Test
    void precoAbaixoSemAutorizacaoERecusadoEPedidoExistenteReproduzOBugDaDotNet() {
        assertThatThrownBy(() -> vendas.registrar(EMPRESA,
                venda("2,00", List.of(item("CT001", "1", "2")), pag("dinheiro", "2")), null))
                .hasMessageContaining("sem autorização de gerente");

        VendaRequests.Venda comPedido = venda("9,00", List.of(item("CT001", "2", "3"), item("CT002", "1", "3")),
                pag("dinheiro", "9"));
        comPedido.orderNumber = "1";
        assertThatThrownBy(() -> vendas.registrar(EMPRESA, comPedido, null)).isInstanceOf(IllegalStateException.class);
        assertThat(contar("SELECT Status FROM Pedidos WHERE OrderNumber = '1'")).isZero();
        assertThat(estoque("CT001")).isEqualByComparingTo("10");
    }

    // ---------------------------------------------------------------- D13: vendas cruzadas

    @Test
    void vendasCruzadasEmParaleloNaoDaoDeadlockESaldosFicamExatos() throws Exception {
        jdbc.update("UPDATE Produtos SET ProductQnt = 1000 WHERE CompanyId = ? AND ProductCode IN ('CT001','CT002')",
                EMPRESA);
        int rodadas = 25;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<VendaRegistroResultado>> futuros = new ArrayList<>();
        try {
            for (int r = 0; r < rodadas; r++) {
                CountDownLatch largada = new CountDownLatch(1);
                Callable<VendaRegistroResultado> ab = () -> {
                    largada.await();
                    return vendas.registrar(EMPRESA, venda("6,00",
                            List.of(item("CT001", "1", "3"), item("CT002", "1", "3")), pag("dinheiro", "6")), null);
                };
                Callable<VendaRegistroResultado> ba = () -> {
                    largada.await();
                    return vendas.registrar(EMPRESA, venda("6,00",
                            List.of(item("CT002", "1", "3"), item("CT001", "1", "3")), pag("dinheiro", "6")), null);
                };
                Future<VendaRegistroResultado> f1 = pool.submit(ab);
                Future<VendaRegistroResultado> f2 = pool.submit(ba);
                largada.countDown();
                futuros.add(f1);
                futuros.add(f2);
                f1.get();
                f2.get();
            }
        } finally {
            pool.shutdownNow();
        }

        Set<String> numeros = new HashSet<>();
        for (Future<VendaRegistroResultado> f : futuros) numeros.add(f.get().saleNumber());
        assertThat(numeros).hasSize(rodadas * 2);
        assertThat(estoque("CT001")).isEqualByComparingTo(String.valueOf(1000 - rodadas * 2));
        assertThat(estoque("CT002")).isEqualByComparingTo(String.valueOf(1000 - rodadas * 2));
        assertThat(contar("SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE OrigemTipo = 'venda' AND OrigemId LIKE 'pend-%'"))
                .isZero();
    }

    // ---------------------------------------------------------------- 3.3: lista por turno

    @Test
    void listaPorTurnoSoMostraOProprioTurnoSalvoGerenteEAdministrador() {
        var caixa = new br.com.quack.pdv.security.UsuarioAutenticado("usr-ct-caixa", EMPRESA, "s", "Cai Caixa", "c@x",
                "caixa", List.of());
        var gerente = new br.com.quack.pdv.security.UsuarioAutenticado("usr-ct-gerente", EMPRESA, "s", "Gil Gerente",
                "g@x", "gerente", List.of());

        // o turno cx-ct-aberto e do gerente e guarda as 3 vendas do seed
        assertThat(consulta.listar(gerente, null, "cx-ct-aberto")).extracting(l -> l.saleNumber())
                .containsExactlyInAnyOrder("15040", "15041", "15042");
        assertThatThrownBy(() -> consulta.listar(caixa, null, "cx-ct-aberto")).isInstanceOfSatisfying(
                NegocioException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getMessage()).isEqualTo("Turno de caixa não encontrado ou pertencente a outro operador.");
                });
        // turno do caixa: so as vendas ligadas a ele
        jdbc.update("INSERT INTO CaixaSessoes (Id, CompanyId, OpenedAt, OperatorId, OperatorName, OpeningAmount, "
                + "ClosingAmount, ClosedById, ClosedByName, Note) VALUES ('cx-it', ?, SYSDATETIMEOFFSET(), "
                + "'usr-ct-caixa', 'Cai Caixa', 10, 0, '', '', '')", EMPRESA);
        VendaRegistroResultado r = vendas.registrar(EMPRESA,
                venda("3,00", List.of(item("CT001", "1", "3")), pag("dinheiro", "3")), "cx-it");
        assertThat(consulta.listar(caixa, null, "cx-it")).extracting(l -> l.saleNumber())
                .containsExactly(r.saleNumber());
        assertThat(consulta.listar(gerente, null, "cx-it")).hasSize(1);
        assertThat(consulta.listar(caixa, "2027-01-01", null)).isEmpty();
    }
}
