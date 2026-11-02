package br.com.quack.pdv.banco;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.BancoIT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** O banco criado pelo Flyway tem o mesmo esquema do banco criado pela API .NET (snapshot em contrato/esquema). */
@SpringBootTest
class EsquemaEquivalenteIT extends BancoIT {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void esquemaDoFlywayIgualAoDoDotNet() throws Exception {
        ObjectMapper m = new ObjectMapper();
        JsonNode esperado;
        try (InputStream in = getClass().getResourceAsStream("/contrato/esquema/esquema-dotnet.json")) {
            esperado = m.readTree(in);
        }
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/contrato/esquema/snapshot-esquema.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // O script e de uma instrucao so (SET NOCOUNT ON + SELECT ... FOR JSON): o JSON vem em varias linhas.
        String json = String.join("", jdbc.queryForList(sql.replace("SET NOCOUNT ON;", ""), String.class));
        JsonNode atual = m.readTree(json);

        for (String grupo : new String[] {"colunas", "indices", "chaves"}) {
            assertThat(atual.get(grupo).size()).as("quantidade de " + grupo).isEqualTo(esperado.get(grupo).size());
            for (int i = 0; i < esperado.get(grupo).size(); i++) {
                assertThat(atual.get(grupo).get(i)).as(grupo + "[" + i + "]").isEqualTo(esperado.get(grupo).get(i));
            }
        }
    }
}
