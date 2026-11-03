package br.com.quack.pdv.security;

import br.com.quack.pdv.config.QuackProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** JWT HS256 escrito a mao, bit a bit como o HorusJwtService da API .NET. */
@Component
public class JwtService {

    public static final String AUTH_COOKIE_NAME = "horuspdv.auth";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final QuackProperties props;
    private final Clock clock;

    public JwtService(QuackProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    public int sessionHours() {
        return props.getAuth().getSessionHours();
    }

    public String criarToken(String userId, String companyId, String name, String email, String role,
                             String sessionId, String ip) {
        long now = clock.instant().getEpochSecond();
        long exp = now + sessionHours() * 3600L;
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", props.getAuth().getIssuer());
        payload.put("aud", props.getAuth().getAudience());
        payload.put("sub", userId);
        payload.put("id", userId);
        payload.put("companyId", companyId);
        payload.put("name", name);
        payload.put("email", email);
        payload.put("role", role);
        payload.put("tokenType", "Bearer");
        payload.put("sessionId", sessionId);
        payload.put("sessionTtl", sessionHours() + "h");
        payload.put("ip", ip);
        payload.put("iat", now);
        payload.put("exp", exp);
        try {
            String h = base64Url(JSON.writeValueAsBytes(header));
            String p = base64Url(JSON.writeValueAsBytes(payload));
            return h + "." + p + "." + assinar(h + "." + p);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao emitir token.", e);
        }
    }

    /** Devolve o usuario do token, ou null se a assinatura, a validade, o emissor ou o publico falharem. */
    public UsuarioAutenticado validarToken(String token) {
        if (token == null) return null;
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) return null;
        try {
            String assinatura = assinar(parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(assinatura.getBytes(StandardCharsets.UTF_8), parts[2].getBytes(StandardCharsets.UTF_8))) {
                return null;
            }
            JsonNode root = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            long exp = root.path("exp").asLong(0);
            if (exp <= clock.instant().getEpochSecond()) return null;
            if (!props.getAuth().getIssuer().equals(texto(root, "iss", ""))
                    || !props.getAuth().getAudience().equals(texto(root, "aud", ""))) {
                return null;
            }
            String userId = texto(root, "sub", "");
            String sessionId = texto(root, "sessionId", "");
            if (userId.isBlank() || sessionId.isBlank()) return null;
            return new UsuarioAutenticado(userId, texto(root, "companyId", "empresa-principal"), sessionId,
                    texto(root, "name", ""), texto(root, "email", ""), texto(root, "role", ""), List.of());
        } catch (Exception e) {
            return null;
        }
    }

    private static String texto(JsonNode root, String campo, String padrao) {
        JsonNode n = root.get(campo);
        return n == null || n.isNull() ? padrao : n.asText(padrao);
    }

    private String assinar(String valor) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(props.getAuth().getJwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return base64Url(mac.doFinal(valor.getBytes(StandardCharsets.UTF_8)));
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
