package br.com.quack.pdv.produto.produto.web;

import br.com.quack.pdv.estoque.movimentacao.app.EstoqueMovimentacaoService;
import br.com.quack.pdv.estoque.movimentacao.web.EstoqueMovimentacaoDto;
import br.com.quack.pdv.produto.produto.app.ProdutoService;
import br.com.quack.pdv.security.AutenticacaoFilter;
import br.com.quack.pdv.security.PerfisPermitidos;
import br.com.quack.pdv.security.UsuarioAutenticado;
import br.com.quack.pdv.shared.ApiResponse;
import br.com.quack.pdv.shared.NegocioException;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("api/Produto")
public class ProdutoController {

    private final ProdutoService produtos;
    private final EstoqueMovimentacaoService movimentacoes;

    public ProdutoController(ProdutoService produtos, EstoqueMovimentacaoService movimentacoes) {
        this.produtos = produtos;
        this.movimentacoes = movimentacoes;
    }

    @GetMapping
    public ApiResponse<List<ProdutoModel>> listar(HttpServletRequest req) {
        return ApiResponse.ok("Produtos obtidos com sucesso.", produtos.listar(atual(req).companyId()));
    }

    @PostMapping
    @PerfisPermitidos({"administrador", "gerente", "atendente"})
    public ResponseEntity<ApiResponse<ProdutoModel>> criar(
            @RequestBody(required = false) ProdutoRequest body, HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        ProdutoModel criado = produtos.criar(u.companyId(), body, u.id(), u.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Produto criado com sucesso.", criado));
    }

    @PutMapping("{id}")
    @PerfisPermitidos({"administrador", "gerente", "atendente"})
    public ApiResponse<ProdutoModel> atualizar(
            @PathVariable String id,
            @RequestBody(required = false) ProdutoRequest body,
            HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        ProdutoModel atualizado = produtos.atualizar(u.companyId(), id, body, u.id(), u.name())
                .orElseThrow(() -> naoEncontrado());
        return ApiResponse.ok("Produto atualizado com sucesso.", atualizado);
    }

    @PatchMapping("{id}/preco-venda")
    @PerfisPermitidos({"administrador", "gerente", "atendente"})
    public ApiResponse<ProdutoModel> atualizarPreco(
            @PathVariable String id,
            @RequestBody(required = false) ProdutoPrecoRequest body,
            HttpServletRequest req) {
        ProdutoModel atualizado = produtos.atualizarPreco(atual(req).companyId(), id, body)
                .orElseThrow(() -> naoEncontrado());
        return ApiResponse.ok("Preço de venda atualizado.", atualizado);
    }

    @DeleteMapping("{id}")
    @PerfisPermitidos({"administrador", "gerente", "atendente"})
    public ApiResponse<Object> excluir(@PathVariable String id, HttpServletRequest req) {
        if (!produtos.excluir(atual(req).companyId(), id)) {
            throw naoEncontrado();
        }
        return ApiResponse.ok("Produto removido com sucesso.");
    }

    @GetMapping("vencimentos")
    public ApiResponse<List<ProdutoModel>> listarVencimentos(
            @RequestParam(defaultValue = "15") int dias, HttpServletRequest req) {
        return ApiResponse.ok(
                "Produtos com controle de validade obtidos com sucesso.",
                produtos.listarVencimentos(atual(req).companyId(), dias));
    }

    @GetMapping("vencimentos/resumo")
    public ApiResponse<VencimentoResumoModel> obterResumoVencimentos(HttpServletRequest req) {
        return ApiResponse.ok(
                "Resumo de vencimentos obtido com sucesso.",
                produtos.obterResumoVencimentos(atual(req).companyId()));
    }

    @PutMapping("{id}/validade")
    @PerfisPermitidos({"administrador", "gerente", "atendente", "estoquista"})
    public ApiResponse<Object> atualizarValidade(
            @PathVariable String id,
            @RequestBody(required = false) AtualizarValidadeRequest body,
            HttpServletRequest req) {
        String dv = body == null ? null : body.dataValidade();
        if (!produtos.atualizarValidade(atual(req).companyId(), id, dv)) {
            throw naoEncontrado();
        }
        return ApiResponse.ok("Validade atualizada com sucesso.");
    }

    @PostMapping("{id}/ajuste-estoque")
    @PerfisPermitidos({"administrador", "gerente", "atendente", "estoquista"})
    public ApiResponse<Object> ajustarEstoque(
            @PathVariable String id,
            @RequestBody(required = false) AjusteEstoqueRequest body,
            HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        String tipo = body == null ? null : body.tipo();
        BigDecimal qtd = body == null ? null : body.quantidade();
        String motivo = body == null ? null : body.motivo();
        String dv = body == null ? null : body.dataValidade();
        String lote = body == null ? null : body.numeroLote();

        boolean ok = produtos.ajustarEstoque(
                u.companyId(), id, tipo, qtd, motivo, u.id(), u.name(), dv, lote);
        if (!ok) {
            throw naoEncontrado();
        }
        String tipoNorm = (tipo == null ? "" : tipo.trim()).toLowerCase(Locale.ROOT);
        String label = switch (tipoNorm) {
            case "entrada" -> "Entrada";
            case "uso_interno" -> "Uso interno";
            case "perda" -> "Perda";
            default -> "Saída";
        };
        return ApiResponse.ok(label + " de " + qtd.toPlainString() + " registrada com sucesso.");
    }

    @GetMapping("{id}/movimentacoes")
    @PerfisPermitidos({"administrador", "gerente", "atendente", "estoquista"})
    public ApiResponse<List<EstoqueMovimentacaoDto>> listarMovimentacoes(
            @PathVariable String id, HttpServletRequest req) {
        return ApiResponse.ok(
                "Movimentações obtidas com sucesso.",
                movimentacoes.listarPorProduto(atual(req).companyId(), id));
    }

    @GetMapping("movimentacoes")
    @PerfisPermitidos({"administrador", "gerente", "atendente", "estoquista"})
    public ApiResponse<List<EstoqueMovimentacaoDto>> listarTodasMovimentacoes(
            @RequestParam(required = false) String tipo,
            @RequestParam(defaultValue = "200") int limit,
            HttpServletRequest req) {
        return ApiResponse.ok(
                "Movimentações obtidas com sucesso.",
                movimentacoes.listar(atual(req).companyId(), tipo, limit));
    }

    @PostMapping("importar-legado")
    @PerfisPermitidos({"administrador", "gerente"})
    public ResponseEntity<ApiResponse<?>> importarLegado(HttpServletRequest req) {
        try {
            int rows = produtos.importarCargaLegado(atual(req).companyId());
            return ResponseEntity.ok(ApiResponse.ok(
                    "Carga de 4.714 produtos importada com sucesso!",
                    Map.of("rowsAffected", rows)));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(ApiResponse.erro("Erro ao importar carga: " + ex.getMessage()));
        }
    }

    private static NegocioException naoEncontrado() {
        return new NegocioException(HttpStatus.NOT_FOUND, "Produto não encontrado.");
    }

    private static UsuarioAutenticado atual(HttpServletRequest req) {
        return (UsuarioAutenticado) req.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO);
    }
}
