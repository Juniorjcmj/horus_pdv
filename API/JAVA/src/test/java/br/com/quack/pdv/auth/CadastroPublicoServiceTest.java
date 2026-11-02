package br.com.quack.pdv.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.quack.pdv.auth.app.CadastroPublicoService;
import br.com.quack.pdv.auth.web.AuthRequests;
import br.com.quack.pdv.empresa.repo.EmpresaRepository;
import br.com.quack.pdv.security.PasswordHasher;
import br.com.quack.pdv.sessao.repo.SessaoRepository;
import br.com.quack.pdv.shared.NegocioException;
import br.com.quack.pdv.usuario.app.UsuarioService;
import br.com.quack.pdv.usuario.domain.Usuario;
import br.com.quack.pdv.usuario.repo.UsuarioRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CadastroPublicoServiceTest {

    UsuarioRepository usuarios = mock(UsuarioRepository.class);
    EmpresaRepository empresas = mock(EmpresaRepository.class);
    Clock clock = Clock.fixed(Instant.parse("2026-11-01T22:30:00.123Z"), ZoneOffset.UTC);
    CadastroPublicoService service;

    @BeforeEach
    void preparar() {
        UsuarioService usuarioService = new UsuarioService(usuarios, mock(SessaoRepository.class), clock);
        service = new CadastroPublicoService(usuarios, empresas, usuarioService, clock);
    }

    AuthRequests.Register pedido(String cnpj, String senha, String confirma) {
        return new AuthRequests.Register(cnpj, " Mercado Bom ", " Dono@Mercado.com ", " 11999 ", senha, confirma, "");
    }

    NegocioException recusa(AuthRequests.Register r) {
        Throwable t = org.assertj.core.api.Assertions.catchThrowable(() -> service.cadastrar(r));
        assertThat(t).isInstanceOf(NegocioException.class);
        return (NegocioException) t;
    }

    @Test
    void cadastraEmpresaPendenteQuandoAAprovacaoEstaLigada() {
        when(empresas.exigeAprovacaoParaNovasEmpresas()).thenReturn(Optional.of(true));
        var dto = service.cadastrar(pedido("11.222.333/0001-81", "Senha#1234", "Senha#1234"));

        assertThat(dto.id()).isEqualTo("usr-" + clock.millis());
        assertThat(dto.companyId()).isEqualTo("emp-" + clock.millis());
        assertThat(dto.role()).isEqualTo("administrador");
        assertThat(dto.status()).isEqualTo("ativo");
        assertThat(dto.mustChangePassword()).isFalse();
        assertThat(dto.email()).isEqualTo("dono@mercado.com");
        assertThat(dto.cpf()).isEqualTo("11.222.333/0001-81"); // o CNPJ vai no campo Cpf, como no .NET
        verify(empresas).inserirEmpresaCadastroPublico("emp-" + clock.millis(), "Mercado Bom", "11.222.333/0001-81",
                "dono@mercado.com", "11999", "pendente");
        ArgumentCaptor<Usuario> u = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarios).saveAndFlush(u.capture());
        assertThat(PasswordHasher.verify("Senha#1234", u.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void cadastraEmpresaAprovadaQuandoAAprovacaoEstaDesligada() {
        when(empresas.exigeAprovacaoParaNovasEmpresas()).thenReturn(Optional.of(false));
        service.cadastrar(pedido("11222333000181", "Senha#1234", "Senha#1234"));
        verify(empresas).inserirEmpresaCadastroPublico(anyString(), anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.eq("aprovada"));
    }

    @Test
    void semConfiguracaoDaEmpresaPrincipalExigeAprovacao() {
        when(empresas.exigeAprovacaoParaNovasEmpresas()).thenReturn(Optional.empty());
        service.cadastrar(pedido("11222333000181", "Senha#1234", "Senha#1234"));
        verify(empresas).inserirEmpresaCadastroPublico(anyString(), anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.eq("pendente"));
    }

    @Test
    void validacoes() {
        assertThat(recusa(pedido("11.222.333/0001-80", "Senha#1234", "Senha#1234")).getMessage()).isEqualTo("CNPJ inválido.");
        assertThat(recusa(pedido("11.222.333/0001-81", "Senha#1234", "Outra#1234")).getMessage())
                .isEqualTo("A confirmação de senha não confere.");
        assertThat(recusa(pedido("11.222.333/0001-81", "curta", "curta")).getMessage())
                .isEqualTo("Senha deve ter no minimo 8 caracteres.");
        verify(empresas, never()).inserirEmpresaCadastroPublico(any(), any(), any(), any(), any(), any());
    }

    @Test
    void recusaEmailECnpjJaCadastrados() {
        when(usuarios.contarPorEmail("dono@mercado.com")).thenReturn(1);
        assertThat(recusa(pedido("11.222.333/0001-81", "Senha#1234", "Senha#1234")).getMessage())
                .isEqualTo("Este e-mail já está cadastrado no sistema. Faça login para acessar.");

        when(usuarios.contarPorEmail("dono@mercado.com")).thenReturn(0);
        when(empresas.contarPorCnpj("11.222.333/0001-81")).thenReturn(1);
        assertThat(recusa(pedido("11.222.333/0001-81", "Senha#1234", "Senha#1234")).getMessage())
                .isEqualTo("Este CNPJ já possui uma empresa cadastrada no sistema. Faça login com sua conta.");
        verify(usuarios, never()).saveAndFlush(any());
    }
}
