package br.com.quack.pdv.banco;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.quack.pdv.BancoIT;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Test;

/**
 * Banco restaurado da producao (esquema pronto, sem flyway_schema_history) e migracao alterada depois de aplicada.
 * Usa o Flyway direto, com a mesma configuracao do application.yml, em bancos proprios do container.
 */
class BaselineBancoRestauradoIT extends BancoIT {

    static Flyway flyway(String banco, String alvo) {
        var cfg = Flyway.configure()
                .dataSource(urlDoBanco(banco), SQL.getUsername(), SQL.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .validateOnMigrate(true);
        if (alvo != null) cfg = cfg.target(alvo);
        return cfg.load();
    }

    static Connection conexao(String banco) throws Exception {
        return DriverManager.getConnection(urlDoBanco(banco), SQL.getUsername(), SQL.getPassword());
    }

    static List<String> historico(String banco) throws Exception {
        List<String> linhas = new ArrayList<>();
        try (Connection c = conexao(banco); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT version, type FROM flyway_schema_history ORDER BY installed_rank")) {
            while (r.next()) linhas.add(r.getString(1) + ":" + r.getString(2));
        }
        return linhas;
    }

    @Test
    void bancoRestauradoSobeComBaselineSemRodarOV1EPreservaOsDados() throws Exception {
        criarBanco("restaurado");
        // 1) esquema pronto, como o banco criado pelo script .NET (V1 aplicado e historico apagado)
        flyway("restaurado", "1").migrate();
        try (Connection c = conexao("restaurado"); Statement s = c.createStatement()) {
            s.execute("DROP TABLE flyway_schema_history");
            // 2) dados "de producao" que nao podem sumir
            s.execute("INSERT INTO Empresas (Id, FantasyName, CorporateName, Cnpj, Status) VALUES "
                    + "(N'empresa-principal', N'Empresa Restaurada', N'Restaurada LTDA', N'06.332.765/0001-05', N'aprovada')");
            s.execute("INSERT INTO Usuarios (Id, CompanyId, Cpf, Name, Email, Role, Status, CreatedAt, LastLoginAt, PasswordHash) "
                    + "VALUES (N'usr-prod-1', N'empresa-principal', N'11111111111', N'Maria da Produção', "
                    + "N'maria@producao.com', N'administrador', N'ativo', N'2025-01-01', N'-', N'100000.x.y')");
        }

        // 3) a API sobe: baseline na versao 1, V2, V3 e V4 rodam
        flyway("restaurado", null).migrate();

        assertThat(historico("restaurado")).containsExactly("1:BASELINE", "2:SQL", "3:SQL", "4:SQL");
        try (Connection c = conexao("restaurado"); Statement s = c.createStatement()) {
            try (ResultSet r = s.executeQuery("SELECT FantasyName FROM Empresas WHERE Id = N'empresa-principal'")) {
                assertThat(r.next()).isTrue();
                assertThat(r.getString(1)).isEqualTo("Empresa Restaurada");   // V2 usa IF NOT EXISTS
            }
            try (ResultSet r = s.executeQuery("SELECT Name FROM Usuarios WHERE Id = N'usr-prod-1'")) {
                assertThat(r.next()).isTrue();
                assertThat(r.getString(1)).isEqualTo("Maria da Produção");   // acentos preservados
            }
        }
    }

    @Test
    void migracaoAlteradaDepoisDeAplicadaImpedeASubida() throws Exception {
        criarBanco("checksum");
        flyway("checksum", null).migrate();
        try (Connection c = conexao("checksum"); Statement s = c.createStatement()) {
            s.execute("UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '2'");
        }
        assertThatThrownBy(() -> flyway("checksum", null).migrate())
                .isInstanceOf(FlywayValidateException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    void bancoNovoRecebeV1V2V3EV4() throws Exception {
        criarBanco("novo");
        flyway("novo", null).migrate();
        assertThat(historico("novo")).containsExactly("1:SQL", "2:SQL", "3:SQL", "4:SQL");
    }
}
