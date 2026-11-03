package br.com.quack.pdv.promocao.promocao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.quack.pdv.config.QuackProperties;
import br.com.quack.pdv.promocao.promocao.web.PromocaoBindingAdvice;
import br.com.quack.pdv.promocao.promocao.web.PromocaoRequest;
import br.com.quack.pdv.shared.GlobalExceptionHandler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;

class PromocaoBindingTest {
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final PromocaoBindingAdvice advice =
            new PromocaoBindingAdvice(new GlobalExceptionHandler(new QuackProperties()));

    private Object erros(byte[] bytes) {
        var causa =
                assertThrows(
                        JsonProcessingException.class,
                        () -> json.readValue(bytes, PromocaoRequest.class));
        var request = new MockHttpServletRequest();
        request.setAttribute("quack.requestId", "0123456789abcdef0123456789abcdef");
        var r =
                advice.invalido(
                        new HttpMessageNotReadableException(
                                "binding", causa, new MockHttpInputMessage(bytes)),
                        request);
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        return r.getBody().get("errors");
    }

    @ParameterizedTest
    @ValueSource(strings = {"criar-produtoids-null", "criar-inicio-null", "criar-fim-null"})
    void reproduzErrosDeBindingCapturadosDoDotNet(String nome) throws Exception {
        var cenarios =
                json.readTree(getClass().getResourceAsStream("/contrato/cenarios/promocao.json"));
        for (var c : cenarios)
            if (c.path("nome").asText().equals(nome)) {
                var esperado =
                        json.readTree(
                                getClass()
                                        .getResourceAsStream(
                                                "/contrato/gravacoes/promocao/" + nome + ".json"));
                com.fasterxml.jackson.databind.JsonNode atual =
                        json.valueToTree(
                                erros(json.writeValueAsBytes(c.path("requisicao").path("corpo"))));
                assertThat(atual).isEqualTo(esperado.path("corpo").path("errors"));
                return;
            }
        throw new AssertionError("cenário ausente");
    }

    @Test
    void posicaoContaBytesUtf8NaLinhaDoLiteralNull() {
        String corpo = "{\n\"nome\":\"Promoção\",\"inicioVigencia\":null}";
        byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
        int fimDoNullNaLinha =
                new String(bytes, StandardCharsets.ISO_8859_1).indexOf("null")
                        + 4
                        - (corpo.indexOf('\n') + 1);
        assertThat(erros(bytes).toString())
                .contains("LineNumber: 1 | BytePositionInLine: " + fimDoNullNaLinha + ".");
    }
}
