package br.com.quack.pdv.plataforma.monitoramento;

import br.com.quack.pdv.config.QuackProperties;
import br.com.quack.pdv.security.RespostaJson;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Protege {@code /actuator/prometheus} com um token proprio de metricas (M2). Roda antes de todos os outros filtros
 * (modo nuvem, recuperacao, autenticacao), que so liberam a rota depois que este confere o token.
 * <ul>
 *   <li>token nao configurado: 404 igual a qualquer rota inexistente (a rota "nao existe");</li>
 *   <li>sem {@code Authorization: Bearer ...} ou diferente: 401, sem nenhuma metrica no corpo;</li>
 *   <li>correto: segue a cadeia (comparacao em tempo constante).</li>
 * </ul>
 */
public class MetricasTokenFilter extends OncePerRequestFilter {

    static final String ROTA = "/actuator/prometheus";
    private static final String BEARER = "Bearer ";

    private final QuackProperties props;
    private final RespostaJson resposta;

    public MetricasTokenFilter(QuackProperties props, RespostaJson resposta) {
        this.props = props;
        this.resposta = resposta;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!ROTA.equals(caminho(request))) {
            chain.doFilter(request, response);
            return;
        }
        String esperado = props.getMetrics().getToken();
        if (esperado.isEmpty()) {
            resposta.erro(response, 404, "Recurso nao encontrado.");
            return;
        }
        String cabecalho = request.getHeader("Authorization");
        String enviado = cabecalho != null && cabecalho.startsWith(BEARER) ? cabecalho.substring(BEARER.length()).trim() : "";
        boolean confere = MessageDigest.isEqual(enviado.getBytes(StandardCharsets.UTF_8), esperado.getBytes(StandardCharsets.UTF_8));
        if (!confere) {
            resposta.erro(response, 401, "Token de metricas invalido.");
            return;
        }
        chain.doFilter(request, response);
    }

    private static String caminho(HttpServletRequest request) {
        String uri = request.getRequestURI() == null ? "" : request.getRequestURI();
        String contexto = request.getContextPath() == null ? "" : request.getContextPath();
        String c = (uri.startsWith(contexto) ? uri.substring(contexto.length()) : uri).toLowerCase(Locale.ROOT);
        while (c.length() > 1 && c.endsWith("/")) c = c.substring(0, c.length() - 1);
        return c;
    }
}
