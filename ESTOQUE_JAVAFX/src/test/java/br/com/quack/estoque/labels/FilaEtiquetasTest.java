package br.com.quack.estoque.labels;

import static br.com.quack.estoque.TestData.produto;
import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.domain.Catalogo;
import br.com.quack.estoque.domain.DomainException;
import br.com.quack.estoque.domain.Produto;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilaEtiquetasTest {
    @TempDir
    Path dir;

    @Test
    void filaESomaCopiasERestauraDepoisDeFechar() {
        Path f = dir.resolve("etiquetas.json");
        FilaEtiquetas fila = new FilaEtiquetas(f);
        fila.adicionar("p1", 1);
        fila.adicionar("p2", 5);
        fila.adicionar("p1", 2);
        assertEquals(8, fila.totalEtiquetas());
        fila.definirCopias("p2", 3);
        fila.remover("p1");
        fila.definirModelo(LabelModel.R40X25);
        fila.definirImpressora("Elgin L42");
        fila.definirCalibracao(-2, 1.5);

        FilaEtiquetas reaberta = new FilaEtiquetas(f); // "fechou e abriu o aplicativo"
        assertEquals(1, reaberta.itens().size());
        assertEquals("p2", reaberta.itens().get(0).produtoId);
        assertEquals(3, reaberta.itens().get(0).copias);
        assertEquals(LabelModel.R40X25.id(), reaberta.modelo().id());
        assertEquals("Elgin L42", reaberta.impressora());
        assertEquals(-2, reaberta.dxMm(), 1e-9);
        assertEquals(1.5, reaberta.dyMm(), 1e-9);
    }

    @Test
    void personalizadoEhRestaurado() {
        Path f = dir.resolve("etiquetas.json");
        FilaEtiquetas fila = new FilaEtiquetas(f);
        fila.definirModelo(LabelModel.personalizado(45, 30, 2, 5, 2, 1, 4, 6));
        LabelModel m = new FilaEtiquetas(f).modelo();
        assertEquals("personalizado", m.id());
        assertEquals(45, m.larguraMm());
        assertEquals(2, m.colunas());
        assertEquals(10, m.porFolha());
        assertTrue(m.folha());
        assertThrows(IllegalArgumentException.class, () -> LabelModel.personalizado(5, 5, 1, 1, 0, 0, 0, 0));
    }

    @Test
    void precosAlteradosDesdeAUltimaImpressao() {
        FilaEtiquetas fila = new FilaEtiquetas(dir.resolve("etiquetas.json"));
        Catalogo cat = new Catalogo();
        cat.carregar(List.of(produto("p1", "1", "A", "", "10,00", "UN"), produto("p2", "2", "B", "", "5,00", "UN"),
                produto("p3", "3", "C", "", "7,00", "UN")));
        fila.registrarImpressao("p1", new BigDecimal("10.00"));
        fila.registrarImpressao("p2", new BigDecimal("4.50")); // subiu de 4,50 para 5,00
        assertEquals(List.of("p2"), fila.precosAlterados(cat).stream().map(Produto::id).toList(),
                "p3 nunca teve etiqueta e p1 não mudou");
        assertEquals(1, fila.adicionarPrecosAlterados(cat));
        assertEquals("p2", fila.itens().get(0).produtoId);
        fila.registrarImpressao("p2", new BigDecimal("5.00"));
        assertTrue(fila.precosAlterados(cat).isEmpty());
        // o último preço também sobrevive ao reabrir
        assertEquals(0, new BigDecimal("5.00").compareTo(new FilaEtiquetas(dir.resolve("etiquetas.json")).ultimoPreco("p2")));
    }

    @Test
    void copiasInvalidasERecusadas() {
        FilaEtiquetas fila = new FilaEtiquetas(null);
        assertThrows(DomainException.class, () -> fila.adicionar("p1", 0));
        fila.adicionar("p1", 1);
        assertThrows(DomainException.class, () -> fila.definirCopias("p1", 0));
    }

    @Test
    void arquivoCorrompidoComecaVazio() throws Exception {
        Path f = dir.resolve("etiquetas.json");
        Files.writeString(f, "{ isto não é json");
        assertTrue(new FilaEtiquetas(f).itens().isEmpty());
    }
}
