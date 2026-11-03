package br.com.quack.pdv.promocao.promocao.web;

import br.com.quack.pdv.promocao.promocao.app.PromocaoService;
import br.com.quack.pdv.security.AutenticacaoFilter;
import br.com.quack.pdv.security.PerfisPermitidos;
import br.com.quack.pdv.security.UsuarioAutenticado;
import br.com.quack.pdv.shared.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("api/Promocao")
public class PromocaoController {
    private final PromocaoService service;

    public PromocaoController(PromocaoService service) {
        this.service = service;
    }

    private UsuarioAutenticado atual(HttpServletRequest r) {
        return (UsuarioAutenticado) r.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO);
    }

    @GetMapping
    public ApiResponse<?> listar(HttpServletRequest r) {
        return ApiResponse.ok(
                "Promoções obtidas com sucesso.", service.listar(atual(r).companyId()));
    }

    @GetMapping("ativas")
    public ApiResponse<?> ativas(HttpServletRequest r) {
        return ApiResponse.ok(
                "Promoções ativas obtidas com sucesso.", service.ativas(atual(r).companyId()));
    }

    @GetMapping("{id}")
    public ResponseEntity<?> obter(@PathVariable String id, HttpServletRequest r) {
        return service.obter(atual(r).companyId(), id)
                .<ResponseEntity<?>>map(
                        p -> ResponseEntity.ok(ApiResponse.ok("Promoção obtida com sucesso.", p)))
                .orElseGet(
                        () ->
                                ResponseEntity.status(404)
                                        .body(ApiResponse.erro("Promoção não encontrada.")));
    }

    @GetMapping("{id}/resultado")
    public ApiResponse<?> resultado(@PathVariable String id, HttpServletRequest r) {
        return ApiResponse.ok(
                "Resultado da promoção obtido com sucesso.",
                service.resultado(atual(r).companyId(), id));
    }

    @PostMapping
    @PerfisPermitidos({"administrador", "gerente"})
    public ResponseEntity<?> criar(@RequestBody PromocaoRequest pedido, HttpServletRequest r) {
        return ResponseEntity.status(201)
                .body(
                        ApiResponse.ok(
                                "Promoção criada com sucesso.",
                                service.criar(atual(r).companyId(), atual(r).name(), pedido)));
    }

    @PutMapping("{id}")
    @PerfisPermitidos({"administrador", "gerente"})
    public ApiResponse<?> atualizar(
            @PathVariable String id, @RequestBody PromocaoRequest pedido, HttpServletRequest r) {
        return ApiResponse.ok(
                "Promoção atualizada com sucesso.",
                service.atualizar(atual(r).companyId(), id, pedido));
    }

    @PatchMapping("{id}/status")
    @PerfisPermitidos({"administrador", "gerente"})
    public ApiResponse<?> status(
            @PathVariable String id,
            @RequestBody PromocaoRequest.Status pedido,
            HttpServletRequest r) {
        service.status(atual(r).companyId(), id, pedido.ativa);
        return ApiResponse.ok(
                pedido.ativa ? "Promoção ativada com sucesso." : "Promoção desativada com sucesso.",
                null);
    }

    @DeleteMapping("{id}")
    @PerfisPermitidos({"administrador", "gerente"})
    public ApiResponse<?> excluir(@PathVariable String id, HttpServletRequest r) {
        service.excluir(atual(r).companyId(), id);
        return ApiResponse.ok("Promoção excluída com sucesso.", null);
    }
}
