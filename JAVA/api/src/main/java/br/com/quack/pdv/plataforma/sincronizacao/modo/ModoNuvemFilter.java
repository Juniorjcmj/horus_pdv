package br.com.quack.pdv.plataforma.sincronizacao.modo;

import br.com.quack.pdv.security.RespostaJson;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Perfil {@code nuvem} (D1): o receptor so atende {@code /api/loja-sync/**} e {@code /actuator/health}. Todo o resto
 * responde 404, como se a rota nao existisse (Caixa, painel e Quack Estoque continuam na API .NET).
 */
public class ModoNuvemFilter extends OncePerRequestFilter {

    private final RespostaJson resposta;

    public ModoNuvemFilter(RespostaJson resposta) {
        this.resposta = resposta;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (permitida(caminho(request))) {
            chain.doFilter(request, response);
            return;
        }
        resposta.erro(response, 404, "Recurso nao encontrado.");
    }

    private static String caminho(HttpServletRequest request) {
        String uri = request.getRequestURI() == null ? "" : request.getRequestURI();
        String contexto = request.getContextPath() == null ? "" : request.getContextPath();
        String c = uri.startsWith(contexto) ? uri.substring(contexto.length()) : uri;
        c = c.toLowerCase(Locale.ROOT);
        while (c.length() > 1 && c.endsWith("/")) c = c.substring(0, c.length() - 1);
        return c;
    }

    static boolean permitida(String c) {
        if (c.contains("..") || c.contains(";") || c.contains("//") || c.contains("%")) return false;
        return c.equals("/api/loja-sync") || c.startsWith("/api/loja-sync/")
                || c.equals("/actuator/health") || c.startsWith("/actuator/health/");
    }
}
