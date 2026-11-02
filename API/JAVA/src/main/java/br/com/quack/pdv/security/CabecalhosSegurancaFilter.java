package br.com.quack.pdv.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Os 7 cabecalhos de seguranca em toda resposta (igual ao HorusSecurityHeadersMiddleware). */
public class CabecalhosSegurancaFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        adicionarSeAusente(response, "X-Content-Type-Options", "nosniff");
        adicionarSeAusente(response, "X-Frame-Options", "DENY");
        adicionarSeAusente(response, "Referrer-Policy", "no-referrer");
        adicionarSeAusente(response, "Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        adicionarSeAusente(response, "X-Permitted-Cross-Domain-Policies", "none");
        adicionarSeAusente(response, "Cross-Origin-Resource-Policy", "cross-origin");
        adicionarSeAusente(response, "Cache-Control", "no-store");
        chain.doFilter(request, response);
    }

    private static void adicionarSeAusente(HttpServletResponse response, String nome, String valor) {
        if (!response.containsHeader(nome)) response.setHeader(nome, valor);
    }
}
