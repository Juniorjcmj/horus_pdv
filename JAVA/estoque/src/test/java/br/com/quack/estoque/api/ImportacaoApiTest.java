package br.com.quack.estoque.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Cliente da importação contra um servidor HTTP falso: o campo "arquivo" vai em multipart e o relatório é lido. */
class ImportacaoApiTest {
    private HttpServer servidor;
    private final AtomicReference<String> rota = new AtomicReference<>();
    private final AtomicReference<String> tipo = new AtomicReference<>();
    private final AtomicReference<String> corpo = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String resposta;
    private ImportacaoApi api;

    @BeforeEach
    void subir() throws IOException {
        resposta = """
                {"success":true,"message":"ok","data":{
                  "totais":{"lidas":3,"novos":2,"atualizados":0,"recusados":1,"avisos":1,"categoriasNovas":4,
                            "produtosComSaldo":2,"quantidadeTotal":12.5,"valorEstoque":30.00},
                  "resumoFiscal":[{"cst":"060","csosn":"500","cfop":"5405","quantidade":2}],
                  "recusados":[{"linha":4,"codigo":"X","descricao":"Sem NCM","motivo":"NCM inválido"}],
                  "avisos":[{"linha":2,"codigo":"A","descricao":"Item","motivo":"sem preço de venda"}],
                  "hashArquivo":"abc","gravadoEm":null}}
                """;
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", troca -> {
            rota.set(troca.getRequestURI().getPath());
            tipo.set(troca.getRequestHeaders().getFirst("Content-Type"));
            corpo.set(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] saida = resposta.getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(status, saida.length);
            troca.getResponseBody().write(saida);
            troca.close();
        });
        servidor.start();
        api = new ImportacaoApi(new ApiClient("http://127.0.0.1:" + servidor.getAddress().getPort()));
    }

    @AfterEach
    void descer() {
        servidor.stop(0);
    }

    @Test
    void simularEnviaOCampoArquivoEmMultipartELeORelatorio() {
        ImportacaoApi.Relatorio r = api.simular("planilha.csv", "Código;X".getBytes(StandardCharsets.UTF_8));

        assertEquals("/api/Produto/importacao/simular", rota.get());
        assertTrue(tipo.get().startsWith("multipart/form-data; boundary="));
        assertTrue(corpo.get().contains("name=\"arquivo\"; filename=\"planilha.csv\""));
        assertTrue(corpo.get().contains("Código;X"));
        assertEquals(2, r.totais().novos());
        assertEquals(4, r.totais().categoriasNovas());
        assertEquals(0, new java.math.BigDecimal("12.5").compareTo(r.totais().quantidadeTotal()));
        assertEquals("5405", r.resumoFiscal().getFirst().cfop());
        assertEquals("NCM inválido", r.recusados().getFirst().motivo());
        assertEquals("sem preço de venda", r.avisos().getFirst().motivo());
        assertEquals(null, r.gravadoEm());
    }

    @Test
    void confirmarUsaARotaDeConfirmacao() {
        api.confirmar("p.csv", new byte[] {65});

        assertEquals("/api/Produto/importacao/confirmar", rota.get());
    }

    @Test
    void erroDaApiViraApiExceptionComAMensagem() {
        status = 400;
        resposta = "{\"success\":false,\"message\":\"Colunas obrigatórias ausentes no cabeçalho: Código NCM.\"}";

        ApiException e = assertThrows(ApiException.class, () -> api.simular("p.csv", new byte[] {65}));

        assertTrue(e.getMessage().contains("Código NCM"));
    }
}
