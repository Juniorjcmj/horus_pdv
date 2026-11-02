package br.com.quack.pdv.usuario.app;

import br.com.quack.pdv.security.CnpjValidator;
import br.com.quack.pdv.security.PasswordHasher;
import br.com.quack.pdv.security.Perfis;
import br.com.quack.pdv.sessao.repo.SessaoRepository;
import br.com.quack.pdv.shared.NegocioException;
import br.com.quack.pdv.usuario.domain.Usuario;
import br.com.quack.pdv.usuario.repo.UsuarioRepository;
import br.com.quack.pdv.usuario.web.UsuarioDto;
import br.com.quack.pdv.usuario.web.UsuarioRequest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Gestao de usuarios da empresa (equivale as regras de usuarios do HorusSecurityStore). */
@Service
public class UsuarioService {

    public record Supervisor(String id, String name, String role) {}

    private final UsuarioRepository usuarios;
    private final SessaoRepository sessoes;
    private final Clock clock;

    public UsuarioService(UsuarioRepository usuarios, SessaoRepository sessoes, Clock clock) {
        this.usuarios = usuarios;
        this.sessoes = sessoes;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<UsuarioDto> listar(String companyId) {
        return usuarios.findByCompanyIdOrderByName(companyId).stream().map(UsuarioDto::de).toList();
    }

    @Transactional(readOnly = true)
    public List<Supervisor> supervisores(String companyId) {
        return usuarios.findByCompanyIdAndStatusAndRoleInOrderByName(companyId, "ativo",
                        List.of(Perfis.ADMINISTRADOR, Perfis.GERENTE)).stream()
                .map(u -> new Supervisor(u.getId(), u.getName(), u.getRole())).toList();
    }

    @Transactional
    public UsuarioDto criar(UsuarioRequest request, String companyId, boolean podeAlterarPerfisAdicionais) {
        Usuario novo = montar("usr-" + clock.millis(), request, true);
        novo.setCompanyId(companyId);
        if (!podeAlterarPerfisAdicionais) novo.setPerfisAdicionais("");
        validarDuplicidade(novo, null, companyId);
        usuarios.save(novo);
        return UsuarioDto.de(novo);
    }

    /** Devolve vazio quando o usuario nao existe na empresa. */
    @Transactional
    public Optional<UsuarioDto> atualizar(String id, UsuarioRequest request, String companyId,
                                          boolean podeAlterarPerfisAdicionais) {
        Optional<Usuario> atual = usuarios.findByIdAndCompanyId(id, companyId);
        if (atual.isEmpty()) return Optional.empty();
        Usuario current = atual.get();

        Usuario novo = montar(id, request, false);
        novo.setCompanyId(companyId);
        if (!podeAlterarPerfisAdicionais || request.perfisAdicionais() == null) {
            novo.setPerfisAdicionais(current.getPerfisAdicionais());
        }
        validarDuplicidade(novo, id, companyId);
        novo.setPasswordHash(request.password().isBlank() ? current.getPasswordHash() : PasswordHasher.hash(request.password()));
        novo.setMustChangePassword(!request.password().isBlank() || current.isMustChangePassword());

        // Igual ao UpdateUserRecord do .NET: grava estes campos (inclusive LastLoginAt "-") e nao mexe em CreatedAt.
        current.setCpf(novo.getCpf());
        current.setName(novo.getName());
        current.setEmail(novo.getEmail());
        current.setPhone(novo.getPhone());
        current.setRole(novo.getRole());
        current.setStatus(novo.getStatus());
        current.setLastLoginAt(novo.getLastLoginAt());
        current.setPasswordHash(novo.getPasswordHash());
        current.setMustChangePassword(novo.isMustChangePassword());
        current.setPerfisAdicionais(novo.getPerfisAdicionais());
        usuarios.saveAndFlush(current);

        if ("inativo".equals(novo.getStatus())) sessoes.apagarTodasDoUsuario(id);
        // O .NET devolve o registro montado (CreatedAt = hoje), nao o gravado.
        return Optional.of(UsuarioDto.de(novo));
    }

    @Transactional
    public Optional<UsuarioDto> alterarStatus(String id, String status, String companyId) {
        Optional<Usuario> atual = usuarios.findByIdAndCompanyId(id, companyId);
        if (atual.isEmpty()) return Optional.empty();
        Usuario u = atual.get();
        u.setStatus("inativo".equals(status) ? "inativo" : "ativo");
        usuarios.saveAndFlush(u);
        if ("inativo".equals(u.getStatus())) sessoes.apagarTodasDoUsuario(u.getId());
        return Optional.of(UsuarioDto.de(u));
    }

    // ---- regras compartilhadas com o cadastro publico e o proprio perfil ----

    /** MapRequest do .NET: valida e monta o registro (sem gravar). */
    public Usuario montar(String id, UsuarioRequest request, boolean criacao) {
        String digitos = CnpjValidator.somenteDigitos(request.cpf());
        if (digitos.length() != 11 && digitos.length() != 14) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "CPF/CNPJ invalido.");
        }
        if (request.name().isBlank()) throw new NegocioException(HttpStatus.BAD_REQUEST, "Nome e obrigatorio.");
        if (request.email().isBlank() || !request.email().contains("@")) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "E-mail invalido.");
        }
        if (criacao && request.password().length() < 8) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "Senha deve ter no minimo 8 caracteres.");
        }

        Usuario u = new Usuario();
        u.setId(id);
        u.setCompanyId(request.companyId());
        u.setCpf(request.cpf().trim());
        u.setName(request.name().trim());
        u.setEmail(request.email().trim().toLowerCase(Locale.ROOT));
        u.setPhone(request.phone().trim());
        u.setRole(request.role().isBlank() ? Perfis.ATENDENTE : request.role().trim());
        u.setStatus("inativo".equals(request.status()) ? "inativo" : "ativo");
        u.setCreatedAt(LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString());
        u.setLastLoginAt("-");
        u.setPasswordHash(request.password().isBlank() ? "" : PasswordHasher.hash(request.password()));
        u.setMustChangePassword(criacao || !request.password().isBlank());
        u.setPerfisAdicionais(Perfis.juntar(request.perfisAdicionais()));
        return u;
    }

    public void validarDuplicidade(Usuario user, String currentId, String companyId) {
        String atual = currentId == null ? "" : currentId;
        if (usuarios.contarEmailDuplicado(atual, user.getEmail()) > 0) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "Este e-mail já está cadastrado no sistema.");
        }
        if (usuarios.contarCpfDuplicado(atual, companyId, user.getCpf()) > 0) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "Já existe um usuário com este CPF nesta empresa.");
        }
    }
}
