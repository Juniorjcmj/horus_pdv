package br.com.quack.pdv.usuario.repo;

import br.com.quack.pdv.usuario.domain.Usuario;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsuarioRepository extends JpaRepository<Usuario, String> {

    Optional<Usuario> findByEmail(String email);

    Optional<Usuario> findByIdAndCompanyId(String id, String companyId);

    List<Usuario> findByCompanyIdOrderByName(String companyId);

    List<Usuario> findByCompanyIdAndStatusAndRoleInOrderByName(String companyId, String status, List<String> roles);

    /** E-mail e unico no sistema todo (comparacao sem espacos e sem diferenciar maiusculas). */
    @Query(value = """
            SELECT COUNT(1) FROM Usuarios
             WHERE Id <> :currentId
               AND LOWER(LTRIM(RTRIM(Email))) = LOWER(LTRIM(RTRIM(:email)))
            """, nativeQuery = true)
    int contarEmailDuplicado(@Param("currentId") String currentId, @Param("email") String email);

    /** CPF e unico por empresa. */
    @Query(value = """
            SELECT COUNT(1) FROM Usuarios
             WHERE Id <> :currentId AND CompanyId = :companyId AND Cpf = :cpf
            """, nativeQuery = true)
    int contarCpfDuplicado(@Param("currentId") String currentId, @Param("companyId") String companyId,
                           @Param("cpf") String cpf);

    @Query(value = "SELECT COUNT(1) FROM Usuarios WHERE LOWER(LTRIM(RTRIM(Email))) = :email", nativeQuery = true)
    int contarPorEmail(@Param("email") String email);

    @Query(value = """
            SELECT * FROM Usuarios
             WHERE REPLACE(REPLACE(REPLACE(REPLACE(Cpf, '.', ''), '/', ''), '-', ''), ' ', '') = :cnpj
               AND Email = :email
               AND Status = N'ativo'
            """, nativeQuery = true)
    Optional<Usuario> buscarParaRedefinicao(@Param("cnpj") String cnpj, @Param("email") String email);

    /** Mesmo SQL atomico de RegisterFailedAttemptDb: soma a tentativa e so bloqueia ao chegar no maximo. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE Usuarios
               SET FailedLoginAttempts = FailedLoginAttempts + 1,
                   FirstFailedLoginAt = ISNULL(FirstFailedLoginAt, :agora),
                   LockoutEnd = CASE
                       WHEN FailedLoginAttempts + 1 >= :maximo THEN :fimBloqueio
                       ELSE LockoutEnd
                   END
             WHERE Id = :id
            """, nativeQuery = true)
    int registrarTentativaFalha(@Param("id") String id, @Param("agora") OffsetDateTime agora,
                                @Param("maximo") int maximo, @Param("fimBloqueio") OffsetDateTime fimBloqueio);

    @Query("SELECT u.lockoutEnd FROM Usuario u WHERE u.id = :id")
    Optional<OffsetDateTime> buscarFimBloqueio(@Param("id") String id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE Usuarios SET FailedLoginAttempts = 0, FirstFailedLoginAt = NULL, LockoutEnd = NULL WHERE Id = :id",
            nativeQuery = true)
    int zerarBloqueio(@Param("id") String id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE Usuarios
               SET LastLoginAt = :lastLoginAt, FailedLoginAttempts = 0, FirstFailedLoginAt = NULL, LockoutEnd = NULL
             WHERE Id = :id
            """, nativeQuery = true)
    int registrarLoginComSucesso(@Param("id") String id, @Param("lastLoginAt") String lastLoginAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE Usuarios SET PasswordHash = :hash, MustChangePassword = 0 WHERE Id = :id", nativeQuery = true)
    int trocarSenha(@Param("id") String id, @Param("hash") String hash);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE Usuarios SET MustChangePassword = 1 WHERE Id = :id AND CompanyId = :companyId", nativeQuery = true)
    int exigirTrocaDeSenha(@Param("id") String id, @Param("companyId") String companyId);
}
