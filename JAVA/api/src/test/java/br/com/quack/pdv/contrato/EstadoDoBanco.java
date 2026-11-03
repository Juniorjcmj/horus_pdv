package br.com.quack.pdv.contrato;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.Supplier;

/** Restaura o banco do contrato (reset + seed) por uma conexao propria, nos dois lados da comparacao. */
public final class EstadoDoBanco {

    private final Supplier<Connection> conexoes;
    private final String reset;
    private final String seed;

    public EstadoDoBanco(Supplier<Connection> conexoes) {
        this.conexoes = conexoes;
        this.reset = ler("/contrato/seed/reset-contrato.sql");
        this.seed = ler("/contrato/seed/seed-contrato.sql");
    }

    public static EstadoDoBanco por(String url, String usuario, String senha) {
        return new EstadoDoBanco(() -> {
            try {
                return DriverManager.getConnection(url, usuario, senha);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    public void restaurar() {
        try (Connection c = conexoes.get(); Statement s = c.createStatement()) {
            s.execute(reset);
            s.execute(seed);
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao restaurar o estado do banco do contrato", e);
        }
    }

    private static String ler(String recurso) {
        try (var in = EstadoDoBanco.class.getResourceAsStream(recurso)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("recurso ausente: " + recurso, e);
        }
    }
}
