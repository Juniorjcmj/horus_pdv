package br.com.quack.pdv.contrato;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.BancoIT;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Compara a API Java com as gravacoes da API .NET: sobe a aplicacao inteira (porta real) sobre um
 * SQL Server do Testcontainers criado pelo Flyway + seed, repete cada cenario e compara status,
 * cabecalhos e corpo por caminho JSON.
 */
@Tag("contrato")
@Import(ApiJavaContratoTest.RelogioDoContrato.class)
// Mesmas chaves do ambiente .NET de contrato (contrato/ambiente-dotnet/subir.sh): o seed traz
// segredos `enc:v1:`
// cifrados com elas, que os dois lados precisam decifrar igual.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "quack.auth.jwt-secret=segredo-jwt-do-contrato-0123456789-abcdefghijkl",
            "quack.security.encryption-key=chave-de-cifra-do-contrato-0123456789-abcdefgh"
        })
class ApiJavaContratoTest extends BancoIT {

    @TestConfiguration
    static class RelogioDoContrato {
        @Bean
        @Primary
        Clock clockDoContrato() {
            // Validades e faixas dos vetores .NET foram gravadas em 02/11/2026.
            // Restrito a esta suíte; avança normalmente para preservar IDs e ordem.
            Clock real = Clock.systemUTC();
            return Clock.offset(
                    real,
                    java.time.Duration.between(
                            real.instant(), Instant.parse("2026-11-02T15:00:00.1234567Z")));
        }
    }

    @LocalServerPort int porta;

    @TestFactory
    Stream<DynamicTest> cenarios() throws Exception {
        EstadoDoBanco estado =
                EstadoDoBanco.por(urlDoBanco("quack"), SQL.getUsername(), SQL.getPassword());
        MotorDeContrato motor = new MotorDeContrato("http://localhost:" + porta, estado::restaurar);

        List<DynamicTest> testes = new ArrayList<>();
        String so = System.getProperty("contrato.grupo");
        String[] grupos = so != null && !so.isBlank() ? so.split(",") : MotorDeContrato.GRUPOS;
        for (String grupo : grupos) {
            for (JsonNode cenario : MotorDeContrato.carregarCenarios(grupo)) {
                String nome = cenario.get("nome").asText();
                String filtro = System.getProperty("contrato.cenario");
                if (filtro != null && !nome.matches(filtro)) continue;
                testes.add(
                        DynamicTest.dynamicTest(
                                grupo + "/" + nome,
                                () -> {
                                    JsonNode gravada =
                                            MotorDeContrato.carregarGravacao(grupo, nome);
                                    JsonNode atual = motor.executar(cenario);
                                    if (cenario.has("divergencia")) {
                                        // Divergencia consciente (ver "motivo" no cenario): a Java
                                        // segue o spec, nao o comportamento da .NET.
                                        JsonNode d = cenario.get("divergencia");
                                        assertThat(atual.get("status").asInt())
                                                .as("status (divergencia)")
                                                .isEqualTo(d.get("status").asInt());
                                        assertThat(atual.path("corpo").path("message").asText())
                                                .isEqualTo(d.get("mensagem").asText());
                                        return;
                                    }
                                    List<String> difs =
                                            MotorDeContrato.comparar(gravada, atual, cenario);
                                    assertThat(difs)
                                            .as(
                                                    "diferencas de contrato em "
                                                            + grupo
                                                            + "/"
                                                            + nome
                                                            + "\nresposta Java: "
                                                            + atual)
                                            .isEmpty();
                                }));
            }
        }
        return testes.stream();
    }
}
