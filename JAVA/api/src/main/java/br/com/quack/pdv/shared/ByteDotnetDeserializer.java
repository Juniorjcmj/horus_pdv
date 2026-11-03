package br.com.quack.pdv.shared;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
/** System.Byte has the inclusive range 0..255; reject overflow before calling the service. */
public class ByteDotnetDeserializer extends JsonDeserializer<Integer> {
    @Override public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) return context.reportInputMismatch(Integer.class, "Expected an unsigned byte.");
        var value=parser.getBigIntegerValue();
        if (value.signum() < 0 || value.compareTo(java.math.BigInteger.valueOf(255)) > 0) return context.reportInputMismatch(Integer.class, "Unsigned byte outside 0..255.");
        return value.intValue();
    }
    @Override public Integer getNullValue(DeserializationContext context) throws com.fasterxml.jackson.databind.JsonMappingException {
        return context.reportInputMismatch(Integer.class, "Expected an unsigned byte.");
    }
}
