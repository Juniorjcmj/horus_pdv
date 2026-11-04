package br.com.quack.pdv.plataforma.sincronizacao.carga;

import br.com.quack.pdv.auth.app.AutenticacaoService;
import br.com.quack.pdv.plataforma.sincronizacao.nuvem.ServidorLojaFilter;
import br.com.quack.pdv.security.Perfis;
import br.com.quack.pdv.shared.NegocioException;
import br.com.quack.pdv.usuario.domain.Usuario;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Registro e revogacao do servidor da loja na nuvem (D5, D9). O registro valida e-mail e senha pelo mesmo caminho do
 * login ({@link AutenticacaoService#autenticar}: hash, bloqueio por tentativas, mensagens e situacao da empresa), exige
 * administrador e cria {@code SyncServidores} com o SHA-256 do token (32 bytes aleatorios, base64url, devolvido uma
 * vez). No maximo um servidor nao revogado por empresa: um {@code ativo} recusa o novo registro citando-o; um
 * {@code pendente} (carga que nao terminou) e substituido.
 */
@Service
@Profile("nuvem")
@org.springframework.context.annotation.Lazy
public class RegistroServidorLoja {

    public record Registro(String serverId, String companyId, String token) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AutenticacaoService autenticacao;
    private final JdbcTemplate jdbc;
    private final TransactionOperations tx;

    public RegistroServidorLoja(AutenticacaoService autenticacao, JdbcTemplate jdbc, TransactionOperations tx) {
        this.autenticacao = autenticacao;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public Registro registrar(String email, String senha, String nomeServidor) {
        String nome = nomeServidor == null ? "" : nomeServidor.strip();
        if (nome.isEmpty() || nome.length() > 120) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "Informe o nome do servidor (ate 120 caracteres).");
        }
        Usuario usuario = autenticacao.autenticar(email, senha);
        if (!Perfis.ADMINISTRADOR.equalsIgnoreCase(usuario.getRole())) {
            throw new NegocioException(HttpStatus.FORBIDDEN, "Somente um administrador pode registrar o servidor da loja.");
        }
        String empresa = usuario.getCompanyId();
        byte[] bruto = new byte[32];
        RANDOM.nextBytes(bruto);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bruto);
        String id = "srv-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        try {
            tx.executeWithoutResult(s -> {
                List<Map<String, Object>> vivos = jdbc.queryForList(
                        "SELECT Id, Nome, Status FROM dbo.SyncServidores WITH (UPDLOCK, HOLDLOCK) "
                                + "WHERE CompanyId = ? AND Status <> N'revogado'", empresa);
                for (Map<String, Object> v : vivos) {
                    if ("ativo".equals(v.get("Status"))) {
                        throw new NegocioException(HttpStatus.CONFLICT, "A empresa ja tem um servidor de loja ativo: '"
                                + v.get("Nome") + "' (" + v.get("Id") + "). Revogue-o antes de registrar outro.");
                    }
                }
                jdbc.update("UPDATE dbo.SyncServidores SET Status = N'revogado', RevogadoEm = SYSDATETIMEOFFSET(), "
                        + "RevogadoPor = ? WHERE CompanyId = ? AND Status = N'pendente'", usuario.getId(), empresa);
                jdbc.update("INSERT INTO dbo.SyncServidores (Id, CompanyId, Nome, TokenHash, Status) "
                        + "VALUES (?, ?, ?, ?, N'pendente')", id, empresa, nome, ServidorLojaFilter.hash(token));
            });
        } catch (DuplicateKeyException e) {
            throw new NegocioException(HttpStatus.CONFLICT, "A empresa ja tem um servidor de loja registrado.");
        }
        return new Registro(id, empresa, token);
    }

    /** Revoga o servidor (de qualquer status). Administrador so revoga servidor da propria empresa. */
    public void revogar(String servidorId, String companyIdDoAdministrador, String administradorId) {
        List<String> dono = jdbc.queryForList("SELECT CompanyId FROM dbo.SyncServidores WHERE Id = ?", String.class,
                servidorId);
        if (dono.isEmpty()) {
            throw new NegocioException(HttpStatus.NOT_FOUND, "Servidor nao encontrado.");
        }
        if (!dono.getFirst().equals(companyIdDoAdministrador)) {
            throw new NegocioException(HttpStatus.FORBIDDEN, "O servidor pertence a outra empresa.");
        }
        jdbc.update("UPDATE dbo.SyncServidores SET Status = N'revogado', RevogadoEm = SYSDATETIMEOFFSET(), RevogadoPor = ? "
                + "WHERE Id = ? AND Status <> N'revogado'", administradorId, servidorId);
    }
}
