package br.com.quack.estoque.api;

import br.com.quack.estoque.persistence.JsonFiles;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.function.Consumer;

/** HttpClient com cookie de sessão, envelope {success,message,data} e detecção de rede. */
public class ApiClient {
    public static final String AUTH_COOKIE = "horuspdv.auth";
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration LIST_TIMEOUT = Duration.ofSeconds(20);
    public static final Duration CASH_TIMEOUT = Duration.ofSeconds(20);
    public static final Duration LONG_TIMEOUT = Duration.ofSeconds(90);
    public static final Duration SALE_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http;
    private volatile String baseUrl;
    private volatile boolean apiReachable = true;
    /** Token do cookie horuspdv.auth, reenviado como Bearer (cookie Secure não volta em http://). */
    private volatile String authToken;
    private volatile Runnable onUnauthorized = () -> { };
    private volatile Consumer<Boolean> onReachabilityChanged = b -> { };

    public ApiClient(String baseUrl) {
        this.baseUrl = normalize(baseUrl);
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .cookieHandler(cookies)
                .build();
    }

    private static String normalize(String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    public void setBaseUrl(String url) {
        this.baseUrl = normalize(url);
    }

    public String baseUrl() {
        return baseUrl;
    }

    public boolean apiReachable() {
        return apiReachable;
    }

    public void onUnauthorized(Runnable r) {
        this.onUnauthorized = r;
    }

    public void onReachabilityChanged(Consumer<Boolean> c) {
        this.onReachabilityChanged = c;
    }

    private void setReachable(boolean v) {
        if (apiReachable != v) {
            apiReachable = v;
            onReachabilityChanged.accept(v);
        }
    }

    /** Esquece o token (logout ou sessão expirada). */
    public void clearAuth() {
        authToken = null;
    }

    private void captureAuthToken(HttpResponse<?> resp) {
        String prefix = AUTH_COOKIE + "=";
        for (String header : resp.headers().allValues("set-cookie")) {
            if (!header.startsWith(prefix)) {
                continue;
            }
            int end = header.indexOf(';');
            String value = header.substring(prefix.length(), end < 0 ? header.length() : end).trim();
            authToken = value.isEmpty() ? null : value;
        }
    }

    /** Devolve o no "data" do envelope (ou null). */
    public JsonNode send(String method, String path, Object body, Duration timeout) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout)
                .header("Accept", "application/json");
        String token = authToken;
        if (token != null && !token.isBlank()) {
            b.header("Authorization", "Bearer " + token);
        }
        try {
            if (body == null) {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                b.header("Content-Type", "application/json");
                b.method(method, HttpRequest.BodyPublishers.ofString(JsonFiles.MAPPER.writeValueAsString(body)));
            }
        } catch (IOException e) {
            throw new ApiException(0, "Falha ao montar a requisição: " + e.getMessage());
        }
        return tratar(path, executar(b.build()));
    }

    /** Envia a requisição e traduz as falhas de rede em ApiException. */
    private HttpResponse<String> executar(HttpRequest request) {
        HttpResponse<String> resp;
        try {
            resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            setReachable(false);
            throw new ApiException(0, "A API não respondeu a tempo.");
        } catch (IOException e) {
            setReachable(false);
            throw new ApiException(0, "Sem conexão com a API (" + baseUrl + ").");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(0, "Operação interrompida.");
        } catch (IllegalArgumentException e) {
            throw new ApiException(0, "Endereço da API inválido: " + baseUrl);
        }
        return resp;
    }

    /** Lê o envelope da resposta: devolve "data" ou lança ApiException com a mensagem da API. */
    private JsonNode tratar(String path, HttpResponse<String> resp) {
        int status = resp.statusCode();
        captureAuthToken(resp);
        if (status < 500) {
            setReachable(true);
        } else {
            setReachable(status != 502 && status != 504);
        }
        JsonNode root = null;
        try {
            if (resp.body() != null && !resp.body().isBlank()) {
                root = JsonFiles.MAPPER.readTree(resp.body());
            }
        } catch (IOException ignored) {
            // corpo não-JSON
        }
        JsonNode data = root == null ? null : root.get("data");
        if (data != null && data.isNull()) {
            data = null;
        }
        boolean successFlag = root == null || !root.has("success") || root.get("success").asBoolean(true);
        if (status < 200 || status >= 300 || !successFlag) {
            if (status == 401 && !path.startsWith("/api/Auth/login")) {
                authToken = null;
                onUnauthorized.run();
            }
            String msg = root != null && root.hasNonNull("message") ? root.get("message").asText() : "";
            if (root != null && root.hasNonNull("details") && !root.get("details").asText().isBlank()) {
                msg = msg.isBlank() ? root.get("details").asText() : msg + " " + root.get("details").asText();
            }
            if (msg.isBlank()) {
                msg = "Erro HTTP " + status + ".";
            }
            throw new ApiException(status < 200 || status >= 300 ? status : 400, msg, data);
        }
        return data;
    }

    /**
     * Envia um arquivo em multipart/form-data (campo {@code campo}) e devolve o "data" do envelope. Usado pela importação
     * de produtos por planilha.
     */
    public JsonNode sendMultipart(String path, String campo, String nomeArquivo, byte[] conteudo, Duration timeout) {
        String limite = "----quack" + java.util.UUID.randomUUID().toString().replace("-", "");
        java.io.ByteArrayOutputStream corpo = new java.io.ByteArrayOutputStream(conteudo.length + 512);
        String crlf = "" + (char) 13 + (char) 10;
        String cabecalho = "--" + limite + crlf + "Content-Disposition: form-data; name=" + aspas(campo) + "; filename="
                + aspas(nomeArquivo) + crlf + "Content-Type: text/csv" + crlf + crlf;
        corpo.writeBytes(cabecalho.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        corpo.writeBytes(conteudo);
        corpo.writeBytes((crlf + "--" + limite + "--" + crlf).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout)
                .header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + limite);
        String token = authToken;
        if (token != null && !token.isBlank()) {
            b.header("Authorization", "Bearer " + token);
        }
        b.POST(HttpRequest.BodyPublishers.ofByteArray(corpo.toByteArray()));
        return tratar(path, executar(b.build()));
    }

    public <T> T send(String method, String path, Object body, Duration timeout, JavaType type) {
        JsonNode data = send(method, path, body, timeout);
        if (data == null) {
            return null;
        }
        try {
            return JsonFiles.MAPPER.readerFor(type).readValue(data);
        } catch (IOException e) {
            throw new ApiException(0, "Resposta inesperada da API: " + e.getMessage());
        }
    }

    public <T> T send(String method, String path, Object body, Duration timeout, Class<T> type) {
        return send(method, path, body, timeout, JsonFiles.MAPPER.getTypeFactory().constructType(type));
    }

    /** Baixa um arquivo (ex.: XML da nota de entrada). Erros viram ApiException com a mensagem da API. */
    public byte[] getBytes(String path, Duration timeout) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout);
        String token = authToken;
        if (token != null && !token.isBlank()) {
            b.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<byte[]> resp = http.send(b.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            captureAuthToken(resp);
            if (resp.statusCode() == 401) {
                authToken = null;
                onUnauthorized.run();
            }
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                String msg = "Erro HTTP " + resp.statusCode() + ".";
                try {
                    JsonNode root = JsonFiles.MAPPER.readTree(resp.body());
                    if (root != null && root.hasNonNull("message")) {
                        msg = root.get("message").asText();
                    }
                } catch (IOException ignored) {
                    // corpo não-JSON
                }
                throw new ApiException(resp.statusCode(), msg);
            }
            return resp.body();
        } catch (HttpTimeoutException e) {
            throw new ApiException(0, "A API não respondeu a tempo.");
        } catch (IOException e) {
            throw new ApiException(0, "Sem conexão com a API (" + baseUrl + ").");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(0, "Operação interrompida.");
        }
    }

    private static String aspas(String v) {
        String sem = v.replace(String.valueOf((char) 34), "");
        return (char) 34 + sem + (char) 34;
    }

    /** Codifica um valor de query string. */
    public static String q(String v) {
        return java.net.URLEncoder.encode(v == null ? "" : v, java.nio.charset.StandardCharsets.UTF_8);
    }
}
