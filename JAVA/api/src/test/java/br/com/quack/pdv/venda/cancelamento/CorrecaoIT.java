package br.com.quack.pdv.venda.cancelamento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.quack.pdv.BancoIT;
import br.com.quack.pdv.contrato.EstadoDoBanco;
import br.com.quack.pdv.security.SecretProtector;
import br.com.quack.pdv.shared.NegocioException;
import br.com.quack.pdv.venda.cancelamento.app.CorrecaoVendaService;
import br.com.quack.pdv.venda.cancelamento.domain.CorrecaoResultado;
import br.com.quack.pdv.venda.registro.VendaRegistroIT;
import br.com.quack.pdv.venda.registro.app.VendaRegistroService;
import br.com.quack.pdv.venda.registro.domain.VendaRegistroResultado;
import br.com.quack.pdv.venda.registro.web.VendaRequests;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
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
 * Correcao de venda (5.3) com SQL Server real: original "corrigida", venda nova com os itens restantes numerada pelo
 * NumeradorDocumentos, estoque dos removidos de volta, pagamentos proporcionais, fiado reajustado, esquisitice dos
 * documentos fiscais (Status = 3) e decisao fiscal so com emitente.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
class CorrecaoIT {

    static final String EMPRESA = "empresa-principal";
    private static final ObjectMapper JSON = new ObjectMapper();

