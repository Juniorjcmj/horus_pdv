package br.com.quack.pdv.banco;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.BancoIT;
import br.com.quack.pdv.security.PasswordHasher;
import br.com.quack.pdv.usuario.domain.Usuario;
import br.com.quack.pdv.usuario.repo.UsuarioRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** No perfil dev o Flyway tambem aplica db/dev e o usuario de desenvolvimento aparece (banco proprio). */
@SpringBootTest
@ActiveProfiles({"it", "dev"})
class SeedDevIT {

    static {
        BancoIT.criarBanco("quackdev");
    }

    @DynamicPropertySource
    static void bancoDev(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> BancoIT.urlDoBanco("quackdev"));
        registro.add("spring.datasource.username", BancoIT.SQL::getUsername);
        registro.add("spring.datasource.password", BancoIT.SQL::getPassword);
    }

    @Autowired UsuarioRepository usuarios;

    @Test
    void perfilDevTemOUsuarioDeDesenvolvimento() {
        Usuario dev = usuarios.findById("usr-001").orElseThrow();
        assertThat(dev.getEmail()).isEqualTo("flavio@hpdv.com.br");
        assertThat(dev.getRole()).isEqualTo("administrador");
        assertThat(PasswordHasher.verify("Senha#Quack2026", dev.getPasswordHash())).isTrue();
    }
}
