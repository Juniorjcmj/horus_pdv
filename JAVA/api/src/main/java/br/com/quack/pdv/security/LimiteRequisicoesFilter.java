package br.com.quack.pdv.security;

import br.com.quack.pdv.config.QuackProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Janela fixa por IP (igual ao HorusRateLimitMiddleware): a janela abre na primeira requisicao e fecha
 * depois de window-seconds; acima de max-requests dentro dela, 429 com Retry-After.
 */
public class LimiteRequisicoesFilter extends OncePerRequestFilter {

    private static final int LIMITE_LIMPEZA = 500;

    static final class Balde {
        int contagem;
        Instant expiraEm;
    }

    private final Map<String, Balde> baldes = new ConcurrentHashMap<>();
    private final QuackProperties props;
    private final ClienteIpResolver ipResolver;
    private final RespostaJson resposta;
    private final Clock clock;

    public LimiteRequisicoesFilter(QuackProperties props, ClienteIpResolver ipResolver, RespostaJson resposta, Clock clock) {
        this.props = props;
        this.ipResolver = ipResolver;
        this.resposta = resposta;
        this.clock = clock;
    }

    int tamanho() {
        return baldes.size();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || ignorado(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        Instant agora = clock.instant();
        String chave = ipResolver.resolver(request);
        long janelaSegundos = props.getSecurity().getRateLimit().getWindowSeconds();
        Balde balde = baldes.compute(chave, (k, atual) -> {
            if (atual == null) {
                Balde novo = new Balde();
                novo.contagem = 1;
                novo.expiraEm = agora.plusSeconds(janelaSegundos);
                return novo;
            }
            if (!atual.expiraEm.isAfter(agora)) {
                atual.contagem = 1;
                atual.expiraEm = agora.plusSeconds(janelaSegundos);
            } else {
                atual.contagem += 1;
            }
            return atual;
        });

        int contagem;
        Instant expiraEm;
        synchronized (balde) {
            contagem = balde.contagem;
            expiraEm = balde.expiraEm;
        }
        if (contagem > props.getSecurity().getRateLimit().getMaxRequests()) {
            double restante = (expiraEm.toEpochMilli() - agora.toEpochMilli()) / 1000.0;
            long retryAfter = Math.max(1, (long) Math.ceil(restante));
            response.setHeader("Retry-After", Long.toString(retryAfter));
            resposta.erro(response, 429, "Limite de requisições excedido. Tente novamente em instantes.");
            return;
        }

        limparExpirados(agora);
        chain.doFilter(request, response);
    }

    private void limparExpirados(Instant agora) {
        if (baldes.size() < LIMITE_LIMPEZA) return;
        baldes.entrySet().removeIf(e -> !e.getValue().expiraEm.isAfter(agora));
    }

    private static boolean ignorado(String caminho) {
        if (caminho == null || caminho.isBlank()) return false;
        String c = caminho.toLowerCase(Locale.ROOT);
        return c.startsWith("/swagger") || c.startsWith("/v3/api-docs");
    }
}
