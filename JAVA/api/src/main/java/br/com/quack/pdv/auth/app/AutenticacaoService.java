package br.com.quack.pdv.auth.app;

import br.com.quack.pdv.empresa.domain.Empresa;
import br.com.quack.pdv.empresa.repo.EmpresaRepository;
import br.com.quack.pdv.security.PasswordHasher;
import br.com.quack.pdv.security.RotuloDispositivo;
import br.com.quack.pdv.sessao.domain.Sessao;
import br.com.quack.pdv.sessao.repo.SessaoRepository;
import br.com.quack.pdv.shared.FormatoO;
import br.com.quack.pdv.shared.NegocioException;
import br.com.quack.pdv.usuario.app.UsuarioService;
import br.com.quack.pdv.usuario.domain.Usuario;
import br.com.quack.pdv.usuario.repo.UsuarioRepository;
import br.com.quack.pdv.usuario.web.UsuarioDto;
import br.com.quack.pdv.usuario.web.UsuarioRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/** Login, conta do proprio usuario e troca de senha (equivale ao HorusSecurityStore.Authenticate e afins). */
@Service
public class AutenticacaoService {

    static final int MAX_TENTATIVAS = 5;
    static final Duration JANELA_TENTATIVAS = Duration.ofMinutes(15);
    static final Duration DURACAO_BLOQUEIO = Duration.ofMinutes(10);

    public record LoginSucesso(UsuarioDto usuario, Sessao sessao) {}

    private final UsuarioRepository usuarios;
    private final SessaoRepository sessoes;
    private final EmpresaRepository empresas;
    private final UsuarioService usuarioService;
    private final TransactionOperations tx;
    private final Clock clock;

