package br.com.quack.pdv.sessao.repo;

import br.com.quack.pdv.sessao.domain.Sessao;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessaoRepository extends JpaRepository<Sessao, String> {

    List<Sessao> findByUserIdOrderByCreatedAtDesc(String userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM Sessoes WHERE Id = :id AND UserId = :userId", nativeQuery = true)
    int apagarDoUsuario(@Param("id") String id, @Param("userId") String userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM Sessoes WHERE UserId = :userId AND Id <> :atual", nativeQuery = true)
    int apagarOutras(@Param("userId") String userId, @Param("atual") String atual);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM Sessoes WHERE UserId = :userId", nativeQuery = true)
    int apagarTodasDoUsuario(@Param("userId") String userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM Sessoes WHERE Id = :id", nativeQuery = true)
    int apagarPorId(@Param("id") String id);

    /** Validacao de toda requisicao autenticada em UMA consulta (igual ao ValidateRequestAsync do .NET). */
    @Query(value = """
            SELECT
                CASE WHEN EXISTS (SELECT 1 FROM Sessoes WHERE Id = :sessionId) THEN 1 ELSE 0 END AS sessionActive,
                (SELECT TOP 1 Status FROM Usuarios
                  WHERE Id = :userId AND (:companyId IS NULL OR CompanyId = :companyId)) AS userStatus,
                (SELECT TOP 1 PerfisAdicionais FROM Usuarios
                  WHERE Id = :userId AND (:companyId IS NULL OR CompanyId = :companyId)) AS perfisAdicionais,
                (SELECT TOP 1 Status FROM Empresas WHERE Id = :companyId) AS companyStatus
            """, nativeQuery = true)
    Optional<ValidacaoRequisicaoView> validarRequisicao(@Param("sessionId") String sessionId,
                                                         @Param("userId") String userId,
                                                         @Param("companyId") String companyId);

    interface ValidacaoRequisicaoView {
        Integer getSessionActive();

        String getUserStatus();

        String getPerfisAdicionais();

        String getCompanyStatus();
    }
}
