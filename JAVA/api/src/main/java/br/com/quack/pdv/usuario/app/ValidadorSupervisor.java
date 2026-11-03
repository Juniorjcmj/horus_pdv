package br.com.quack.pdv.usuario.app;

import br.com.quack.pdv.security.PasswordHasher;
import br.com.quack.pdv.security.Perfis;
import br.com.quack.pdv.usuario.domain.Usuario;
import br.com.quack.pdv.usuario.repo.UsuarioRepository;
import br.com.quack.pdv.usuario.web.UsuarioDto;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Porta de HorusSecurityStore.ValidateSupervisorCredentials: confere usuario ativo da empresa com perfil
 * administrador ou gerente e senha valida, registrando tentativa falha em transacao propria.
 */
@Service
public class ValidadorSupervisor {

    static final int MAX_TENTATIVAS = 5;
    static final Duration DURACAO_BLOQUEIO = Duration.ofMinutes(10);

    public record Resultado(boolean valido, String mensagem, UsuarioDto supervisor) {}

    private final UsuarioRepository usuarios;
    private final TransactionOperations txNova;
    private final Clock clock;

    @Autowired
    public ValidadorSupervisor(UsuarioRepository usuarios, PlatformTransactionManager transactionManager, Clock clock) {
        this(usuarios, criarRequiresNew(transactionManager), clock);
    }

    ValidadorSupervisor(UsuarioRepository usuarios, TransactionOperations txNova, Clock clock) {
        this.usuarios = usuarios;
        this.txNova = txNova;
        this.clock = clock;
    }

    private static TransactionTemplate criarRequiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tt;
    }

    public Resultado validar(String supervisorId, String senha, String companyId) {
        if (supervisorId == null || supervisorId.isBlank() || senha == null || senha.isBlank()) {
            return new Resultado(false, "Identificação e senha do gerente/supervisor são obrigatórias.", null);
        }

        Usuario user = usuarios.findByIdAndCompanyId(supervisorId, companyId).orElse(null);
        if (user == null || !"ativo".equals(user.getStatus())) {
            return new Resultado(false, "Gerente não encontrado ou com cadastro inativo.", null);
        }

        String role = user.getRole() == null ? "" : user.getRole().trim().toLowerCase(Locale.ROOT);
        if (!Perfis.ADMINISTRADOR.equals(role) && !Perfis.GERENTE.equals(role)) {
            return new Resultado(false, "Usuário selecionado não possui perfil de gerente ou administrador.", null);
        }

        OffsetDateTime agora = OffsetDateTime.now(clock);
        if (user.getLockoutEnd() != null && user.getLockoutEnd().isAfter(agora)) {
            return new Resultado(false, "Conta do gerente temporariamente bloqueada por excesso de tentativas.", null);
        }

        if (!PasswordHasher.verify(senha, user.getPasswordHash())) {
            final String id = user.getId();
            txNova.executeWithoutResult(s ->
                    usuarios.registrarTentativaFalha(id, agora, MAX_TENTATIVAS, agora.plus(DURACAO_BLOQUEIO)));
            return new Resultado(false, "Senha do gerente incorreta.", null);
        }

        return new Resultado(true, "Gerente validado com sucesso.", UsuarioDto.de(user));
    }
}
