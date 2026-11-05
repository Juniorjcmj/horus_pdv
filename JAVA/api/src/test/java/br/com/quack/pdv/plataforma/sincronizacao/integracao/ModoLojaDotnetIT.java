package br.com.quack.pdv.plataforma.sincronizacao.integracao;

import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.EMPRESA;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.esperar;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.SincronizacaoIT.contar;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.plataforma.sincronizacao.conferencia.ConferenciaSincronizacao;
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
 * D13: a API .NET de verdade (imagem quack-api-dotnet:contrato) no banco da nuvem do teste. Um preco mudado e um cliente
 * cadastrado pelas rotas dela precisam chegar a loja: prova que o detector pega a gravacao real da .NET (sem o contexto de
 * Change Tracking "sync-loja", que so a Java usa) e que o comando e aplicado. Opcional: exige Docker e a imagem
 * ({@code contrato/ambiente-dotnet/subir.sh} a constroi). Rodar com {@code -Dmodoloja.dotnet=true}.
 */
@EnabledIfSystemProperty(named = "modoloja.dotnet", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ModoLojaDotnetIT {

    static final ObjectMapper JSON = new ObjectMapper();
    static AmbienteSync amb;
    static String token;

    @BeforeAll
    static void subir() throws Exception {
        amb = AmbienteSync.iniciarComDotNet("dn");
        amb.esperarConvergencia(60_000);
        token = login();
    }

    @AfterAll
    static void descer() {
        if (amb != null) amb.close();
    }

    private static String login() throws Exception {
        HttpResponse<String> r = enviar("POST", "/api/Auth/login", null,
                "{\"email\":\"admin@contrato.quack\",\"password\":\"Senha#Quack2026\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return r.headers().allValues("set-cookie").stream().filter(c -> c.startsWith("horuspdv.auth="))
                .map(c -> c.substring("horuspdv.auth=".length(), c.indexOf(';'))).findFirst().orElseThrow();
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
    void precoMudadoPelaDotnetChegaALoja() throws Exception {
        BigDecimal qnt = amb.jdbcNuvem.queryForObject(
                "SELECT ProductQnt FROM Produtos WHERE CompanyId = ? AND Id = 'pr-ct-1'", BigDecimal.class, EMPRESA);
        String q = qnt.stripTrailingZeros().toPlainString();
        String corpo = "{\"productName\":\"Produto Contrato 1 pela DotNet\",\"productCode\":\"CT001\",\"productQnt\":\"" + q
                + "\",\"estoqueConsultado\":\"" + q + "\",\"productUnitPrice\":\"2,00\",\"productSalePrice\":\"4,75\"}";

        HttpResponse<String> r = enviar("PUT", "/api/Produto/pr-ct-1", token, corpo);

        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(amb.jdbcNuvem.queryForObject("SELECT ProductSalePrice FROM Produtos WHERE Id = 'pr-ct-1'", BigDecimal.class))
                .isEqualByComparingTo("4.75");
        esperar("preco da .NET aplicado na loja", 60_000, () -> new BigDecimal("4.75").compareTo(amb.jdbcLoja.queryForObject(
                "SELECT ProductSalePrice FROM Produtos WHERE Id = 'pr-ct-1'", BigDecimal.class)) == 0);
        assertThat(amb.jdbcLoja.queryForObject("SELECT ProductName FROM Produtos WHERE Id = 'pr-ct-1'", String.class))
                .isEqualTo("Produto Contrato 1 pela DotNet");
        assertThat(contar(amb.jdbcNuvem, "SELECT COUNT(*) FROM SyncQuarentena WHERE CompanyId = ?", EMPRESA))
                .as("edicao de cadastro nao vai para a quarentena").isZero();
    }

    @Test
    @Order(2)
    void clienteCadastradoPelaDotnetChegaALoja() throws Exception {
        HttpResponse<String> r = enviar("POST", "/api/Cliente", token,
                "{\"customerName\":\"Cliente pela DotNet\",\"telephone\":\"11999990000\",\"limiteCredito\":100}");

        assertThat(r.statusCode()).as(r.body()).isIn(200, 201);
        JsonNode dados = JSON.readTree(r.body()).path("data");
        String id = dados.path("id").asText();
        assertThat(id).isNotBlank();
        assertThat(contar(amb.jdbcNuvem, "SELECT COUNT(*) FROM Clientes WHERE Id = ?", id)).isEqualTo(1);
        esperar("cliente da .NET aplicado na loja", 60_000,
                () -> contar(amb.jdbcLoja, "SELECT COUNT(*) FROM Clientes WHERE Id = ?", id) == 1);
        assertThat(amb.jdbcLoja.queryForObject("SELECT CustomerName FROM Clientes WHERE Id = ?", String.class, id))
                .isEqualTo("Cliente pela DotNet");
    }

    @Test
    @Order(3)
    void depoisDasEdicoesDaDotnetAConferenciaFechaOk() {
        amb.esperarConvergencia(60_000);
        ConferenciaSincronizacao.Relatorio r = amb.loja.getBean(ConferenciaSincronizacao.class).executar("manual");
        assertThat(r.resultado()).as(r.detalhe().toString()).isEqualTo("ok");
    }
}
