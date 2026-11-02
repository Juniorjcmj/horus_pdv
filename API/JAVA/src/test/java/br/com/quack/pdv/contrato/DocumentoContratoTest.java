package br.com.quack.pdv.contrato;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class DocumentoContratoTest {
    @Test void comparaCabecalhosEFormatoSemIgualarGeradores() throws Exception {
        var j=MotorDeContrato.JSON;
        var esperado=j.readTree("{\"status\":200,\"cabecalhos\":{\"content-type\":\"application/json\"},\"corpo\":{\"openapi\":\"3.0.0\",\"paths\":{\"/dotnet\":{}}}}");
        var atual=j.readTree("{\"status\":200,\"cabecalhos\":{\"content-type\":\"application/json\"},\"corpo\":{\"openapi\":\"3.1.0\",\"paths\":{\"/java\":{}}}}");
        var cenario=j.readTree("{\"compararDocumentoOpenApi\":true}");
        assertThat(MotorDeContrato.comparar(esperado,atual,cenario)).isEmpty();
        ((com.fasterxml.jackson.databind.node.ObjectNode)atual.get("corpo")).remove("paths");
        assertThat(MotorDeContrato.comparar(esperado,atual,cenario)).contains("documento OpenAPI 3 invalido");
    }
    @Test void idsGuidCanonicosNaoComparamPrefixoAleatorio() throws Exception {
        var j=MotorDeContrato.JSON;
        var esperado=j.createObjectNode();esperado.put("status",200);esperado.putObject("cabecalhos");esperado.putObject("corpo").putObject("data").put("id","11111111-1111-4111-8111-111111111111");
        var atual=esperado.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)atual.path("corpo").path("data")).put("id","22222222-2222-4222-8222-222222222222");
        var cenario=j.createObjectNode();cenario.putObject("volateis").put("data.id","id");
        assertThat(MotorDeContrato.comparar(esperado,atual,cenario)).isEmpty();
        ((com.fasterxml.jackson.databind.node.ObjectNode)atual.path("corpo").path("data")).put("id","22222222-invalido");
        assertThat(MotorDeContrato.comparar(esperado,atual,cenario)).isNotEmpty();
    }
}
