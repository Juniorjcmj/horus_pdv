package br.com.quack.pdv.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.quack.pdv.auth.app.AutenticacaoService;
import br.com.quack.pdv.empresa.domain.Empresa;
import br.com.quack.pdv.empresa.repo.EmpresaRepository;
import br.com.quack.pdv.security.PasswordHasher;
import br.com.quack.pdv.sessao.domain.Sessao;
import br.com.quack.pdv.sessao.repo.SessaoRepository;
import br.com.quack.pdv.shared.NegocioException;
import br.com.quack.pdv.usuario.app.UsuarioService;
import br.com.quack.pdv.usuario.domain.Usuario;
import br.com.quack.pdv.usuario.repo.UsuarioRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionOperations;

class AutenticacaoServiceTest {

    static final Instant T0 = Instant.parse("2026-11-01T12:00:00Z");
    static final String SENHA = "Senha#Quack2026";

    UsuarioRepository usuarios = mock(UsuarioRepository.class);
    SessaoRepository sessoes = mock(SessaoRepository.class);
    EmpresaRepository empresas = mock(EmpresaRepository.class);
    UsuarioService usuarioService = mock(UsuarioService.class);
    Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    AutenticacaoService service;
    String hash = PasswordHasher.hash(SENHA);

    @BeforeEach
    void preparar() {
        service = new AutenticacaoService(usuarios, sessoes, empresas, usuarioService,
                TransactionOperations.withoutTransaction(), clock);
    }

    Usuario usuario(String companyId) {
        Usuario u = new Usuario();
        u.setId("usr-1");
        u.setCompanyId(companyId);
        u.setCpf("11111111111");
        u.setName("Ana");
        u.setEmail("ana@x.com");
        u.setRole("gerente");
        u.setStatus("ativo");
        u.setCreatedAt("2026-01-01");
        u.setPasswordHash(hash);
        u.setMustChangePassword(false);
        return u;
    }

    NegocioException falha(String email, String senha) {
        Throwable t = org.assertj.core.api.Assertions.catchThrowable(() -> service.login(email, senha, "1.1.1.1", "UA"));
        assertThat(t).isInstanceOf(NegocioException.class);
        return (NegocioException) t;
    }

    @Test
    void loginValidoCriaSessaoEAtualizaUltimoLogin() {
        when(usuarios.findByEmail("ana@x.com")).thenReturn(Optional.of(usuario("empresa-principal")));

        AutenticacaoService.LoginSucesso r = service.login("  ANA@X.com ", SENHA, "200.1.1.1",
                "Mozilla/5.0 (iPhone) Mobile Safari/604");

        assertThat(r.usuario().email()).isEqualTo("ana@x.com");
        assertThat(r.usuario().lastLoginAt()).isEqualTo("2026-11-01T12:00:00.0000000Z");
        Sessao s = r.sessao();
        assertThat(s.getId()).matches("sess-[0-9a-f]{32}");
        assertThat(s.getUserId()).isEqualTo("usr-1");
        assertThat(s.getDevice()).isEqualTo("Navegador - Safari");
        assertThat(s.getPlatform()).isEqualTo("mobile");
        assertThat(s.getLocation()).isEqualTo("Localização indisponível");
        assertThat(s.getLastActive()).isEqualTo("Agora mesmo");
        assertThat(s.getIp()).isEqualTo("200.1.1.1");
        assertThat(s.getCreatedAt()).isEqualTo(OffsetDateTime.ofInstant(T0, ZoneOffset.UTC));
        verify(usuarios).registrarLoginComSucesso("usr-1", "2026-11-01T12:00:00.0000000Z");
        verify(sessoes).save(s);
    }

    @Test
    void plataformaDesktopSemMobileNoUserAgent() {
        when(usuarios.findByEmail("ana@x.com")).thenReturn(Optional.of(usuario("empresa-principal")));
        assertThat(service.login("ana@x.com", SENHA, "ip", "Mozilla Chrome").sessao().getPlatform()).isEqualTo("desktop");
    }

