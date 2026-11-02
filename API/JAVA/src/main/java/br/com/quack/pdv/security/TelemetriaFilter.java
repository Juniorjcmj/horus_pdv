package br.com.quack.pdv.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/** X-Request-Id e log http.request.completed (igual ao HorusRequestTelemetryMiddleware). */
public class TelemetriaFilter extends OncePerRequestFilter {

    public static final String ATRIBUTO_REQUEST_ID = "quack.requestId";
    private static final Logger LOG = LoggerFactory.getLogger(TelemetriaFilter.class);

    private final ClienteIpResolver ipResolver;

    public TelemetriaFilter(ClienteIpResolver ipResolver) {
        this.ipResolver = ipResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        long inicio = System.nanoTime();
        request.setAttribute(ATRIBUTO_REQUEST_ID, requestId);
        if (!response.containsHeader("X-Request-Id")) response.setHeader("X-Request-Id", requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            long duracaoMs = Math.max(0, Math.round((System.nanoTime() - inicio) / 1_000_000.0));
            Object atual = request.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO);
            UsuarioAutenticado usuario = atual instanceof UsuarioAutenticado u ? u : null;
            LOG.info("http.request.completed requestId={} method={} path={} statusCode={} durationMs={} userId={} sessionId={} ip={}",
                    requestId, request.getMethod(), request.getRequestURI(), response.getStatus(), duracaoMs,
                    usuario == null ? null : usuario.id(), usuario == null ? null : usuario.sessionId(),
                    ipResolver.resolver(request));
        }
    }
}
