package br.com.quack.pdv.plataforma.sincronizacao.recuperacao;

import br.com.quack.pdv.security.RespostaJson;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Perfil {@code loja}, durante a recuperacao pos-restauracao (tarefa 7.4): o servidor NAO aceita vendas nem nada que
 * escreva ou leia dados da empresa. Responde 503 com {@code Retry-After}, que o Caixa trata como servidor fora do ar
 * (fica na fila offline dele e reenvia depois, sem duplicar). Passam so a saude, o login e as rotas {@code /api/loja-sync}
 * (situacao, para o administrador acompanhar).
 *
 * <p>Escolha (design, "Notas da recuperacao"): bloquear, em vez de aceitar vendas durante a recuperacao. Venda aceita no
 * meio da puxada concorreria com as linhas que chegam da nuvem (numero de venda e de nota, saldos de estoque e de fiado)
 * e seria enviada com versoes que a reativacao descarta.
 */
public class RecuperacaoFilter extends OncePerRequestFilter {

    private final EstadoRecuperacao estado;
    private final RespostaJson resposta;

    public RecuperacaoFilter(EstadoRecuperacao estado, RespostaJson resposta) {
        this.estado = estado;
        this.resposta = resposta;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!estado.emAndamento() || permitida(caminho(request))) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("Retry-After", "30");
        resposta.erro(response, 503, "Servidor da loja em recuperacao depois de uma restauracao. "
                + "As vendas voltam sozinhas quando terminar; mantenha-as na fila offline do caixa.");
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
                || c.equals("/api/auth/login") || c.equals("/api/auth/logout") || c.equals("/api/auth/me")
                || c.equals("/actuator/health") || c.startsWith("/actuator/health/");
    }
}
