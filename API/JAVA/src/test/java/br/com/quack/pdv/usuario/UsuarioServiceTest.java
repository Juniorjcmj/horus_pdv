package br.com.quack.pdv.usuario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.quack.pdv.security.PasswordHasher;
import br.com.quack.pdv.sessao.repo.SessaoRepository;
import br.com.quack.pdv.shared.NegocioException;
import br.com.quack.pdv.usuario.app.UsuarioService;
import br.com.quack.pdv.usuario.domain.Usuario;
import br.com.quack.pdv.usuario.repo.UsuarioRepository;
import br.com.quack.pdv.usuario.web.UsuarioDto;
import br.com.quack.pdv.usuario.web.UsuarioRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

class UsuarioServiceTest {

    UsuarioRepository repo = mock(UsuarioRepository.class);
    SessaoRepository sessoes = mock(SessaoRepository.class);
    Clock clock = Clock.fixed(Instant.parse("2026-11-01T22:30:00.123Z"), ZoneOffset.UTC);
    UsuarioService service;

    @BeforeEach
    void preparar() {
        service = new UsuarioService(repo, sessoes, clock);
    }

    UsuarioRequest pedido(String cpf, String nome, String email, String role, String status, String senha, List<String> perfis) {
        return new UsuarioRequest("", cpf, nome, email, "11999", role, status, senha, perfis);
    }

    UsuarioRequest valido() {
        return pedido("111.444.777-35", "  João Silva ", " JOAO@Teste.COM ", "", "", "Senha#1234", null);
    }

    NegocioException recusa(Runnable acao) {
        Throwable t = org.assertj.core.api.Assertions.catchThrowable(acao::run);
        assertThat(t).isInstanceOf(NegocioException.class);
        NegocioException e = (NegocioException) t;
        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        return e;
    }

    // ---- criar ----

