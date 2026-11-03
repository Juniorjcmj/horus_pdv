package br.com.quack.pdv.contrato;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class MotorDeContratoDataJsonTest {
    @Test
    void aceitaFracaoAparadaSomenteNaRegraExplicitaJson() {
        var json = new ObjectMapper();
        var esperado = json.createObjectNode();
        esperado.put("status", 200);
        esperado.set("cabecalhos", json.createObjectNode());
        esperado.set(
                "corpo", json.createObjectNode().put("data", "2026-11-03T09:00:00.1234567-03:00"));
        var cenario = json.createObjectNode();
        cenario.set("volateis", json.createObjectNode().put("data", "iso-json"));
        for (String data :
                new String[] {"2026-11-03T09:00:00-03:00", "2026-11-03T09:00:00.123456-03:00"}) {
            var atual = esperado.deepCopy();
            atual.withObject("corpo").put("data", data);
            assertThat(MotorDeContrato.comparar(esperado, atual, cenario)).isEmpty();
            cenario.withObject("volateis").put("data", "iso");
            assertThat(MotorDeContrato.comparar(esperado, atual, cenario)).isNotEmpty();
            cenario.withObject("volateis").put("data", "iso-json");
        }
        for (String data :
                new String[] {
                    "2026-11-03",
                    "2026-11-03T09:00:00Z",
                    "2026-11-03T09:00:00.12345678-03:00",
                    "2026-11-03T09:00:00.1234560-03:00"
                }) {
            var atual = esperado.deepCopy();
            atual.withObject("corpo").put("data", data);
            assertThat(MotorDeContrato.comparar(esperado, atual, cenario)).isNotEmpty();
        }
    }
}
