package br.com.quack.pdv.cadastro.fornecedor.web;
import br.com.quack.pdv.cadastro.fornecedor.app.FornecedorService;
import br.com.quack.pdv.security.*;
import br.com.quack.pdv.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("api/Fornecedor") @PerfisPermitidos({"administrador","gerente","atendente"})
public class FornecedorController {
    private final FornecedorService fornecedores;
    public FornecedorController(FornecedorService fornecedores) { this.fornecedores=fornecedores; }
    @GetMapping public ApiResponse<?> listar(HttpServletRequest h) { return ApiResponse.ok("Fornecedores obtidos com sucesso.",fornecedores.listar(atual(h).companyId())); }
    @PostMapping public ResponseEntity<?> criar(@RequestBody FornecedorRequest r,HttpServletRequest h) { return ResponseEntity.status(201).body(ApiResponse.ok("Fornecedor criado com sucesso.",fornecedores.criar(atual(h).companyId(),r))); }
    @PutMapping("{id}") public ApiResponse<?> atualizar(@PathVariable String id,@RequestBody FornecedorRequest r,HttpServletRequest h) { return ApiResponse.ok("Fornecedor atualizado com sucesso.",fornecedores.atualizar(atual(h).companyId(),id,r)); }
    @DeleteMapping("{id}") public ApiResponse<?> excluir(@PathVariable String id,HttpServletRequest h) { fornecedores.excluir(atual(h).companyId(),id);return ApiResponse.ok("Fornecedor removido com sucesso."); }
    private UsuarioAutenticado atual(HttpServletRequest h) { return (UsuarioAutenticado)h.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO); }
}
