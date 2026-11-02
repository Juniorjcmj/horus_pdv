package br.com.quack.pdv.shared;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Formata datas como o System.Text.Json do .NET: yyyy-MM-ddTHH:mm:ss[.fffffff]+00:00, com a fracao em ate
 * 7 casas (100 ns) e sem zeros finais. Centralizado aqui para o formato nao divergir entre as rotas.
 */
public final class FormatoDataDotNet {

    private static final DateTimeFormatter BASE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private FormatoDataDotNet() {}

    public static String formatar(OffsetDateTime data) {
        OffsetDateTime d = data.truncatedTo(ChronoUnit.NANOS);
        StringBuilder sb = new StringBuilder(BASE.format(d));
        int ticks = d.getNano() / 100; // 0..9_999_999
        if (ticks > 0) {
            String fracao = String.format("%07d", ticks);
            int fim = fracao.length();
            while (fim > 0 && fracao.charAt(fim - 1) == '0') fim--;
            sb.append('.').append(fracao, 0, fim);
        }
        ZoneOffset offset = d.getOffset();
        sb.append(offset.getTotalSeconds() == 0 ? "+00:00" : offset.getId());
        return sb.toString();
    }

    public static class Serializador extends StdSerializer<OffsetDateTime> {
        public Serializador() {
            super(OffsetDateTime.class);
        }

        @Override
        public void serialize(OffsetDateTime value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeString(formatar(value));
        }
    }
}
