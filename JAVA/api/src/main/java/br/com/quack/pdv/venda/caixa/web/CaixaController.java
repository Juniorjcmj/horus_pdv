package br.com.quack.pdv.venda.caixa.web;

import br.com.quack.pdv.security.AutenticacaoFilter;
import br.com.quack.pdv.security.ClienteIpResolver;
import br.com.quack.pdv.security.UsuarioAutenticado;
import br.com.quack.pdv.shared.ApiResponse;
import br.com.quack.pdv.venda.caixa.app.CaixaComandos;
import br.com.quack.pdv.venda.caixa.app.CaixaService;
import br.com.quack.pdv.venda.caixa.app.CaixaTempo;
import br.com.quack.pdv.venda.caixa.domain.CaixaStatusDto;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas do caixa (CaixaController.cs). Sem atributo de perfil no .NET: qualquer usuario autenticado; o que cada
 * perfil enxerga e decidido no servico.
 */
@RestController
@RequestMapping("api/Caixa")
public class CaixaController {

    private static final MediaType PROBLEMA = MediaType.parseMediaType("application/problem+json");

    private final CaixaService service;
    private final ClienteIpResolver ipResolver;

    public CaixaController(CaixaService service, ClienteIpResolver ipResolver) {
        this.service = service;
        this.ipResolver = ipResolver;
    }

    private static UsuarioAutenticado atual(HttpServletRequest r) {
        return (UsuarioAutenticado) r.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO);
    }

    private static ResponseEntity<?> semSessao() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.erro("Sessão não encontrada."));
    }

    @GetMapping("status")
    public ResponseEntity<?> status(HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        if (u == null) return semSessao();
        return ResponseEntity.ok(ApiResponse.ok("Status do caixa obtido com sucesso.", service.status(u)));
    }

    @GetMapping("sessao/{id}")
    public ResponseEntity<?> sessao(@PathVariable String id, HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        if (u == null) return semSessao();
        return service.sessao(u, id)
                .<ResponseEntity<?>>map(s -> ResponseEntity.ok(ApiResponse.ok("Turno de caixa obtido com sucesso.", s)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.erro("Turno de caixa não encontrado.")));
    }

    @GetMapping("movimentos")
    public ResponseEntity<?> movimentos(@RequestParam(required = false) String de,
                                        @RequestParam(required = false) String ate, HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        if (u == null) return semSessao();
        LocalDate hoje = service.hoje();
        LocalDate dtDe = CaixaTempo.lerData(de).orElse(hoje.minusDays(30));
        LocalDate dtAte = CaixaTempo.lerData(ate).orElse(hoje);
        if (dtAte.isBefore(dtDe)) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.erro("Período inválido: a data final é anterior à inicial."));
        }
        return ResponseEntity.ok(ApiResponse.ok("Movimentos de caixa obtidos com sucesso.",
                service.movimentosPorPeriodo(u, dtDe, dtAte)));
    }

    @PostMapping("abrir")
    public ResponseEntity<?> abrir(@RequestBody CaixaRequests.Abrir body, HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        if (u == null) return semSessao();
        ResponseEntity<?> invalido = validar(body.camposObrigatoriosAusentes(), req);
        if (invalido != null) return invalido;
        CaixaStatusDto r = service.abrir(new CaixaComandos.Abrir(body.eventId, body.payloadHash, body.openingAmount,
                body.clientSessionId, body.openedAt), u, ipResolver.resolver(req));
        return ResponseEntity.ok(ApiResponse.ok(r.isReplay()
                ? "Abertura de caixa já processada anteriormente (idempotente)." : "Caixa aberto com sucesso.", r));
    }

    @PostMapping("fechar")
    public ResponseEntity<?> fechar(@RequestBody CaixaRequests.Fechar body, HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        if (u == null) return semSessao();
        ResponseEntity<?> invalido = validar(body.camposObrigatoriosAusentes(), req);
        if (invalido != null) return invalido;
        CaixaStatusDto r = service.fechar(new CaixaComandos.Fechar(body.eventId, body.payloadHash, body.closingAmount,
                body.note, body.differenceReason, body.clientSessionId, body.closedAt), u, ipResolver.resolver(req));
        return ResponseEntity.ok(ApiResponse.ok(r.isReplay()
                ? "Fechamento de caixa já processado anteriormente (idempotente)." : "Caixa fechado com sucesso.", r));
    }

    @PostMapping("movimento")
    public ResponseEntity<?> movimento(@RequestBody CaixaRequests.Movimento body, HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        if (u == null) return semSessao();
        ResponseEntity<?> invalido = validar(body.camposObrigatoriosAusentes(), req);
        if (invalido != null) return invalido;
        CaixaStatusDto r = service.registrarMovimento(new CaixaComandos.Movimento(body.eventId, body.payloadHash,
                body.tipo, body.valor, body.motivo), u, ipResolver.resolver(req));
        if (r.isReplay()) {
            return ResponseEntity.ok(ApiResponse.ok("Movimento de caixa já processado anteriormente (idempotente).", r));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Movimento de caixa registrado com sucesso.", r));
    }

    /** Validacao de modelo do ASP.NET: 400 application/problem+json com os campos obrigatorios ausentes. */
    private static ResponseEntity<?> validar(Map<String, String> ausentes, HttpServletRequest req) {
        if (ausentes.isEmpty()) return null;
        Map<String, Object> erros = new LinkedHashMap<>();
        ausentes.forEach((campo, msg) -> erros.put(campo, List.of(msg)));
        String id = String.valueOf(req.getAttribute("quack.requestId"));
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("type", "https://tools.ietf.org/html/rfc9110#section-15.5.1");
        corpo.put("title", "One or more validation errors occurred.");
        corpo.put("status", 400);
        corpo.put("errors", erros);
        corpo.put("traceId", "00-" + id + "-" + id.substring(0, Math.min(16, id.length())) + "-00");
        return ResponseEntity.badRequest().contentType(PROBLEMA).body(corpo);
    }
}
