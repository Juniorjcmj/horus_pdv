package br.com.quack.pdv.estoque.inventario.web;

import br.com.quack.pdv.estoque.inventario.app.InventarioAtor;
import br.com.quack.pdv.estoque.inventario.app.InventarioConsultas;
import br.com.quack.pdv.estoque.inventario.app.InventarioResultado;
import br.com.quack.pdv.estoque.inventario.app.InventarioService;
import br.com.quack.pdv.security.AutenticacaoFilter;
import br.com.quack.pdv.security.ClienteIpResolver;
import br.com.quack.pdv.security.Perfis;
import br.com.quack.pdv.security.PerfisPermitidos;
import br.com.quack.pdv.security.UsuarioAutenticado;
import br.com.quack.pdv.shared.ApiResponse;
import br.com.quack.pdv.usuario.app.ValidadorSupervisor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas do inventario. Abrir, contar, consultar e anular: administrador, gerente e estoquista. Recontar, aplicar,
 * cancelar e trilha: gerencia. Aplicar exige tambem a senha de um supervisor da empresa.
 */
@RestController
@RequestMapping("api/Inventario")
public class InventarioController {

    private final InventarioService service;
    private final InventarioConsultas consultas;
    private final ValidadorSupervisor supervisor;
    private final ClienteIpResolver ipResolver;

    public InventarioController(
            InventarioService service,
            InventarioConsultas consultas,
            ValidadorSupervisor supervisor,
            ClienteIpResolver ipResolver) {
        this.service = service;
        this.consultas = consultas;
        this.supervisor = supervisor;
        this.ipResolver = ipResolver;
    }

