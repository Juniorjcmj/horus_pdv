package br.com.quack.pdv.empresa;
import br.com.quack.pdv.empresa.repo.GerenciamentoEmpresasRepository;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
/** Captures the real repository SQL and bound values; no service mock can hide broken JDBC markers. */
class GerenciamentoEmpresasSqlTest {
    static class CapturaJdbc extends JdbcTemplate {
        final List<String> comandos=new ArrayList<>();
        final List<Object[]> parametros=new ArrayList<>();
        final PreparedStatement statement=mock(PreparedStatement.class);
        @Override public int update(String sql,Object...args) {comandos.add(sql);parametros.add(args);return 1;}
        @Override @SuppressWarnings("unchecked") public <T> List<T> query(String sql,RowMapper<T> mapper,Object...args) {comandos.add(sql);parametros.add(args);return List.of((T)new String[]{"usr-admin","Nome Admin"});}
        @Override public <T>T queryForObject(String sql,Class<T> tipo,Object...args) {comandos.add(sql);parametros.add(args);return tipo.cast(Integer.valueOf(0));}
        @Override public <T>T execute(String sql,PreparedStatementCallback<T> callback) {
            comandos.add(sql);
            try {when(statement.execute()).thenReturn(false);when(statement.getUpdateCount()).thenReturn(1,-1);return callback.doInPreparedStatement(statement);}
            catch(SQLException e){throw new IllegalStateException(e);}
        }
    }
    @Test void configuracaoUsaMarcadorJdbc() {var jdbc=new CapturaJdbc();new GerenciamentoEmpresasRepository(jdbc).configurar(true);assertThat(jdbc.comandos).containsExactly("UPDATE Empresas SET RequireApprovalForNewCompanies=? WHERE Id='empresa-principal'");assertThat(jdbc.parametros.getFirst()).containsExactly(true);}
    @Test void credenciaisPreservamMarcadoresNoSelectUpdateERevogacao() {
        var jdbc=new CapturaJdbc();var r=new GerenciamentoEmpresasRepository(jdbc).credenciais("empresa-alvo"," NOVO@LOJA.COM ","Senha#Nova2026");assertThat(r.success()).isTrue();
        assertThat(jdbc.comandos).containsExactly(
            "SELECT TOP 1 Id,Name FROM Usuarios WHERE CompanyId=? AND Role=N'administrador' AND Status=N'ativo' ORDER BY CreatedAt",
            "SELECT COUNT(1) FROM Usuarios WHERE Email=? AND Id<>?",
            "UPDATE Usuarios SET Email=?, PasswordHash=?, MustChangePassword=0 WHERE Id=?",
            "DELETE FROM Sessoes WHERE UserId=?");
        assertThat(jdbc.parametros.get(1)).containsExactly("novo@loja.com","usr-admin");assertThat(jdbc.parametros.get(2)[1].toString()).startsWith("100000.");assertThat(jdbc.parametros.get(3)).containsExactly("usr-admin");
    }
    @Test void loteRecebeIdIntegralESomaTodosResultados() throws Exception {
        var jdbc=new CapturaJdbc();String id="e".repeat(40)+"-sufixo";assertThat(new GerenciamentoEmpresasRepository(jdbc).excluir(id)).isTrue();
        assertThat(jdbc.comandos.getFirst()).startsWith("DECLARE @CompanyId nvarchar(max)=?;");
        verify(jdbc.statement).setString(1,id);
    }
}
