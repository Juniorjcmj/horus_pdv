package br.com.quack.pdv.venda.pedido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import br.com.quack.pdv.BancoIT;
import br.com.quack.pdv.contrato.EstadoDoBanco;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
 * Pedidos com SQL Server real (6.1). A finalizacao roda com o erro de status da .NET desligado
 * ({@code quack.vendas.pedido.reproduzir-erro-dotnet=false}); com o padrao (ligado) ela responde 500 como a .NET, o que
 * o grupo "pedido" do contrato cobre.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
class PedidoIT {

    static final String SENHA = "Senha#Quack2026";
    private static final ObjectMapper JSON = new ObjectMapper();

    static {
        BancoIT.criarBanco("pedido");
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> BancoIT.urlDoBanco("pedido"));
        r.add("spring.datasource.username", BancoIT.SQL::getUsername);
        r.add("spring.datasource.password", BancoIT.SQL::getPassword);
        r.add("quack.lote.retentativa.ativa", () -> "false");
        r.add("quack.fiscal.fila-minima.ativa", () -> "false");
        r.add("quack.vendas.pedido.reproduzir-erro-dotnet", () -> "false");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        EstadoDoBanco.por(BancoIT.urlDoBanco("pedido"), BancoIT.SQL.getUsername(), BancoIT.SQL.getPassword())
                .restaurar();
    }

    private String bearer(String email) throws Exception {
        MvcResult r = mvc.perform(post("/api/Auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("email", email, "password", SENHA)))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return "Bearer " + r.getResponse().getCookie("horuspdv.auth").getValue();
    }

    private JsonNode chamar(String metodo, String caminho, String email, String corpo, int statusEsperado)
            throws Exception {
        var b = metodo.equals("GET") ? get(caminho) : post(caminho);
        b.header("Authorization", bearer(email));
        if (corpo != null) b.contentType(MediaType.APPLICATION_JSON).content(corpo);
        MvcResult r = mvc.perform(b).andReturn();
        String texto = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(texto).isEqualTo(statusEsperado);
        return JSON.readTree(texto);
    }

    private void abrirCaixa() throws Exception {
        chamar("POST", "/api/Caixa/abrir", "caixa@contrato.quack", "{\"openingAmount\":\"100,00\"}", 200);
    }

    private String criarPedido(String corpo) throws Exception {
        return chamar("POST", "/api/Pedido", "atendente@contrato.quack", corpo, 201).path("data").path("orderNumber")
                .asText();
    }

    private BigDecimal estoque(String codigo) {
        return jdbc.queryForObject("SELECT ProductQnt FROM Produtos WHERE CompanyId = N'empresa-principal' "
                + "AND ProductCode = ?", BigDecimal.class, codigo);
    }

    private int contar(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static final String DOIS_ITENS = "{\"items\":[{\"productCode\":\"CT001\",\"quantity\":3},"
            + "{\"productCode\":\"CT002\",\"quantity\":1}]}";

    @Test
    void criarPedidoNaoMudaEstoqueNemGeraMovimentacao() throws Exception {
        String numero = criarPedido(DOIS_ITENS);

        assertThat(numero).isEqualTo("2");
        assertThat(estoque("CT001")).isEqualByComparingTo("10");
        assertThat(estoque("CT002")).isEqualByComparingTo("10");
        assertThat(contar("SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE Tipo = 'saida_venda'")).isZero();
        assertThat(contar("SELECT COUNT(*) FROM PedidoItens WHERE PedidoId LIKE N'ped-%' AND ProductCode = N'CT001' "
                + "AND Quantity = 3")).isEqualTo(1);
    }

    @Test
    void finalizarBaixaOEstoqueECobraOPrecoCongeladoDoPedido() throws Exception {
        String numero = criarPedido(DOIS_ITENS); // 3 x 3,00 + 1 x 3,00 = 12,00
        jdbc.update("UPDATE Produtos SET ProductSalePrice = 5 WHERE CompanyId = N'empresa-principal'");
        abrirCaixa();

        JsonNode r = chamar("POST", "/api/Pedido/" + numero + "/finalizar", "caixa@contrato.quack",
                "{\"paymentType\":\"dinheiro\",\"payments\":[{\"paymentType\":\"dinheiro\",\"amount\":12}]}", 200);

        assertThat(r.path("message").asText()).isEqualTo("Pedido finalizado com sucesso.");
        String saleNumber = r.path("data").path("saleNumber").asText();
        assertThat(r.path("data").path("rows").size()).isEqualTo(2);
        assertThat(r.path("data").path("rows").get(0).path("unitPrice").asText()).isEqualTo("3,00");
        assertThat(estoque("CT001")).isEqualByComparingTo("7");
        assertThat(estoque("CT002")).isEqualByComparingTo("9");
        Map<String, Object> venda = jdbc.queryForMap("SELECT TotalAmount, CaixaSessaoId, CustomerName FROM Vendas "
                + "WHERE SaleNumber = ?", saleNumber);
        assertThat((BigDecimal) venda.get("TotalAmount")).isEqualByComparingTo("12");
        assertThat((String) venda.get("CaixaSessaoId")).startsWith("cx-");
        assertThat(contar("SELECT COUNT(*) FROM VendaItens WHERE VendaId = ? AND UnitPrice = 3", "sale-empresa-principal-"
                + saleNumber)).isEqualTo(2);
        Map<String, Object> pedido = jdbc.queryForMap("SELECT Status, VendaId FROM Pedidos WHERE OrderNumber = ?",
                numero);
        assertThat(((Number) pedido.get("Status")).intValue()).isEqualTo(1);
        assertThat(pedido.get("VendaId")).isEqualTo("sale-empresa-principal-" + saleNumber);
        assertThat(contar("SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE OrigemId = ? AND OrigemTipo = 'venda'",
                saleNumber)).isEqualTo(2);
    }

    @Test
    void segundaFinalizacaoDevolveAMesmaVendaSemBaixarDeNovo() throws Exception {
        String numero = criarPedido(DOIS_ITENS);
        abrirCaixa();
        String corpo = "{\"paymentType\":\"dinheiro\",\"payments\":[{\"paymentType\":\"dinheiro\",\"amount\":12}]}";
        JsonNode primeira = chamar("POST", "/api/Pedido/" + numero + "/finalizar", "caixa@contrato.quack", corpo, 200);

        JsonNode segunda = chamar("POST", "/api/Pedido/" + numero + "/finalizar", "caixa@contrato.quack", corpo, 200);

        assertThat(segunda.path("data").path("saleNumber").asText())
                .isEqualTo(primeira.path("data").path("saleNumber").asText());
        assertThat(segunda.path("data").path("fiscalQueued").asBoolean()).isFalse();
        assertThat(estoque("CT001")).isEqualByComparingTo("7");
        assertThat(contar("SELECT COUNT(*) FROM Vendas WHERE OperatorName = N'Cai Caixa'")).isEqualTo(1);
    }

    @Test
    void finalizacaoDecideANfceComoUmaVendaComumEEnfileiraSoUmaVez() throws Exception {
        String numero = criarPedido(DOIS_ITENS);
        abrirCaixa();
        String corpo = "{\"paymentType\":\"pix\",\"payments\":[{\"paymentType\":\"pix\",\"amount\":12}]}";

        JsonNode primeira = chamar("POST", "/api/Pedido/" + numero + "/finalizar", "caixa@contrato.quack", corpo, 200);
        JsonNode segunda = chamar("POST", "/api/Pedido/" + numero + "/finalizar", "caixa@contrato.quack", corpo, 200);

        assertThat(primeira.path("data").path("emitirFiscal").asBoolean()).isTrue();
        assertThat(primeira.path("data").path("fiscalQueued").asBoolean()).isTrue();
        assertThat(segunda.path("data").path("emitirFiscal").asBoolean()).isTrue();
        assertThat(segunda.path("data").path("fiscalQueued").asBoolean()).isFalse();
        assertThat(contar("SELECT COUNT(*) FROM DocumentosFiscais WHERE VendaId LIKE N'sale-empresa-principal-%'"
                + " AND Id <> N'df-ct-nota'")).isEqualTo(1);
    }

    @Test
    void finalizarComFiadoDebitaOClienteDoPedido() throws Exception {
        String numero = criarPedido("{\"customerName\":\"x\",\"customerCpf\":\"111.444.777-35\","
                + "\"items\":[{\"productCode\":\"CT001\",\"quantity\":2}]}");
        abrirCaixa();

        chamar("POST", "/api/Pedido/" + numero + "/finalizar", "caixa@contrato.quack",
                "{\"paymentType\":\"fiado\",\"payments\":[{\"paymentType\":\"fiado\",\"amount\":6}]}", 200);

        assertThat(jdbc.queryForObject("SELECT SaldoDevedor FROM Clientes WHERE Id = N'cl-ct-folgado'",
                BigDecimal.class)).isEqualByComparingTo("6");
        assertThat(contar("SELECT COUNT(*) FROM AuditLog WHERE EventType = 'FiadoDebito'")).isEqualTo(1);
    }

    @Test
    void estoqueInsuficienteRecusaEDeixaOPedidoAberto() throws Exception {
        String numero = criarPedido(DOIS_ITENS);
        jdbc.update("UPDATE Produtos SET ProductQnt = 1 WHERE ProductCode = N'CT001' "
                + "AND CompanyId = N'empresa-principal'");
        abrirCaixa();

        JsonNode r = chamar("POST", "/api/Pedido/" + numero + "/finalizar", "caixa@contrato.quack",
                "{\"paymentType\":\"dinheiro\",\"payments\":[{\"paymentType\":\"dinheiro\",\"amount\":12}]}", 400);

        assertThat(r.path("message").asText()).isEqualTo("Estoque insuficiente para Produto Contrato 1. Disponível: 1.");
        assertThat(contar("SELECT Status FROM Pedidos WHERE OrderNumber = ?", numero)).isZero();
        assertThat(estoque("CT002")).isEqualByComparingTo("10");
    }

    @Test
    void pedidoCanceladoNaoFinaliza() throws Exception {
        String numero = criarPedido(DOIS_ITENS);
        chamar("POST", "/api/Pedido/" + numero + "/cancelar", "atendente@contrato.quack", null, 200);
        abrirCaixa();

        JsonNode r = chamar("POST", "/api/Pedido/" + numero + "/finalizar", "caixa@contrato.quack",
                "{\"paymentType\":\"dinheiro\",\"payments\":[]}", 400);

        assertThat(r.path("message").asText()).isEqualTo("Este pedido foi cancelado.");
    }
}
