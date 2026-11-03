package br.com.quack.pdv.cadastro.categoria.web;
import br.com.quack.pdv.cadastro.categoria.app.CategoriaService;
import br.com.quack.pdv.security.*;
import br.com.quack.pdv.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("api/Categoria")
public class CategoriaController {
    public record StatusRequest(boolean ativa) {}
    private final CategoriaService categorias;
    public CategoriaController(CategoriaService categorias) {this.categorias=categorias;}
    @GetMapping public ApiResponse<?> listar(@RequestParam(defaultValue="false")boolean plana,@RequestParam(defaultValue="false")boolean apenasAtivas,HttpServletRequest h) {return ApiResponse.ok("Categorias obtidas com sucesso.",categorias.listar(atual(h).companyId(),plana,apenasAtivas));}
    @GetMapping("{id}") public ApiResponse<?> obter(@PathVariable String id,HttpServletRequest h) {return ApiResponse.ok("Categoria obtida com sucesso.",categorias.obter(atual(h).companyId(),id));}
    @PostMapping @PerfisPermitidos({"administrador","gerente"}) public ResponseEntity<?> criar(@RequestBody CategoriaRequest r,HttpServletRequest h) {return ResponseEntity.status(201).body(ApiResponse.ok("Categoria criada com sucesso.",categorias.criar(atual(h).companyId(),r)));}
    @PutMapping("{id}") @PerfisPermitidos({"administrador","gerente"}) public ApiResponse<?> atualizar(@PathVariable String id,@RequestBody CategoriaRequest r,HttpServletRequest h) {return ApiResponse.ok("Categoria atualizada com sucesso.",categorias.atualizar(atual(h).companyId(),id,r));}
    @PatchMapping("{id}/status") @PerfisPermitidos({"administrador","gerente"}) public ApiResponse<?> status(@PathVariable String id,@RequestBody StatusRequest r,HttpServletRequest h) {categorias.status(atual(h).companyId(),id,r.ativa());return ApiResponse.ok(r.ativa()?"Categoria ativada com sucesso.":"Categoria desativada com sucesso.");}
    @DeleteMapping("{id}") @PerfisPermitidos({"administrador","gerente"}) public ApiResponse<?> excluir(@PathVariable String id,HttpServletRequest h) {categorias.excluir(atual(h).companyId(),id);return ApiResponse.ok("Categoria removida com sucesso.");}
    private UsuarioAutenticado atual(HttpServletRequest h) {return (UsuarioAutenticado)h.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO);}
}
