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

import br.com.quack.pdv.auth.app.RecuperacaoSenhaService;
import br.com.quack.pdv.auth.domain.PasswordResetToken;
import br.com.quack.pdv.auth.repo.PasswordResetTokenRepository;
import br.com.quack.pdv.config.QuackProperties;
import br.com.quack.pdv.empresa.repo.EmpresaRepository;
import br.com.quack.pdv.security.PasswordHasher;
import br.com.quack.pdv.security.TokenRedefinicao;
import br.com.quack.pdv.sessao.repo.SessaoRepository;
import br.com.quack.pdv.shared.NegocioException;
import br.com.quack.pdv.usuario.domain.Usuario;
import br.com.quack.pdv.usuario.repo.UsuarioRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

class RecuperacaoSenhaServiceTest {

    static final Instant T0 = Instant.parse("2026-11-01T12:00:00Z");
    static final OffsetDateTime AGORA = OffsetDateTime.ofInstant(T0, ZoneOffset.UTC);

    UsuarioRepository usuarios = mock(UsuarioRepository.class);
    SessaoRepository sessoes = mock(SessaoRepository.class);
    PasswordResetTokenRepository tokens = mock(PasswordResetTokenRepository.class);
    EmpresaRepository empresas = mock(EmpresaRepository.class);
    QuackProperties props = new QuackProperties();
    RecuperacaoSenhaService service;

    @BeforeEach
    void preparar() {
        props.getAuth().setJwtSecret("segredo-de-teste-com-mais-de-32-caracteres-aqui");
        service = new RecuperacaoSenhaService(usuarios, sessoes, tokens, empresas, props,
                TransactionOperations.withoutTransaction(), Clock.fixed(T0, ZoneOffset.UTC));
    }

    Usuario usuario() {
        Usuario u = new Usuario();
        u.setId("usr-1");
        u.setCompanyId("emp-1");
        u.setCpf("11222333000181");
        u.setName("Ana");
        u.setEmail("ana@x.com");
        u.setRole("administrador");
        u.setStatus("ativo");
        u.setCreatedAt("2026-01-01");
        u.setPasswordHash("h");
        return u;
    }

    // ---- pedido ----

    @Test
    void pedidoParaEmailInexistenteNaoRevelaNada() {
        when(usuarios.buscarParaRedefinicao("11222333000181", "nao@x.com")).thenReturn(Optional.empty());
        RecuperacaoSenhaService.PedidoRedefinicao r = service.solicitar("11.222.333/0001-81", " NAO@x.com ", "ip", "UA");
        assertThat(r).isEqualTo(new RecuperacaoSenhaService.PedidoRedefinicao(true, null, null, null));
        verify(tokens, never()).saveAndFlush(any());
        verify(tokens).apagarVencidosAntesDe(AGORA.minusDays(7));
    }

