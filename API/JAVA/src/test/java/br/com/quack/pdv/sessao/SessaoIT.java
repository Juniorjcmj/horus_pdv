package br.com.quack.pdv.sessao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import br.com.quack.pdv.ApiIT;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** Sessoes de ponta a ponta: encerrar uma sessao invalida o token dela (401), logout e troca de senha tambem. */
class SessaoIT extends ApiIT {

    String idDaSessao(String bearer) throws Exception {
        JsonNode lista = corpo(mvc.perform(get("/api/Sessao").header(HttpHeaders.AUTHORIZATION, bearer)).andReturn()).get("data");
        for (JsonNode s : lista) if (s.get("current").asBoolean()) return s.get("id").asText();
        throw new IllegalStateException("sem sessao atual");
    }

    @Test
    void encerrarUmaSessaoDerrubaOTokenDela() throws Exception {
        jdbc.update("DELETE FROM Sessoes WHERE UserId = 'usr-ct-atend'");
        String a = bearer("atendente@contrato.quack");
        String b = bearer("atendente@contrato.quack");
        String idB = idDaSessao(b);
        assertThat(idDaSessao(a)).isNotEqualTo(idB);

        var lista = mvc.perform(get("/api/Sessao").header(HttpHeaders.AUTHORIZATION, a)).andReturn();
        assertThat(corpo(lista).get("data")).hasSize(2);

        var encerrar = mvc.perform(delete("/api/Sessao/" + idB).header(HttpHeaders.AUTHORIZATION, a)).andReturn();
        assertThat(encerrar.getResponse().getStatus()).isEqualTo(200);
        assertThat(corpo(encerrar).get("data")).hasSize(1);

        var comTokenDeB = mvc.perform(get("/api/Auth/me").header(HttpHeaders.AUTHORIZATION, b)).andReturn();
        assertThat(comTokenDeB.getResponse().getStatus()).isEqualTo(401);
        assertThat(corpo(comTokenDeB).get("message").asText()).isEqualTo("Sessao expirada ou token invalido.");
        assertThat(mvc.perform(get("/api/Auth/me").header(HttpHeaders.AUTHORIZATION, a)).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void encerrarASessaoAtualOuInexistenteEEncerrarOutras() throws Exception {
        jdbc.update("DELETE FROM Sessoes WHERE UserId = 'usr-ct-gerente'");
        String a = bearer("gerente@contrato.quack");
        bearer("gerente@contrato.quack");
        bearer("gerente@contrato.quack");

        var atual = mvc.perform(delete("/api/Sessao/" + idDaSessao(a)).header(HttpHeaders.AUTHORIZATION, a)).andReturn();
        assertThat(atual.getResponse().getStatus()).isEqualTo(400);
        assertThat(corpo(atual).get("message").asText()).isEqualTo("A sessão atual não pode ser encerrada por esta ação.");

        var inexistente = mvc.perform(delete("/api/Sessao/sess-nao-existe").header(HttpHeaders.AUTHORIZATION, a)).andReturn();
        assertThat(inexistente.getResponse().getStatus()).isEqualTo(404);

        var outras = mvc.perform(delete("/api/Sessao/outras").header(HttpHeaders.AUTHORIZATION, a)).andReturn();
        assertThat(outras.getResponse().getStatus()).isEqualTo(200);
        assertThat(corpo(outras).get("data")).hasSize(1);
    }

    @Test
    void naoEncerraSessaoDeOutroUsuario() throws Exception {
        String admin = bearer("admin@contrato.quack");
        String caixa = bearer("caixa@contrato.quack");
        String idCaixa = idDaSessao(caixa);
        var r = mvc.perform(delete("/api/Sessao/" + idCaixa).header(HttpHeaders.AUTHORIZATION, admin)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/api/Auth/me").header(HttpHeaders.AUTHORIZATION, caixa)).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void logoutApagaASessaoDoBanco() throws Exception {
        String t = bearer("gerfin@contrato.quack");
        String id = idDaSessao(t);
        assertThat(mvc.perform(post("/api/Auth/logout").header(HttpHeaders.AUTHORIZATION, t)).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM Sessoes WHERE Id = ?", Integer.class, id)).isZero();
        assertThat(mvc.perform(get("/api/Auth/me").header(HttpHeaders.AUTHORIZATION, t)).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void inativarOUsuarioDerrubaTodasAsSessoesDele() throws Exception {
        jdbc.update("UPDATE Usuarios SET Status = 'ativo' WHERE Id = 'usr-ct-troca'");
        String admin = bearer("admin@contrato.quack");
        // o usuario-alvo usa a senha dele; troca@ e usado por outros testes, entao so conferimos a sessao
        String alvo = bearer("troca@contrato.quack");
        var r = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/Usuario/usr-ct-troca/status")
                .header(HttpHeaders.AUTHORIZATION, admin).contentType("application/json").content("{\"status\":\"inativo\"}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/api/Auth/me").header(HttpHeaders.AUTHORIZATION, alvo)).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM Sessoes WHERE UserId = 'usr-ct-troca'", Integer.class)).isZero();
        jdbc.update("UPDATE Usuarios SET Status = 'ativo' WHERE Id = 'usr-ct-troca'");
    }
}
