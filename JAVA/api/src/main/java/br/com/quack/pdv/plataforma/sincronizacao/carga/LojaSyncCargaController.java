package br.com.quack.pdv.plataforma.sincronizacao.carga;

import br.com.quack.pdv.plataforma.sincronizacao.nuvem.ServidorLoja;
import br.com.quack.pdv.security.AutenticacaoFilter;
import br.com.quack.pdv.security.PerfisPermitidos;
import br.com.quack.pdv.security.UsuarioAutenticado;
import br.com.quack.pdv.shared.ApiResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas da nuvem para registro, carga inicial e ativacao do servidor da loja (D5). {@code registrar} e sem token
 * (credenciais no corpo); {@code revogar} e do administrador (JWT da .NET); {@code carga}, {@code carga/delta} e
 * {@code ativar} usam o token do servidor ({@code ServidorLojaFilter}).
 */
@RestController
@RequestMapping("api/loja-sync")
@Profile("nuvem")
public class LojaSyncCargaController {

    public record RegistrarRequest(String email, String password, String serverName) {}

    public record AtivarRequest(Long desde, Long versaoLoja, Boolean recuperacao) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int LIMITE_PADRAO = 500;

    private final RegistroServidorLoja registro;
    private final LeitorCargaNuvem leitor;
    private final AtivacaoNuvem ativacao;

    public LojaSyncCargaController(@org.springframework.context.annotation.Lazy RegistroServidorLoja registro,
                                   @org.springframework.context.annotation.Lazy LeitorCargaNuvem leitor,
                                   @org.springframework.context.annotation.Lazy AtivacaoNuvem ativacao) {
        this.registro = registro;
        this.leitor = leitor;
        this.ativacao = ativacao;
    }

    @PostMapping("registrar")
    public ResponseEntity<ApiResponse<RegistroServidorLoja.Registro>> registrar(@RequestBody RegistrarRequest r) {
        if (r == null || r.email() == null || r.email().isBlank() || r.password() == null || r.password().isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.erro("Informe e-mail e senha."));
        }
        return ResponseEntity.ok(ApiResponse.ok("Servidor registrado. Guarde o token: ele nao sera mostrado de novo.",
                registro.registrar(r.email(), r.password(), r.serverName())));
    }

    @PostMapping("servidores/{id}/revogar")
    @PerfisPermitidos({"administrador"})
    public ResponseEntity<ApiResponse<Object>> revogar(@PathVariable String id, HttpServletRequest http) {
        UsuarioAutenticado admin = (UsuarioAutenticado) http.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO);
        registro.revogar(id, admin.companyId(), admin.id());
        return ResponseEntity.ok(ApiResponse.ok("Servidor revogado."));
    }

    /** Pagina da carga; {@code depois} e o cursor (array JSON com a chave da ultima linha da pagina anterior). */
    @GetMapping("carga")
    public ResponseEntity<ApiResponse<CargaPagina>> carga(@RequestParam String tabela,
                                                         @RequestParam(required = false) String depois,
                                                         @RequestParam(required = false) Integer limite,
                                                         @RequestParam(required = false, defaultValue = "false") boolean renovar,
                                                         HttpServletRequest http) {
        ServidorLoja servidor = servidor(http);
        if (servidor == null) return semToken();
        List<String> cursor;
        try {
            cursor = depois == null || depois.isBlank() ? null
                    : JSON.readValue(depois, new TypeReference<List<String>>() {});
            long versao = ativacao.abrirCarga(servidor, renovar);
            CargaPagina p = leitor.pagina(servidor.companyId(), tabela, cursor, limite == null ? LIMITE_PADRAO : limite);
            ativacao.registrarPagina(servidor, p);
            return ResponseEntity.ok(ApiResponse.ok("Pagina da carga.", new CargaPagina(p.tabela(), versao, p.colunas(), p.linhas(),
                    p.ultimaChave(), p.fim())));
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return ResponseEntity.badRequest().body(ApiResponse.erro(e.getMessage()));
        }
    }

    @GetMapping("carga/delta")
    public ResponseEntity<ApiResponse<CargaDelta>> delta(@RequestParam long desde, HttpServletRequest http) {
        ServidorLoja servidor = servidor(http);
        if (servidor == null) return semToken();
        try {
            return ResponseEntity.ok(ApiResponse.ok("Delta da nuvem.", leitor.delta(servidor.companyId(), desde)));
        } catch (LeitorCargaNuvem.VersaoExpiradaException e) {
            return ResponseEntity.status(410).body(ApiResponse.erro(e.getMessage()));
        }
    }

    @PostMapping("ativar")
    public ResponseEntity<ApiResponse<AtivacaoNuvem.Ativacao>> ativar(@RequestBody AtivarRequest r, HttpServletRequest http) {
        ServidorLoja servidor = servidor(http);
        if (servidor == null) return semToken();
        if (r == null || r.desde() == null || r.versaoLoja() == null) {
            return ResponseEntity.badRequest().body(ApiResponse.erro("Informe desde e versaoLoja."));
        }
        try {
            return ResponseEntity.ok(ApiResponse.ok("Servidor ativado.", ativacao.ativar(servidor, r.desde(), r.versaoLoja(), Boolean.TRUE.equals(r.recuperacao()))));
        } catch (LeitorCargaNuvem.VersaoExpiradaException e) {
            return ResponseEntity.status(410).body(ApiResponse.erro(e.getMessage()));
        }
    }

    private static ServidorLoja servidor(HttpServletRequest http) {
        return http.getAttribute(ServidorLoja.ATRIBUTO) instanceof ServidorLoja s ? s : null;
    }

    private static <T> ResponseEntity<ApiResponse<T>> semToken() {
        return ResponseEntity.status(401).body(ApiResponse.erro("Token do servidor invalido ou revogado."));
    }
}
