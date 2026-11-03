package br.com.quack.pdv.produto.lote.web;

import br.com.quack.pdv.produto.lote.app.LoteService;
import br.com.quack.pdv.produto.lote.web.LoteDtos.CategoriaValidadeRequest;
import br.com.quack.pdv.produto.lote.web.LoteDtos.FefoModoRequest;
import br.com.quack.pdv.produto.lote.web.LoteDtos.FefoStatusModel;
import br.com.quack.pdv.produto.lote.web.LoteDtos.LoteAlertasResumoModel;
import br.com.quack.pdv.produto.lote.web.LoteDtos.LoteConsultaFiltro;
import br.com.quack.pdv.produto.lote.web.LoteDtos.LoteConsultaModel;
import br.com.quack.pdv.produto.lote.web.LoteDtos.LoteModel;
import br.com.quack.pdv.produto.lote.web.LoteDtos.RegistrarLoteRequest;
import br.com.quack.pdv.security.AutenticacaoFilter;
import br.com.quack.pdv.security.PerfisPermitidos;
import br.com.quack.pdv.security.UsuarioAutenticado;
import br.com.quack.pdv.shared.ApiResponse;
import br.com.quack.pdv.shared.NegocioException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Controle de validade por lote (porta de LoteController), sob api/Produto/lotes. */
@RestController
@RequestMapping("api/Produto/lotes")
public class LoteController {

    private final LoteService lotes;

    public LoteController(LoteService lotes) {
        this.lotes = lotes;
    }

    @GetMapping("alertas")
    public ApiResponse<LoteAlertasResumoModel> alertas(HttpServletRequest req) {
        return ApiResponse.ok(
                "Alertas de validade por lote obtidos com sucesso.", lotes.listarAlertas(atual(req).companyId()));
    }

    @GetMapping("consulta")
    public ApiResponse<LoteConsultaModel> consulta(
            @RequestParam(required = false) String busca,
            @RequestParam(required = false) String de,
            @RequestParam(required = false) String ate,
            @RequestParam(required = false) String categoriaId,
            @RequestParam(required = false) String faixa,
            @RequestParam(required = false) Boolean comSaldo,
            @RequestParam(required = false) String origem,
            @RequestParam(defaultValue = "1") int pagina,
            @RequestParam(defaultValue = "25") int tamanhoPagina,
            HttpServletRequest req) {
        LoteConsultaFiltro filtro =
                new LoteConsultaFiltro(busca, de, ate, categoriaId, faixa, comSaldo, origem, pagina, tamanhoPagina);
        return ApiResponse.ok("Lotes obtidos com sucesso.", lotes.consultar(atual(req).companyId(), filtro));
    }

    @GetMapping("produto/{produtoId}")
    public ApiResponse<List<LoteModel>> porProduto(@PathVariable String produtoId, HttpServletRequest req) {
        return ApiResponse.ok(
                "Lotes do produto obtidos com sucesso.", lotes.listarPorProduto(atual(req).companyId(), produtoId));
    }

    @PostMapping("registrar")
    @PerfisPermitidos({"administrador", "gerente", "atendente", "estoquista"})
    public ResponseEntity<ApiResponse<Object>> registrar(
            @RequestBody(required = false) RegistrarLoteRequest body, HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        lotes.registrarManual(u.companyId(), u.name(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Lote registrado com sucesso."));
    }

    @GetMapping("fefo")
    public ApiResponse<FefoStatusModel> fefoStatus(HttpServletRequest req) {
        return ApiResponse.ok(
                "Situação da baixa por lote obtida com sucesso.", lotes.obterFefoStatus(atual(req).companyId()));
    }

    @PutMapping("fefo/modo")
    @PerfisPermitidos({"administrador", "gerente"})
    public ApiResponse<Object> definirModoFefo(
            @RequestBody(required = false) FefoModoRequest body, HttpServletRequest req) {
        lotes.definirModo(atual(req).companyId(), body == null ? "" : body.modo());
        return ApiResponse.ok("Modo da baixa por lote atualizado.");
    }

    @GetMapping("categorias")
    public ApiResponse<List<CategoriaValidadeModel>> categorias(HttpServletRequest req) {
        return ApiResponse.ok(
                "Prazos de validade por categoria obtidos com sucesso.", lotes.listarCategorias(atual(req).companyId()));
    }

    @PutMapping("categorias/{categoriaId}")
    @PerfisPermitidos({"administrador", "gerente"})
    public ApiResponse<Object> salvarCategoria(
            @PathVariable String categoriaId,
            @RequestBody(required = false) CategoriaValidadeRequest body,
            HttpServletRequest req) {
        if (!lotes.salvarCategoria(atual(req).companyId(), categoriaId, body)) {
            throw new NegocioException(HttpStatus.NOT_FOUND, "Categoria não encontrada.");
        }
        return ApiResponse.ok("Prazo de validade da categoria atualizado.");
    }

    private static UsuarioAutenticado atual(HttpServletRequest req) {
        return (UsuarioAutenticado) req.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO);
    }
}
