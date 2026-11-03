package br.com.quack.pdv.produto.entrada.web;

import br.com.quack.pdv.produto.entrada.app.EntradaManualService;
import br.com.quack.pdv.security.*;
import br.com.quack.pdv.shared.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("api/Produto/entrada-manual")
@PerfisPermitidos({"administrador", "gerente", "atendente", "estoquista"})
public class EntradaManualController {
    private final EntradaManualService service;

    public EntradaManualController(EntradaManualService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<?> confirmar(
            @RequestBody NfeImportRequests.EntradaManualRequest r, HttpServletRequest req) {
        var u = (UsuarioAutenticado) req.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO);
        return ApiResponse.ok(
                "Entrada registrada no estoque.",
                service.confirmar(u.companyId(), r, u.id(), u.name()));
    }
}