    private static UsuarioAutenticado atual(HttpServletRequest r) {
        return (UsuarioAutenticado) r.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO);
    }

    private InventarioAtor ator(UsuarioAutenticado u, String terminal, HttpServletRequest r) {
        String t = terminal == null || terminal.isBlank() ? null : terminal.trim();
        return new InventarioAtor(u.id(), u.name(), ipResolver.resolver(r), t);
    }

    private static boolean comValores(UsuarioAutenticado u) {
        return Perfis.ehGerenteOuAdmin(u.role());
    }

    @PostMapping
    @PerfisPermitidos({"administrador", "gerente", "estoquista"})
    public ResponseEntity<?> abrir(@RequestBody InventarioRequests.Abrir body, HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        return responder(service.abrir(u.companyId(), ator(u, body.terminal, req), body.descricao == null ? "" : body.descricao,
                body.escopo, body.categoriaIds, body.produtoIds));
    }

    @GetMapping
    @PerfisPermitidos({"administrador", "gerente", "estoquista"})
    public ApiResponse<?> listar(@RequestParam(required = false) String status, HttpServletRequest req) {
        return ApiResponse.ok("Inventários.", consultas.listar(atual(req).companyId(), status));
    }

    @GetMapping("{id}")
    @PerfisPermitidos({"administrador", "gerente", "estoquista"})
    public ResponseEntity<?> obter(@PathVariable String id, HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        return consultas.obter(u.companyId(), id, comValores(u))
                .<ResponseEntity<?>>map(d -> ResponseEntity.ok(ApiResponse.ok("Inventário.", d)))
                .orElseGet(InventarioController::naoEncontrado);
    }

    @GetMapping("{id}/itens")
    @PerfisPermitidos({"administrador", "gerente", "estoquista"})
    public ResponseEntity<?> itens(
            @PathVariable String id, @RequestParam(required = false) String filtro, HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        return consultas.itens(u.companyId(), id, filtro, comValores(u))
                .<ResponseEntity<?>>map(d -> ResponseEntity.ok(ApiResponse.ok("Itens do inventário.", d)))
                .orElseGet(InventarioController::naoEncontrado);
    }

    /** Contagens do inventario. Por padrao so as do usuario logado (ultimos lancamentos). */
    @GetMapping("{id}/contagens")
    @PerfisPermitidos({"administrador", "gerente", "estoquista"})
    public ApiResponse<?> contagens(
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean todas,
            @RequestParam(required = false) String produtoId,
            @RequestParam(defaultValue = "50") int limite,
            HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        boolean soDoUsuario = !(todas && comValores(u));
        return ApiResponse.ok("Contagens.",
                consultas.contagens(u.companyId(), id, soDoUsuario ? u.id() : null, produtoId, limite));
    }

    @PostMapping("{id}/contagens")
    @PerfisPermitidos({"administrador", "gerente", "estoquista"})
    public ResponseEntity<?> contar(
            @PathVariable String id, @RequestBody InventarioRequests.Contagem body, HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        return responder(service.contar(u.companyId(), id, ator(u, body.terminal, req), body.produtoId, body.codigo,
                body.quantidade, body.local));
    }

    @PostMapping("{id}/contagens/{contagemId}/anular")
    @PerfisPermitidos({"administrador", "gerente", "estoquista"})
    public ResponseEntity<?> anular(
            @PathVariable String id, @PathVariable String contagemId,
            @RequestBody InventarioRequests.Anular body, HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        return responder(service.anularContagem(u.companyId(), id, contagemId, ator(u, null, req), body.motivo,
                !Perfis.ehGerenteOuAdmin(u.role())));
    }

    @PostMapping("{id}/recontar")
    @PerfisPermitidos({"administrador", "gerente"})
    public ResponseEntity<?> recontar(
            @PathVariable String id, @RequestBody InventarioRequests.Recontar body, HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        return responder(service.recontar(u.companyId(), id, ator(u, null, req), body.produtoIds));
    }

    @PostMapping("{id}/aplicar")
    @PerfisPermitidos({"administrador", "gerente"})
    public ResponseEntity<?> aplicar(
            @PathVariable String id, @RequestBody InventarioRequests.Aplicar body, HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        InventarioAtor ator = ator(u, body.terminal, req);
        String justificativa = body.justificativa == null ? "" : body.justificativa.trim();

        if (justificativa.length() < 5) {
            String msg = "Informe uma justificativa de no mínimo 5 caracteres.";
            service.registrarNegada(u.companyId(), id, ator, body.supervisorId, msg);
            return ResponseEntity.badRequest().body(erroComCodigo(msg, "JUSTIFICATIVA_CURTA"));
        }

        ValidadorSupervisor.Resultado v = supervisor.validar(body.supervisorId, body.supervisorPassword, u.companyId());
        if (!v.valido() || v.supervisor() == null) {
            service.registrarNegada(u.companyId(), id, ator, body.supervisorId, v.mensagem());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(erroComCodigo(v.mensagem(), "SENHA_INVALIDA"));
        }

        InventarioResultado r = service.aplicar(u.companyId(), id, ator, v.supervisor().id(), v.supervisor().name(),
                justificativa, body.zerarNaoContados);
        if (!r.sucesso() && !"NAO_ENCONTRADO".equals(r.codigo())) {
            service.registrarNegada(u.companyId(), id, ator, v.supervisor().id(), r.mensagem());
        }
        // Ponto de extensao (Fase 6): invalidar o cache da analise de estoque da empresa quando r.sucesso().
        return responder(r);
    }

    @PostMapping("{id}/cancelar")
    @PerfisPermitidos({"administrador", "gerente"})
    public ResponseEntity<?> cancelar(
            @PathVariable String id, @RequestBody InventarioRequests.Cancelar body, HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        return responder(service.cancelar(u.companyId(), id, ator(u, body.terminal, req), body.motivo));
    }

    @GetMapping("{id}/trilha")
    @PerfisPermitidos({"administrador", "gerente"})
    public ResponseEntity<?> trilha(@PathVariable String id, HttpServletRequest req) {
        Optional<Map<String, Object>> t = consultas.trilha(atual(req).companyId(), id);
        return t.<ResponseEntity<?>>map(d -> ResponseEntity.ok(ApiResponse.ok("Trilha do inventário.", d)))
                .orElseGet(InventarioController::naoEncontrado);
    }

    // ------------------------------------------------------------------

    /** Mesmo mapa codigo -> HTTP do Responder do .NET. */
    private static ResponseEntity<?> responder(InventarioResultado r) {
        if (r.sucesso()) return ResponseEntity.ok(ApiResponse.ok(r.mensagem(), r.dados()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("codigo", r.codigo());
        if (r.dados() != null) data.put("detalhe", r.dados());
        return ResponseEntity.status(statusDe(r.codigo())).body(ApiResponse.erro(r.mensagem(), null, data));
    }

    static HttpStatus statusDe(String codigo) {
        return switch (codigo) {
            case "NAO_ENCONTRADO", "CODIGO_DESCONHECIDO" -> HttpStatus.NOT_FOUND;
            case "ESCOPO_SOBREPOSTO", "INVENTARIO_ENCERRADO", "JA_APLICADO", "JA_ANULADA", "OCUPADO" -> HttpStatus.CONFLICT;
            case "SEM_PERMISSAO" -> HttpStatus.FORBIDDEN;
            case "FORA_DO_ESCOPO" -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    private static ApiResponse<Object> erroComCodigo(String mensagem, String codigo) {
        return ApiResponse.erro(mensagem, null, (Object) Map.of("codigo", codigo));
    }

    private static ResponseEntity<?> naoEncontrado() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.erro("Inventário não encontrado.", null, (Object) Map.of("codigo", "NAO_ENCONTRADO")));
    }
}
