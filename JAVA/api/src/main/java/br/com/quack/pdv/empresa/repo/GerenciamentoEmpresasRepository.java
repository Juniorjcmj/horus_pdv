package br.com.quack.pdv.empresa.repo;
import br.com.quack.pdv.empresa.web.EmpresaAdminItem;
import br.com.quack.pdv.security.PasswordHasher;
import br.com.quack.pdv.shared.FormatoDataDotNet;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
@Repository
public class GerenciamentoEmpresasRepository {
    private static final String CONTAR="""

            SELECT COUNT(1)
            FROM Empresas e
            WHERE (:status IS NULL OR ISNULL(e.Status, 'aprovada') = :status)
              AND (:searchPattern IS NULL OR
                   e.FantasyName LIKE :searchPattern OR
                   e.CorporateName LIKE :searchPattern OR
                   e.Cnpj LIKE :searchPattern OR
                   e.Email LIKE :searchPattern);
            
        """;
    private static final String LISTAR="""

            SELECT
                e.Id,
                e.FantasyName,
                e.CorporateName,
                e.Cnpj,
                e.Email,
                e.Phone,
                e.Mobile,
                e.City,
                e.Uf,
                ISNULL(e.Status, 'aprovada') AS Status,
                ISNULL(e.CreatedAt, SYSDATETIMEOFFSET()) AS CreatedAt,
                e.ReviewedAt,
                e.ReviewedBy,
                e.RejectionReason,
                (SELECT COUNT(1) FROM Usuarios u WHERE u.CompanyId = e.Id) AS TotalUsers,
                (SELECT COUNT(1) FROM Produtos p WHERE p.CompanyId = e.Id) AS TotalProducts,
                (SELECT COUNT(1) FROM Vendas v WHERE v.CompanyId = e.Id) AS TotalSales,
                (SELECT TOP 1 u.Name FROM Usuarios u WHERE u.CompanyId = e.Id ORDER BY u.CreatedAt ASC) AS AdminUserName,
                (SELECT TOP 1 u.Email FROM Usuarios u WHERE u.CompanyId = e.Id ORDER BY u.CreatedAt ASC) AS AdminUserEmail,
                (SELECT TOP 1 u.Phone FROM Usuarios u WHERE u.CompanyId = e.Id ORDER BY u.CreatedAt ASC) AS AdminUserPhone
            FROM Empresas e
            WHERE (:status IS NULL OR ISNULL(e.Status, 'aprovada') = :status)
              AND (:searchPattern IS NULL OR
                   e.FantasyName LIKE :searchPattern OR
                   e.CorporateName LIKE :searchPattern OR
                   e.Cnpj LIKE :searchPattern OR
                   e.Email LIKE :searchPattern)
            ORDER BY
                CASE WHEN e.Id = 'empresa-principal' THEN 0 ELSE 1 END ASC,
                CASE WHEN ISNULL(e.Status, 'aprovada') = 'pendente' THEN 0 ELSE 1 END ASC,
                e.CreatedAt DESC
            OFFSET :offset ROWS FETCH NEXT :pageSize ROWS ONLY;
            
        """;
    private static final String METRICAS="""

            SELECT
                COUNT(1) AS Total,
                SUM(CASE WHEN ISNULL(Status, 'aprovada') = 'pendente' THEN 1 ELSE 0 END) AS Pendentes,
                SUM(CASE WHEN ISNULL(Status, 'aprovada') = 'aprovada' THEN 1 ELSE 0 END) AS Aprovadas,
                SUM(CASE WHEN ISNULL(Status, 'aprovada') = 'rejeitada' THEN 1 ELSE 0 END) AS Rejeitadas,
                SUM(CASE WHEN ISNULL(Status, 'aprovada') = 'bloqueada' THEN 1 ELSE 0 END) AS Bloqueadas
            FROM Empresas;
            
        """;
    private static final String APROVAR="""

            UPDATE Empresas
               SET Status = 'aprovada',
                   ReviewedAt = SYSDATETIMEOFFSET(),
                   ReviewedBy = :reviewer,
                   RejectionReason = NULL
             WHERE Id = :companyId AND Id <> 'empresa-principal';
            
        """;
    private static final String REJEITAR="""

                UPDATE Empresas
                   SET Status = 'rejeitada',
                       ReviewedAt = SYSDATETIMEOFFSET(),
                       ReviewedBy = :reviewer,
                       RejectionReason = :reason
                 WHERE Id = :companyId AND Id <> 'empresa-principal';

                DELETE s
                FROM Sessoes s
                INNER JOIN Usuarios u ON s.UserId = u.Id
                WHERE u.CompanyId = :companyId;
                
        """;
    private static final String BLOQUEAR="""

                UPDATE Empresas
                   SET Status = 'bloqueada',
                       ReviewedAt = SYSDATETIMEOFFSET(),
                       ReviewedBy = :reviewer,
                       RejectionReason = :reason
                 WHERE Id = :companyId AND Id <> 'empresa-principal';

                DELETE s
                FROM Sessoes s
                INNER JOIN Usuarios u ON s.UserId = u.Id
                WHERE u.CompanyId = :companyId;
                
        """;
    private static final String REATIVAR="""

            UPDATE Empresas
               SET Status = 'aprovada',
                   ReviewedAt = SYSDATETIMEOFFSET(),
                   ReviewedBy = :reviewer,
                   RejectionReason = NULL
             WHERE Id = :companyId AND Id <> 'empresa-principal';
            
        """;
    private static final String EXCLUIR="""
DECLARE @CompanyId nvarchar(max)=?;

            BEGIN TRANSACTION;
            BEGIN TRY
                -- Tabelas-filhas (sem CompanyId, referenciam via FK)
                IF OBJECT_ID(N'PromocaoProdutos', N'U') IS NOT NULL
                    DELETE FROM PromocaoProdutos WHERE PromocaoId IN (SELECT Id FROM Promocoes WHERE CompanyId = @CompanyId);
                IF OBJECT_ID(N'PedidoItens', N'U') IS NOT NULL
                    DELETE FROM PedidoItens WHERE PedidoId IN (SELECT Id FROM Pedidos WHERE CompanyId = @CompanyId);
                IF OBJECT_ID(N'VendaPagamentos', N'U') IS NOT NULL
                    DELETE FROM VendaPagamentos WHERE VendaId IN (SELECT Id FROM Vendas WHERE CompanyId = @CompanyId);
                IF OBJECT_ID(N'VendaItens', N'U') IS NOT NULL
                    DELETE FROM VendaItens WHERE VendaId IN (SELECT Id FROM Vendas WHERE CompanyId = @CompanyId);
                IF OBJECT_ID(N'CaixaMovimentos', N'U') IS NOT NULL
                    DELETE FROM CaixaMovimentos WHERE CaixaSessaoId IN (SELECT Id FROM CaixaSessoes WHERE CompanyId = @CompanyId);
                IF OBJECT_ID(N'Sessoes', N'U') IS NOT NULL
                    DELETE FROM Sessoes WHERE UserId IN (SELECT Id FROM Usuarios WHERE CompanyId = @CompanyId);
                IF OBJECT_ID(N'PasswordResetTokens', N'U') IS NOT NULL
                    DELETE FROM PasswordResetTokens WHERE UserId IN (SELECT Id FROM Usuarios WHERE CompanyId = @CompanyId);

                -- Tabelas com CompanyId direto
                IF OBJECT_ID(N'FiadoMovimentos', N'U') IS NOT NULL AND COL_LENGTH(N'FiadoMovimentos', N'CompanyId') IS NOT NULL
                    DELETE FROM FiadoMovimentos WHERE CompanyId = @CompanyId;
                IF OBJECT_ID(N'Promocoes', N'U') IS NOT NULL AND COL_LENGTH(N'Promocoes', N'CompanyId') IS NOT NULL
                    DELETE FROM Promocoes WHERE CompanyId = @CompanyId;
                IF OBJECT_ID(N'Categorias', N'U') IS NOT NULL AND COL_LENGTH(N'Categorias', N'CompanyId') IS NOT NULL
                    DELETE FROM Categorias WHERE CompanyId = @CompanyId;
                IF OBJECT_ID(N'DocumentosFiscais', N'U') IS NOT NULL AND COL_LENGTH(N'DocumentosFiscais', N'CompanyId') IS NOT NULL
                    DELETE FROM DocumentosFiscais WHERE CompanyId = @CompanyId;
                IF OBJECT_ID(N'FiscalSequencias', N'U') IS NOT NULL AND COL_LENGTH(N'FiscalSequencias', N'CompanyId') IS NOT NULL
                    DELETE FROM FiscalSequencias WHERE CompanyId = @CompanyId;
                IF OBJECT_ID(N'Pedidos', N'U') IS NOT NULL AND COL_LENGTH(N'Pedidos', N'CompanyId') IS NOT NULL
                    DELETE FROM Pedidos WHERE CompanyId = @CompanyId;
                IF OBJECT_ID(N'Vendas', N'U') IS NOT NULL AND COL_LENGTH(N'Vendas', N'CompanyId') IS NOT NULL
                    DELETE FROM Vendas WHERE CompanyId = @CompanyId;
                IF OBJECT_ID(N'CaixaSessoes', N'U') IS NOT NULL AND COL_LENGTH(N'CaixaSessoes', N'CompanyId') IS NOT NULL
                    DELETE FROM CaixaSessoes WHERE CompanyId = @CompanyId;
                IF OBJECT_ID(N'ModuloMercadoRegistros', N'U') IS NOT NULL AND COL_LENGTH(N'ModuloMercadoRegistros', N'CompanyId') IS NOT NULL
                    DELETE FROM ModuloMercadoRegistros WHERE CompanyId = @CompanyId;
                IF OBJECT_ID(N'AuditLog', N'U') IS NOT NULL AND COL_LENGTH(N'AuditLog', N'CompanyId') IS NOT NULL
                    DELETE FROM AuditLog WHERE CompanyId = @CompanyId;
                IF OBJECT_ID(N'Produtos', N'U') IS NOT NULL AND COL_LENGTH(N'Produtos', N'CompanyId') IS NOT NULL
                    DELETE FROM Produtos WHERE CompanyId = @CompanyId;
                IF OBJECT_ID(N'Clientes', N'U') IS NOT NULL AND COL_LENGTH(N'Clientes', N'CompanyId') IS NOT NULL
                    DELETE FROM Clientes WHERE CompanyId = @CompanyId;
                IF OBJECT_ID(N'Fornecedores', N'U') IS NOT NULL AND COL_LENGTH(N'Fornecedores', N'CompanyId') IS NOT NULL
                    DELETE FROM Fornecedores WHERE CompanyId = @CompanyId;
                IF OBJECT_ID(N'Usuarios', N'U') IS NOT NULL AND COL_LENGTH(N'Usuarios', N'CompanyId') IS NOT NULL
                    DELETE FROM Usuarios WHERE CompanyId = @CompanyId;

                -- Exclui a empresa
                DELETE FROM Empresas WHERE Id = @CompanyId AND Id <> N'empresa-principal';

                COMMIT TRANSACTION;
            END TRY
            BEGIN CATCH
                ROLLBACK TRANSACTION;
                THROW;
            END CATCH
            
        """;
    public record Lista(List<EmpresaAdminItem> items,int totalCount,int page,int pageSize) {}
    public record Metricas(int total,int pendentes,int aprovadas,int rejeitadas,int bloqueadas,boolean requireApprovalForNewCompanies) {}
    public record Credenciais(boolean success,String message) {}
    private final JdbcTemplate jdbc;private final NamedParameterJdbcTemplate named;
    public GerenciamentoEmpresasRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;this.named=new NamedParameterJdbcTemplate(jdbc);}
    public Lista listar(String search,String status,int page,int pageSize) {
        var p=new MapSqlParameterSource().addValue("status",status,Types.NVARCHAR).addValue("searchPattern",search,Types.NVARCHAR).addValue("offset",(page-1)*pageSize).addValue("pageSize",pageSize);
        int total=named.queryForObject(CONTAR,p,Integer.class);
        var rows=named.query(LISTAR,p,(rs,n)->{var item=new EmpresaAdminItem();
            item.id=texto(rs.getString("Id"));
            item.fantasyName=texto(rs.getString("FantasyName"));
            item.corporateName=texto(rs.getString("CorporateName"));
            item.cnpj=texto(rs.getString("Cnpj"));
            item.email=texto(rs.getString("Email"));
            item.phone=texto(rs.getString("Phone"));
            item.mobile=texto(rs.getString("Mobile"));
            item.city=texto(rs.getString("City"));
            item.uf=texto(rs.getString("Uf"));
            item.status=texto(rs.getString("Status"));
            item.createdAt=data(rs,"CreatedAt");
            item.reviewedAt=data(rs,"ReviewedAt");
            item.reviewedBy=rs.getString("ReviewedBy");
            item.rejectionReason=rs.getString("RejectionReason");
            item.adminUserName=rs.getString("AdminUserName");
            item.adminUserEmail=rs.getString("AdminUserEmail");
            item.adminUserPhone=rs.getString("AdminUserPhone");
            item.totalUsers=rs.getInt("TotalUsers");
            item.totalProducts=rs.getInt("TotalProducts");
            item.totalSales=rs.getInt("TotalSales");
            return item;
        });return new Lista(rows,total,page,pageSize);
    }
    public Metricas metricas() {boolean exige=configuracao();return jdbc.queryForObject(METRICAS,(r,n)->new Metricas(r.getInt("Total"),r.getInt("Pendentes"),r.getInt("Aprovadas"),r.getInt("Rejeitadas"),r.getInt("Bloqueadas"),exige));}
    public boolean configuracao() {try{var v=jdbc.query("SELECT TOP 1 ISNULL(RequireApprovalForNewCompanies,1) FROM Empresas WHERE Id='empresa-principal'",(rs,n)->rs.getBoolean(1));return !v.isEmpty()&&v.getFirst();}catch(RuntimeException e){return true;}}
    public void configurar(boolean required) {jdbc.update("UPDATE Empresas SET RequireApprovalForNewCompanies=? WHERE Id='empresa-principal'",required);}
    @Transactional public boolean situacao(String acao,String companyId,String reviewer,String reason) {
        String sql=switch(acao){case "aprovar"->APROVAR;case "rejeitar"->REJEITAR;case "bloquear"->BLOQUEAR;case "reativar"->REATIVAR;default->throw new IllegalArgumentException(acao);};
        var params=new MapSqlParameterSource().addValue("companyId",companyId).addValue("reviewer",reviewer).addValue("reason",reason==null?null:reason.trim(),Types.NVARCHAR);
        // O .NET roda UPDATE + DELETE num só comando e usa o total de linhas; o mssql-jdbc devolveria só a contagem
        // da última instrução. Por isso cada instrução roda separada, na mesma transação, e as contagens são somadas.
        int linhas=0;
        for(String instrucao:sql.split(";")){
            if(!instrucao.isBlank())linhas+=named.update(instrucao,params);
        }
        return linhas>0;
    }
    @Transactional public Credenciais credenciais(String companyId,String newEmail,String newPassword) {
        boolean email=newEmail!=null&&!newEmail.isBlank(),senha=newPassword!=null&&!newPassword.isBlank();
        if(!email&&!senha)return new Credenciais(false,"Informe o novo e-mail ou a nova senha.");
        var admins=jdbc.query("SELECT TOP 1 Id,Name FROM Usuarios WHERE CompanyId=? AND Role=N'administrador' AND Status=N'ativo' ORDER BY CreatedAt",(rs,n)->new String[]{rs.getString("Id"),rs.getString("Name")},companyId);
        if(admins.isEmpty())return new Credenciais(false,"Nenhum usuário administrador ativo encontrado nesta empresa.");
        String userId=admins.getFirst()[0],name=admins.getFirst()[1];
        String normalizado=email?newEmail.trim().toLowerCase(Locale.ROOT):null;
        if(email) {if(!normalizado.contains("@"))return new Credenciais(false,"E-mail inválido.");if(jdbc.queryForObject("SELECT COUNT(1) FROM Usuarios WHERE Email=? AND Id<>?",Integer.class,normalizado,userId)>0)return new Credenciais(false,"Este e-mail já está em uso por outro usuário.");}
        var sets=new ArrayList<String>();var args=new ArrayList<Object>();
        if(email){sets.add("Email=?");args.add(normalizado);}if(senha){sets.add("PasswordHash=?");sets.add("MustChangePassword=0");args.add(PasswordHasher.hash(newPassword));}args.add(userId);
        jdbc.update("UPDATE Usuarios SET "+String.join(", ",sets)+" WHERE Id=?",args.toArray());
        if(senha)jdbc.update("DELETE FROM Sessoes WHERE UserId=?",userId);
        return new Credenciais(true,"Credenciais atualizadas ("+(email?(senha?"e-mail e senha":"e-mail"):"senha")+") para o administrador "+name+".");
    }
    public boolean excluir(String companyId) {
        if("empresa-principal".equalsIgnoreCase(companyId))return false;
        return Boolean.TRUE.equals(jdbc.execute(EXCLUIR,(PreparedStatementCallback<Boolean>)statement->{
            statement.setString(1,companyId);boolean result=statement.execute();int afetadas=0;
            while(true){if(!result){int count=statement.getUpdateCount();if(count==-1)break;afetadas+=count;}result=statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);}
            return afetadas>0;
        }));
    }
    private static String data(ResultSet rs,String column)throws SQLException{var d=rs.getObject(column,OffsetDateTime.class);return d==null?null:FormatoDataDotNet.formatar(d);}
    private static String texto(String v){return v==null?"":v;}
}
