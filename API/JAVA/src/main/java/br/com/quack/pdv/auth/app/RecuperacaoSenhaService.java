package br.com.quack.pdv.auth.app;

import br.com.quack.pdv.auth.domain.PasswordResetToken;
import br.com.quack.pdv.auth.repo.PasswordResetTokenRepository;
import br.com.quack.pdv.config.QuackProperties;
import br.com.quack.pdv.empresa.repo.EmpresaRepository;
import br.com.quack.pdv.security.CnpjValidator;
import br.com.quack.pdv.security.MascaraEmail;
import br.com.quack.pdv.security.PasswordHasher;
import br.com.quack.pdv.security.RotuloDispositivo;
import br.com.quack.pdv.security.TokenRedefinicao;
import br.com.quack.pdv.sessao.repo.SessaoRepository;
import br.com.quack.pdv.shared.NegocioException;
import br.com.quack.pdv.usuario.domain.Usuario;
import br.com.quack.pdv.usuario.repo.UsuarioRepository;
import br.com.quack.pdv.usuario.web.UsuarioDto;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/** Pedido e uso do token de redefinicao de senha (equivale as rotinas de PasswordResetTokens do .NET). */
@Service
public class RecuperacaoSenhaService {

    /** Resultado do pedido: accepted sempre verdadeiro; os demais so existem se o usuario foi achado. */
    public record PedidoRedefinicao(boolean accepted, String maskedEmail, String resetToken, OffsetDateTime expiresAt) {
        static PedidoRedefinicao vazio() { return new PedidoRedefinicao(true, null, null, null); }
    }

    public record RedefinicaoAdministrativa(UsuarioDto user, boolean accepted, String maskedEmail, String resetToken,
                                            OffsetDateTime expiresAt) {}

    private final UsuarioRepository usuarios;
    private final SessaoRepository sessoes;
    private final PasswordResetTokenRepository tokens;
    private final EmpresaRepository empresas;
    private final QuackProperties props;
    private final TransactionOperations tx;
    private final Clock clock;

