package br.com.quack.pdv.empresa.web;
import br.com.quack.pdv.empresa.app.EmpresaTemaService;
import br.com.quack.pdv.security.AutenticacaoFilter;
import br.com.quack.pdv.security.PerfisPermitidos;
import br.com.quack.pdv.security.UsuarioAutenticado;
import br.com.quack.pdv.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("api/company-theme")
@PerfisPermitidos({"administrador", "gerente", "atendente", "caixa"})
public class CompanyThemeController {
    public record Request(String systemAccent, String pdvAccent) {}
    private final EmpresaTemaService temas;
    public CompanyThemeController(EmpresaTemaService temas) { this.temas=temas; }
    @GetMapping
    public ApiResponse<EmpresaTemaService.Tema> obter(HttpServletRequest http) {
        var tema = temas.obter(atual(http).companyId());
        return ApiResponse.ok(tema == null ? "Sem tema personalizado." : "Tema da empresa.", tema);
    }
    @PutMapping
    @PerfisPermitidos({"administrador", "gerente"})
    public ApiResponse<EmpresaTemaService.Tema> salvar(@RequestBody Request r, HttpServletRequest http) {
        var usuario=atual(http);
        return ApiResponse.ok("Tema salvo.", temas.salvar(usuario.companyId(), usuario.id(), r.systemAccent(), r.pdvAccent()));
    }
    private UsuarioAutenticado atual(HttpServletRequest http) { return (UsuarioAutenticado) http.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO); }
}
