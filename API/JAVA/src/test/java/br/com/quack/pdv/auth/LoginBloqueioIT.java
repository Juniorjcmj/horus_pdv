package br.com.quack.pdv.auth;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.ApiIT;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** Login e bloqueio por tentativas contra o SQL Server real (SQL atomico, janela e lockout persistidos). */
class LoginBloqueioIT extends ApiIT {

    void zerar(String email) {
        jdbc.update("UPDATE Usuarios SET FailedLoginAttempts = 0, FirstFailedLoginAt = NULL, LockoutEnd = NULL WHERE Email = ?", email);
    }

    @Test
    void loginValidoGravaSessaoELastLoginAtNoFormatoDotNet() throws Exception {
        zerar("caixa@contrato.quack");
        MvcResult r = login("Caixa@Contrato.Quack ", SENHA);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        var dados = corpo(r).get("data");
        assertThat(dados.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(dados.get("expiresInSeconds").asInt()).isEqualTo(28800);
        String sessionId = dados.get("sessionId").asText();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM Sessoes WHERE Id = ?", Integer.class, sessionId)).isEqualTo(1);
        String ultimo = jdbc.queryForObject("SELECT LastLoginAt FROM Usuarios WHERE Email = 'caixa@contrato.quack'", String.class);
        assertThat(ultimo).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{7}Z");
        assertThat(dados.get("user").get("lastLoginAt").asText()).isEqualTo(ultimo);
    }

    @Test
    void quintaSenhaErradaBloqueiaPor10MinutosEOLoginCertoTambemFicaBloqueado() throws Exception {
        String email = "bloqueio@contrato.quack";
        zerar(email);
        for (int i = 1; i <= 4; i++) {
            MvcResult r = login(email, "errada" + i);
            assertThat(r.getResponse().getStatus()).as("tentativa " + i).isEqualTo(401);
            assertThat(corpo(r).get("message").asText()).isEqualTo("E-mail ou senha inválidos.");
        }
        MvcResult quinta = login(email, "errada5");
        assertThat(quinta.getResponse().getStatus()).isEqualTo(429);
        assertThat(corpo(quinta).get("message").asText()).isEqualTo("E-mail ou senha inválidos.");
        String ate = corpo(quinta).get("data").get("lockedUntil").asText();
        assertThat(ate).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{7}\\+00:00");

        MvcResult comSenhaCerta = login(email, SENHA);
        assertThat(comSenhaCerta.getResponse().getStatus()).isEqualTo(429);
        assertThat(corpo(comSenhaCerta).get("message").asText())
                .isEqualTo("Muitas tentativas inválidas. Aguarde alguns minutos para tentar novamente.");
        assertThat(corpo(comSenhaCerta).get("data").get("lockedUntil").asText()).isEqualTo(ate);
    }

    @Test
    void bloqueioVencidoLiberaOLoginEZeraOContador() throws Exception {
        String email = "bloqueio2@contrato.quack";
        zerar(email);
        jdbc.update("UPDATE Usuarios SET FailedLoginAttempts = 5, FirstFailedLoginAt = DATEADD(MINUTE, -11, SYSDATETIMEOFFSET()), "
                + "LockoutEnd = DATEADD(MINUTE, -1, SYSDATETIMEOFFSET()) WHERE Email = ?", email);
        assertThat(login(email, SENHA).getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT FailedLoginAttempts FROM Usuarios WHERE Email = ?", Integer.class, email)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM Usuarios WHERE Email = ? AND LockoutEnd IS NULL AND FirstFailedLoginAt IS NULL",
                Integer.class, email)).isEqualTo(1);
    }

    @Test
    void janelaDe15MinutosExpiradaRecomecaAContagemEUmLoginCertoZeraTudo() throws Exception {
        String email = "troca@contrato.quack";
        zerar(email);
        jdbc.update("UPDATE Usuarios SET FailedLoginAttempts = 4, FirstFailedLoginAt = DATEADD(MINUTE, -16, SYSDATETIMEOFFSET()) WHERE Email = ?", email);
        MvcResult erro = login(email, "errada");
        assertThat(erro.getResponse().getStatus()).isEqualTo(401); // sem bloqueio: a janela velha foi zerada
        assertThat(jdbc.queryForObject("SELECT FailedLoginAttempts FROM Usuarios WHERE Email = ?", Integer.class, email)).isEqualTo(1);

        assertThat(login(email, SENHA).getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT FailedLoginAttempts FROM Usuarios WHERE Email = ?", Integer.class, email)).isZero();
    }

    @Test
    void usuarioInativoEEmpresaForaDeAprovadaNaoEntram() throws Exception {
        assertThat(login("inativo@contrato.quack", SENHA).getResponse().getStatus()).isEqualTo(401);
        MvcResult pendente = login("adm.pendente@contrato.quack", SENHA);
        assertThat(pendente.getResponse().getStatus()).isEqualTo(401);
        assertThat(corpo(pendente).get("message").asText())
                .isEqualTo("O cadastro da sua empresa está em análise e aguarda aprovação pelo administrador do sistema.");
        MvcResult bloqueada = login("adm.bloqueada@contrato.quack", SENHA);
        assertThat(corpo(bloqueada).get("message").asText())
                .isEqualTo("O acesso da sua empresa está suspenso/bloqueado. Motivo: Inadimplência");
        assertThat(login("adm.aprovada@contrato.quack", SENHA).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void emailInexistenteNaoBloqueiaNemRevelaNada() throws Exception {
        MvcResult r = login("ninguem@contrato.quack", SENHA);
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(corpo(r).get("message").asText()).isEqualTo("E-mail ou senha inválidos.");
    }
}
