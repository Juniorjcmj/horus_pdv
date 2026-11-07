package br.com.quack.pdv.plataforma.sincronizacao.integracao;

import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.EMPRESA;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.esperar;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.SincronizacaoIT.contar;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Ponta a ponta com a API .NET real (imagem quack-api-dotnet:contrato) na nuvem de teste: uma entrada manual e o estorno
 * da nota, feitos pelas rotas da .NET, chegam a loja como operacoes de estoque. Opcional (Docker e a imagem); rodar com
 * {@code -Dmodoloja.dotnet=true}, como o {@link ModoLojaDotnetIT}.
 */
@EnabledIfSystemProperty(named = "modoloja.dotnet", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OperacoesNuvemDotnetIT {

    static final ObjectMapper JSON = new ObjectMapper();
    static AmbienteSync amb;
    static String token;
    static String notaId;
    static BigDecimal saldoInicial;

    @BeforeAll
    static void subir() throws Exception {
        amb = AmbienteSync.iniciarComDotNet("opdn");
        amb.esperarConvergencia(60_000);
        esperar("recurso anunciado", 60_000, () -> "operacao-estoque".equals(amb.jdbcNuvem.queryForObject(
                "SELECT Recursos FROM SyncServidores WHERE CompanyId = ?", String.class, EMPRESA)));
        HttpResponse<String> r = enviar("POST", "/api/Auth/login", null,
                "{\"email\":\"admin@contrato.quack\",\"password\":\"Senha#Quack2026\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        token = r.headers().allValues("set-cookie").stream().filter(c -> c.startsWith("horuspdv.auth="))
                .map(c -> c.substring("horuspdv.auth=".length(), c.indexOf(';'))).findFirst().orElseThrow();
        saldoInicial = amb.jdbcLoja.queryForObject("SELECT ProductQnt FROM Produtos WHERE Id = 'pr-ct-2'", BigDecimal.class);
    }

    @AfterAll
    static void descer() {
        if (amb != null) amb.close();
    }

    private static HttpResponse<String> enviar(String metodo, String caminho, String bearer, String corpo) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(amb.urlBaseDotNet() + caminho))
                .header("Content-Type", "application/json")
                .method(metodo, corpo == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(corpo));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return amb.http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Order(1)
    void entradaManualPelaDotnetSobeOEstoqueDaLoja() throws Exception {
        String corpo = """
                {"operacaoId":"aaaaaaaa-bbbb-cccc-dddd-0000000000f1","dataCompra":"2026-11-02","fornecedorId":null,
                 "tipoDocumento":"cupom","numeroDocumento":"CP-OP1","observacao":"Compra de teste",
                 "itens":[{"quantidadeCompra":"2","unidadeCompra":"CX","fatorConversao":"6","custoCompra":"12,00",
                   "alterarPrecoVenda":false,"produtoExistenteId":"pr-ct-2","productCode":"CT002","productName":"Produto Contrato 2",
                   "unidadeComercial":"UN","dataValidade":"2027-01-10","numeroLote":"LM-1"},
                  {"quantidadeCompra":"5","unidadeCompra":"UN","fatorConversao":"1","custoCompra":"1,5000","alterarPrecoVenda":false,
                   "productCode":"","productName":"Chiclete Menta Op","unidadeComercial":"UN","precoVenda":"2,99"}]}
                """;
        HttpResponse<String> r = enviar("POST", "/api/Produto/entrada-manual", token, corpo);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode dados = JSON.readTree(r.body()).path("data");
        notaId = dados.path("notaEntradaId").asText();
        assertThat(notaId).isNotBlank();

        esperar("nota na loja", 90_000, () -> contar(amb.jdbcLoja, "SELECT COUNT(*) FROM NotasEntradaArquivo WHERE Id = ?", notaId) == 1);
        esperar("saldo do produto existente", 60_000, () -> saldoInicial.add(new BigDecimal("12")).compareTo(
                amb.jdbcLoja.queryForObject("SELECT ProductQnt FROM Produtos WHERE Id = 'pr-ct-2'", BigDecimal.class)) == 0);
        assertThat(amb.jdbcLoja.queryForObject(
                "SELECT ProductQnt FROM Produtos WHERE CompanyId = ? AND ProductName = 'Chiclete Menta Op'", BigDecimal.class, EMPRESA))
                .as("produto novo: 5, nao 10").isEqualByComparingTo("5");
        assertThat(contar(amb.jdbcNuvem, "SELECT COUNT(*) FROM SyncQuarentena WHERE CompanyId = ?", EMPRESA)).isZero();
        amb.esperarConvergencia(60_000);
    }

    @Test
    @Order(2)
    void estornoPelaDotnetBaixaOSaldoEMarcaANotaNaLoja() throws Exception {
        HttpResponse<String> r = enviar("DELETE", "/api/NfeImport/notas-entrada/" + notaId + "?justificativa=Lancamento%20em%20duplicidade",
                token, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        esperar("nota estornada na loja", 90_000, () -> Boolean.TRUE.equals(amb.jdbcLoja.queryForObject(
                "SELECT Estornada FROM NotasEntradaArquivo WHERE Id = ?", Boolean.class, notaId)));
        esperar("saldo de volta", 60_000, () -> saldoInicial.compareTo(
                amb.jdbcLoja.queryForObject("SELECT ProductQnt FROM Produtos WHERE Id = 'pr-ct-2'", BigDecimal.class)) == 0);
        amb.esperarConvergencia(60_000);
    }
}
