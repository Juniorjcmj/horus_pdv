package br.com.quack.estoque.analise;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.IntNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnaliseFmtTest {
    @Test
    void valoresDosCartoesEmPtBr() {
        assertEquals("R$ 140.655,74", AnaliseFmt.valor("R$", DoubleNode.valueOf(140655.74)));
        assertEquals("6,12x", AnaliseFmt.valor("x", DoubleNode.valueOf(6.120278)));
        assertEquals("8,5%", AnaliseFmt.valor("%", DoubleNode.valueOf(8.5)));
        assertEquals("176 dias", AnaliseFmt.valor("dias", DoubleNode.valueOf(176.48)));
        assertEquals("1 dia", AnaliseFmt.valor("dias", DoubleNode.valueOf(1.2)));
        assertEquals("183", AnaliseFmt.valor("itens", IntNode.valueOf(183)));
        assertEquals("—", AnaliseFmt.valor("R$", NullNode.getInstance()), "sem valor (sem giro/sem histórico) não vira zero");
    }

    @Test
    void variacaoComSetaEPeriodoAnterior() {
        assertEquals("▲ 12,5% vs período anterior", AnaliseFmt.variacao(DoubleNode.valueOf(12.5)));
        assertEquals("▼ 2,6% vs período anterior", AnaliseFmt.variacao(DoubleNode.valueOf(-2.57)));
        assertEquals("● 0,0% vs período anterior", AnaliseFmt.variacao(DoubleNode.valueOf(0)));
        assertEquals("", AnaliseFmt.variacao(NullNode.getInstance()), "anterior zero/ausente não mostra variação");
    }

    @Test
    void tooltipsEmPortugues() {
        assertEquals("05/10/2026\nValor em estoque: R$ 1.234,50", AnaliseFmt.tooltipSerie("2026-10-05", "Valor em estoque", "R$", 1234.5));
        assertEquals("05/10/2026\nEm ruptura: 8,5%", AnaliseFmt.tooltipSerie("2026-10-05", "Em ruptura", "%", 8.5));
        assertEquals("05/10/2026\nItens em estoque: 7.266", AnaliseFmt.tooltipSerie("2026-10-05T00:00:00", "Itens em estoque", "un", 7266));
        assertEquals("Frios\nCobertura: 324 dias", AnaliseFmt.tooltipItem("Frios", "Cobertura", "dias", 323.7));
        assertEquals("Classe A: 69 produtos · R$ 41.331,89 (29,4%)",
                AnaliseFmt.tooltipFatia("A", 69, DoubleNode.valueOf(41331.89), DoubleNode.valueOf(29.4)));
        assertEquals("Classe C: 1 produto", AnaliseFmt.tooltipFatia("C", 1, NullNode.getInstance(), NullNode.getInstance()),
                "estoquista não recebe valores");
        assertEquals("05/10", AnaliseFmt.diaMes("2026-10-05"));
        assertEquals("Racao Cao…", AnaliseFmt.curto("Racao Cao 4 Frango", 10));
    }

    @Test
    void eixoCurto() {
        assertEquals("R$ 12,5 mil", Graficos.eixo(12500, "R$"));
        assertEquals("R$ 140", Graficos.eixo(140, "R$"));
        assertEquals("2,5 mi", Graficos.eixo(2_500_000, "un"));
        assertEquals("8%", Graficos.eixo(8, "%"));
    }

    @Test
    void atalhosDePeriodo() {
        LocalDate hoje = LocalDate.of(2026, 10, 31);
        FiltrosAnalise f = new FiltrosAnalise();
        f.aplicarAtalho("Últimos 30 dias", hoje);
        assertEquals(LocalDate.of(2026, 10, 2), f.de);
        assertEquals(hoje, f.ate);
        assertEquals(30, f.dias());
        f.aplicarAtalho("Mês anterior", hoje);
        assertEquals(LocalDate.of(2026, 9, 1), f.de);
        assertEquals(LocalDate.of(2026, 9, 30), f.ate);
        f.aplicarAtalho("Mês atual", LocalDate.of(2026, 3, 15));
        assertEquals(LocalDate.of(2026, 3, 1), f.de);
        f.aplicarAtalho("Últimos 12 meses", hoje);
        assertEquals(365, f.dias());
    }

    @Test
    void filtrosViramQuery() {
        FiltrosAnalise f = new FiltrosAnalise();
        f.de = LocalDate.of(2026, 10, 1);
        f.ate = LocalDate.of(2026, 10, 31);
        assertEquals("?de=2026-10-01&ate=2026-10-31", f.query());
        f.categoriaId = "cat 1";
        f.fornecedorId = "f-2";
        f.comparar = true;
        assertEquals("?de=2026-10-01&ate=2026-10-31&categoriaId=cat+1&fornecedorId=f-2&comparar=true", f.query());
    }

    @Test
    void filtrosFicamNaMemoriaDoTerminal(@TempDir Path pasta) {
        Path arquivo = pasta.resolve("sub").resolve("analise.json");
        LocalDate hoje = LocalDate.of(2026, 10, 31);
        FiltrosAnalise f = new FiltrosAnalise();
        f.aplicarAtalho("Últimos 90 dias", hoje);
        f.categoriaId = "c1";
        f.comparar = true;
        f.salvar(arquivo);
        assertTrue(Files.isRegularFile(arquivo));

        // no dia seguinte, "Últimos 90 dias" continua sendo os 90 dias contados de hoje
        FiltrosAnalise lido = FiltrosAnalise.carregar(arquivo, hoje.plusDays(1));
        assertEquals("Últimos 90 dias", lido.atalho);
        assertEquals(hoje.plusDays(1), lido.ate);
        assertEquals(90, lido.dias());
        assertEquals("c1", lido.categoriaId);
        assertNull(lido.fornecedorId);
        assertTrue(lido.comparar);

        // período personalizado guarda as datas
        FiltrosAnalise p = new FiltrosAnalise();
        p.atalho = FiltrosAnalise.PERSONALIZADO;
        p.de = LocalDate.of(2026, 1, 5);
        p.ate = LocalDate.of(2026, 2, 10);
        p.salvar(arquivo);
        FiltrosAnalise lidoP = FiltrosAnalise.carregar(arquivo, hoje);
        assertEquals(LocalDate.of(2026, 1, 5), lidoP.de);
        assertEquals(LocalDate.of(2026, 2, 10), lidoP.ate);
    }

    @Test
    void arquivoAusenteOuCorrompidoVoltaAosPadroes(@TempDir Path pasta) throws Exception {
        LocalDate hoje = LocalDate.of(2026, 10, 31);
        FiltrosAnalise ausente = FiltrosAnalise.carregar(pasta.resolve("nao-existe.json"), hoje);
        assertEquals("Últimos 30 dias", ausente.atalho);
        assertEquals(hoje, ausente.ate);

        Path ruim = pasta.resolve("analise.json");
        Files.writeString(ruim, "{ isto não é json");
        FiltrosAnalise corrompido = FiltrosAnalise.carregar(ruim, hoje);
        assertEquals("Últimos 30 dias", corrompido.atalho);
        assertEquals(30, corrompido.dias());
        assertFalse(corrompido.comparar);
    }
}