    static {
        BancoIT.criarBanco("correcao");
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> BancoIT.urlDoBanco("correcao"));
        r.add("spring.datasource.username", BancoIT.SQL::getUsername);
        r.add("spring.datasource.password", BancoIT.SQL::getPassword);
        r.add("quack.lote.retentativa.ativa", () -> "false");
    }

    @Autowired VendaRegistroService vendas;
    @Autowired CorrecaoVendaService correcao;
    @Autowired SecretProtector segredos;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @BeforeEach
    void seed() {
        EstadoDoBanco.por(BancoIT.urlDoBanco("correcao"), BancoIT.SQL.getUsername(), BancoIT.SQL.getPassword())
                .restaurar();
    }

    // ---------------------------------------------------------------- helpers

    private VendaRegistroResultado vendaDeDoisItens() {
        return vendas.registrar(EMPRESA, VendaRegistroIT.venda("9,00", List.of(VendaRegistroIT.item("CT001", "2", "3"),
                VendaRegistroIT.item("CT002", "1", "3")), VendaRegistroIT.pag("dinheiro", "9")), null);
    }

    private VendaRegistroResultado vendaMista() {
        VendaRequests.Pagamento dinheiro = VendaRegistroIT.pag("dinheiro", "5");
        dinheiro.cashGiven = new BigDecimal("10");
        dinheiro.changeAmount = new BigDecimal("5");
        return vendas.registrar(EMPRESA, VendaRegistroIT.venda("9,00", List.of(VendaRegistroIT.item("CT001", "2", "3"),
                VendaRegistroIT.item("CT002", "1", "3")), dinheiro, VendaRegistroIT.pag("credito", "4")), null);
    }

    private CorrecaoResultado corrigir(String saleNumber, String... remover) {
        return correcao.corrigir(EMPRESA, saleNumber, List.of(remover), "usr-ct-gerente", "Gil Gerente",
                "Item lançado por engano.");
    }

    private BigDecimal estoque(String codigo) {
        return jdbc.queryForObject("SELECT ProductQnt FROM Produtos WHERE CompanyId = ? AND ProductCode = ?",
                BigDecimal.class, EMPRESA, codigo);
    }

    private int contar(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    // ---------------------------------------------------------------- venda e estoque

    @Test
    void vendaOriginalFicaCorrigidaANovaTemOsItensRestantesEOEstoqueDosRemovidosVolta() {
        VendaRegistroResultado original = vendaDeDoisItens();
        assertThat(estoque("CT001")).isEqualByComparingTo("8");
        assertThat(estoque("CT002")).isEqualByComparingTo("9");

        CorrecaoResultado r = corrigir(original.saleNumber(), "CT002");

        assertThat(r.vendaOriginalNumber()).isEqualTo("15043");
        assertThat(r.novaVendaNumber()).isEqualTo("15044");
        assertThat(r.financeiroNome()).isEqualTo("Gil Gerente");
        assertThat(r.itensRemovidos()).isEqualTo(1);
        assertThat(r.novoTotal()).isEqualTo("6,00");
        assertThat(r.fiscalQueued()).isFalse();

        Map<String, Object> velha = jdbc.queryForMap("SELECT Status, CorrigidaParaVendaId, CanceladoPorOperadorId, "
                + "CanceladoPorSupervisorNome, CanceladoJustificativa FROM Vendas WHERE Id = ?", original.vendaId());
        assertThat(velha).containsEntry("Status", "corrigida")
                .containsEntry("CorrigidaParaVendaId", "sale-empresa-principal-15044")
                .containsEntry("CanceladoPorOperadorId", "usr-ct-gerente")
                .containsEntry("CanceladoPorSupervisorNome", "Gil Gerente")
                .containsEntry("CanceladoJustificativa", "Item lançado por engano.");
        Map<String, Object> nova = jdbc.queryForMap("SELECT Status, CorrigidaDeVendaId, TotalAmount, PaymentType, "
                + "OperatorName FROM Vendas WHERE Id = N'sale-empresa-principal-15044'");
        assertThat(nova).containsEntry("CorrigidaDeVendaId", original.vendaId())
                .containsEntry("PaymentType", "dinheiro").containsEntry("OperatorName", "Cai Caixa");
        assertThat((BigDecimal) nova.get("TotalAmount")).isEqualByComparingTo("6");
        List<Map<String, Object>> itens = jdbc.queryForList("SELECT ProductCode, Quantity, ItemTotal FROM VendaItens "
                + "WHERE VendaId = N'sale-empresa-principal-15044' ORDER BY Id");
        assertThat(itens).hasSize(1);
        assertThat(itens.getFirst()).containsEntry("ProductCode", "CT001");

        // CT001 voltou (+2) e foi baixado de novo (-2); CT002 so voltou
        assertThat(estoque("CT001")).isEqualByComparingTo("8");
        assertThat(estoque("CT002")).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT Motivo FROM EstoqueMovimentacoes WHERE Tipo = 'devolucao' AND "
                + "ProdutoId = N'pr-ct-2'", String.class)).isEqualTo("Correcao da venda #15043 (devolucao da original)");
        assertThat(jdbc.queryForMap("SELECT OrigemTipo, OrigemId, Motivo FROM EstoqueMovimentacoes WHERE Tipo = "
                + "'saida_venda' AND OrigemId = N'15044'")).containsEntry("OrigemTipo", "correcao")
                .containsEntry("Motivo", "Venda #15044");
        assertThat(contar("SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE OrigemId LIKE 'pend-%'")).isZero();
        assertThat(jdbc.queryForObject("SELECT Description FROM AuditLog WHERE EventType = 'VendaCorrigida'",
                String.class)).startsWith("Venda #15043 corrigida → nova venda #15044. Itens removidos: 1.");
    }

    @Test
    void aNumeracaoDaVendaNovaSegueOContadorDoNumeradorEAProximaVendaContinuaDepois() {
        VendaRegistroResultado original = vendaDeDoisItens();

        CorrecaoResultado r = corrigir(original.saleNumber(), "CT002");
        VendaRegistroResultado seguinte = vendas.registrar(EMPRESA, VendaRegistroIT.venda("3,00",
                List.of(VendaRegistroIT.item("CT001", "1", "3")), VendaRegistroIT.pag("dinheiro", "3")), null);

        assertThat(r.novaVendaNumber()).isEqualTo("15044");
        assertThat(seguinte.saleNumber()).isEqualTo("15045");
    }

    @Test
    void pagamentosDaVendaNovaSaoProporcionaisEODinheiroRecebidoViraOValorSemTroco() {
        VendaRegistroResultado original = vendaMista();

        corrigir(original.saleNumber(), "CT002");

        List<Map<String, Object>> pags = jdbc.queryForList("SELECT PaymentType, Amount, CashGiven, ChangeAmount FROM "
                + "VendaPagamentos WHERE VendaId = N'sale-empresa-principal-15044' ORDER BY Id");
        assertThat(pags).hasSize(2);
        assertThat(pags.get(0)).containsEntry("PaymentType", "dinheiro");
        assertThat((BigDecimal) pags.get(0).get("Amount")).isEqualByComparingTo("3.33");
        assertThat((BigDecimal) pags.get(0).get("CashGiven")).isEqualByComparingTo("3.33");
        assertThat((BigDecimal) pags.get(0).get("ChangeAmount")).isEqualByComparingTo("0");
        assertThat(pags.get(1)).containsEntry("PaymentType", "credito");
        assertThat((BigDecimal) pags.get(1).get("Amount")).isEqualByComparingTo("2.67");
        assertThat(pags.get(1).get("CashGiven")).isNull();
        assertThat(jdbc.queryForObject("SELECT PaymentType FROM Vendas WHERE Id = N'sale-empresa-principal-15044'",
                String.class)).isEqualTo("Múltiplo");
    }

    @Test
    void fiadoDaOriginalEEstornadoEODebitoDaNovaVendaEProporcional() {
        VendaRequests.Venda fiado = VendaRegistroIT.venda("9,00", List.of(VendaRegistroIT.item("CT001", "2", "3"),
                VendaRegistroIT.item("CT002", "1", "3")), VendaRegistroIT.pag("fiado", "9"));
        fiado.paymentType = "fiado";
        fiado.customerCpf = "111.444.777-35";
        VendaRegistroResultado original = vendas.registrar(EMPRESA, fiado, null);

        corrigir(original.saleNumber(), "CT002");

        assertThat(jdbc.queryForObject("SELECT SaldoDevedor FROM Clientes WHERE Id = N'cl-ct-folgado'",
                BigDecimal.class)).isEqualByComparingTo("6");
        assertThat(jdbc.queryForObject("SELECT Valor FROM FiadoMovimentos WHERE VendaId = ? AND Tipo = 2",
                BigDecimal.class, original.vendaId())).isEqualByComparingTo("9");
        assertThat(jdbc.queryForObject("SELECT Valor FROM FiadoMovimentos WHERE VendaId = N'sale-empresa-principal-"
                + "15044' AND Tipo = 1", BigDecimal.class)).isEqualByComparingTo("6");
    }

    @Test
    void documentosFiscaisDaOriginalFicamEmStatus3ComoNaDotNet() {
        VendaRegistroResultado original = vendaDeDoisItens();
        jdbc.update("INSERT INTO DocumentosFiscais (Id, CompanyId, VendaId, Modelo, Serie, NumeroNf, Ambiente, Status,"
                + " TpEmis) VALUES (N'doc-it-orig', N'empresa-principal', ?, 65, 2, 5, 2, 1, 1)", original.vendaId());

        corrigir(original.saleNumber(), "CT002");

        // Esquisitice copiada da .NET (D12): Status = 3 (Autorizado no enum), nao 6 (Cancelado).
        Map<String, Object> doc = jdbc.queryForMap("SELECT Status, CanceladoPorSupervisorId, CanceladoPorOperador, "
                + "CanceladoJustificativa FROM DocumentosFiscais WHERE Id = N'doc-it-orig'");
        assertThat(((Number) doc.get("Status")).intValue()).isEqualTo(3);
        assertThat(doc).containsEntry("CanceladoPorSupervisorId", "usr-ct-gerente")
                .containsEntry("CanceladoPorOperador", "Gil Gerente");
    }

    // ---------------------------------------------------------------- regras e rollback

    @Test
    void regrasDaDotNetViramBadRequestComAMensagemExata() {
        VendaRegistroResultado original = vendaDeDoisItens();

        assertThatThrownBy(() -> corrigir("99999", "CT001")).isInstanceOf(NegocioException.class)
                .hasMessage("Venda não encontrada.");
        assertThatThrownBy(() -> corrigir(original.saleNumber(), "ZZZ")).isInstanceOf(NegocioException.class)
                .hasMessage("Nenhum item válido selecionado para remoção.");
        assertThatThrownBy(() -> corrigir(original.saleNumber(), "CT001", "ct002"))
                .isInstanceOf(NegocioException.class)
                .hasMessage("Não é possível remover todos os itens. Para isso, utilize o cancelamento da venda.");
        corrigir(original.saleNumber(), "CT002");
        assertThatThrownBy(() -> corrigir(original.saleNumber(), "CT001")).isInstanceOf(NegocioException.class)
                .hasMessage("Apenas vendas finalizadas podem ser corrigidas. Status atual: corrigida.");
    }

    @Test
    void falhaNoMeioDaCorrecaoDesfazTudo() {
        VendaRegistroResultado original = vendaDeDoisItens();
        // CT002 e o item que fica; sem ele cadastrado a re-baixa falha depois de devolver o estoque
        jdbc.update("UPDATE Produtos SET ProductCode = N'CT002X' WHERE CompanyId = ? AND ProductCode = N'CT002'",
                EMPRESA);

        assertThatThrownBy(() -> corrigir(original.saleNumber(), "CT001")).isInstanceOf(NegocioException.class)
                .hasMessage("Produto CT002 não encontrado ao re-baixar estoque.");

        assertThat(jdbc.queryForObject("SELECT Status FROM Vendas WHERE Id = ?", String.class, original.vendaId()))
                .isEqualTo("finalizada");
        assertThat(estoque("CT001")).isEqualByComparingTo("8");
        assertThat(contar("SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE Tipo = 'devolucao'")).isZero();
        assertThat(contar("SELECT COUNT(*) FROM Vendas WHERE SaleNumber = '15044'")).isZero();
    }

    // ---------------------------------------------------------------- decisao fiscal (so com emitente)

    private String bearerGerente() throws Exception {
        MvcResult r = mvc.perform(post("/api/Auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("email", "gerente@contrato.quack", "password",
                        "Senha#Quack2026")))).andReturn();
        return "Bearer " + r.getResponse().getCookie("horuspdv.auth").getValue();
    }

    @Test
    void semEmitenteACorrecaoNaoDecideNemGravaNotaFiscal() throws Exception {
        VendaRegistroResultado original = vendaDeDoisItens();

        mvc.perform(post("/api/HistoricoVendas/" + original.saleNumber() + "/corrigir")
                .header("Authorization", bearerGerente()).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("produtosParaRemover", List.of("CT002"), "justificativa",
                        "Item lançado por engano.", "supervisorId", "usr-ct-gerente", "supervisorPassword",
                        "Senha#Quack2026"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message")
                        .value("Venda #15043 corrigida com sucesso. Nova venda: #15044."))
                .andExpect(jsonPath("$.data.fiscalQueued").value(false));

        assertThat(contar("SELECT COUNT(*) FROM DocumentosFiscais WHERE VendaId = N'sale-empresa-principal-15044'"))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT EmitirNfce FROM Vendas WHERE Id = N'sale-empresa-principal-15044'",
                Boolean.class)).isNull();
    }

    @Test
    void comEmitenteACorrecaoDecideEEnfileiraANotaDaVendaNova() throws Exception {
        VendaRegistroResultado original = vendaDeDoisItens();
        jdbc.update("UPDATE Empresas SET CertificadoPfxCifrado = ?, CertificadoSenhaCifrada = ?, CscId = N'000001',"
                        + " CscCifrado = ? WHERE Id = ?", segredos.protect("QUFBQQ=="), segredos.protect("senha"),
                segredos.protect("csc-de-teste"), EMPRESA);
        jdbc.update("UPDATE RegrasEmissaoNfce SET Habilitado = 0 WHERE CompanyId = ?", EMPRESA); // desligado: emite todas

        mvc.perform(post("/api/HistoricoVendas/" + original.saleNumber() + "/corrigir")
                .header("Authorization", bearerGerente()).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("produtosParaRemover", List.of("CT002"), "justificativa",
                        "Item lançado por engano.", "supervisorId", "usr-ct-gerente", "supervisorPassword",
                        "Senha#Quack2026"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.fiscalQueued").value(true));

        assertThat(jdbc.queryForObject("SELECT EmitirNfce FROM Vendas WHERE Id = N'sale-empresa-principal-15044'",
                Boolean.class)).isTrue();
        Map<String, Object> nota = jdbc.queryForMap("SELECT Modelo, Status, NumeroNf FROM DocumentosFiscais WHERE "
                + "VendaId = N'sale-empresa-principal-15044'");
        assertThat(((Number) nota.get("Modelo")).intValue()).isEqualTo(65);
        assertThat(((Number) nota.get("Status")).intValue()).isEqualTo(1);
    }
}
