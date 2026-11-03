package br.com.quack.caixa.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.LinkedHashMap;
import java.util.Map;

public class AuthApi {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(String id, String name, String email, String role, String companyId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LoginData(User user) {
    }

    private final ApiClient api;

    public AuthApi(ApiClient api) {
        this.api = api;
    }

    public User login(String email, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        body.put("rememberMe", true);
        body.put("recaptchaToken", "");
        LoginData d = api.send("POST", "/api/Auth/login", body, ApiClient.CASH_TIMEOUT, LoginData.class);
        if (d == null || d.user() == null) {
            throw new ApiException(500, "Resposta de login inválida.");
        }
        return d.user();
    }

    public void logout() {
        try {
            api.send("POST", "/api/Auth/logout", null, ApiClient.READ_TIMEOUT);
        } catch (ApiException ignored) {
            // sair localmente de qualquer forma
        } finally {
            api.clearAuth();
        }
    }
}
