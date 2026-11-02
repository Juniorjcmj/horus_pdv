package br.com.quack.pdv.auth.app;

import br.com.quack.pdv.auth.web.AuthRequests;
import br.com.quack.pdv.empresa.repo.EmpresaRepository;
import br.com.quack.pdv.security.CnpjValidator;
import br.com.quack.pdv.shared.NegocioException;
import br.com.quack.pdv.usuario.app.UsuarioService;
import br.com.quack.pdv.usuario.domain.Usuario;
import br.com.quack.pdv.usuario.repo.UsuarioRepository;
import br.com.quack.pdv.usuario.web.UsuarioDto;
import br.com.quack.pdv.usuario.web.UsuarioRequest;
import java.time.Clock;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cadastro publico de empresa e do administrador dela (RegisterPublicUser do .NET). */
@Service
public class CadastroPublicoService {

    private final UsuarioRepository usuarios;
    private final EmpresaRepository empresas;
    private final UsuarioService usuarioService;
    private final Clock clock;

    public CadastroPublicoService(UsuarioRepository usuarios, EmpresaRepository empresas,
                                  UsuarioService usuarioService, Clock clock) {
        this.usuarios = usuarios;
        this.empresas = empresas;
        this.usuarioService = usuarioService;
        this.clock = clock;
    }

    @Transactional
    public UsuarioDto cadastrar(AuthRequests.Register request) {
        if (!CnpjValidator.valido(request.cnpj())) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "CNPJ inválido.");
        }
        if (!request.password().equals(request.confirmPassword())) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "A confirmação de senha não confere.");
        }

        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String cnpj = request.cnpj().trim();

        if (usuarios.contarPorEmail(email) > 0) {
            throw new NegocioException(HttpStatus.BAD_REQUEST,
                    "Este e-mail já está cadastrado no sistema. Faça login para acessar.");
        }
        if (empresas.contarPorCnpj(cnpj) > 0) {
            throw new NegocioException(HttpStatus.BAD_REQUEST,
                    "Este CNPJ já possui uma empresa cadastrada no sistema. Faça login com sua conta.");
        }

        long agora = clock.millis();
        String companyId = "emp-" + agora;
        Usuario user = usuarioService.montar("usr-" + agora, new UsuarioRequest("", request.cnpj(), request.name(),
                request.email(), request.phone(), "administrador", "ativo", request.password(), null), true);
        user.setCompanyId(companyId);
        user.setMustChangePassword(false);
        usuarioService.validarDuplicidade(user, null, companyId);

        boolean exigeAprovacao = empresas.exigeAprovacaoParaNovasEmpresas().orElse(true);
        empresas.inserirEmpresaCadastroPublico(companyId, request.name().trim(), cnpj, email, request.phone().trim(),
                exigeAprovacao ? "pendente" : "aprovada");
        usuarios.saveAndFlush(user);
        return UsuarioDto.de(user);
    }
}
