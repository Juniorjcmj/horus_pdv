package br.com.quack.pdv.security;

import br.com.quack.pdv.config.QuackProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Content-Length acima do limite vira 413 (igual ao HorusRequestBodyLimitMiddleware). */
public class LimiteCorpoFilter extends OncePerRequestFilter {

    private final QuackProperties props;
    private final RespostaJson resposta;

    public LimiteCorpoFilter(QuackProperties props, RespostaJson resposta) {
        this.props = props;
        this.resposta = resposta;
    }

    /** A rota de lotes da sincronizacao tem limite proprio (16 MB); o geral continua valendo nas demais (D4). */
    private long limite(HttpServletRequest request) {
        String c = request.getRequestURI().substring(request.getContextPath().length());
        return c.equalsIgnoreCase("/api/loja-sync/lotes") ? props.getSync().getMaxLoteBytes()
                : props.getSecurity().getMaxRequestBodyBytes();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long tamanho = request.getContentLengthLong();
        if (tamanho >= 0 && tamanho > limite(request)) {
            resposta.erro(response, 413, "Payload maior que o permitido.");
            return;
        }
        chain.doFilter(request, response);
    }
}