    @Test
    void criarMontaOUsuarioComIdPerfilPadraoENormalizacao() {
        UsuarioDto dto = service.criar(valido(), "emp-1", false);
        assertThat(dto.id()).isEqualTo("usr-" + clock.millis());
        assertThat(dto.companyId()).isEqualTo("emp-1");
        assertThat(dto.name()).isEqualTo("João Silva");
        assertThat(dto.email()).isEqualTo("joao@teste.com");
        assertThat(dto.role()).isEqualTo("atendente");
        assertThat(dto.status()).isEqualTo("ativo");
        assertThat(dto.createdAt()).isEqualTo("2026-11-01");
        assertThat(dto.lastLoginAt()).isEqualTo("-");
        assertThat(dto.mustChangePassword()).isTrue();
        ArgumentCaptor<Usuario> salvo = ArgumentCaptor.forClass(Usuario.class);
        verify(repo).save(salvo.capture());
        assertThat(PasswordHasher.verify("Senha#1234", salvo.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void criarValidaCpfNomeEmailESenha() {
        assertThat(recusa(() -> service.criar(pedido("123", "A", "a@x.com", "", "", "Senha#1234", null), "e", false))
                .getMessage()).isEqualTo("CPF/CNPJ invalido.");
        assertThat(recusa(() -> service.criar(pedido("11144477735", "  ", "a@x.com", "", "", "Senha#1234", null), "e", false))
                .getMessage()).isEqualTo("Nome e obrigatorio.");
        assertThat(recusa(() -> service.criar(pedido("11144477735", "A", "semarroba", "", "", "Senha#1234", null), "e", false))
                .getMessage()).isEqualTo("E-mail invalido.");
        assertThat(recusa(() -> service.criar(pedido("11144477735", "A", "a@x.com", "", "", "curta", null), "e", false))
                .getMessage()).isEqualTo("Senha deve ter no minimo 8 caracteres.");
        verify(repo, never()).save(any());
    }

    @Test
    void criarRecusaEmailDuplicadoNoSistemaECpfDuplicadoNaEmpresa() {
        when(repo.contarEmailDuplicado("", "joao@teste.com")).thenReturn(1);
        assertThat(recusa(() -> service.criar(valido(), "emp-1", false)).getMessage())
                .isEqualTo("Este e-mail já está cadastrado no sistema.");

        when(repo.contarEmailDuplicado("", "joao@teste.com")).thenReturn(0);
        when(repo.contarCpfDuplicado("", "emp-1", "111.444.777-35")).thenReturn(1);
        assertThat(recusa(() -> service.criar(valido(), "emp-1", false)).getMessage())
                .isEqualTo("Já existe um usuário com este CPF nesta empresa.");
    }

    @Test
    void soAdministradorDefinePerfisAdicionaisNaCriacao() {
        UsuarioRequest comFinanceiro = pedido("11144477735", "A", "a@x.com", "gerente", "", "Senha#1234", List.of("Financeiro", "lixo"));
        assertThat(service.criar(comFinanceiro, "e", false).perfisAdicionais()).isEmpty();
        assertThat(service.criar(comFinanceiro, "e", true).perfisAdicionais()).containsExactly("financeiro");
    }

    @Test
    void statusDiferenteDeInativoViraAtivo() {
        assertThat(service.criar(pedido("11144477735", "A", "a@x.com", "caixa", "bloqueado", "Senha#1234", null), "e", false).status())
                .isEqualTo("ativo");
        assertThat(service.criar(pedido("11144477735", "A", "a@x.com", "caixa", "inativo", "Senha#1234", null), "e", false).status())
                .isEqualTo("inativo");
    }

    // ---- editar ----

    Usuario existente() {
        Usuario u = new Usuario();
        u.setId("usr-9");
        u.setCompanyId("emp-1");
        u.setCpf("11144477735");
        u.setName("Antigo");
        u.setEmail("antigo@x.com");
        u.setRole("gerente");
        u.setStatus("ativo");
        u.setCreatedAt("2025-05-05");
        u.setLastLoginAt("2026-10-30T10:00:00.0000000Z");
        u.setPasswordHash("hash-antigo");
        u.setMustChangePassword(false);
        u.setPerfisAdicionais("financeiro");
        return u;
    }

    @Test
    void editarSemSenhaMantemOHashEOPerfisAdicionaisDoGerente() {
        Usuario atual = existente();
        when(repo.findByIdAndCompanyId("usr-9", "emp-1")).thenReturn(Optional.of(atual));
        Optional<UsuarioDto> r = service.atualizar("usr-9",
                pedido("11144477735", "Novo Nome", "novo@x.com", "caixa", "ativo", "", List.of()), "emp-1", false);
        assertThat(r).isPresent();
        assertThat(atual.getPasswordHash()).isEqualTo("hash-antigo");
        assertThat(atual.isMustChangePassword()).isFalse();
        assertThat(atual.getPerfisAdicionais()).isEqualTo("financeiro");
        assertThat(atual.getName()).isEqualTo("Novo Nome");
        assertThat(atual.getEmail()).isEqualTo("novo@x.com");
        assertThat(atual.getRole()).isEqualTo("caixa");
        assertThat(atual.getLastLoginAt()).isEqualTo("-"); // o .NET regrava LastLoginAt com "-"
        assertThat(atual.getCreatedAt()).isEqualTo("2025-05-05");
        assertThat(r.get().createdAt()).isEqualTo("2026-11-01"); // e devolve o registro montado (CreatedAt de hoje)
        verify(sessoes, never()).apagarTodasDoUsuario(anyString());
    }

    @Test
    void editarComSenhaNovaMarcaTrocaObrigatoria() {
        Usuario atual = existente();
        when(repo.findByIdAndCompanyId("usr-9", "emp-1")).thenReturn(Optional.of(atual));
        service.atualizar("usr-9", pedido("11144477735", "A", "a@x.com", "gerente", "ativo", "NovaSenha123", null), "emp-1", false);
        assertThat(PasswordHasher.verify("NovaSenha123", atual.getPasswordHash())).isTrue();
        assertThat(atual.isMustChangePassword()).isTrue();
    }

    @Test
    void administradorMudaOsPerfisAdicionais() {
        Usuario atual = existente();
        when(repo.findByIdAndCompanyId("usr-9", "emp-1")).thenReturn(Optional.of(atual));
        service.atualizar("usr-9", pedido("11144477735", "A", "a@x.com", "gerente", "ativo", "", List.of()), "emp-1", true);
        assertThat(atual.getPerfisAdicionais()).isEmpty();
    }

    @Test
    void editarParaInativoApagaTodasAsSessoes() {
        when(repo.findByIdAndCompanyId("usr-9", "emp-1")).thenReturn(Optional.of(existente()));
        service.atualizar("usr-9", pedido("11144477735", "A", "a@x.com", "gerente", "inativo", "", null), "emp-1", false);
        verify(sessoes).apagarTodasDoUsuario("usr-9");
    }

    @Test
    void editarUsuarioDeOutraEmpresaDevolveVazio() {
        when(repo.findByIdAndCompanyId("usr-9", "emp-2")).thenReturn(Optional.empty());
        assertThat(service.atualizar("usr-9", valido(), "emp-2", false)).isEmpty();
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void editarValidaDuplicidadeIgnorandoOProprioRegistro() {
        when(repo.findByIdAndCompanyId("usr-9", "emp-1")).thenReturn(Optional.of(existente()));
        when(repo.contarEmailDuplicado("usr-9", "outro@x.com")).thenReturn(1);
        assertThat(recusa(() -> service.atualizar("usr-9", pedido("11144477735", "A", "outro@x.com", "gerente", "ativo", "", null), "emp-1", false))
                .getMessage()).isEqualTo("Este e-mail já está cadastrado no sistema.");
    }

    // ---- status ----

    @Test
    void alterarStatusInativaEApagaSessoes() {
        Usuario u = existente();
        when(repo.findByIdAndCompanyId("usr-9", "emp-1")).thenReturn(Optional.of(u));
        assertThat(service.alterarStatus("usr-9", "inativo", "emp-1").get().status()).isEqualTo("inativo");
        verify(sessoes).apagarTodasDoUsuario("usr-9");
    }

    @Test
    void alterarStatusQualquerOutroValorViraAtivo() {
        when(repo.findByIdAndCompanyId("usr-9", "emp-1")).thenReturn(Optional.of(existente()));
        assertThat(service.alterarStatus("usr-9", "qualquer", "emp-1").get().status()).isEqualTo("ativo");
        verify(sessoes, never()).apagarTodasDoUsuario(anyString());
        when(repo.findByIdAndCompanyId("usr-9", "emp-2")).thenReturn(Optional.empty());
        assertThat(service.alterarStatus("usr-9", "ativo", "emp-2")).isEmpty();
    }

    // ---- listagens ----

    @Test
    void supervisoresSaoSoAdministradoresEGerentesAtivos() {
        Usuario g = existente();
        when(repo.findByCompanyIdAndStatusAndRoleInOrderByName("emp-1", "ativo", List.of("administrador", "gerente")))
                .thenReturn(List.of(g));
        List<UsuarioService.Supervisor> r = service.supervisores("emp-1");
        assertThat(r).containsExactly(new UsuarioService.Supervisor("usr-9", "Antigo", "gerente"));
    }

    @Test
    void listarDevolveOsUsuariosDaEmpresa() {
        when(repo.findByCompanyIdOrderByName("emp-1")).thenReturn(List.of(existente()));
        assertThat(service.listar("emp-1")).hasSize(1);
        assertThat(service.listar("emp-1").get(0).perfisAdicionais()).containsExactly("financeiro");
    }

    @Test
    void erroDeValidacaoNaoViraExcecaoGenerica() {
        assertThatThrownBy(() -> service.criar(pedido("", "A", "a@x.com", "", "", "Senha#1234", null), "e", false))
                .isInstanceOf(NegocioException.class);
    }
}
