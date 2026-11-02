package br.com.quack.pdv.sessao.web;

import br.com.quack.pdv.security.AutenticacaoFilter;
import br.com.quack.pdv.security.UsuarioAutenticado;
import br.com.quack.pdv.sessao.app.SessaoService;
import br.com.quack.pdv.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Rotas /api/Sessao: sessoes do proprio usuario. */
@RestController
@RequestMapping("api/Sessao")
public class SessaoController {

    private final SessaoService sessoes;

    public SessaoController(SessaoService sessoes) {
        this.sessoes = sessoes;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Object>> listar(HttpServletRequest http) {
        UsuarioAutenticado atual = atual(http);
        if (atual == null) return semSessao();
        return ResponseEntity.ok(ApiResponse.ok("Sessoes obtidas com sucesso.", sessoes.listar(atual.id(), atual.sessionId())));
    }

    @DeleteMapping("outras")
    public ResponseEntity<ApiResponse<Object>> encerrarOutras(HttpServletRequest http) {
        UsuarioAutenticado atual = atual(http);
        if (atual == null) return semSessao();
        return ResponseEntity.ok(ApiResponse.ok("Outras sessoes encerradas com sucesso.",
                sessoes.encerrarOutras(atual.id(), atual.sessionId())));
    }

    @DeleteMapping("{id}")
    public ResponseEntity<ApiResponse<Object>> encerrar(@PathVariable("id") String id, HttpServletRequest http) {
        UsuarioAutenticado atual = atual(http);
        if (atual == null) return semSessao();
        return ResponseEntity.ok(ApiResponse.ok("Sessao encerrada com sucesso.",
                sessoes.encerrar(atual.id(), id, atual.sessionId())));
    }

    private static ResponseEntity<ApiResponse<Object>> semSessao() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.erro("Sessão não encontrada."));
    }

    private static UsuarioAutenticado atual(HttpServletRequest http) {
        Object o = http.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO);
        return o instanceof UsuarioAutenticado u ? u : null;
    }
}
