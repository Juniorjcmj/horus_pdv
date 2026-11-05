package br.com.quack.pdv.plataforma.sincronizacao.envio;

import br.com.quack.pdv.plataforma.sincronizacao.conferencia.ConferenciaSincronizacao;
import br.com.quack.pdv.plataforma.sincronizacao.recuperacao.EstadoRecuperacao;
import br.com.quack.pdv.plataforma.sincronizacao.recuperacao.RecuperacaoLoja;
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
    private final EstadoRecuperacao recuperacao;
    private final RecuperacaoLoja executorRecuperacao;

    public LojaSyncLojaController(EnvioSincronizacao envio, SituacaoLoja situacao, ConferenciaSincronizacao conferencia,
                                  Clock clock, EstadoRecuperacao recuperacao, RecuperacaoLoja executorRecuperacao) {
        this.recuperacao = recuperacao;
        this.executorRecuperacao = executorRecuperacao;
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

    /**
     * Pede a recuperacao pos-restauracao (a mesma que o {@code restaurar.ps1} dispara) e a executa em segundo plano.
     * Serve tambem para repetir uma recuperacao pendente depois que a internet voltou.
     */
    @PostMapping("recuperar")
    @PerfisPermitidos({"administrador"})
    public ResponseEntity<ApiResponse<Object>> recuperar() {
        recuperacao.solicitar("pedido-do-administrador");
        Thread t = new Thread(executorRecuperacao::executar, "recuperacao-loja");
        t.setDaemon(true);
        t.start();
        return ResponseEntity.accepted().body(ApiResponse.ok("Recuperacao iniciada. Acompanhe pela situacao."));
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
