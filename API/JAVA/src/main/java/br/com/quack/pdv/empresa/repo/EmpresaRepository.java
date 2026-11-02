package br.com.quack.pdv.empresa.repo;

import br.com.quack.pdv.empresa.domain.Empresa;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmpresaRepository extends JpaRepository<Empresa, String> {

    @Query(value = "SELECT COUNT(1) FROM Empresas WHERE Cnpj = :cnpj AND Id <> 'empresa-principal'", nativeQuery = true)
    int contarPorCnpj(@Param("cnpj") String cnpj);

    @Query(value = "SELECT TOP 1 ISNULL(RequireApprovalForNewCompanies, 1) FROM Empresas WHERE Id = 'empresa-principal'",
            nativeQuery = true)
    Optional<Boolean> exigeAprovacaoParaNovasEmpresas();

    @Query(value = "SELECT Cnpj FROM Empresas WHERE Id = :id", nativeQuery = true)
    Optional<String> buscarCnpj(@Param("id") String id);

    /** Mesmo INSERT de EnsureCompanyForPublicRegistration (o .NET so insere se a empresa nao existir). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            IF NOT EXISTS (SELECT 1 FROM Empresas WHERE Id = :id)
            BEGIN
                INSERT INTO Empresas
                    (Id, FantasyName, CorporateName, Cnpj, StateRegistration, Website, Email, SacPhone, Phone, Mobile,
                     Cep, Address, Number, Neighborhood, City, Uf, Complement, EmailSmtpEnabled, EmailSmtpHost,
                     EmailSmtpPort, EmailSmtpEnableSsl, EmailSmtpUser, EmailSmtpPassword, EmailSmtpFromEmail,
                     EmailSmtpFromName, EmailSmtpReplyTo, Status, CreatedAt)
                VALUES
                    (:id, :nome, :nome, :cnpj, N'', N'', :email, N'', :telefone, :telefone,
                     N'', N'', N'', N'', N'', N'', N'', 0, N'smtp-mail.outlook.com',
                     587, 1, N'', N'', N'', :nome, N'', :status, SYSDATETIMEOFFSET())
            END
            """, nativeQuery = true)
    int inserirEmpresaCadastroPublico(@Param("id") String id, @Param("nome") String nome, @Param("cnpj") String cnpj,
                                      @Param("email") String email, @Param("telefone") String telefone,
                                      @Param("status") String status);
}
