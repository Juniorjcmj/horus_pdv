package br.com.quack.pdv.usuario.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import br.com.quack.pdv.security.PasswordHasher;
import br.com.quack.pdv.usuario.domain.Usuario;
import br.com.quack.pdv.usuario.repo.UsuarioRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

class ValidadorSupervisorTest {

    private static final Instant AGORA_INSTANT = Instant.parse("2026-02-15T14:00:00Z");
    private static final OffsetDateTime AGORA = OffsetDateTime.ofInstant(AGORA_INSTANT, ZoneOffset.UTC);
    private static final String SENHA_CORRETA = "Senha#Quack2026";
    private static final String HASH_CORRETO = PasswordHasher.hash(SENHA_CORRETA);

    private UsuarioRepository usuarios;
    private ValidadorSupervisor validador;

    @BeforeEach
    void setUp() {
        usuarios = mock(UsuarioRepository.class);
        Clock clock = Clock.fixed(AGORA_INSTANT, ZoneOffset.UTC);
        validador = new ValidadorSupervisor(usuarios, TransactionOperations.withoutTransaction(), clock);
    }

    @Test
    void rejeitaCredenciaisEmBranco() {
        assertThat(validador.validar("", SENHA_CORRETA, "emp-1"))
                .isEqualTo(new ValidadorSupervisor.Resultado(false,
                        "Identificação e senha do gerente/supervisor são obrigatórias.", null));
        assertThat(validador.validar("usr-1", "   ", "emp-1"))
                .isEqualTo(new ValidadorSupervisor.Resultado(false,
                        "Identificação e senha do gerente/supervisor são obrigatórias.", null));
        assertThat(validador.validar(null, null, "emp-1"))
                .isEqualTo(new ValidadorSupervisor.Resultado(false,
                        "Identificação e senha do gerente/supervisor são obrigatórias.", null));
    }

    @Test
    void rejeitaUsuarioInexistenteOuDeOutraEmpresa() {
        when(usuarios.findByIdAndCompanyId("usr-outro", "emp-1")).thenReturn(Optional.empty());

        ValidadorSupervisor.Resultado res = validador.validar("usr-outro", SENHA_CORRETA, "emp-1");

        assertThat(res.valido()).isFalse();
        assertThat(res.mensagem()).isEqualTo("Gerente não encontrado ou com cadastro inativo.");
        assertThat(res.supervisor()).isNull();
    }

    @Test
    void rejeitaUsuarioInativo() {
        Usuario u = criarUsuario("usr-inativo", "emp-1", "gerente", "inativo");
        when(usuarios.findByIdAndCompanyId("usr-inativo", "emp-1")).thenReturn(Optional.of(u));

        ValidadorSupervisor.Resultado res = validador.validar("usr-inativo", SENHA_CORRETA, "emp-1");

        assertThat(res.valido()).isFalse();
        assertThat(res.mensagem()).isEqualTo("Gerente não encontrado ou com cadastro inativo.");
        assertThat(res.supervisor()).isNull();
    }

    @Test
    void rejeitaPerfilSemPermissaoDeSupervisor() {
        for (String perfil : new String[]{"atendente", "caixa", "estoquista", "financeiro"}) {
            Usuario u = criarUsuario("usr-p", "emp-1", perfil, "ativo");
            when(usuarios.findByIdAndCompanyId("usr-p", "emp-1")).thenReturn(Optional.of(u));

            ValidadorSupervisor.Resultado res = validador.validar("usr-p", SENHA_CORRETA, "emp-1");

            assertThat(res.valido()).isFalse();
            assertThat(res.mensagem()).isEqualTo("Usuário selecionado não possui perfil de gerente ou administrador.");
            assertThat(res.supervisor()).isNull();
        }
    }

    @Test
    void rejeitaContaTemporariamenteBloqueada() {
        Usuario u = criarUsuario("usr-lock", "emp-1", "gerente", "ativo");
        u.setLockoutEnd(AGORA.plusMinutes(3));
        when(usuarios.findByIdAndCompanyId("usr-lock", "emp-1")).thenReturn(Optional.of(u));

        ValidadorSupervisor.Resultado res = validador.validar("usr-lock", SENHA_CORRETA, "emp-1");

        assertThat(res.valido()).isFalse();
        assertThat(res.mensagem()).isEqualTo("Conta do gerente temporariamente bloqueada por excesso de tentativas.");
        assertThat(res.supervisor()).isNull();
    }

    @Test
    void rejeitaSenhaErradaERegistraTentativaFalha() {
        Usuario u = criarUsuario("usr-ger", "emp-1", "gerente", "ativo");
        when(usuarios.findByIdAndCompanyId("usr-ger", "emp-1")).thenReturn(Optional.of(u));

        ValidadorSupervisor.Resultado res = validador.validar("usr-ger", "senha-errada", "emp-1");

        assertThat(res.valido()).isFalse();
        assertThat(res.mensagem()).isEqualTo("Senha do gerente incorreta.");
        assertThat(res.supervisor()).isNull();
        verify(usuarios).findByIdAndCompanyId("usr-ger", "emp-1");
        verify(usuarios).registrarTentativaFalha("usr-ger", AGORA, 5, AGORA.plusMinutes(10));
        verifyNoMoreInteractions(usuarios);
    }

    @Test
    void aprovaGerenteEAdministradorAtivosComSenhaCorreta() {
        Usuario ger = criarUsuario("usr-ger", "emp-1", "  Gerente ", "ativo");
        when(usuarios.findByIdAndCompanyId("usr-ger", "emp-1")).thenReturn(Optional.of(ger));

        ValidadorSupervisor.Resultado resGer = validador.validar("usr-ger", SENHA_CORRETA, "emp-1");
        assertThat(resGer.valido()).isTrue();
        assertThat(resGer.mensagem()).isEqualTo("Gerente validado com sucesso.");
        assertThat(resGer.supervisor()).isNotNull();
        assertThat(resGer.supervisor().id()).isEqualTo("usr-ger");
        assertThat(resGer.supervisor().name()).isEqualTo("Gerente Teste");

        Usuario adm = criarUsuario("usr-adm", "emp-1", "ADMINISTRADOR", "ativo");
        when(usuarios.findByIdAndCompanyId("usr-adm", "emp-1")).thenReturn(Optional.of(adm));

        ValidadorSupervisor.Resultado resAdm = validador.validar("usr-adm", SENHA_CORRETA, "emp-1");
        assertThat(resAdm.valido()).isTrue();
        assertThat(resAdm.mensagem()).isEqualTo("Gerente validado com sucesso.");
        assertThat(resAdm.supervisor()).isNotNull();
        assertThat(resAdm.supervisor().id()).isEqualTo("usr-adm");
    }

    private static Usuario criarUsuario(String id, String companyId, String role, String status) {
        Usuario u = new Usuario();
        u.setId(id);
        u.setCompanyId(companyId);
        u.setCpf("12345678901");
        u.setName("Gerente Teste");
        u.setEmail("ger@teste.com");
        u.setPhone("");
        u.setRole(role);
        u.setStatus(status);
        u.setCreatedAt("2026-01-01");
        u.setLastLoginAt("-");
        u.setPasswordHash(HASH_CORRETO);
        u.setMustChangePassword(false);
        u.setPerfisAdicionais("");
        return u;
    }
}
