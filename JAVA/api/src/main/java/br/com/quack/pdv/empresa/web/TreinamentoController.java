package br.com.quack.pdv.empresa.web;
import br.com.quack.pdv.empresa.app.*;
import br.com.quack.pdv.security.*;
import br.com.quack.pdv.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("api/treinamento") @PerfisPermitidos({"administrador","gerente","atendente","caixa"})
public class TreinamentoController {
    private final TreinamentoService treinamento;private final AdministradorGeral geral;
    public TreinamentoController(TreinamentoService treinamento,AdministradorGeral geral) {this.treinamento=treinamento;this.geral=geral;}
    @GetMapping public ApiResponse<?> listar(HttpServletRequest h) {return ApiResponse.ok("Treinamentos obtidos com sucesso.",Map.of("podeGerenciar",geral.podeGerenciarTreinamento(atual(h)),"secoes",treinamento.listar()));}
    @PostMapping("secoes") public ApiResponse<?> criarSecao(@RequestBody TreinamentoService.SecaoRequest r,HttpServletRequest h) {return salvarSecao(null,r,h);}
    @PutMapping("secoes/{id}") public ApiResponse<?> salvarSecao(@PathVariable String id,@RequestBody TreinamentoService.SecaoRequest r,HttpServletRequest h) {negar(h);return ApiResponse.ok("Seção salva com sucesso.",Map.of("id",treinamento.salvarSecao(id,r)));}
    @DeleteMapping("secoes/{id}") public ApiResponse<?> excluirSecao(@PathVariable String id,HttpServletRequest h) {negar(h);treinamento.excluirSecao(id);return ApiResponse.ok("Seção excluída com sucesso.");}
    @PostMapping("videos") public ApiResponse<?> criarVideo(@RequestBody TreinamentoService.VideoRequest r,HttpServletRequest h) {return salvarVideo(null,r,h);}
    @PutMapping("videos/{id}") public ApiResponse<?> salvarVideo(@PathVariable String id,@RequestBody TreinamentoService.VideoRequest r,HttpServletRequest h) {negar(h);return ApiResponse.ok("Vídeo salvo com sucesso.",Map.of("id",treinamento.salvarVideo(id,r)));}
    @DeleteMapping("videos/{id}") public ApiResponse<?> excluirVideo(@PathVariable String id,HttpServletRequest h) {negar(h);treinamento.excluirVideo(id);return ApiResponse.ok("Vídeo excluído com sucesso.");}
    private void negar(HttpServletRequest h) {if(!geral.podeGerenciarTreinamento(atual(h)))throw new NegocioException(HttpStatus.FORBIDDEN,"Somente o administrador geral da plataforma pode editar os treinamentos.");}
    private UsuarioAutenticado atual(HttpServletRequest h) {return (UsuarioAutenticado)h.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO);}
}
