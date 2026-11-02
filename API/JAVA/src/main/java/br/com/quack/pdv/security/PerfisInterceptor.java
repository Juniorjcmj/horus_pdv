package br.com.quack.pdv.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** Aplica @PerfisPermitidos depois da autenticacao: nao permitido vira 403 com a mensagem padrao. */
public class PerfisInterceptor implements HandlerInterceptor {

    private final RespostaJson resposta;

    public PerfisInterceptor(RespostaJson resposta) {
        this.resposta = resposta;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod metodo)) return true;
        Object atributo = request.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO);
        if (!(atributo instanceof UsuarioAutenticado usuario)) return true;

        PerfisPermitidos politica = AnnotatedElementUtils.findMergedAnnotation(metodo.getMethod(), PerfisPermitidos.class);
        if (politica == null) {
            politica = AnnotatedElementUtils.findMergedAnnotation(metodo.getBeanType(), PerfisPermitidos.class);
        }
        if (politica == null) return true;

        Set<String> permitidos = Arrays.stream(politica.value()).map(r -> r.trim().toLowerCase(Locale.ROOT))
                .filter(r -> !r.isEmpty()).collect(Collectors.toSet());
        if (permitidos.isEmpty()) return true;

        boolean ok = permitidos.contains(usuario.role() == null ? "" : usuario.role().toLowerCase(Locale.ROOT))
                || usuario.perfisAdicionais().stream().anyMatch(p -> permitidos.contains(p.toLowerCase(Locale.ROOT)));
        if (!ok) {
            resposta.erro(response, 403, "Usuário sem permissão para esta ação.");
            return false;
        }
        return true;
    }
}
