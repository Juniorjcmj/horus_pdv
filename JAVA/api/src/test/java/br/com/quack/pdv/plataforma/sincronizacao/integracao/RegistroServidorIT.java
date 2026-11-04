package br.com.quack.pdv.plataforma.sincronizacao.integracao;

import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.EMPRESA;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.plataforma.sincronizacao.nuvem.ServidorLojaFilter;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.ParteLote;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.SerializadorLote;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/** Registro e revogacao do servidor da loja na nuvem (tarefa 3.1), pelo HTTP real do contexto nuvem. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RegistroServidorIT {

    static final String SENHA = "Senha#Quack2026";
    static final ObjectMapper JSON = new ObjectMapper();
    static AmbienteSync amb;
    static String idDoPrimeiro;
    static String tokenDoPrimeiro;

    @BeforeAll
    static void subir() {
        amb = AmbienteSync.iniciarParaCarga("reg");
    }

    @AfterAll
    static void descer() {
        if (amb != null) amb.close();
    }

    @Test
    @Order(1)
    void gerenteEAtendenteSaoRecusadosSemCriarServidor() throws Exception {
        assertThat(registrar("gerente@contrato.quack", SENHA, "Loja").statusCode()).isEqualTo(403);
        assertThat(registrar("atendente@contrato.quack", SENHA, "Loja").statusCode()).isEqualTo(403);
        assertThat(amb.jdbcNuvem.queryForObject("SELECT COUNT(*) FROM SyncServidores", Integer.class)).isZero();
    }

    @Test
    @Order(2)
    void senhaErradaTemAMesmaMensagemEOMesmoBloqueioDoLogin() throws Exception {
        HttpResponse<String> r = registrar("admin@contrato.quack", "errada", "Loja");
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(JSON.readTree(r.body()).path("message").asText()).isEqualTo("E-mail ou senha inválidos.");
        for (int i = 0; i < 4; i++) registrar("bloqueio2@contrato.quack", "errada", "Loja");
        HttpResponse<String> bloqueado = registrar("bloqueio2@contrato.quack", "errada", "Loja");
        assertThat(bloqueado.statusCode()).isEqualTo(429);
        assertThat(registrar("bloqueio2@contrato.quack", SENHA, "Loja").statusCode()).as("ate a senha certa espera").isEqualTo(429);
        assertThat(registrar("admin@contrato.quack", SENHA, "  ").statusCode()).as("nome obrigatorio").isEqualTo(400);
        assertThat(registrar("", "", "Loja").statusCode()).isEqualTo(400);
    }

    @Test
    @Order(3)
    void administradorRegistraEOTokenSoGuardaOHash() throws Exception {
        HttpResponse<String> r = registrar("admin@contrato.quack", SENHA, "Servidor da loja");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode dados = JSON.readTree(r.body()).path("data");
        idDoPrimeiro = dados.path("serverId").asText();
        tokenDoPrimeiro = dados.path("token").asText();
        assertThat(dados.path("companyId").asText()).isEqualTo(EMPRESA);
        assertThat(tokenDoPrimeiro).matches("[A-Za-z0-9_-]{43}"); // 32 bytes em base64url sem padding
        Map<String, Object> linha = amb.jdbcNuvem.queryForMap("SELECT Nome, TokenHash, Status FROM SyncServidores WHERE Id = ?",
                idDoPrimeiro);
        assertThat(linha.get("Nome")).isEqualTo("Servidor da loja");
        assertThat(linha.get("Status")).isEqualTo("pendente");
        assertThat(linha.get("TokenHash")).isEqualTo(ServidorLojaFilter.hash(tokenDoPrimeiro)).isNotEqualTo(tokenDoPrimeiro);
    }

    @Test
    @Order(4)
    void umServidorAtivoPorEmpresaESegundoRegistroCitaOAtivo() throws Exception {
        // pendente (carga que nao terminou) e substituido
        HttpResponse<String> novo = registrar("admin@contrato.quack", SENHA, "Servidor de novo");
        assertThat(novo.statusCode()).isEqualTo(200);
        String idNovo = JSON.readTree(novo.body()).path("data").path("serverId").asText();
        assertThat(amb.jdbcNuvem.queryForObject("SELECT Status FROM SyncServidores WHERE Id = ?", String.class, idDoPrimeiro))
                .isEqualTo("revogado");
        assertThat(lote(JSON.readTree(novo.body()).path("data").path("token").asText(), EMPRESA).statusCode())
                .as("pendente ainda nao envia lotes").isEqualTo(401);

        amb.jdbcNuvem.update("UPDATE SyncServidores SET Status = 'ativo' WHERE Id = ?", idNovo);
        HttpResponse<String> segundo = registrar("admin@contrato.quack", SENHA, "Outro servidor");
        assertThat(segundo.statusCode()).isEqualTo(409);
        String msg = JSON.readTree(segundo.body()).path("message").asText();
        assertThat(msg).contains("Servidor de novo").contains(idNovo);
        assertThat(amb.jdbcNuvem.queryForObject("SELECT COUNT(*) FROM SyncServidores WHERE Status <> 'revogado'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @Order(5)
    void tokenDeOutraEmpresaDa403ETokenRevogadoDa401() throws Exception {
        String idAtivo = amb.jdbcNuvem.queryForObject("SELECT Id FROM SyncServidores WHERE Status = 'ativo'", String.class);
        amb.registrarServidor("srv-outra", "emp-contrato-aprovada", "tok-outra-empresa", "ativo");

        assertThat(lote("tok-outra-empresa", EMPRESA).statusCode()).as("lote de outra empresa").isEqualTo(403);
        assertThat(lote("tok-outra-empresa", "emp-contrato-aprovada").statusCode()).as("o proprio lote vazio passa").isEqualTo(200);

        String gerente = amb.tokenNuvem("usr-ct-gerente", EMPRESA, "Gil Gerente", "gerente@contrato.quack", "gerente");
        String admin = amb.tokenNuvem("usr-ct-admin", EMPRESA, "Ana Administradora", "admin@contrato.quack", "administrador");
        assertThat(revogar(idAtivo, gerente).statusCode()).as("gerente nao revoga").isEqualTo(403);
        assertThat(revogar(idAtivo, null).statusCode()).as("sem sessao").isEqualTo(401);
        assertThat(revogar("srv-outra", admin).statusCode()).as("servidor de outra empresa").isEqualTo(403);
        assertThat(revogar("srv-inexistente", admin).statusCode()).isEqualTo(404);
        assertThat(revogar(idAtivo, admin).statusCode()).isEqualTo(200);

        String tokenRevogado = amb.jdbcNuvem.queryForObject("SELECT TokenHash FROM SyncServidores WHERE Id = ?", String.class, idAtivo);
        assertThat(tokenRevogado).isNotBlank();
        // o token de texto simples nao e guardado: usa-se o do primeiro registro, ja revogado no passo 4
        assertThat(lote(tokenDoPrimeiro, EMPRESA).statusCode()).as("revogado").isEqualTo(401);
        assertThat(get("/api/loja-sync/carga?tabela=Produtos", "LojaServidor " + tokenDoPrimeiro).statusCode()).isEqualTo(401);
        assertThat(get("/api/loja-sync/carga?tabela=Produtos", null).statusCode()).as("sem token").isEqualTo(401);

        // revogado, a empresa pode registrar de novo
        assertThat(registrar("admin@contrato.quack", SENHA, "Servidor 3").statusCode()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ HTTP

    static HttpResponse<String> registrar(String email, String senha, String nome) throws Exception {
        String corpo = JSON.writeValueAsString(Map.of("email", email, "password", senha, "serverName", nome));
        return amb.http.send(HttpRequest.newBuilder(URI.create(amb.urlBaseNuvem() + "/api/loja-sync/registrar"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(corpo)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> revogar(String id, String jwt) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(amb.urlBaseNuvem() + "/api/loja-sync/servidores/" + id + "/revogar"))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (jwt != null) b.header("Authorization", "Bearer " + jwt);
        return amb.http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> get(String caminho, String autorizacao) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(amb.urlBaseNuvem() + caminho)).GET();
        if (autorizacao != null) b.header("Authorization", autorizacao);
        return amb.http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Lote vazio (so confere o token e a empresa) de {@code empresa}, enviado com o token dado. */
    static HttpResponse<String> lote(String token, String empresa) throws Exception {
        byte[] corpo = SerializadorLote.paraGzip(new ParteLote("lote-it", empresa, 0, 1, false, 1, true, List.of()));
        return amb.postLote("LojaServidor " + token, corpo);
    }
}
