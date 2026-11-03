package br.com.quack.pdv.empresa.web;
import br.com.quack.pdv.empresa.app.*;
import br.com.quack.pdv.security.*;
import br.com.quack.pdv.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("api/Admin/Empresas")
public class GerenciamentoEmpresasController {
    public record Motivo(String reason) {}
    public record Credenciais(String newEmail,String newPassword) {}
    public static class Configuracao {public boolean requireApprovalForNewCompanies=true;}
    private final GerenciamentoEmpresasService empresas;private final AdministradorGeral geral;
    public GerenciamentoEmpresasController(GerenciamentoEmpresasService empresas,AdministradorGeral geral) {this.empresas=empresas;this.geral=geral;}
    @GetMapping public ApiResponse<?> listar(@RequestParam(required=false)String search,@RequestParam(required=false)String status,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,HttpServletRequest h) {admin(h);return ApiResponse.ok("Empresas listadas com sucesso.",empresas.listar(search,status,page,pageSize));}
    @GetMapping("metricas") public ApiResponse<?> metricas(HttpServletRequest h) {admin(h);return ApiResponse.ok("Métricas obtidas com sucesso.",empresas.metricas());}
    @GetMapping("configuracao") public ApiResponse<?> configuracao(HttpServletRequest h) {admin(h);return ApiResponse.ok("Configuração obtida com sucesso.",Map.of("requireApprovalForNewCompanies",empresas.configuracao()));}
    @PutMapping("configuracao") public ApiResponse<?> configurar(@RequestBody Configuracao r,HttpServletRequest h) {admin(h);return ApiResponse.ok(empresas.configurar(r.requireApprovalForNewCompanies),Map.of("requireApprovalForNewCompanies",r.requireApprovalForNewCompanies));}
    @PostMapping("{id}/aprovar") public ApiResponse<?> aprovar(@PathVariable String id,HttpServletRequest h) {return situacao("aprovar",id,null,h);}
    @PostMapping("{id}/reativar") public ApiResponse<?> reativar(@PathVariable String id,HttpServletRequest h) {return situacao("reativar",id,null,h);}
    @PostMapping("{id}/rejeitar") public ApiResponse<?> rejeitar(@PathVariable String id,@RequestBody Motivo r,HttpServletRequest h) {return situacao("rejeitar",id,r.reason(),h);}
    @PostMapping("{id}/bloquear") public ApiResponse<?> bloquear(@PathVariable String id,@RequestBody Motivo r,HttpServletRequest h) {return situacao("bloquear",id,r.reason(),h);}
    @PutMapping("{id}/credenciais") public ApiResponse<?> credenciais(@PathVariable String id,@RequestBody Credenciais r,HttpServletRequest h) {admin(h);return ApiResponse.ok(empresas.credenciais(id,r.newEmail(),r.newPassword()));}
    @DeleteMapping("{id}") public ApiResponse<?> excluir(@PathVariable String id,HttpServletRequest h) {admin(h);return ApiResponse.ok(empresas.excluir(id));}
    private ApiResponse<?> situacao(String acao,String id,String motivo,HttpServletRequest h) {return ApiResponse.ok(empresas.situacao(acao,id,motivo,admin(h).name()));}
    private UsuarioAutenticado admin(HttpServletRequest h) {var u=(UsuarioAutenticado)h.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO);if(!geral.ehAdministradorGeral(u))throw new NegocioException(HttpStatus.FORBIDDEN,"Acesso negado: módulo restrito ao Administrador Geral da Plataforma.");return u;}
}
