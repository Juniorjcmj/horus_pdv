package br.com.quack.pdv.plataforma.sincronizacao.situacao;

import br.com.quack.pdv.plataforma.sincronizacao.nuvem.ServidorLoja;
import br.com.quack.pdv.security.AutenticacaoFilter;
import br.com.quack.pdv.security.PerfisPermitidos;
import br.com.quack.pdv.security.UsuarioAutenticado;
import br.com.quack.pdv.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Situacao da sincronizacao na nuvem (D11): para o token do servidor da loja (empresa do token) ou para o administrador
 * da empresa (JWT da .NET). Outros perfis recebem 403 pelo {@link PerfisPermitidos}; sem credencial, 401.
 */
@RestController
@RequestMapping("api/loja-sync")
@Profile("nuvem")
public class LojaSyncSituacaoNuvemController {

    private final SituacaoNuvem situacao;
    private final Clock clock;

    public LojaSyncSituacaoNuvemController(@Lazy SituacaoNuvem situacao, Clock clock) {
        this.situacao = situacao;
        this.clock = clock;
    }

    @GetMapping("situacao")
    @PerfisPermitidos({"administrador"})
    public ResponseEntity<ApiResponse<SituacaoSync>> situacao(HttpServletRequest req) {
        String empresa;
        if (req.getAttribute(ServidorLoja.ATRIBUTO) instanceof ServidorLoja servidor) {
            empresa = servidor.companyId();
        } else if (req.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO) instanceof UsuarioAutenticado admin) {
            empresa = admin.companyId();
        } else {
            return ResponseEntity.status(401).body(ApiResponse.erro("Sessao expirada ou token invalido."));
        }
        return ResponseEntity.ok(ApiResponse.ok("Situacao da sincronizacao.", situacao.consultar(empresa, OffsetDateTime.now(clock))));
    }
}
