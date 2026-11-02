package br.com.quack.pdv.contrato;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Grava as respostas da API .NET para cada cenario em src/test/resources/contrato/gravacoes/.
 * So roda com a API .NET do ambiente do contrato no ar (contrato/ambiente-dotnet/subir.sh):
 *   mvn test -Dtest=GravadorDoContrato -Dgroups=gravar-contrato -Dexcluir.grupos=nenhum \
 *            -Dcontrato.gravar=true -Dcontrato.url=http://localhost:5270
 * Opcional: -Dcontrato.grupo=auth (so um grupo), -Dcontrato.sql=jdbc:sqlserver://localhost:14334;...
 */
@Tag("gravar-contrato")
@EnabledIfSystemProperty(named = "contrato.gravar", matches = "true")
class GravadorDoContrato {

    @Test
    void gravarCenariosContraAApiDotNet() throws Exception {
        String url = System.getProperty("contrato.url", "http://localhost:5270");
        String sql = System.getProperty("contrato.sql",
                "jdbc:sqlserver://localhost:14334;databaseName=HorusPdv;encrypt=true;trustServerCertificate=true");
        EstadoDoBanco estado = EstadoDoBanco.por(sql, "sa", System.getProperty("contrato.sqlsenha", "Contrato#Quack2026"));
        MotorDeContrato motor = new MotorDeContrato(url, estado::restaurar);
        Path raiz = Path.of("src", "test", "resources", "contrato", "gravacoes");
        String so = System.getProperty("contrato.grupo");

        int total = 0;
        for (String grupo : MotorDeContrato.GRUPOS) {
            if (so != null && !so.isBlank() && !so.equals(grupo)) continue;
            for (JsonNode cenario : MotorDeContrato.carregarCenarios(grupo)) {
                JsonNode gravacao = motor.executar(cenario);
                MotorDeContrato.gravar(raiz, grupo, cenario.get("nome").asText(), gravacao);
                total++;
            }
        }
        System.out.println("Contrato gravado: " + total + " cenarios em " + raiz.toAbsolutePath());
    }
}
