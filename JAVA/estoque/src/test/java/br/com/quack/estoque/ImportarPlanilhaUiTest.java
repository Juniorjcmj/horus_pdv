package br.com.quack.estoque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.quack.estoque.api.ImportacaoApi.LinhaRelato;
import br.com.quack.estoque.domain.ImportacaoRelatorios;
import br.com.quack.estoque.ui.ImportarPlanilhaView;
import br.com.quack.estoque.ui.Tela;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tela "Importar planilha" contra um servidor HTTP falso: o botão Importar só habilita depois da simulação, a
 * confirmação traz gravadoEm, e os recusados saem em CSV com código, descrição e motivo.
 */
class ImportarPlanilhaUiTest {
    @TempDir
    static Path tmp;
    static HttpServer servidor;
    static final AtomicInteger simulacoes = new AtomicInteger();
    static final AtomicInteger confirmacoes = new AtomicInteger();

    private static String json(String gravadoEm) {
        return "{\"success\":true,\"message\":\"ok\",\"data\":{"
                + "\"totais\":{\"lidas\":3,\"novos\":2,\"atualizados\":0,\"recusados\":1,\"avisos\":0,\"categoriasNovas\":1,"
                + "\"produtosComSaldo\":1,\"quantidadeTotal\":5,\"valorEstoque\":10.00},"
                + "\"resumoFiscal\":[{\"cst\":\"060\",\"csosn\":\"500\",\"cfop\":\"5405\",\"quantidade\":2}],"
                + "\"recusados\":[{\"linha\":4,\"codigo\":\"X1\",\"descricao\":\"Item; com ponto e vírgula\",\"motivo\":\"NCM inválido\"}],"
                + "\"avisos\":[],\"hashArquivo\":\"h\",\"gravadoEm\":" + gravadoEm + "}}";
    }

    @BeforeAll
    static void subir() throws Exception {
        FxTestSupport.iniciar();
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", troca -> {
            String rota = troca.getRequestURI().getPath();
            troca.getRequestBody().readAllBytes();
            boolean confirma = rota.endsWith("/confirmar");
            (confirma ? confirmacoes : simulacoes).incrementAndGet();
            byte[] saida = json(confirma ? "\"2026-11-07T12:00:00Z\"" : "null").getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(200, saida.length);
            troca.getResponseBody().write(saida);
            troca.close();
        });
        servidor.start();
    }

    @AfterAll
    static void descer() {
        servidor.stop(0);
    }

    private static void esperar(BooleanSupplier condicao) throws Exception {
        long fim = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < fim) {
            if (FxTestSupport.naFx(() -> condicao.getAsBoolean())) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("condição não atendida a tempo");
    }

    @Test
    void importarSoHabilitaDepoisDaSimulacaoEConfirmaGravando() throws Exception {
        System.setProperty("quack.home", tmp.resolve("home").toString());
        Path csv = tmp.resolve("planilha.csv");
        Files.writeString(csv, "Código;Descrição Completa\r\n", StandardCharsets.ISO_8859_1);

        ImportarPlanilhaView view = FxTestSupport.naFx(() -> {
            AppContext ctx = new AppContext();
            ctx.api.setBaseUrl("http://127.0.0.1:" + servidor.getAddress().getPort());
            return new ImportarPlanilhaView(ctx, new Tela.Navegador() {
                @Override
                public void ir(String id) {
                }

                @Override
                public void novaEntradaNfe() {
                }

                @Override
                public void recarregarCatalogo() {
                }
            });
        });
        assertFalse(FxTestSupport.naFx(view::importarHabilitado), "antes de simular não pode importar");

        FxTestSupport.naFx(() -> {
            view.carregarArquivo(csv.toFile());
            return null;
        });
        esperar(view::importarHabilitado);

        assertEquals(1, simulacoes.get());
        assertEquals(0, confirmacoes.get(), "a simulação não confirma");
        assertNull(view.relatorioAtual().gravadoEm());
        assertEquals(2, view.relatorioAtual().totais().novos());

        FxTestSupport.naFx(() -> {
            view.gravar();
            return null;
        });
        esperar(() -> view.relatorioAtual().gravadoEm() != null);
        assertEquals(1, confirmacoes.get());
        assertNotNull(view.relatorioAtual().gravadoEm());
    }

    @Test
    void recusadosSaemEmCsvComCodigoDescricaoEMotivo() throws Exception {
        List<LinhaRelato> recusados = List.of(new LinhaRelato(4, "X1", "Item; com ponto e vírgula", "NCM inválido"));

        String csv = ImportacaoRelatorios.csv(recusados);

        assertTrue(csv.startsWith("Linha;Código;Descrição;Motivo"));
        assertTrue(csv.contains("4;X1;\"Item; com ponto e vírgula\";NCM inválido"));
        Path destino = tmp.resolve("recusados.csv");
        ImportacaoRelatorios.salvar(destino, recusados);
        assertTrue(Files.readString(destino, StandardCharsets.UTF_8).contains("NCM inválido"));
    }
}
