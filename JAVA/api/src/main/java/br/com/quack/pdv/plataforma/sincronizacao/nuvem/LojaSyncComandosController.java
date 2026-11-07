package br.com.quack.pdv.plataforma.sincronizacao.nuvem;

import br.com.quack.pdv.config.QuackProperties;
import br.com.quack.pdv.security.AutenticacaoFilter;
import br.com.quack.pdv.security.PerfisPermitidos;
import br.com.quack.pdv.security.UsuarioAutenticado;
import br.com.quack.pdv.shared.ApiResponse;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas da nuvem para comandos nuvem -> loja (D6) e quarentena (D7). {@code comandos} e {@code comandos/resultados}
 * usam o token do servidor; {@code quarentena/{id}/resolver} e do administrador (JWT da .NET).
 */
@RestController
@RequestMapping("api/loja-sync")
@Profile("nuvem")
public class LojaSyncComandosController {

    public record ListaComandos(List<ServicoComandos.Comando> comandos) {}

    public record Resultados(List<ServicoComandos.Resultado> resultados) {}

    private static final long PASSO_MS = 300;

    private final ServicoComandos servico;
    private final QuackProperties props;
    private final JdbcTemplate jdbc;
    private final MeterRegistry metricas;

    public LojaSyncComandosController(@Lazy ServicoComandos servico, QuackProperties props, @Lazy JdbcTemplate jdbc,
                                      MeterRegistry metricas) {
        this.metricas = metricas;
        this.servico = servico;
        this.props = props;
        this.jdbc = jdbc;
    }

    /** Long-poll: responde assim que houver comando, ou vazio ao fim de {@code comandos-espera-ms} (25 s). */
    @GetMapping("comandos")
    public ResponseEntity<ApiResponse<ListaComandos>> comandos(@RequestParam(defaultValue = "0") long apos, HttpServletRequest req)
            throws InterruptedException {
        if (!(req.getAttribute(ServidorLoja.ATRIBUTO) instanceof ServidorLoja servidor)) {
            return ResponseEntity.status(401).body(ApiResponse.erro("Token do servidor invalido ou revogado."));
        }
        long fim = System.currentTimeMillis() + props.getSync().getComandosEsperaMs();
        List<ServicoComandos.Comando> lista = servico.pendentes(servidor.companyId(), apos);
        while (lista.isEmpty() && System.currentTimeMillis() < fim) {
            Thread.sleep(PASSO_MS);
            lista = servico.pendentes(servidor.companyId(), apos);
        }
        if (!lista.isEmpty()) {
            metricas.counter("quack_sync_comandos_entregues", "empresa", servidor.companyId()).increment(lista.size());
        }
        return ResponseEntity.ok(ApiResponse.ok("Comandos.", new ListaComandos(lista)));
    }

    @PostMapping("comandos/resultados")
    public ResponseEntity<ApiResponse<Object>> resultados(@RequestBody Resultados corpo, HttpServletRequest req) {
        if (!(req.getAttribute(ServidorLoja.ATRIBUTO) instanceof ServidorLoja servidor)) {
            return ResponseEntity.status(401).body(ApiResponse.erro("Token do servidor invalido ou revogado."));
        }
        servico.registrar(servidor, corpo == null || corpo.resultados() == null ? List.of() : corpo.resultados());
        return ResponseEntity.ok(ApiResponse.ok("Resultados registrados."));
    }

    /** Marca como resolvida uma entrada da quarentena da empresa do administrador (D7). */
    @PostMapping("quarentena/{id}/resolver")
    @PerfisPermitidos({"administrador"})
    public ResponseEntity<ApiResponse<Object>> resolver(@PathVariable long id, HttpServletRequest http) {
        UsuarioAutenticado admin = (UsuarioAutenticado) http.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO);
        int n = jdbc.update("UPDATE dbo.SyncQuarentena SET ResolvidoEm = SYSDATETIMEOFFSET(), ResolvidoPor = ? "
                + "WHERE Id = ? AND CompanyId = ? AND ResolvidoEm IS NULL", admin.id(), id, admin.companyId());
        if (n == 0) return ResponseEntity.status(404).body(ApiResponse.erro("Entrada da quarentena nao encontrada ou ja resolvida."));
        return ResponseEntity.ok(ApiResponse.ok("Quarentena resolvida."));
    }
}
