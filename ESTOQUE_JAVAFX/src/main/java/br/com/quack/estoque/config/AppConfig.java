package br.com.quack.estoque.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

/** config.properties: apiBaseUrl, deviceId (gerado na 1a execução) e lastEmail. */
public final class AppConfig {
    /** API de produção (a mesma do caixa: FRONTEND/.env.production). Para testes locais: http://localhost:5260. */
    public static final String DEFAULT_API_URL = "https://api-pdv.quacksistemas.com.br";

    private final Path file;
    private final Properties props = new Properties();

    public AppConfig(AppPaths paths) {
        this.file = paths.configFile();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // usa padrões
            }
        }
        if (props.getProperty("deviceId") == null) {
            props.setProperty("deviceId", UUID.randomUUID().toString());
            save();
        }
    }

    public boolean hasApiUrl() {
        String v = props.getProperty("apiBaseUrl");
        return v != null && !v.isBlank();
    }

    public String apiBaseUrl() {
        return hasApiUrl() ? props.getProperty("apiBaseUrl") : DEFAULT_API_URL;
    }

    /** Site da versão web (apresentação, cadastro e recuperação de senha). Chave "webBaseUrl" no config. */
    public String webBaseUrl() {
        String v = props.getProperty("webBaseUrl", "").trim();
        return v.isEmpty() ? "https://pdv.quacksistemas.com.br" : v.replaceAll("/+$", "");
    }

    /** Tema "vidro" (painéis translúcidos). Desligado por padrão. */
    public boolean glassTheme() {
        return "true".equals(props.getProperty("glassTheme", "false"));
    }

    public void setGlassTheme(boolean on) {
        props.setProperty("glassTheme", String.valueOf(on));
        save();
    }

    /** "Manter sessão conectada": lembra o e-mail na próxima abertura. */
    public boolean rememberEmail() {
        return !"false".equals(props.getProperty("rememberEmail", "true"));
    }

    public void setRememberEmail(boolean remember) {
        props.setProperty("rememberEmail", String.valueOf(remember));
        save();
    }

    public String deviceId() {
        return props.getProperty("deviceId");
    }

    public String lastEmail() {
        return props.getProperty("lastEmail", "");
    }

    public void setApiBaseUrl(String url) {
        props.setProperty("apiBaseUrl", url.trim());
        save();
    }

    public void setLastEmail(String email) {
        props.setProperty("lastEmail", email == null ? "" : email.trim());
        save();
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8),
                        "Quack Estoque");
            }
        } catch (IOException ignored) {
            // configuração não persistida; segue com os valores em memória
        }
    }
}
