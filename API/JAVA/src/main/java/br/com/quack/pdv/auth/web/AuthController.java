package br.com.quack.pdv.auth.web;

import br.com.quack.pdv.auth.app.AutenticacaoService;
import br.com.quack.pdv.auth.app.CadastroPublicoService;
import br.com.quack.pdv.auth.app.RecaptchaService;
import br.com.quack.pdv.auth.app.RecuperacaoSenhaService;
import br.com.quack.pdv.config.QuackProperties;
import br.com.quack.pdv.email.app.EmailService;
import br.com.quack.pdv.security.AutenticacaoFilter;
import br.com.quack.pdv.security.CnpjValidator;
import br.com.quack.pdv.security.ClienteIpResolver;
import br.com.quack.pdv.security.JwtService;
import br.com.quack.pdv.security.UsuarioAutenticado;
import br.com.quack.pdv.shared.ApiResponse;
import br.com.quack.pdv.shared.NegocioException;
import br.com.quack.pdv.usuario.web.UsuarioDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Rotas /api/Auth: login, conta do usuario, senha e cadastro (equivale ao AuthController da API .NET). */
@RestController
@RequestMapping("api/Auth")
public class AuthController {

    private static final Logger LOG = LoggerFactory.getLogger(AuthController.class);
    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH);

    public record LoginData(String tokenType, int expiresInSeconds, String sessionId, UsuarioDto user) {}

    private final AutenticacaoService autenticacao;
    private final RecuperacaoSenhaService recuperacao;
    private final CadastroPublicoService cadastro;
    private final RecaptchaService recaptcha;
    private final EmailService email;
    private final JwtService jwt;
    private final QuackProperties props;
    private final ClienteIpResolver ipResolver;

    public AuthController(AutenticacaoService autenticacao, RecuperacaoSenhaService recuperacao,
                          CadastroPublicoService cadastro, RecaptchaService recaptcha, EmailService email,
                          JwtService jwt, QuackProperties props, ClienteIpResolver ipResolver) {
        this.autenticacao = autenticacao;
        this.recuperacao = recuperacao;
        this.cadastro = cadastro;
        this.recaptcha = recaptcha;
        this.email = email;
        this.jwt = jwt;
        this.props = props;
        this.ipResolver = ipResolver;
    }

    @PostMapping("login")
    public ResponseEntity<ApiResponse<Object>> login(@RequestBody AuthRequests.Login request,
                                                     HttpServletRequest http, HttpServletResponse response) {
        if (request.email().isBlank() || request.password().isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.erro("Informe e-mail e senha."));
        }
        String ip = ip(http);
        ResponseEntity<ApiResponse<Object>> falhaRecaptcha = verificarRecaptcha(request.recaptchaToken(), "login", ip);
        if (falhaRecaptcha != null) return falhaRecaptcha;

        AutenticacaoService.LoginSucesso sucesso =
                autenticacao.login(request.email(), request.password(), ip, userAgent(http));
        String token = jwt.criarToken(sucesso.usuario().id(), sucesso.usuario().companyId(), sucesso.usuario().name(),
                sucesso.usuario().email(), sucesso.usuario().role(), sucesso.sessao().getId(), ip);
        adicionarCookie(response, token, request.rememberMe());
        return ResponseEntity.ok(ApiResponse.ok("Login realizado com sucesso.", new LoginData("Bearer",
                jwt.sessionHours() * 60 * 60, sucesso.sessao().getId(), sucesso.usuario())));
    }

    @PostMapping("forgot-password")
    public ResponseEntity<ApiResponse<Object>> esqueciASenha(@RequestBody AuthRequests.ForgotPassword request,
                                                              HttpServletRequest http) {
        if (!CnpjValidator.valido(request.cnpj())) {
            return ResponseEntity.badRequest().body(ApiResponse.erro("Informe um CNPJ válido."));
        }
        if (request.email().isBlank() || !request.email().contains("@")) {
            return ResponseEntity.badRequest().body(ApiResponse.erro("Informe um e-mail válido."));
        }
        ResponseEntity<ApiResponse<Object>> falhaRecaptcha =
                verificarRecaptcha(request.recaptchaToken(), "password_forgot_request", ip(http));
        if (falhaRecaptcha != null) return falhaRecaptcha;

        boolean emailLigado = email.habilitado();
        if (!emailLigado && !props.isDev()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.erro("Recuperação de senha indisponível. Configure o envio de e-mail."));
        }

        String ip = ip(http);
        String ua = userAgent(http);
        RecuperacaoSenhaService.PedidoRedefinicao pedido =
                recuperacao.solicitar(request.cnpj(), request.email(), ip, ua);
        if (pedido.resetToken() != null && !pedido.resetToken().isBlank() && pedido.expiresAt() != null) {
            String url = email.montarUrlRedefinicao(pedido.resetToken());
            try {
                email.enviarRedefinicaoSenha(request.email(), "Quack PDV", url, pedido.expiresAt());
            } catch (RuntimeException e) {
                recuperacao.consumir(pedido.resetToken(), ip, ua);
                LOG.warn("Não foi possível enviar e-mail de recuperação para {}.", request.email(), e);
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiResponse.erro(
                        "Não foi possível enviar o e-mail de recuperação. Verifique a configuração SMTP."));
            }
        }

        Object data = emailLigado || !props.isDev() ? semToken(pedido) : pedido;
        return ResponseEntity.ok(ApiResponse.ok("Se o e-mail estiver cadastrado, enviaremos as instruções de recuperação.", data));
    }

    @PostMapping("reset-password")
    public ResponseEntity<ApiResponse<Object>> redefinirSenha(@RequestBody AuthRequests.ResetPassword request,
                                                               HttpServletRequest http, HttpServletResponse response) {
        ResponseEntity<ApiResponse<Object>> falhaRecaptcha =
                verificarRecaptcha(request.recaptchaToken(), "password_reset", ip(http));
        if (falhaRecaptcha != null) return falhaRecaptcha;

        UsuarioDto usuario = recuperacao.redefinir(request.token(), request.nextPassword(), request.confirmPassword(),
                ip(http), userAgent(http));
        apagarCookie(response);
        return ResponseEntity.ok(ApiResponse.ok("Senha redefinida com sucesso.", usuario));
    }

    @PostMapping("register")
    public ResponseEntity<ApiResponse<Object>> cadastrar(@RequestBody AuthRequests.Register request,
                                                          HttpServletRequest http) {
        ResponseEntity<ApiResponse<Object>> falhaRecaptcha =
                verificarRecaptcha(request.recaptchaToken(), "signup_complete", ip(http));
        if (falhaRecaptcha != null) return falhaRecaptcha;

        UsuarioDto usuario;
        try {
            usuario = cadastro.cadastrar(request);
        } catch (NegocioException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.error("Erro inesperado ao registrar nova empresa/usuário.", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.erro(
                    "Não foi possível concluir o cadastro. Se já possui conta, faça login."));
        }
        try {
            email.enviarBoasVindas(request.email(), request.name());
        } catch (RuntimeException e) {
            LOG.warn("Não foi possível enviar e-mail de cadastro para {}.", request.email(), e);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Cadastro criado com sucesso.", usuario));
    }

    @GetMapping("me")
    public ResponseEntity<ApiResponse<Object>> me(HttpServletRequest http) {
        UsuarioAutenticado atual = atual(http);
        if (atual == null) return semSessao();
        Optional<UsuarioDto> usuario = autenticacao.usuarioAtivo(atual.id(), atual.companyId());
        if (usuario.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.erro("Usuario inativo ou inexistente."));
        }
        return ResponseEntity.ok(ApiResponse.ok("Usuario autenticado obtido com sucesso.", usuario.get()));
    }

    @PutMapping("me")
    public ResponseEntity<ApiResponse<Object>> atualizarMe(@RequestBody AuthRequests.UpdateProfile request,
                                                            HttpServletRequest http) {
        UsuarioAutenticado atual = atual(http);
        if (atual == null) return semSessao();
        Optional<UsuarioDto> usuario = autenticacao.atualizarPerfil(atual.id(), atual.companyId(), request.name(),
                request.email(), request.phone());
        if (usuario.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.erro("Usuário inativo ou inexistente."));
        }
        return ResponseEntity.ok(ApiResponse.ok("Perfil atualizado com sucesso.", usuario.get()));
    }

    @PostMapping("logout")
    public ResponseEntity<ApiResponse<Object>> sair(HttpServletRequest http, HttpServletResponse response) {
        UsuarioAutenticado atual = atual(http);
        if (atual != null) autenticacao.logout(atual.sessionId());
        apagarCookie(response);
        return ResponseEntity.ok(ApiResponse.ok("Logout realizado com sucesso."));
    }

    @PostMapping("change-password")
    public ResponseEntity<ApiResponse<Object>> trocarSenha(@RequestBody AuthRequests.ChangePassword request,
                                                            HttpServletRequest http) {
        UsuarioAutenticado atual = atual(http);
        if (atual == null) return semSessao();
        boolean trocou = autenticacao.trocarSenha(atual.id(), atual.companyId(), request.currentPassword(),
                request.nextPassword());
        if (!trocou) return ResponseEntity.badRequest().body(ApiResponse.erro("Senha atual inválida."));
        return ResponseEntity.ok(ApiResponse.ok("Senha atualizada com sucesso. Faça login novamente."));
    }

    // ---- auxiliares ----

    private ResponseEntity<ApiResponse<Object>> verificarRecaptcha(String token, String acao, String ip) {
        RecaptchaService.Resultado r = recaptcha.verificar(token, acao, ip);
        if (r.success()) return null;
        return ResponseEntity.status(r.statusCode()).body(ApiResponse.erro(r.message(), r.details()));
    }

    private static Map<String, Object> semToken(RecuperacaoSenhaService.PedidoRedefinicao p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accepted", p.accepted());
        m.put("maskedEmail", p.maskedEmail());
        m.put("expiresAt", p.expiresAt());
        return m;
    }

    private static ResponseEntity<ApiResponse<Object>> semSessao() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.erro("Sessão não encontrada."));
    }

    private static UsuarioAutenticado atual(HttpServletRequest http) {
        Object o = http.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO);
        return o instanceof UsuarioAutenticado u ? u : null;
    }

    private String ip(HttpServletRequest http) {
        return ipResolver.resolver(http, "0.0.0.0");
    }

    private static String userAgent(HttpServletRequest http) {
        String ua = http.getHeader(HttpHeaders.USER_AGENT);
        return ua == null ? "" : ua;
    }

    /** Set-Cookie no formato do ASP.NET: expires; path; secure; samesite; httponly. */
    private void adicionarCookie(HttpServletResponse response, String token, boolean lembrar) {
        StringBuilder sb = new StringBuilder(JwtService.AUTH_COOKIE_NAME).append('=').append(token);
        if (lembrar) {
            ZonedDateTime expira = ZonedDateTime.now(ZoneOffset.UTC).plusHours(jwt.sessionHours());
            sb.append("; expires=").append(HTTP_DATE.format(expira));
        }
        anexarAtributos(sb);
        response.addHeader(HttpHeaders.SET_COOKIE, sb.toString());
    }

    private void apagarCookie(HttpServletResponse response) {
        StringBuilder sb = new StringBuilder(JwtService.AUTH_COOKIE_NAME).append("=; expires=Thu, 01 Jan 1970 00:00:00 GMT");
        anexarAtributos(sb);
        response.addHeader(HttpHeaders.SET_COOKIE, sb.toString());
    }

    private void anexarAtributos(StringBuilder sb) {
        sb.append("; path=/");
        if (!props.isDev()) sb.append("; secure");
        sb.append("; samesite=").append(props.isDev() ? "lax" : "strict").append("; httponly");
    }

}
