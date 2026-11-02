package br.com.quack.pdv.usuario.web;

import br.com.quack.pdv.auth.app.RecuperacaoSenhaService;
import br.com.quack.pdv.config.QuackProperties;
import br.com.quack.pdv.email.app.EmailService;
import br.com.quack.pdv.security.AutenticacaoFilter;
import br.com.quack.pdv.security.ClienteIpResolver;
import br.com.quack.pdv.security.Perfis;
import br.com.quack.pdv.security.PerfisPermitidos;
import br.com.quack.pdv.security.UsuarioAutenticado;
import br.com.quack.pdv.shared.ApiResponse;
import br.com.quack.pdv.usuario.app.UsuarioService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Rotas /api/Usuario: gestao dos usuarios da empresa (administrador e gerente). */
@RestController
@RequestMapping("api/Usuario")
@PerfisPermitidos({"administrador", "gerente"})
public class UsuarioController {

    private static final Logger LOG = LoggerFactory.getLogger(UsuarioController.class);

    public record StatusRequest(String status) {
        public StatusRequest {
            status = status == null ? "ativo" : status;
        }
    }

    private final UsuarioService usuarios;
    private final RecuperacaoSenhaService recuperacao;
    private final EmailService email;
    private final QuackProperties props;
    private final ClienteIpResolver ipResolver;

    public UsuarioController(UsuarioService usuarios, RecuperacaoSenhaService recuperacao, EmailService email,
                             QuackProperties props, ClienteIpResolver ipResolver) {
        this.usuarios = usuarios;
        this.recuperacao = recuperacao;
        this.email = email;
        this.props = props;
        this.ipResolver = ipResolver;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Object>> listar(HttpServletRequest http) {
        UsuarioAutenticado atual = atual(http);
        if (atual == null) return semSessao();
        return ResponseEntity.ok(ApiResponse.ok("Usuarios obtidos com sucesso.", usuarios.listar(atual.companyId())));
    }

    @GetMapping("supervisores")
    @PerfisPermitidos({"administrador", "gerente", "atendente", "caixa"})
    public ResponseEntity<ApiResponse<Object>> supervisores(HttpServletRequest http) {
        UsuarioAutenticado atual = atual(http);
        if (atual == null) return semSessao();
        return ResponseEntity.ok(ApiResponse.ok("Supervisores obtidos com sucesso.", usuarios.supervisores(atual.companyId())));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Object>> criar(@RequestBody UsuarioRequest request, HttpServletRequest http) {
        UsuarioAutenticado atual = atual(http);
        if (atual == null) return semSessao();
        UsuarioDto criado = usuarios.criar(request, atual.companyId(), podeAlterarPerfisAdicionais(atual));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Usuario criado com sucesso.", criado));
    }

    @PutMapping("{id}")
    public ResponseEntity<ApiResponse<Object>> atualizar(@PathVariable("id") String id,
                                                          @RequestBody UsuarioRequest request, HttpServletRequest http) {
        UsuarioAutenticado atual = atual(http);
        if (atual == null) return semSessao();
        Optional<UsuarioDto> usuario = usuarios.atualizar(id, request, atual.companyId(), podeAlterarPerfisAdicionais(atual));
        if (usuario.isEmpty()) return naoEncontrado();
        return ResponseEntity.ok(ApiResponse.ok("Usuario atualizado com sucesso.", usuario.get()));
    }

    @PatchMapping("{id}/status")
    public ResponseEntity<ApiResponse<Object>> alterarStatus(@PathVariable("id") String id,
                                                              @RequestBody StatusRequest request, HttpServletRequest http) {
        UsuarioAutenticado atual = atual(http);
        if (atual == null) return semSessao();
        Optional<UsuarioDto> usuario = usuarios.alterarStatus(id, request.status(), atual.companyId());
        if (usuario.isEmpty()) return naoEncontrado();
        return ResponseEntity.ok(ApiResponse.ok("Status atualizado com sucesso.", usuario.get()));
    }

    @PostMapping("{id}/resetar-senha")
    public ResponseEntity<ApiResponse<Object>> resetarSenha(@PathVariable("id") String id, HttpServletRequest http) {
        UsuarioAutenticado atual = atual(http);
        if (atual == null) return semSessao();

        boolean emailLigado = email.habilitado();
        if (!emailLigado && !props.isDev()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.erro("Reset de senha indisponível. Configure o envio de e-mail."));
        }

        String ip = ipResolver.resolver(http, "0.0.0.0");
        String ua = http.getHeader(HttpHeaders.USER_AGENT) == null ? "" : http.getHeader(HttpHeaders.USER_AGENT);
        Optional<RecuperacaoSenhaService.RedefinicaoAdministrativa> resultado =
                recuperacao.solicitarAdministrativo(id, atual.companyId(), ip, ua);
        if (resultado.isEmpty()) return naoEncontrado();
        RecuperacaoSenhaService.RedefinicaoAdministrativa r = resultado.get();

        if (r.resetToken() != null && !r.resetToken().isBlank() && r.expiresAt() != null && emailLigado) {
            String url = email.montarUrlRedefinicao(r.resetToken());
            try {
                email.enviarRedefinicaoSenha(r.user().email(), "Quack PDV", url, r.expiresAt());
            } catch (RuntimeException e) {
                recuperacao.consumir(r.resetToken(), ip, ua);
                LOG.warn("Não foi possível enviar e-mail de reset administrativo para {}.", r.user().email(), e);
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(ApiResponse.erro("Não foi possível enviar o e-mail de redefinição de senha."));
            }
        }

        Object dados;
        if (emailLigado || !props.isDev()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("user", r.user());
            m.put("accepted", r.accepted());
            m.put("maskedEmail", r.maskedEmail());
            m.put("expiresAt", r.expiresAt());
            dados = m;
        } else {
            dados = r;
        }
        return ResponseEntity.ok(ApiResponse.ok("Redefinição de senha gerada com sucesso.", dados));
    }

    /** So o administrador da/tira perfis adicionais (ex.: financeiro); de um gerente o campo e ignorado. */
    private static boolean podeAlterarPerfisAdicionais(UsuarioAutenticado atual) {
        return Perfis.ADMINISTRADOR.equalsIgnoreCase(atual.role());
    }

    private static ResponseEntity<ApiResponse<Object>> semSessao() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.erro("Sessão não encontrada."));
    }

    private static ResponseEntity<ApiResponse<Object>> naoEncontrado() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.erro("Usuário não encontrado."));
    }

    private static UsuarioAutenticado atual(HttpServletRequest http) {
        Object o = http.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO);
        return o instanceof UsuarioAutenticado u ? u : null;
    }
}
