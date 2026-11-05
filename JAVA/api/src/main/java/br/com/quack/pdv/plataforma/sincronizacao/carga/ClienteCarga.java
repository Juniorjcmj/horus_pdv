package br.com.quack.pdv.plataforma.sincronizacao.carga;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Cliente HTTP da loja para registro, carga e ativacao (D5). So a loja abre conexoes (D9). */
@Component
@Profile("loja")
public class ClienteCarga {

    /** Falha da nuvem: status HTTP (0 = rede) e a mensagem dela. */
    public static class CargaException extends RuntimeException {
        private final int status;

        public CargaException(int status, String mensagem, Throwable causa) {
            super(mensagem, causa);
            this.status = status;
        }

        public int status() { return status; }
    }

    public record Registro(String serverId, String companyId, String token) {}

    public record Ativacao(long aplicadoAte, long detectorDesde) {}

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public Registro registrar(String urlBase, String email, String senha, String nomeServidor) {
        String corpo = escrever(Map.of("email", email, "password", senha, "serverName", nomeServidor));
        return ler(enviar(requisicao(urlBase, "/api/loja-sync/registrar", null).POST(HttpRequest.BodyPublishers.ofString(corpo))
                .header("Content-Type", "application/json")), Registro.class);
    }

    public CargaPagina pagina(String urlBase, String token, String tabela, List<String> depois, int limite) {
        return pagina(urlBase, token, tabela, depois, limite, false);
    }

    /** {@code renovar}: a recuperacao pede um retrato novo, em vez de reaproveitar uma carga aberta antiga. */
    public CargaPagina pagina(String urlBase, String token, String tabela, List<String> depois, int limite, boolean renovar) {
        StringBuilder q = new StringBuilder("/api/loja-sync/carga?tabela=").append(enc(tabela)).append("&limite=").append(limite);
        if (renovar) q.append("&renovar=true");
        if (depois != null) q.append("&depois=").append(enc(escrever(depois)));
        return ler(enviar(requisicao(urlBase, q.toString(), token).GET()), CargaPagina.class);
    }

    public CargaDelta delta(String urlBase, String token, long desde) {
        return ler(enviar(requisicao(urlBase, "/api/loja-sync/carga/delta?desde=" + desde, token).GET()), CargaDelta.class);
    }

    public Ativacao ativar(String urlBase, String token, long desde, long versaoLoja) {
        return ativar(urlBase, token, desde, versaoLoja, false);
    }

    /** {@code recuperacao}: a nuvem tambem dispensa os comandos pendentes que o retrato ja cobre. */
    public Ativacao ativar(String urlBase, String token, long desde, long versaoLoja, boolean recuperacao) {
        String corpo = escrever(Map.of("desde", desde, "versaoLoja", versaoLoja, "recuperacao", recuperacao));
        return ler(enviar(requisicao(urlBase, "/api/loja-sync/ativar", token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corpo))), Ativacao.class);
    }

    /** Ate que versao da loja a nuvem ja aplicou (situacao da nuvem); a recuperacao usa para saber o que a nuvem ja viu. */
    public long versaoConfirmada(String urlBase, String token) {
        com.fasterxml.jackson.databind.JsonNode d = ler(enviar(requisicao(urlBase, "/api/loja-sync/situacao", token).GET()),
                com.fasterxml.jackson.databind.JsonNode.class);
        return d.path("versaoConfirmada").asLong(0);
    }

    // ------------------------------------------------------------------ apoio

    private static HttpRequest.Builder requisicao(String urlBase, String caminho, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(urlBase.replaceAll("/+$", "") + caminho))
                .timeout(Duration.ofMinutes(5));
        if (token != null) b.header("Authorization", "LojaServidor " + token);
        return b;
    }

    private volatile boolean semInternet;

    /** Interruptor de "sem internet" dos testes (como o do ClienteNuvemHttp). */
    public void setSemInternet(boolean semInternet) { this.semInternet = semInternet; }

    private HttpResponse<String> enviar(HttpRequest.Builder b) {
        if (semInternet) throw new CargaException(0, "Sem comunicacao com a nuvem (simulado).", null);
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CargaException(0, "Carga interrompida.", e);
        } catch (IOException e) {
            throw new CargaException(0, "Sem comunicacao com a nuvem: " + e.getMessage(), e);
        }
    }

    private static <T> T ler(HttpResponse<String> r, Class<T> tipo) {
        JsonNode raiz = null;
        try {
            raiz = JSON.readTree(r.body() == null ? "" : r.body());
        } catch (IOException ignorada) {
            // corpo que nao e JSON (proxy): so o status vale
        }
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            String msg = raiz == null ? null : raiz.path("message").asText(null);
            throw new CargaException(r.statusCode(), "Nuvem respondeu " + r.statusCode() + (msg == null ? "" : ": " + msg), null);
        }
        try {
            return JSON.treeToValue(raiz.path("data"), tipo);
        } catch (JsonProcessingException | NullPointerException e) {
            throw new CargaException(r.statusCode(), "Resposta da nuvem ilegivel.", e);
        }
    }

    private static String escrever(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
