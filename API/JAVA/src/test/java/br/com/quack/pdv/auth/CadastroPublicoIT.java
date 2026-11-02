package br.com.quack.pdv.auth;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.ApiIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Cadastro publico de empresa + administrador contra o banco real. */
class CadastroPublicoIT extends ApiIT {

    MvcResult registrar(String cnpj, String email, String senha, String confirma) throws Exception {
        String json = "{\"cnpj\":\"" + cnpj + "\",\"name\":\"Mercado Teste\",\"email\":\"" + email + "\",\"phone\":\"11999\","
                + "\"password\":\"" + senha + "\",\"confirmPassword\":\"" + confirma + "\"}";
        return mvc.perform(MockMvcRequestBuilders.post("/api/Auth/register").contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    @Test
    void cadastroCriaEmpresaPendenteEUsuarioAdministradorQueAindaNaoEntra() throws Exception {
        jdbc.update("UPDATE Empresas SET RequireApprovalForNewCompanies = 1 WHERE Id = 'empresa-principal'");
        MvcResult r = registrar("45.997.418/0001-53", "Dono1@Mercado.com", "Senha#1234", "Senha#1234");
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        var usuario = corpo(r).get("data");
        assertThat(corpo(r).get("message").asText()).isEqualTo("Cadastro criado com sucesso.");
        assertThat(usuario.get("id").asText()).matches("usr-\\d+");
        assertThat(usuario.get("companyId").asText()).matches("emp-\\d+");
        assertThat(usuario.get("role").asText()).isEqualTo("administrador");
        assertThat(usuario.get("email").asText()).isEqualTo("dono1@mercado.com");
        assertThat(usuario.get("mustChangePassword").asBoolean()).isFalse();

        String companyId = usuario.get("companyId").asText();
        assertThat(jdbc.queryForObject("SELECT Status FROM Empresas WHERE Id = ?", String.class, companyId)).isEqualTo("pendente");
        assertThat(jdbc.queryForObject("SELECT FantasyName FROM Empresas WHERE Id = ?", String.class, companyId)).isEqualTo("Mercado Teste");

        MvcResult login = login("dono1@mercado.com", "Senha#1234");
        assertThat(login.getResponse().getStatus()).isEqualTo(401);
        assertThat(corpo(login).get("message").asText())
                .isEqualTo("O cadastro da sua empresa está em análise e aguarda aprovação pelo administrador do sistema.");
    }

    @Test
    void comAprovacaoDesligadaAEmpresaNasceAprovadaEOAdministradorEntra() throws Exception {
        jdbc.update("UPDATE Empresas SET RequireApprovalForNewCompanies = 0 WHERE Id = 'empresa-principal'");
        try {
            MvcResult r = registrar("12.345.678/0001-95", "dono2@mercado.com", "Senha#1234", "Senha#1234");
            assertThat(r.getResponse().getStatus()).isEqualTo(201);
            String companyId = corpo(r).get("data").get("companyId").asText();
            assertThat(jdbc.queryForObject("SELECT Status FROM Empresas WHERE Id = ?", String.class, companyId)).isEqualTo("aprovada");
            assertThat(login("dono2@mercado.com", "Senha#1234").getResponse().getStatus()).isEqualTo(200);
        } finally {
            jdbc.update("UPDATE Empresas SET RequireApprovalForNewCompanies = 1 WHERE Id = 'empresa-principal'");
        }
    }

    @Test
    void recusaCnpjInvalidoSenhasDiferentesCnpjEEmailRepetidos() throws Exception {
        assertThat(corpo(registrar("11.111.111/0001-11", "x1@mercado.com", "Senha#1234", "Senha#1234")).get("message").asText())
                .isEqualTo("CNPJ inválido.");
        assertThat(corpo(registrar("11.222.333/0001-81", "x2@mercado.com", "Senha#1234", "Outra#1234")).get("message").asText())
                .isEqualTo("A confirmação de senha não confere.");

        // CNPJ ja usado pela empresa aprovada do seed
        MvcResult cnpj = registrar("11.222.333/0001-81", "x3@mercado.com", "Senha#1234", "Senha#1234");
        assertThat(cnpj.getResponse().getStatus()).isEqualTo(400);
        assertThat(corpo(cnpj).get("message").asText())
                .isEqualTo("Este CNPJ já possui uma empresa cadastrada no sistema. Faça login com sua conta.");

        // e-mail ja usado
        MvcResult email = registrar("11.444.777/0001-61", "ADMIN@contrato.quack", "Senha#1234", "Senha#1234");
        assertThat(email.getResponse().getStatus()).isEqualTo(400);
        assertThat(corpo(email).get("message").asText()).isEqualTo("Este e-mail já está cadastrado no sistema. Faça login para acessar.");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM Empresas WHERE Cnpj = '11.444.777/0001-61'", Integer.class)).isZero();
    }
}
