package br.com.quack.pdv.security;

import br.com.quack.pdv.config.QuackProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** IP do cliente sem confiar em cabecalhos de proxy por padrao (igual ao HorusClientIpResolver). */
@Component
public class ClienteIpResolver {

    private final QuackProperties props;

    public ClienteIpResolver(QuackProperties props) {
        this.props = props;
    }

    public String resolver(HttpServletRequest request) {
        return resolver(request, "unknown");
    }

    public String resolver(HttpServletRequest request, String padrao) {
        if (props.getSecurity().isTrustForwardedHeaders()) {
            String encaminhado = request.getHeader("X-Forwarded-For");
            if (encaminhado != null && !encaminhado.isBlank()) {
                return encaminhado.split(",", -1)[0].trim();
            }
        }
        String remoto = request.getRemoteAddr();
        return remoto == null || remoto.isBlank() ? padrao : remoto;
    }
}
