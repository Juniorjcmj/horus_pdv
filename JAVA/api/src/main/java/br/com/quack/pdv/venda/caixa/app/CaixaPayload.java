package br.com.quack.pdv.venda.caixa.app;

import br.com.quack.pdv.venda.caixa.domain.CaixaStatusDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** (De)serializa o CaixaStatusDto de ProcessedEvents.ResponsePayload: JSON camelCase lido tambem pela API .NET. */
final class CaixaPayload {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
            .build();

    private CaixaPayload() {}

    static String serializar(CaixaStatusDto status) {
        try {
            return MAPPER.writeValueAsString(status);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar o status do caixa.", e);
        }
    }

    static CaixaStatusDto ler(String json) {
        try {
            CaixaStatusDto lido = MAPPER.readValue(json, CaixaStatusDto.class);
            if (lido == null) throw new IllegalStateException();
            return lido;
        } catch (JsonProcessingException | RuntimeException e) {
            throw new br.com.quack.pdv.shared.NegocioException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Falha ao recuperar payload de resposta do evento processado.");
        }
    }
}
