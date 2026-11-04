package br.com.quack.pdv.plataforma.sincronizacao.envio;

import br.com.quack.pdv.config.QuackProperties;
import br.com.quack.pdv.security.SecretProtector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Chamadas JSON da loja a nuvem com o token do servidor (conferencia e situacao). Respeita o interruptor "sem internet"
 * dos testes ({@link ClienteNuvemHttp#setSemInternet(boolean)}). Devolve o campo {@code data} da resposta.
 */
@Component
@Profile("loja")
public class ClienteJsonNuvem {

    private static final ObjectMapper JSON = new ObjectMapper();

    private record Destino(String url, String token) {}

    private final JdbcTemplate jdbc;
    private final SecretProtector protetor;
    private final QuackProperties props;
    private final ClienteNuvemHttp internet;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ClienteJsonNuvem(JdbcTemplate jdbc, SecretProtector protetor, QuackProperties props, ClienteNuvemHttp internet) {
        this.jdbc = jdbc;
        this.protetor = protetor;
        this.props = props;
        this.internet = internet;
    }

    public JsonNode get(String caminho, int timeoutSegundos) throws IOException {
        return enviar(caminho, null, timeoutSegundos);
    }

    public JsonNode post(String caminho, Object corpo, int timeoutSegundos) throws IOException {
        return enviar(caminho, corpo == null ? JSON.createObjectNode() : corpo, timeoutSegundos);
    }

    private JsonNode enviar(String caminho, Object corpo, int timeoutSegundos) throws IOException {
        if (internet.isSemInternet()) throw new ConnectException("Sem internet (interruptor).");
        Destino d = destino();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(d.url().replaceAll("/+$", "") + caminho))
                .timeout(Duration.ofSeconds(timeoutSegundos))
                .header("Authorization", "LojaServidor " + d.token());
        HttpRequest req = corpo == null ? b.GET().build()
                : b.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(corpo))).build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() < 200 || r.statusCode() >= 300) {
                throw new IOException("Nuvem respondeu " + r.statusCode() + " em " + caminho);
            }
            return JSON.readTree(r.body()).path("data");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrompido.", e);
        }
    }

    private Destino destino() throws IOException {
        List<String[]> l = jdbc.query("SELECT NuvemUrl, TokenCifrado FROM dbo.SyncEstado WHERE Id = 1",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)});
        String url = l.isEmpty() || l.getFirst()[0] == null || l.getFirst()[0].isBlank()
                ? props.getSync().getNuvemUrl() : l.getFirst()[0];
        if (l.isEmpty() || l.getFirst()[1] == null || l.getFirst()[1].isBlank() || url.isBlank()) {
            throw new IOException("Servidor da loja ainda nao registrado na nuvem.");
        }
        return new Destino(url, protetor.unprotect(l.getFirst()[1]));
    }
}
