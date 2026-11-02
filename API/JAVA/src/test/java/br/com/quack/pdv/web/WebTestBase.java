package br.com.quack.pdv.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import br.com.quack.pdv.auth.app.AutenticacaoService;
import br.com.quack.pdv.auth.app.CadastroPublicoService;
import br.com.quack.pdv.auth.app.RecaptchaService;
import br.com.quack.pdv.auth.app.RecuperacaoSenhaService;
import br.com.quack.pdv.auditoria.app.AuditoriaService;
import br.com.quack.pdv.email.app.EmailService;
import br.com.quack.pdv.security.JwtService;
import br.com.quack.pdv.sessao.app.SessaoService;
import br.com.quack.pdv.sessao.app.ValidadorSessao;
import br.com.quack.pdv.usuario.app.UsuarioService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base dos testes web: aplicacao inteira (filtros, interceptor, advice, Jackson) em MockMvc, sem banco; os servicos
 * ficam como mocks. O token e emitido pelo JwtService real, e o ValidadorSessao devolve a situacao combinada.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class WebTestBase {

    @Autowired protected MockMvc mvc;
    @Autowired protected JwtService jwt;

    @MockitoBean protected ValidadorSessao validador;
    @MockitoBean protected AutenticacaoService autenticacao;
    @MockitoBean protected CadastroPublicoService cadastro;
    @MockitoBean protected RecuperacaoSenhaService recuperacao;
    @MockitoBean protected SessaoService sessoes;
    @MockitoBean protected UsuarioService usuarios;
    @MockitoBean protected EmailService email;
    @MockitoBean protected AuditoriaService auditoria;
    @MockitoBean protected RecaptchaService recaptcha;

    @BeforeEach
    void recaptchaDesligadoPorPadrao() {
        when(recaptcha.verificar(any(), any(), any()))
                .thenReturn(new RecaptchaService.Resultado(true, 200, "", "", true));
    }

    /** Token valido de um usuario da empresa-principal, com a sessao e o usuario "ativos" no ValidadorSessao. */
    protected String tokenDe(String role, String... perfisAdicionais) {
        return tokenDaEmpresa("usr-1", "empresa-principal", role, "aprovada", perfisAdicionais);
    }

    protected String tokenDaEmpresa(String userId, String companyId, String role, String statusEmpresa, String... perfis) {
        String sessionId = "sess-" + userId;
        String token = jwt.criarToken(userId, companyId, "Fulano", "fulano@x.com", role, sessionId, "10.0.0.1");
        when(validador.validar(eq(sessionId), eq(userId), eq(companyId)))
                .thenReturn(new ValidadorSessao.Validacao(true, true, statusEmpresa, String.join(",", perfis)));
        return token;
    }
}
