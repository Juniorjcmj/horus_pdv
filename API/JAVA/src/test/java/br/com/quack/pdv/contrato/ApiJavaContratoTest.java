package br.com.quack.pdv.contrato;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.BancoIT;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Compara a API Java com as gravacoes da API .NET: sobe a aplicacao inteira (porta real) sobre um SQL Server do
 * Testcontainers criado pelo Flyway + seed, repete cada cenario e compara status, cabecalhos e corpo por caminho JSON.
 */
@Tag("contrato")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiJavaContratoTest extends BancoIT {

    @LocalServerPort int porta;

    @TestFactory
    Stream<DynamicTest> cenarios() throws Exception {
        EstadoDoBanco estado = EstadoDoBanco.por(urlDoBanco("quack"), SQL.getUsername(), SQL.getPassword());
        MotorDeContrato motor = new MotorDeContrato("http://localhost:" + porta, estado::restaurar);

        List<DynamicTest> testes = new ArrayList<>();
        for (String grupo : MotorDeContrato.GRUPOS) {
            for (JsonNode cenario : MotorDeContrato.carregarCenarios(grupo)) {
                String nome = cenario.get("nome").asText();
                testes.add(DynamicTest.dynamicTest(grupo + "/" + nome, () -> {
                    JsonNode gravada = MotorDeContrato.carregarGravacao(grupo, nome);
                    JsonNode atual = motor.executar(cenario);
                    if (cenario.has("divergencia")) {
                        // Divergencia consciente (ver "motivo" no cenario): a Java segue o spec, nao o comportamento da .NET.
                        JsonNode d = cenario.get("divergencia");
                        assertThat(atual.get("status").asInt()).as("status (divergencia)").isEqualTo(d.get("status").asInt());
                        assertThat(atual.path("corpo").path("message").asText()).isEqualTo(d.get("mensagem").asText());
                        return;
                    }
                    List<String> difs = MotorDeContrato.comparar(gravada, atual, cenario);
                    assertThat(difs).as("diferencas de contrato em " + grupo + "/" + nome + "\nresposta Java: " + atual).isEmpty();
                }));
            }
        }
        return testes.stream();
    }
}