    public AutenticacaoService(UsuarioRepository usuarios, SessaoRepository sessoes, EmpresaRepository empresas,
                               UsuarioService usuarioService, TransactionOperations tx, Clock clock) {
        this.usuarios = usuarios;
        this.sessoes = sessoes;
        this.empresas = empresas;
        this.usuarioService = usuarioService;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * Autentica e cria a sessao. As falhas (senha errada, bloqueio, empresa) saem como NegocioException; a
     * contagem de tentativas e gravada em transacao propria, entao nao e desfeita pela excecao.
     */
    public LoginSucesso login(String email, String senha, String ip, String userAgent) {
        Usuario user = autenticar(email, senha);
        OffsetDateTime agora = OffsetDateTime.now(clock);
        String ultimoLogin = FormatoO.utcZ(agora.toInstant());
        user.setLastLoginAt(ultimoLogin);
        Sessao sessao = criarSessao(user, ip, userAgent, agora);
        tx.executeWithoutResult(s -> {
            usuarios.registrarLoginComSucesso(user.getId(), ultimoLogin);
            sessoes.save(sessao);
        });
        return new LoginSucesso(UsuarioDto.de(user), sessao);
    }

    /**
     * Confere e-mail e senha com o mesmo hash, a mesma contagem de tentativas, o mesmo bloqueio, as mesmas mensagens e a
     * mesma checagem da empresa do login, sem criar sessao. Tambem usado pelo registro do servidor da loja (D5).
     */
    public Usuario autenticar(String email, String senha) {
        String emailNormalizado = email.trim().toLowerCase(Locale.ROOT);
        OffsetDateTime agora = OffsetDateTime.now(clock);

        Usuario user = usuarios.findByEmail(emailNormalizado).orElse(null);

        if (user != null) {
            if (user.getLockoutEnd() != null && user.getLockoutEnd().isAfter(agora)) {
                throw bloqueado("Muitas tentativas inválidas. Aguarde alguns minutos para tentar novamente.",
                        user.getLockoutEnd());
            }
            // Janela de tentativas expirada: recomeca do zero.
            if (user.getFirstFailedLoginAt() != null
                    && Duration.between(user.getFirstFailedLoginAt(), agora).compareTo(JANELA_TENTATIVAS) > 0) {
                final String id = user.getId();
                tx.executeWithoutResult(s -> usuarios.zerarBloqueio(id));
                user.setFailedLoginAttempts(0);
                user.setFirstFailedLoginAt(null);
                user.setLockoutEnd(null);
            }
        }

        if (user == null || !"ativo".equals(user.getStatus()) || !PasswordHasher.verify(senha, user.getPasswordHash())) {
            OffsetDateTime bloqueadoAte = null;
            if (user != null) {
                final String id = user.getId();
                bloqueadoAte = tx.execute(s -> {
                    usuarios.registrarTentativaFalha(id, agora, MAX_TENTATIVAS, agora.plus(DURACAO_BLOQUEIO));
                    return usuarios.buscarFimBloqueio(id).orElse(null);
                });
            }
            if (bloqueadoAte == null) {
                throw new NegocioException(HttpStatus.UNAUTHORIZED, "E-mail ou senha inválidos.");
            }
            throw bloqueado("E-mail ou senha inválidos.", bloqueadoAte);
        }

        verificarSituacaoDaEmpresa(user.getCompanyId());
        return user;
    }

    private static NegocioException bloqueado(String mensagem, OffsetDateTime ate) {
        return new NegocioException(HttpStatus.TOO_MANY_REQUESTS, mensagem, null,
                Map.of("lockedUntil", FormatoO.comOffset(ate)));
    }

    private void verificarSituacaoDaEmpresa(String companyId) {
        if ("empresa-principal".equalsIgnoreCase(companyId)) return;
        Optional<Empresa> empresa = empresas.findById(companyId);
        if (empresa.isEmpty()) return;
        String status = empresa.get().getStatus() == null || empresa.get().getStatus().isBlank()
                ? "pendente" : empresa.get().getStatus();
        String motivo = empresa.get().getRejectionReason() != null && !empresa.get().getRejectionReason().isBlank()
                ? " Motivo: " + empresa.get().getRejectionReason() : "";
        if ("pendente".equalsIgnoreCase(status)) {
            throw new NegocioException(HttpStatus.UNAUTHORIZED,
                    "O cadastro da sua empresa está em análise e aguarda aprovação pelo administrador do sistema.");
        }
        if ("rejeitada".equalsIgnoreCase(status)) {
            throw new NegocioException(HttpStatus.UNAUTHORIZED, "O cadastro da sua empresa foi recusado." + motivo);
        }
        if ("bloqueada".equalsIgnoreCase(status)) {
            throw new NegocioException(HttpStatus.UNAUTHORIZED,
                    "O acesso da sua empresa está suspenso/bloqueado." + motivo);
        }
    }

    private static Sessao criarSessao(Usuario user, String ip, String userAgent, OffsetDateTime agora) {
        String ua = userAgent == null ? "" : userAgent;
        Sessao s = new Sessao();
        s.setId("sess-" + UUID.randomUUID().toString().replace("-", ""));
        s.setUserId(user.getId());
        s.setDevice(RotuloDispositivo.de(ua));
        s.setLocation("Localização indisponível");
        s.setIp(ip);
        s.setLastActive("Agora mesmo");
        s.setPlatform(ua.toLowerCase(Locale.ROOT).contains("mobile") ? "mobile" : "desktop");
        s.setCreatedAt(agora);
        return s;
    }

    public void logout(String sessaoId) {
        tx.executeWithoutResult(s -> sessoes.apagarPorId(sessaoId));
    }

    /** Usuario ativo da sessao, ou vazio. */
    public Optional<UsuarioDto> usuarioAtivo(String id, String companyId) {
        return usuarios.findByIdAndCompanyId(id, companyId).filter(u -> "ativo".equals(u.getStatus()))
                .map(UsuarioDto::de);
    }

    /** Atualiza nome, e-mail e telefone do proprio usuario (UpdateOwnProfile). Vazio = inativo ou inexistente. */
    public Optional<UsuarioDto> atualizarPerfil(String userId, String companyId, String nome, String email, String telefone) {
        return tx.execute(s -> {
            Optional<Usuario> atual = usuarios.findByIdAndCompanyId(userId, companyId);
            if (atual.isEmpty() || !"ativo".equals(atual.get().getStatus())) return Optional.<UsuarioDto>empty();
            Usuario user = atual.get();

            Usuario novo = usuarioService.montar(user.getId(), new UsuarioRequest(user.getCompanyId(), user.getCpf(),
                    nome, email, telefone, user.getRole(), user.getStatus(), "", null), false);
            usuarioService.validarDuplicidade(novo, user.getId(), user.getCompanyId());

            user.setName(novo.getName());
            user.setEmail(novo.getEmail());
            user.setPhone(novo.getPhone());
            usuarios.saveAndFlush(user);
            return Optional.of(UsuarioDto.de(user));
        });
    }

    /** Troca a senha e encerra todas as sessoes. Falso = usuario inativo ou senha atual errada. */
    public boolean trocarSenha(String userId, String companyId, String senhaAtual, String novaSenha) {
        if (novaSenha.length() < 8) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "A nova senha deve ter no minimo 8 caracteres.");
        }
        Optional<Usuario> atual = usuarios.findByIdAndCompanyId(userId, companyId);
        if (atual.isEmpty() || !"ativo".equals(atual.get().getStatus())) return false;
        Usuario user = atual.get();
        if (!PasswordHasher.verify(senhaAtual, user.getPasswordHash())) return false;

        String hash = PasswordHasher.hash(novaSenha);
        tx.executeWithoutResult(s -> {
            usuarios.trocarSenha(user.getId(), hash);
            sessoes.apagarTodasDoUsuario(user.getId());
        });
        return true;
    }
}
