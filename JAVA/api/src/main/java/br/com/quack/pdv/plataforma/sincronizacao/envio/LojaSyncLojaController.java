package br.com.quack.pdv.plataforma.sincronizacao.envio;

import br.com.quack.pdv.plataforma.sincronizacao.conferencia.ConferenciaSincronizacao;
import br.com.quack.pdv.plataforma.sincronizacao.situacao.SituacaoLoja;
import br.com.quack.pdv.plataforma.sincronizacao.situacao.SituacaoSync;
import br.com.quack.pdv.security.PerfisPermitidos;
import br.com.quack.pdv.shared.ApiResponse;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Rotas humanas da loja (JWT local, D9): retomar, situacao (D11) e pedido de conferencia (D10). Administrador. */
@RestController
@RequestMapping("api/loja-sync")
@Profile("loja")
public class LojaSyncLojaController {

    private final EnvioSincronizacao envio;
    private final SituacaoLoja situacao;
    private final ConferenciaSincronizacao conferencia;
    private final Clock clock;

    public LojaSyncLojaController(EnvioSincronizacao envio, SituacaoLoja situacao, ConferenciaSincronizacao conferencia,
                                  Clock clock) {
        this.envio = envio;
        this.situacao = situacao;
        this.conferencia = conferencia;
        this.clock = clock;
    }

    /** Religa a sincronizacao pausada por erro definitivo, depois da correcao. */
    @PostMapping("retomar")
    @PerfisPermitidos({"administrador"})
    public ResponseEntity<ApiResponse<Object>> retomar() {
        envio.retomar();
        return ResponseEntity.ok(ApiResponse.ok("Sincronizacao retomada."));
    }

    @GetMapping("situacao")
    @PerfisPermitidos({"administrador"})
    public ResponseEntity<ApiResponse<SituacaoSync>> situacao() {
        return ResponseEntity.ok(ApiResponse.ok("Situacao da sincronizacao.", situacao.consultar(OffsetDateTime.now(clock))));
    }

    /** Pedido de conferencia (D10): roda na hora e devolve o resultado, que tambem fica gravado. */
    @PostMapping("conferencia")
    @PerfisPermitidos({"administrador"})
    public ResponseEntity<ApiResponse<ConferenciaSincronizacao.Relatorio>> conferir() {
        ConferenciaSincronizacao.Relatorio r = conferencia.executar("manual");
        return ResponseEntity.ok(ApiResponse.ok("Conferencia " + r.resultado() + ".", r));
    }
}
