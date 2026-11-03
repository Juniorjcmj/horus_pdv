package br.com.quack.pdv.compras.ordem.web;

import br.com.quack.pdv.compras.ordem.app.OrdemCompraService;
import br.com.quack.pdv.security.AutenticacaoFilter;
import br.com.quack.pdv.security.PerfisPermitidos;
import br.com.quack.pdv.security.UsuarioAutenticado;
import br.com.quack.pdv.shared.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("api/OrdemCompra")
@PerfisPermitidos({"administrador", "gerente"})
public class OrdemCompraController {
    private final OrdemCompraService service;

    public OrdemCompraController(OrdemCompraService service) {
        this.service = service;
    }

    private UsuarioAutenticado atual(HttpServletRequest r) {
        return (UsuarioAutenticado) r.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO);
    }

    @PostMapping
    public ResponseEntity<?> criar(
            @RequestBody OrdemCompraRequests.Pedido r, HttpServletRequest req) {
        var u = atual(req);
        try {
            return ResponseEntity.status(201)
                    .body(
                            ApiResponse.ok(
                                    "Ordem de compra criada com sucesso.",
                                    Map.of("orderNumber", service.criar(u.companyId(), r, u))));
        } catch (Exception ex) {
            return erro(ex);
        }
    }

    @PutMapping("{numero}")
    public ResponseEntity<?> atualizar(
            @PathVariable String numero,
            @RequestBody OrdemCompraRequests.Pedido r,
            HttpServletRequest req) {
        try {
            return ResponseEntity.ok(
                    ApiResponse.ok(
                            "Ordem de compra atualizada com sucesso.",
                            service.atualizar(atual(req).companyId(), numero, r)));
        } catch (Exception ex) {
            return erro(ex);
        }
    }

    @GetMapping
    public ApiResponse<?> listar(
            @RequestParam(required = false) Integer status, HttpServletRequest req) {
        return ApiResponse.ok(
                "Ordens de compra listadas.", service.listar(atual(req).companyId(), status));
    }

    @GetMapping("{numero}")
    public ResponseEntity<?> obter(@PathVariable String numero, HttpServletRequest req) {
        return service.obter(atual(req).companyId(), numero)
                .<ResponseEntity<?>>map(
                        o -> ResponseEntity.ok(ApiResponse.ok("Ordem de compra encontrada.", o)))
                .orElseGet(
                        () ->
                                ResponseEntity.status(404)
                                        .body(ApiResponse.erro("Ordem de compra não encontrada.")));
    }

    @PostMapping("{numero}/receber")
    public ResponseEntity<?> receber(
            @PathVariable String numero,
            @RequestBody OrdemCompraRequests.Receber r,
            HttpServletRequest req) {
        var u = atual(req);
        try {
            int status = service.receber(u.companyId(), numero, r, u);
            String mensagem =
                    switch (status) {
                        case 1 -> "Ordem de compra recebida integralmente. Estoque atualizado.";
                        case 3 ->
                                "Recebimento parcial registrado. Estoque atualizado para os itens"
                                        + " recebidos.";
                        default -> "Recebimento processado.";
                    };
            return ResponseEntity.ok(ApiResponse.ok(mensagem, Map.of("status", status)));
        } catch (Exception ex) {
            return erro(ex);
        }
    }

    @PostMapping("{numero}/cancelar")
    public ResponseEntity<?> cancelar(
            @PathVariable String numero,
            @RequestBody(required = false) OrdemCompraRequests.Cancelar r,
            HttpServletRequest req) {
        return service.cancelar(
                        atual(req).companyId(), numero, r == null ? null : r.motivoCancelamento)
                ? ResponseEntity.ok(ApiResponse.ok("Ordem de compra cancelada com sucesso."))
                : ResponseEntity.status(404)
                        .body(
                                ApiResponse.erro(
                                        "Ordem de compra não encontrada ou não está pendente."));
    }

    @GetMapping("sugestoes-reposicao")
    public ApiResponse<?> sugestoes(HttpServletRequest req) {
        return ApiResponse.ok("Sugestões de reposição.", service.sugestoes(atual(req).companyId()));
    }

    private ResponseEntity<?> erro(Exception ex) {
        return ResponseEntity.badRequest().body(ApiResponse.erro(mensagemErro(ex)));
    }

    static String mensagemErro(Exception ex) {
        Throwable causa =
                ex instanceof org.springframework.dao.DataAccessException dados
                        ? dados.getMostSpecificCause()
                        : ex;
        String mensagem = causa.getMessage();
        if (causa instanceof java.sql.SQLException sql && sql.getErrorCode() == 547) {
            // SqlClient inclui 3621 em Errors; JDBC pode descartar esse diagnóstico.
            // Preserve a cadeia quando disponível e reproduza o sufixo somente para
            // a violação FK de INSERT observada no contrato .NET (SQL Server em inglês).
            for (java.sql.SQLException proxima = sql.getNextException();
                    proxima != null;
                    proxima = proxima.getNextException()) {
                if (proxima.getErrorCode() == 3621
                        && proxima.getMessage() != null
                        && !mensagem.contains(proxima.getMessage())) {
                    mensagem += "\n" + proxima.getMessage();
                }
            }
            if (mensagem.startsWith(
                            "The INSERT statement conflicted with the FOREIGN KEY constraint ")
                    && !mensagem.contains("\nThe statement has been terminated.")) {
                mensagem += "\nThe statement has been terminated.";
            }
        }
        return mensagem;
    }
}
