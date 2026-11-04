package br.com.quack.pdv.plataforma.sincronizacao.conferencia;

import br.com.quack.pdv.plataforma.sincronizacao.nuvem.ServidorLoja;
import br.com.quack.pdv.shared.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Rotas de maquina da conferencia (D10), no receptor da nuvem. Todas exigem o token do servidor ativo. */
@RestController
@RequestMapping("api/loja-sync/conferencia")
@Profile("nuvem")
public class LojaSyncConferenciaNuvemController {

    public record PedidoTabela(String tabela) {}

    public record PedidoLinhas(String tabela, List<Integer> faixas) {}

    public record PedidoExclusao(List<ServicoConferencia.ChavesDaTabela> tabelas) {}

    public record Resultado(String origem, String resultado, int voltas, JsonNode detalhe) {}

    private final ServicoConferencia servico;

    public LojaSyncConferenciaNuvemController(@Lazy ServicoConferencia servico) {
        this.servico = servico;
    }

    private static ServidorLoja servidor(HttpServletRequest req) {
        return req.getAttribute(ServidorLoja.ATRIBUTO) instanceof ServidorLoja s ? s : null;
    }

    private static <T> ResponseEntity<ApiResponse<T>> naoAutenticado() {
        return ResponseEntity.status(401).body(ApiResponse.erro("Token do servidor invalido ou revogado."));
    }

    @PostMapping("resumo")
    public ResponseEntity<ApiResponse<List<ServicoConferencia.ResumoTabela>>> resumo(HttpServletRequest req) {
        ServidorLoja s = servidor(req);
        if (s == null) return naoAutenticado();
        return ResponseEntity.ok(ApiResponse.ok("Resumo.", servico.resumos(s.companyId())));
    }

    @PostMapping("faixas")
    public ResponseEntity<ApiResponse<List<ServicoConferencia.ResumoFaixa>>> faixas(@RequestBody PedidoTabela corpo,
                                                                                   HttpServletRequest req) {
        ServidorLoja s = servidor(req);
        if (s == null) return naoAutenticado();
        try {
            return ResponseEntity.ok(ApiResponse.ok("Faixas.", servico.faixas(s.companyId(), corpo.tabela())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.erro(e.getMessage()));
        }
    }

    @PostMapping("linhas")
    public ResponseEntity<ApiResponse<List<ServicoConferencia.LinhaNuvem>>> linhas(@RequestBody PedidoLinhas corpo,
                                                                                  HttpServletRequest req) {
        ServidorLoja s = servidor(req);
        if (s == null) return naoAutenticado();
        try {
            return ResponseEntity.ok(ApiResponse.ok("Linhas.", servico.linhas(s.companyId(), corpo.tabela(),
                    corpo.faixas() == null ? List.of() : corpo.faixas())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.erro(e.getMessage()));
        }
    }

    @PostMapping("excluir")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> excluir(@RequestBody PedidoExclusao corpo, HttpServletRequest req) {
        ServidorLoja s = servidor(req);
        if (s == null) return naoAutenticado();
        int n = servico.excluir(s.companyId(), corpo.tabelas() == null ? List.of() : corpo.tabelas());
        return ResponseEntity.ok(ApiResponse.ok("Sobras excluidas.", Map.of("excluidas", n)));
    }

    @PostMapping("resultado")
    public ResponseEntity<ApiResponse<Object>> resultado(@RequestBody Resultado corpo, HttpServletRequest req) {
        ServidorLoja s = servidor(req);
        if (s == null) return naoAutenticado();
        servico.registrarResultado(s.companyId(), corpo.origem() == null ? "agendada" : corpo.origem(),
                corpo.resultado() == null ? "erro" : corpo.resultado(), corpo.voltas(),
                corpo.detalhe() == null ? null : corpo.detalhe().toString());
        return ResponseEntity.ok(ApiResponse.ok("Resultado registrado."));
    }
}