    public RecuperacaoSenhaService(UsuarioRepository usuarios, SessaoRepository sessoes,
                                   PasswordResetTokenRepository tokens, EmpresaRepository empresas,
                                   QuackProperties props, TransactionOperations tx, Clock clock) {
        this.usuarios = usuarios;
        this.sessoes = sessoes;
        this.tokens = tokens;
        this.empresas = empresas;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    /** Pedido publico (forgot-password): nao revela se o e-mail existe. */
    public PedidoRedefinicao solicitar(String cnpj, String email, String ip, String userAgent) {
        String cnpjDigitos = CnpjValidator.somenteDigitos(cnpj);
        String emailNormalizado = email.trim().toLowerCase(Locale.ROOT);
        OffsetDateTime agora = OffsetDateTime.now(clock);
        OffsetDateTime expira = agora.plusMinutes(props.getAuth().getPasswordResetTokenMinutes());

        return tx.execute(s -> {
            tokens.apagarVencidosAntesDe(agora.minusDays(7));
            Optional<Usuario> user = usuarios.buscarParaRedefinicao(cnpjDigitos, emailNormalizado);
            if (user.isEmpty()) return PedidoRedefinicao.vazio();

            String token = gravarToken(user.get(), cnpjDigitos, agora, expira, ip, userAgent);
            return new PedidoRedefinicao(true, MascaraEmail.mascarar(user.get().getEmail()), token, expira);
        });
    }

    /** Redefinicao pelo gestor (resetar-senha): exige troca no proximo login e derruba as sessoes. */
    public Optional<RedefinicaoAdministrativa> solicitarAdministrativo(String userId, String companyId, String ip,
                                                                       String userAgent) {
        OffsetDateTime agora = OffsetDateTime.now(clock);
        OffsetDateTime expira = agora.plusMinutes(props.getAuth().getPasswordResetTokenMinutes());

        return tx.execute(s -> {
            Optional<Usuario> encontrado = usuarios.findByIdAndCompanyId(userId, companyId);
            if (encontrado.isEmpty()) return Optional.<RedefinicaoAdministrativa>empty();
            Usuario user = encontrado.get();

            String cnpj = CnpjValidator.somenteDigitos(empresas.buscarCnpj(companyId).orElse(""));
            tokens.apagarVencidosAntesDe(agora.minusDays(7));
            String token = gravarToken(user, cnpj, agora, expira, ip, userAgent);
            usuarios.exigirTrocaDeSenha(user.getId(), companyId);
            sessoes.apagarTodasDoUsuario(user.getId());

            UsuarioDto dto = UsuarioDto.de(user);
            UsuarioDto comTroca = new UsuarioDto(dto.id(), dto.companyId(), dto.cpf(), dto.name(), dto.email(),
                    dto.phone(), dto.role(), dto.status(), dto.createdAt(), dto.lastLoginAt(), true,
                    dto.perfisAdicionais());
            return Optional.of(new RedefinicaoAdministrativa(comTroca, true, MascaraEmail.mascarar(user.getEmail()),
                    token, expira));
        });
    }

    private String gravarToken(Usuario user, String cnpj, OffsetDateTime agora, OffsetDateTime expira, String ip,
                               String userAgent) {
        tokens.invalidarNaoUsados(user.getId(), agora);
        String token = TokenRedefinicao.gerar();
        PasswordResetToken t = new PasswordResetToken();
        t.setId("prt-" + UUID.randomUUID().toString().replace("-", ""));
        t.setUserId(user.getId());
        t.setEmail(user.getEmail());
        t.setCnpj(cnpj);
        t.setTokenHash(TokenRedefinicao.hash(token, props.getAuth().getJwtSecret()));
        t.setCreatedAt(agora);
        t.setRequestedAt(agora);
        t.setExpiresAt(expira);
        t.setConsumedAt(null);
        t.setRequestedIp(nulo(ip));
        t.setRequestedUserAgent(nulo(userAgent));
        t.setRequestedDevice(nulo(RotuloDispositivo.de(userAgent)));
        t.setUpdatedAt(agora);
        tokens.saveAndFlush(t);
        return token;
    }

    /** Marca o token como usado (quando o envio do e-mail falha). */
    public void consumir(String token, String ip, String userAgent) {
        if (token == null || token.isBlank()) return;
        OffsetDateTime agora = OffsetDateTime.now(clock);
        String hash = TokenRedefinicao.hash(token.trim(), props.getAuth().getJwtSecret());
        tx.executeWithoutResult(s -> tokens.consumirPorHash(hash, agora, nulo(ip), nulo(userAgent),
                nulo(RotuloDispositivo.de(userAgent))));
    }

    /** Troca a senha com o token ainda valido e nao usado; consome o token e encerra as sessoes. */
    public UsuarioDto redefinir(String token, String novaSenha, String confirmacao, String ip, String userAgent) {
        if (token == null || token.isBlank()) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "Token de redefinição inválido.");
        }
        if (!novaSenha.equals(confirmacao)) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "A confirmação de senha não confere.");
        }
        if (novaSenha.length() < 8) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "A nova senha deve ter no minimo 8 caracteres.");
        }

        OffsetDateTime agora = OffsetDateTime.now(clock);
        String hash = TokenRedefinicao.hash(token.trim(), props.getAuth().getJwtSecret());
        return tx.execute(s -> {
            Optional<PasswordResetToken> t = tokens.findByTokenHashAndConsumedAtIsNull(hash);
            if (t.isEmpty() || !t.get().getExpiresAt().isAfter(agora)) {
                throw new NegocioException(HttpStatus.BAD_REQUEST, "Token de redefinição inválido ou expirado.");
            }
            Usuario user = usuarios.findById(t.get().getUserId()).orElse(null);
            if (user == null || !"ativo".equals(user.getStatus())) {
                throw new NegocioException(HttpStatus.BAD_REQUEST, "Usuário inativo ou inexistente.");
            }

            usuarios.trocarSenha(user.getId(), PasswordHasher.hash(novaSenha));
            tokens.consumirDoUsuario(user.getId(), agora, nulo(ip), nulo(userAgent), nulo(RotuloDispositivo.de(userAgent)));
            sessoes.apagarTodasDoUsuario(user.getId());

            UsuarioDto dto = UsuarioDto.de(user);
            return new UsuarioDto(dto.id(), dto.companyId(), dto.cpf(), dto.name(), dto.email(), dto.phone(),
                    dto.role(), dto.status(), dto.createdAt(), dto.lastLoginAt(), false, dto.perfisAdicionais());
        });
    }

    private static String nulo(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