    @Test
    void pedidoValidoGravaSoOHashInvalidaOsAnterioresEMascaraOEmail() {
        when(usuarios.buscarParaRedefinicao("11222333000181", "ana@x.com")).thenReturn(Optional.of(usuario()));
        RecuperacaoSenhaService.PedidoRedefinicao r = service.solicitar("11.222.333/0001-81", "Ana@x.com", "1.1.1.1", "Mozilla Firefox/1");

        assertThat(r.accepted()).isTrue();
        assertThat(r.maskedEmail()).isEqualTo("a***a@x.com");
        assertThat(r.expiresAt()).isEqualTo(AGORA.plusMinutes(30));
        assertThat(r.resetToken()).matches("[A-Za-z0-9_-]{43}");

        verify(tokens).invalidarNaoUsados("usr-1", AGORA);
        ArgumentCaptor<PasswordResetToken> t = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokens).saveAndFlush(t.capture());
        PasswordResetToken salvo = t.getValue();
        assertThat(salvo.getId()).matches("prt-[0-9a-f]{32}");
        assertThat(salvo.getTokenHash()).isEqualTo(TokenRedefinicao.hash(r.resetToken(), props.getAuth().getJwtSecret()));
        assertThat(salvo.getTokenHash()).doesNotContain(r.resetToken());
        assertThat(salvo.getExpiresAt()).isEqualTo(AGORA.plusMinutes(30));
        assertThat(salvo.getConsumedAt()).isNull();
        assertThat(salvo.getCnpj()).isEqualTo("11222333000181");
        assertThat(salvo.getRequestedDevice()).isEqualTo("Navegador - Firefox");
        assertThat(salvo.getRequestedIp()).isEqualTo("1.1.1.1");
    }

    @Test
    void validadeDoTokenSegueAConfiguracao() {
        props.getAuth().setPasswordResetTokenMinutes(90);
        when(usuarios.buscarParaRedefinicao(anyString(), anyString())).thenReturn(Optional.of(usuario()));
        assertThat(service.solicitar("11222333000181", "ana@x.com", "ip", "UA").expiresAt()).isEqualTo(AGORA.plusMinutes(90));
    }

    // ---- consumir ----

    @Test
    void consumirMarcaOTokenPeloHash() {
        service.consumir(" tok ", "1.1.1.1", "Chrome");
        verify(tokens).consumirPorHash(eq(TokenRedefinicao.hash("tok", props.getAuth().getJwtSecret())), eq(AGORA),
                eq("1.1.1.1"), eq("Chrome"), eq("Navegador - Chrome"));
        service.consumir("  ", "ip", "ua");
        service.consumir(null, "ip", "ua");
    }

    // ---- redefinir ----

    PasswordResetToken tokenValido(String token) {
        PasswordResetToken t = new PasswordResetToken();
        t.setUserId("usr-1");
        t.setExpiresAt(AGORA.plusMinutes(10));
        t.setTokenHash(TokenRedefinicao.hash(token, props.getAuth().getJwtSecret()));
        return t;
    }

    @Test
    void redefinirTrocaASenhaConsomeOTokenEEncerraSessoes() {
        String hash = TokenRedefinicao.hash("tok", props.getAuth().getJwtSecret());
        when(tokens.findByTokenHashAndConsumedAtIsNull(hash)).thenReturn(Optional.of(tokenValido("tok")));
        Usuario u = usuario();
        u.setMustChangePassword(true);
        when(usuarios.findById("usr-1")).thenReturn(Optional.of(u));

        var dto = service.redefinir(" tok ", "NovaSenha123", "NovaSenha123", "ip", "UA");

        assertThat(dto.mustChangePassword()).isFalse();
        ArgumentCaptor<String> novoHash = ArgumentCaptor.forClass(String.class);
        verify(usuarios).trocarSenha(eq("usr-1"), novoHash.capture());
        assertThat(PasswordHasher.verify("NovaSenha123", novoHash.getValue())).isTrue();
        verify(tokens).consumirDoUsuario(eq("usr-1"), eq(AGORA), eq("ip"), eq("UA"), eq("Dispositivo web"));
        verify(sessoes).apagarTodasDoUsuario("usr-1");
    }

    NegocioException recusa(String token, String nova, String confirma) {
        Throwable t = org.assertj.core.api.Assertions.catchThrowable(() -> service.redefinir(token, nova, confirma, "ip", "ua"));
        assertThat(t).isInstanceOf(NegocioException.class);
        return (NegocioException) t;
    }

    @Test
    void redefinirValidaTokenConfirmacaoESenha() {
        assertThat(recusa("", "NovaSenha123", "NovaSenha123").getMessage()).isEqualTo("Token de redefinição inválido.");
        assertThat(recusa("tok", "NovaSenha123", "Outra123456").getMessage()).isEqualTo("A confirmação de senha não confere.");
        assertThat(recusa("tok", "curta", "curta").getMessage()).isEqualTo("A nova senha deve ter no minimo 8 caracteres.");
    }

    @Test
    void tokenExpiradoOuUsadoOuInexistenteRecebe400() {
        String hash = TokenRedefinicao.hash("tok", props.getAuth().getJwtSecret());
        when(tokens.findByTokenHashAndConsumedAtIsNull(hash)).thenReturn(Optional.empty()); // usado ou nao existe
        assertThat(recusa("tok", "NovaSenha123", "NovaSenha123").getMessage()).isEqualTo("Token de redefinição inválido ou expirado.");

        PasswordResetToken vencido = tokenValido("tok");
        vencido.setExpiresAt(AGORA.minusSeconds(1));
        when(tokens.findByTokenHashAndConsumedAtIsNull(hash)).thenReturn(Optional.of(vencido));
        assertThat(recusa("tok", "NovaSenha123", "NovaSenha123").getMessage()).isEqualTo("Token de redefinição inválido ou expirado.");
        verify(usuarios, never()).trocarSenha(anyString(), anyString());
    }

    @Test
    void usuarioInativoOuInexistenteNaoRedefine() {
        String hash = TokenRedefinicao.hash("tok", props.getAuth().getJwtSecret());
        when(tokens.findByTokenHashAndConsumedAtIsNull(hash)).thenReturn(Optional.of(tokenValido("tok")));
        when(usuarios.findById("usr-1")).thenReturn(Optional.empty());
        assertThat(recusa("tok", "NovaSenha123", "NovaSenha123").getMessage()).isEqualTo("Usuário inativo ou inexistente.");
        Usuario inativo = usuario();
        inativo.setStatus("inativo");
        when(usuarios.findById("usr-1")).thenReturn(Optional.of(inativo));
        assertThatThrownBy(() -> service.redefinir("tok", "NovaSenha123", "NovaSenha123", "ip", "ua"))
                .isInstanceOf(NegocioException.class);
    }

    // ---- administrativa ----

    @Test
    void redefinicaoAdministrativaMarcaTrocaObrigatoriaEncerraSessoesEUsaOCnpjDaEmpresa() {
        Usuario u = usuario();
        when(usuarios.findByIdAndCompanyId("usr-1", "emp-1")).thenReturn(Optional.of(u));
        when(empresas.buscarCnpj("emp-1")).thenReturn(Optional.of("11.222.333/0001-81"));

        var r = service.solicitarAdministrativo("usr-1", "emp-1", "ip", "UA").orElseThrow();

        assertThat(r.user().mustChangePassword()).isTrue();
        assertThat(r.maskedEmail()).isEqualTo("a***a@x.com");
        assertThat(r.resetToken()).isNotBlank();
        verify(usuarios).exigirTrocaDeSenha("usr-1", "emp-1");
        verify(sessoes).apagarTodasDoUsuario("usr-1");
        ArgumentCaptor<PasswordResetToken> t = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokens).saveAndFlush(t.capture());
        assertThat(t.getValue().getCnpj()).isEqualTo("11222333000181");
    }

    @Test
    void redefinicaoAdministrativaDeUsuarioDeOutraEmpresaDevolveVazio() {
        when(usuarios.findByIdAndCompanyId("usr-1", "emp-2")).thenReturn(Optional.empty());
        assertThat(service.solicitarAdministrativo("usr-1", "emp-2", "ip", "ua")).isEmpty();
        verify(tokens, never()).saveAndFlush(any());
    }
}
