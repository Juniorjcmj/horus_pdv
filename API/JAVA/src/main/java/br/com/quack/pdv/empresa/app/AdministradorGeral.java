package br.com.quack.pdv.empresa.app;

import br.com.quack.pdv.security.UsuarioAutenticado;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class AdministradorGeral {
    private static final Set<String> DONOS = Set.of("jotacfs2010@hotmail.com", "jotanaval2009@gmail.com", "flavio@hpdv.com.br");

    public boolean ehAdministradorGeral(UsuarioAutenticado usuario) {
        return administrador(usuario) && "empresa-principal".equals(normalizar(usuario.companyId()));
    }

    public boolean podeGerenciarTreinamento(UsuarioAutenticado usuario) {
        return administrador(usuario) && (ehAdministradorGeral(usuario) || DONOS.contains(normalizar(usuario.email())));
    }

    private boolean administrador(UsuarioAutenticado usuario) {
        return usuario != null && "administrador".equals(normalizar(usuario.role()));
    }

    private static String normalizar(String valor) {
        return valor == null ? "" : valor.trim().toLowerCase(Locale.ROOT);
    }
}
