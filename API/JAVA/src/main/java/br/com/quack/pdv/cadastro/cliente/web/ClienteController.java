package br.com.quack.pdv.cadastro.cliente.web;
import br.com.quack.pdv.cadastro.cliente.app.ClienteService;
import br.com.quack.pdv.security.*;
import br.com.quack.pdv.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("api/Cliente") @PerfisPermitidos({"administrador","gerente","atendente","caixa"})
public class ClienteController {
    private final ClienteService clientes;
    public ClienteController(ClienteService clientes) {this.clientes=clientes;}
    @GetMapping public ApiResponse<?> listar(HttpServletRequest h) {return ApiResponse.ok("Clientes obtidos com sucesso.",clientes.listar(atual(h).companyId()));}
    @PostMapping public ResponseEntity<?> criar(@RequestBody ClienteRequest r,HttpServletRequest h) {return ResponseEntity.status(201).body(ApiResponse.ok("Cliente criado com sucesso.",clientes.criar(atual(h),r)));}
    @PutMapping("{id}") @PerfisPermitidos({"administrador","gerente","atendente"}) public ApiResponse<?> atualizar(@PathVariable String id,@RequestBody ClienteRequest r,HttpServletRequest h) {return ApiResponse.ok("Cliente atualizado com sucesso.",clientes.atualizar(atual(h),id,r));}
    @DeleteMapping("{id}") @PerfisPermitidos({"administrador","gerente"}) public ApiResponse<?> excluir(@PathVariable String id,HttpServletRequest h) {clientes.excluir(atual(h),id);return ApiResponse.ok("Cliente removido com sucesso.");}
    @PostMapping("credito-snapshot") public ResponseEntity<?> snapshot(@RequestBody List<String> ids,HttpServletRequest h) {var u=atual(h);if(u==null)return ResponseEntity.status(401).build();return ResponseEntity.ok(ApiResponse.ok("",clientes.snapshot(u.companyId(),ids)));}
    private UsuarioAutenticado atual(HttpServletRequest h) {return (UsuarioAutenticado)h.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO);}
}
