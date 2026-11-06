package br.com.quack.pdv.plataforma.sincronizacao.comandos;

import br.com.quack.pdv.plataforma.sincronizacao.envio.ClienteNuvemHttp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Cliente HTTP da loja para os comandos da nuvem (D6): a loja busca (long-poll) e devolve o resultado. */
@Component
@Profile("loja")
public class ClienteComandos {

    /** Comando recebido da nuvem. O payload ja traz os segredos em claro (TLS no caminho, D9). */
    public record Comando(long seq, String tipo, String tabela, String chave, JsonNode payload, int tentativas) {}

    /** Resultado devolvido a nuvem: aplicado, conflito ou falhou. */
    public record Resultado(long seq, String resultado, JsonNode detalhe) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ClienteNuvemHttp internet;

    public ClienteComandos(ClienteNuvemHttp internet) {
        this.internet = internet;
    }

    public List<Comando> buscar(String urlBase, String token, long apos) throws IOException {
        HttpResponse<String> r = enviar(requisicao(urlBase, "/api/loja-sync/comandos?apos=" + apos, token, 45).GET().build());
        List<Comando> lista = new ArrayList<>();
        for (JsonNode c : JSON.readTree(r.body()).path("data").path("comandos")) {
            lista.add(new Comando(c.path("seq").asLong(), c.path("tipo").asText(), c.path("tabela").asText(),
                    c.path("chave").asText(), c.path("payload"), c.path("tentativas").asInt()));
        }
        return lista;
    }

    public void enviarResultados(String urlBase, String token, List<Resultado> resultados) throws IOException {
        ObjectNode corpo = JSON.createObjectNode();
        ArrayNode arr = corpo.putArray("resultados");
        for (Resultado r : resultados) {
            ObjectNode o = arr.addObject().put("seq", r.seq()).put("resultado", r.resultado());
            o.set("detalhe", r.detalhe());
        }
        enviar(requisicao(urlBase, "/api/loja-sync/comandos/resultados", token, 60).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corpo.toString())).build());
    }

    private static HttpRequest.Builder requisicao(String urlBase, String caminho, String token, int timeoutSegundos) {
        return HttpRequest.newBuilder(URI.create(urlBase.replaceAll("/+$", "") + caminho))
                .timeout(Duration.ofSeconds(timeoutSegundos))
                .header("Authorization", "LojaServidor " + token);
    }

    private HttpResponse<String> enviar(HttpRequest req) throws IOException {
        if (internet.isSemInternet()) throw new ConnectException("Sem internet (interruptor).");
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            // O long-poll aberto antes de o interruptor ser ligado cairia junto com a internet de verdade.
            if (internet.isSemInternet()) throw new ConnectException("Sem internet (interruptor).");
            if (r.statusCode() < 200 || r.statusCode() >= 300) {
                throw new IOException("Nuvem respondeu " + r.statusCode() + " em " + req.uri().getPath());
            }
            return r;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrompido.", e);
        }
    }
}
