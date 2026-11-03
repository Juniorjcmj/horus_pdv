package br.com.quack.pdv.auth.repo;

import br.com.quack.pdv.auth.domain.PasswordResetToken;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, String> {

    Optional<PasswordResetToken> findByTokenHashAndConsumedAtIsNull(String tokenHash);

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM PasswordResetTokens WHERE ExpiresAt <= :corte", nativeQuery = true)
    int apagarVencidosAntesDe(@Param("corte") OffsetDateTime corte);

    /** Invalida os tokens ainda nao usados do usuario. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE PasswordResetTokens SET ConsumedAt = :agora, UpdatedAt = :agora
             WHERE UserId = :userId AND ConsumedAt IS NULL
            """, nativeQuery = true)
    int invalidarNaoUsados(@Param("userId") String userId, @Param("agora") OffsetDateTime agora);

    /** Marca como consumido pelo hash (ConsumePasswordResetToken). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE PasswordResetTokens
               SET ConsumedAt = COALESCE(ConsumedAt, :agora), ResetIp = :ip, ResetUserAgent = :userAgent,
                   ResetDevice = :dispositivo, UpdatedAt = :agora
             WHERE TokenHash = :hash
            """, nativeQuery = true)
    int consumirPorHash(@Param("hash") String hash, @Param("agora") OffsetDateTime agora, @Param("ip") String ip,
                        @Param("userAgent") String userAgent, @Param("dispositivo") String dispositivo);

    /** Consome todos os tokens do usuario ao redefinir a senha. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE PasswordResetTokens
               SET ConsumedAt = COALESCE(ConsumedAt, :agora), ResetIp = :ip, ResetUserAgent = :userAgent,
                   ResetDevice = :dispositivo, UpdatedAt = :agora
             WHERE UserId = :userId
            """, nativeQuery = true)
    int consumirDoUsuario(@Param("userId") String userId, @Param("agora") OffsetDateTime agora, @Param("ip") String ip,
                          @Param("userAgent") String userAgent, @Param("dispositivo") String dispositivo);
}