    @Test
    void usuarioInexistenteRecebe401SemRegistrarTentativa() {
        when(usuarios.findByEmail(anyString())).thenReturn(Optional.empty());
        NegocioException e = falha("nao@existe.com", SENHA);
        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(e.getMessage()).isEqualTo("E-mail ou senha inválidos.");
        verify(usuarios, never()).registrarTentativaFalha(anyString(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void senhaErradaRecebe401ERegistraTentativa() {
        when(usuarios.findByEmail("ana@x.com")).thenReturn(Optional.of(usuario("empresa-principal")));
        when(usuarios.buscarFimBloqueio("usr-1")).thenReturn(Optional.empty());
        NegocioException e = falha("ana@x.com", "errada");
        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(e.getMessage()).isEqualTo("E-mail ou senha inválidos.");
        assertThat(e.getData()).isNull();
        ArgumentCaptor<OffsetDateTime> fim = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(usuarios).registrarTentativaFalha(eq("usr-1"), eq(OffsetDateTime.ofInstant(T0, ZoneOffset.UTC)), eq(5), fim.capture());
        assertThat(fim.getValue()).isEqualTo(OffsetDateTime.ofInstant(T0.plusSeconds(600), ZoneOffset.UTC));
    }

    @Test
    void usuarioInativoRecebe401ComSenhaCerta() {
        Usuario u = usuario("empresa-principal");
        u.setStatus("inativo");
        when(usuarios.findByEmail("ana@x.com")).thenReturn(Optional.of(u));
        assertThat(falha("ana@x.com", SENHA).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void quintaTentativaRecebe429ComLockedUntil() {
        when(usuarios.findByEmail("ana@x.com")).thenReturn(Optional.of(usuario("empresa-principal")));
        OffsetDateTime ate = OffsetDateTime.ofInstant(T0.plusSeconds(600), ZoneOffset.UTC);
        when(usuarios.buscarFimBloqueio("usr-1")).thenReturn(Optional.of(ate));
        NegocioException e = falha("ana@x.com", "errada");
        assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(e.getMessage()).isEqualTo("E-mail ou senha inválidos.");
        assertThat(e.getData()).isEqualTo(Map.of("lockedUntil", "2026-11-01T12:10:00.0000000+00:00"));
    }

    @Test
    void contaBloqueadaRecebe429SemVerificarASenha() {
        Usuario u = usuario("empresa-principal");
        u.setLockoutEnd(OffsetDateTime.ofInstant(T0.plusSeconds(300), ZoneOffset.UTC));
        u.setFirstFailedLoginAt(OffsetDateTime.ofInstant(T0.minusSeconds(60), ZoneOffset.UTC));
        when(usuarios.findByEmail("ana@x.com")).thenReturn(Optional.of(u));
        NegocioException e = falha("ana@x.com", SENHA); // mesmo com a senha certa
        assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(e.getMessage()).isEqualTo("Muitas tentativas inválidas. Aguarde alguns minutos para tentar novamente.");
        assertThat(e.getData()).isEqualTo(Map.of("lockedUntil", "2026-11-01T12:05:00.0000000+00:00"));
        verify(sessoes, never()).save(any());
    }

    @Test
    void bloqueioVencidoNaoImpedeOLoginEJanelaExpiradaZeraOContador() {
        Usuario u = usuario("empresa-principal");
        u.setFailedLoginAttempts(4);
        u.setFirstFailedLoginAt(OffsetDateTime.ofInstant(T0.minusSeconds(16 * 60), ZoneOffset.UTC));
        u.setLockoutEnd(OffsetDateTime.ofInstant(T0.minusSeconds(60), ZoneOffset.UTC));
        when(usuarios.findByEmail("ana@x.com")).thenReturn(Optional.of(u));
        assertThat(service.login("ana@x.com", SENHA, "ip", "UA").usuario().id()).isEqualTo("usr-1");
        verify(usuarios).zerarBloqueio("usr-1");
    }

    @Test
    void janelaDentroDos15MinutosNaoZera() {
        Usuario u = usuario("empresa-principal");
        u.setFirstFailedLoginAt(OffsetDateTime.ofInstant(T0.minusSeconds(14 * 60), ZoneOffset.UTC));
        when(usuarios.findByEmail("ana@x.com")).thenReturn(Optional.of(u));
        service.login("ana@x.com", SENHA, "ip", "UA");
        verify(usuarios, never()).zerarBloqueio(anyString());
    }

    Empresa empresa(String status, String motivo) {
        Empresa e = new Empresa();
        e.setId("emp-1");
        e.setStatus(status);
        e.setRejectionReason(motivo);
        return e;
    }

    @Test
    void situacaoDaEmpresaBloqueiaOLoginComMensagemPropria() {
        when(usuarios.findByEmail("ana@x.com")).thenReturn(Optional.of(usuario("emp-1")));

        when(empresas.findById("emp-1")).thenReturn(Optional.of(empresa("pendente", null)));
        assertThat(falha("ana@x.com", SENHA).getMessage())
                .isEqualTo("O cadastro da sua empresa está em análise e aguarda aprovação pelo administrador do sistema.");

        when(empresas.findById("emp-1")).thenReturn(Optional.of(empresa("rejeitada", "CNPJ divergente")));
        assertThat(falha("ana@x.com", SENHA).getMessage())
                .isEqualTo("O cadastro da sua empresa foi recusado. Motivo: CNPJ divergente");

        when(empresas.findById("emp-1")).thenReturn(Optional.of(empresa("rejeitada", null)));
        assertThat(falha("ana@x.com", SENHA).getMessage()).isEqualTo("O cadastro da sua empresa foi recusado.");

        when(empresas.findById("emp-1")).thenReturn(Optional.of(empresa("bloqueada", "Inadimplência")));
        NegocioException e = falha("ana@x.com", SENHA);
        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(e.getMessage()).isEqualTo("O acesso da sua empresa está suspenso/bloqueado. Motivo: Inadimplência");
    }

    @Test
    void empresaAprovadaOuSemRegistroEmpresaPrincipalNaoBloqueiam() {
        when(usuarios.findByEmail("ana@x.com")).thenReturn(Optional.of(usuario("emp-1")));
        when(empresas.findById("emp-1")).thenReturn(Optional.of(empresa("aprovada", null)));
        service.login("ana@x.com", SENHA, "ip", "UA");
        when(empresas.findById("emp-1")).thenReturn(Optional.empty());
        service.login("ana@x.com", SENHA, "ip", "UA");

        when(usuarios.findByEmail("ana@x.com")).thenReturn(Optional.of(usuario("empresa-principal")));
        service.login("ana@x.com", SENHA, "ip", "UA");
        verify(empresas, never()).findById("empresa-principal");
    }

    @Test
    void trocarSenhaValidaEApagaSessoes() {
        when(usuarios.findByIdAndCompanyId("usr-1", "empresa-principal")).thenReturn(Optional.of(usuario("empresa-principal")));
        assertThat(service.trocarSenha("usr-1", "empresa-principal", SENHA, "NovaSenha123")).isTrue();
        ArgumentCaptor<String> novoHash = ArgumentCaptor.forClass(String.class);
        verify(usuarios).trocarSenha(eq("usr-1"), novoHash.capture());
        assertThat(PasswordHasher.verify("NovaSenha123", novoHash.getValue())).isTrue();
        verify(sessoes).apagarTodasDoUsuario("usr-1");
    }

    @Test
    void trocarSenhaComSenhaAtualErradaOuInativoDevolveFalso() {
        Usuario inativo = usuario("empresa-principal");
        inativo.setStatus("inativo");
        when(usuarios.findByIdAndCompanyId("usr-1", "empresa-principal")).thenReturn(Optional.of(usuario("empresa-principal")));
        assertThat(service.trocarSenha("usr-1", "empresa-principal", "errada", "NovaSenha123")).isFalse();
        when(usuarios.findByIdAndCompanyId("usr-1", "empresa-principal")).thenReturn(Optional.of(inativo));
        assertThat(service.trocarSenha("usr-1", "empresa-principal", SENHA, "NovaSenha123")).isFalse();
        when(usuarios.findByIdAndCompanyId("usr-1", "empresa-principal")).thenReturn(Optional.empty());
        assertThat(service.trocarSenha("usr-1", "empresa-principal", SENHA, "NovaSenha123")).isFalse();
        verify(sessoes, never()).apagarTodasDoUsuario(anyString());
    }

    @Test
    void trocarSenhaCurtaRecebe400() {
        assertThatThrownBy(() -> service.trocarSenha("usr-1", "empresa-principal", SENHA, "curta"))
                .isInstanceOfSatisfying(NegocioException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("A nova senha deve ter no minimo 8 caracteres.");
                });
    }

    @Test
    void usuarioAtivoSoDevolveAtivos() {
        Usuario inativo = usuario("empresa-principal");
        inativo.setStatus("inativo");
        when(usuarios.findByIdAndCompanyId("usr-1", "empresa-principal")).thenReturn(Optional.of(inativo));
        assertThat(service.usuarioAtivo("usr-1", "empresa-principal")).isEmpty();
        when(usuarios.findByIdAndCompanyId("usr-1", "empresa-principal")).thenReturn(Optional.of(usuario("empresa-principal")));
        assertThat(service.usuarioAtivo("usr-1", "empresa-principal")).isPresent();
    }
}
