package br.com.quack.pdv;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MSSQLServerContainer;

/**
 * Base dos testes de integracao: um SQL Server 2022 (Testcontainers) compartilhado pela JVM, com o Flyway aplicando
 * o esquema na subida do contexto Spring.
 */
@ActiveProfiles("it")
public abstract class BancoIT {

    public static final MSSQLServerContainer<?> SQL =
            new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense();

    static {
        SQL.start();
        criarBanco("quack");
    }

    /** Cria um banco vazio no servidor (o master tem tabelas de sistema que sujariam a comparacao de esquema). */
    public static void criarBanco(String nome) {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(SQL.getJdbcUrl(), SQL.getUsername(), SQL.getPassword());
             java.sql.Statement s = c.createStatement()) {
            s.execute("IF DB_ID('" + nome + "') IS NULL BEGIN CREATE DATABASE [" + nome + "]; ALTER DATABASE [" + nome + "] SET RECOVERY SIMPLE; END");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Nao foi possivel criar o banco " + nome, e);
        }
    }

    public static String urlDoBanco(String nome) {
        return SQL.getJdbcUrl() + ";databaseName=" + nome;
    }

    @DynamicPropertySource
    static void propriedadesDoBanco(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> urlDoBanco("quack"));
        registro.add("spring.datasource.username", SQL::getUsername);
        registro.add("spring.datasource.password", SQL::getPassword);
    }
}
