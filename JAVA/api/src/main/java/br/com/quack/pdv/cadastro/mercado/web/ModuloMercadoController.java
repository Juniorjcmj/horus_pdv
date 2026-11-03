package br.com.quack.pdv.cadastro.mercado.web;
import br.com.quack.pdv.cadastro.mercado.app.ModuloMercadoService;
import br.com.quack.pdv.security.*;
import br.com.quack.pdv.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("api/ModuloMercado") @PerfisPermitidos({"administrador","gerente","atendente"})
public class ModuloMercadoController {
    private final ModuloMercadoService modulos;
    public ModuloMercadoController(ModuloMercadoService modulos) {this.modulos=modulos;}
    @GetMapping("{id}") public ApiResponse<?> obter(@PathVariable String id,HttpServletRequest h) {return ApiResponse.ok("Modulo obtido com sucesso.",modulos.obter(atual(h).companyId(),id));}
    @PostMapping("{id}/registros") public ResponseEntity<?> criar(@PathVariable String id,@RequestBody RegistroRequest r,HttpServletRequest h) {return ResponseEntity.status(201).body(ApiResponse.ok("Registro criado com sucesso.",modulos.criar(atual(h).companyId(),id,r)));}
    @PutMapping("{id}/registros/{recordId}") public ApiResponse<?> atualizar(@PathVariable String id,@PathVariable String recordId,@RequestBody RegistroRequest r,HttpServletRequest h) {return ApiResponse.ok("Registro atualizado com sucesso.",modulos.atualizar(atual(h).companyId(),id,recordId,r));}
    @DeleteMapping("{id}/registros/{recordId}") public ApiResponse<?> excluir(@PathVariable String id,@PathVariable String recordId,HttpServletRequest h) {return ApiResponse.ok("Registro removido com sucesso.",modulos.excluir(atual(h).companyId(),id,recordId));}
    private UsuarioAutenticado atual(HttpServletRequest h) {return (UsuarioAutenticado)h.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO);}
}
