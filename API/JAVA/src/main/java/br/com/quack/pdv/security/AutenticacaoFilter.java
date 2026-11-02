package br.com.quack.pdv.security;

import br.com.quack.pdv.config.QuackProperties;
import br.com.quack.pdv.sessao.app.ValidadorSessao;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Autentica cada requisicao como o HorusAuthMiddleware da API .NET: le o token do cookie ou do Bearer, valida o JWT
 * e a sessao no banco, confere a situacao da empresa e guarda o UsuarioAutenticado na requisicao.
 * Tokens do Gateway (qgw_) e de integracao (qmcp_) e o caminho /mcp ficam para as fases 5 e 8: recebem 401.
 */
public class AutenticacaoFilter extends OncePerRequestFilter {

    public static final String ATRIBUTO_USUARIO = "quack.usuarioAutenticado";
    static final String MSG_SESSAO = "Sessao expirada ou token invalido.";

    private final QuackProperties props;
    private final JwtService jwt;
    private final ValidadorSessao validador;
    private final RespostaJson resposta;

    public AutenticacaoFilter(QuackProperties props, JwtService jwt, ValidadorSessao validador, RespostaJson resposta) {
        this.props = props;
        this.jwt = jwt;
        this.validador = validador;
        this.resposta = resposta;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (pular(request)) {
            chain.doFilter(request, response);
            return;
        }

        String caminho = request.getRequestURI();
        boolean mcp = caminho.equalsIgnoreCase("/mcp") || caminho.toLowerCase(Locale.ROOT).startsWith("/mcp/");
        String token = mcp ? tokenBearer(request) : tokenDaRequisicao(request);

        if (mcp || token.startsWith("qgw_") || token.startsWith("qmcp_")) {
            resposta.erro(response, 401, MSG_SESSAO);
            return;
        }

        UsuarioAutenticado usuario = token.isBlank() ? null : jwt.validarToken(token);
        ValidadorSessao.Validacao validacao = usuario == null ? null
                : validador.validar(usuario.sessionId(), usuario.id(), usuario.companyId());
        if (usuario == null || validacao == null || !validacao.sessaoAtiva() || !validacao.usuarioAtivo()) {
            resposta.erro(response, 401, MSG_SESSAO);
            return;
        }
        usuario = usuario.comPerfisAdicionais(Perfis.parseAdicionais(validacao.perfisAdicionais()));

        if (!"empresa-principal".equalsIgnoreCase(usuario.companyId())
                && !"aprovada".equalsIgnoreCase(validacao.statusEmpresa())) {
            resposta.erro(response, 403, mensagemEmpresa(validacao.statusEmpresa()));
            return;
        }

        request.setAttribute(ATRIBUTO_USUARIO, usuario);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                usuario, null, List.of(new SimpleGrantedAuthority("ROLE_" + usuario.role().toUpperCase(Locale.ROOT)))));
        chain.doFilter(request, response);
    }

    static String mensagemEmpresa(String status) {
        return switch (status == null ? "" : status) {
            case "pendente" -> "Sua empresa ainda aguarda aprovação da administração.";
            case "rejeitada" -> "O cadastro da sua empresa foi recusado.";
            case "bloqueada" -> "O acesso da sua empresa está bloqueado/suspenso.";
            default -> "Acesso restrito para esta empresa.";
        };
    }

    private boolean pular(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String caminho = request.getRequestURI();
        String c = caminho.toLowerCase(Locale.ROOT);
        if (c.startsWith("/api/auth/login") || c.startsWith("/api/auth/forgot-password")
                || c.startsWith("/api/auth/reset-password") || c.startsWith("/api/auth/register")) {
            return true;
        }
        // Saude do servico (healthcheck do Docker) nao carrega token.
        if (c.equals("/actuator/health") || c.startsWith("/actuator/health/")) return true;
        return props.isDev() && (c.startsWith("/swagger") || c.startsWith("/v3/api-docs"));
    }

    private static String tokenDaRequisicao(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (JwtService.AUTH_COOKIE_NAME.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                    return c.getValue();
                }
            }
        }
        return tokenBearer(request);
    }

    static String tokenBearer(HttpServletRequest request) {
        String cabecalho = request.getHeader("Authorization");
        if (cabecalho == null) return "";
        String[] partes = java.util.Arrays.stream(cabecalho.split(" ")).map(String::trim)
                .filter(s -> !s.isEmpty()).toArray(String[]::new);
        return partes.length == 2 && partes[0].equals("Bearer") ? partes[1] : "";
    }
}
