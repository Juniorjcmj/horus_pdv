package br.com.quack.pdv.plataforma.sincronizacao.envio;

import br.com.quack.pdv.security.PerfisPermitidos;
import br.com.quack.pdv.shared.ApiResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Rotas humanas da loja (JWT local, D9). */
@RestController
@RequestMapping("api/loja-sync")
@Profile("loja")
public class LojaSyncLojaController {

    private final EnvioSincronizacao envio;

    public LojaSyncLojaController(EnvioSincronizacao envio) {
        this.envio = envio;
    }

    /** Religa a sincronizacao pausada por erro definitivo, depois da correcao. */
    @PostMapping("retomar")
    @PerfisPermitidos({"administrador"})
    public ResponseEntity<ApiResponse<Object>> retomar() {
        envio.retomar();
        return ResponseEntity.ok(ApiResponse.ok("Sincronizacao retomada."));
    }
}
