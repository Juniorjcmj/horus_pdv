package br.com.quack.pdv.venda.registro.app;

import br.com.quack.pdv.shared.FormatoDataDotNet;
import br.com.quack.pdv.shared.NegocioException;
import br.com.quack.pdv.venda.registro.domain.VendaRegistroResultado;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.http.HttpStatus;

/**
 * (De)serializa o VendaRegistroResultado de ProcessedEvents.ResponsePayload: JSON camelCase, nulos presentes, datas no
 * formato do System.Text.Json. A API Java le o que a .NET gravou e a .NET le o que a Java grava.
 */
public final class VendaPayload {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .addModule(new SimpleModule().addSerializer(new FormatoDataDotNet.Serializador()))
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE, false)
            .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .build();

    private VendaPayload() {}

    public static String serializar(VendaRegistroResultado resultado) {
        try {
            return MAPPER.writeValueAsString(resultado);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar o resultado da venda.", e);
        }
    }

    public static VendaRegistroResultado ler(String json) {
        try {
            VendaRegistroResultado lido = MAPPER.readValue(json, VendaRegistroResultado.class);
            if (lido == null) throw new IllegalStateException();
            return lido;
        } catch (JsonProcessingException | RuntimeException e) {
            throw new NegocioException(HttpStatus.BAD_REQUEST,
                    "Falha ao recuperar payload de resposta do evento processado.");
        }
    }
}
