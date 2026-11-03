package br.com.quack.pdv.auth.app;

import br.com.quack.pdv.config.QuackProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/** Verificacao reCAPTCHA igual ao HorusRecaptchaService da API .NET (mesmos status, mensagens e details). */
@Service
public class RecaptchaService {

    public static final String URL = "https://www.google.com/recaptcha/api/siteverify";

    public record Resultado(boolean success, int statusCode, String message, String details, boolean skipped) {
        static Resultado ok(boolean skipped) { return new Resultado(true, 200, "", "", skipped); }

        static Resultado falha(int status, String message) { return new Resultado(false, status, message, "", false); }

        static Resultado falha(int status, String message, String details) {
            return new Resultado(false, status, message, details, false);
        }
    }

    private final QuackProperties props;
    private final RestClient restClient;
    private final ObjectMapper mapper;

    public RecaptchaService(QuackProperties props, RestClient.Builder builder, ObjectMapper mapper) {
        this.props = props;
        this.restClient = builder.build();
        this.mapper = mapper;
    }

    public Resultado verificar(String token, String acao, String ipRemoto) {
        QuackProperties.Recaptcha cfg = props.getRecaptcha();
        if (!cfg.isEnabled()) return Resultado.ok(true);

        if (cfg.getSecretKey().isBlank()) {
            return Resultado.falha(500, "Validação de segurança indisponível. Configure Recaptcha:SecretKey na API.");
        }
        if (token == null || token.isBlank()) {
            return Resultado.falha(400, "Validação de segurança obrigatória. Atualize e tente novamente.");
        }

        MultiValueMap<String, String> campos = new LinkedMultiValueMap<>();
        campos.add("secret", cfg.getSecretKey());
        campos.add("response", token.trim());
        if (ipRemoto != null && !ipRemoto.isBlank()) {
            campos.add("remoteip", ipRemoto.split(",", -1)[0].trim());
        }

        String corpo;
        try {
            corpo = restClient.post().uri(URL).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(campos)
                    .retrieve().body(String.class);
        } catch (RuntimeException e) {
            return Resultado.falha(503, "Não foi possível validar o reCAPTCHA no momento.");
        }

        JsonNode raiz;
        try {
            raiz = mapper.readTree(corpo == null ? "{}" : corpo);
        } catch (Exception e) {
            return Resultado.falha(503, "Não foi possível validar o reCAPTCHA no momento.");
        }

        if (!(raiz.path("success").isBoolean() && raiz.path("success").asBoolean())) {
            Set<String> codigos = StreamSupport.stream(raiz.path("error-codes").spliterator(), false)
                    .map(n -> n.asText("").trim().toLowerCase(Locale.ROOT))
                    .filter(s -> !s.isBlank()).collect(Collectors.toSet());
            boolean repetivel = codigos.contains("timeout-or-duplicate") || codigos.contains("invalid-input-response");
            return Resultado.falha(repetivel ? 409 : 400,
                    repetivel ? "Validação de segurança expirou. Tente novamente."
                            : "Falha na validação de segurança. Tente novamente.",
                    repetivel ? "RECAPTCHA_TOKEN_EXPIRED" : "RECAPTCHA_VALIDATION_FAILED");
        }

        String acaoRecebida = raiz.path("action").isTextual() ? raiz.path("action").asText() : "";
        if (acao != null && !acao.isBlank() && !acaoRecebida.isBlank() && !acaoRecebida.equals(acao)) {
            return Resultado.falha(400, "Validação de segurança inválida para esta operação.", "RECAPTCHA_ACTION_MISMATCH");
        }

        if (raiz.path("score").isNumber() && raiz.path("score").asDouble() < cfg.getMinScore()) {
            return Resultado.falha(400, "Validação de segurança reprovada. Tente novamente.", "RECAPTCHA_SCORE_REJECTED");
        }
        return Resultado.ok(false);
    }
}
