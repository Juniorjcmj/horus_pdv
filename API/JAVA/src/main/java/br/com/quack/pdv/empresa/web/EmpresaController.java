package br.com.quack.pdv.empresa.web;
import br.com.quack.pdv.empresa.app.EmpresaService;
import br.com.quack.pdv.security.AutenticacaoFilter;
import br.com.quack.pdv.security.PerfisPermitidos;
import br.com.quack.pdv.security.UsuarioAutenticado;
import br.com.quack.pdv.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("api/Empresa")
public class EmpresaController {
    private final EmpresaService empresas;
    public EmpresaController(EmpresaService empresas) { this.empresas = empresas; }
    @GetMapping
    public ApiResponse<EmpresaRequest> obter(HttpServletRequest http) {
        return ApiResponse.ok("Dados da empresa obtidos com sucesso.", empresas.obter(atual(http).companyId()));
    }
    @PutMapping
    @PerfisPermitidos({"administrador"})
    public ApiResponse<EmpresaRequest> salvar(@RequestBody EmpresaRequest request, HttpServletRequest http) {
        return ApiResponse.ok("Dados da empresa atualizados com sucesso.", empresas.salvar(atual(http).companyId(), request));
    }
    private UsuarioAutenticado atual(HttpServletRequest http) { return (UsuarioAutenticado) http.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO); }
}
