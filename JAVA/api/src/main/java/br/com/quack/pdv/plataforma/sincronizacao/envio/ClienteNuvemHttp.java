package br.com.quack.pdv.plataforma.sincronizacao.envio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Cliente HTTP da loja. {@link #setSemInternet(boolean)} e o interruptor de "sem internet" dos testes (D13). */
@Component
@Profile("loja")
public class ClienteNuvemHttp implements ClienteNuvem {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile boolean semInternet;
    private final java.util.concurrent.atomic.AtomicInteger respostasAPerder = new java.util.concurrent.atomic.AtomicInteger();

    public void setSemInternet(boolean semInternet) { this.semInternet = semInternet; }

    public boolean isSemInternet() { return semInternet; }

    /** Gancho de teste: a nuvem recebe e aplica, mas a resposta das proximas {@code n} partes "se perde". */
    public void perderProximasRespostas(int n) { respostasAPerder.set(n); }

    @Override
    public Resposta enviarParte(String urlBase, String token, byte[] gzip) throws IOException {
        if (semInternet) throw new ConnectException("Sem internet (interruptor).");
        HttpRequest req = HttpRequest.newBuilder(URI.create(urlBase.replaceAll("/+$", "") + "/api/loja-sync/lotes"))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "LojaServidor " + token)
                .header("Content-Type", "application/json")
                .header("Content-Encoding", "gzip")
                .POST(HttpRequest.BodyPublishers.ofByteArray(gzip))
                .build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() < 300 && respostasAPerder.getAndUpdate(v -> Math.max(0, v - 1)) > 0) {
                throw new java.net.SocketTimeoutException("Resposta perdida (teste).");
            }
            return interpretar(r.statusCode(), r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Envio interrompido.", e);
        }
    }

    static Resposta interpretar(int status, String corpo) {
        Long ate = null;
        boolean aplicado = false;
        String msg = null;
        try {
            JsonNode raiz = JSON.readTree(corpo == null ? "" : corpo);
            if (raiz != null) {
                msg = raiz.path("message").asText(null);
                JsonNode d = raiz.path("data");
                if (d.has("aplicadoAte")) ate = d.get("aplicadoAte").asLong();
                aplicado = d.path("aplicado").asBoolean(false);
            }
        } catch (IOException e) {
            // corpo que nao e JSON (ex.: erro do proxy): so o status vale
        }
        return new Resposta(status, ate, aplicado, msg);
    }
}
