package br.com.quack.estoque.domain;

import static br.com.quack.estoque.TestData.ean;
import static br.com.quack.estoque.TestData.produto;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CatalogoBuscaTest {
    Catalogo cat = new Catalogo();
    String gtinCafe = ean("789100010010");

    @BeforeEach
    void carregar() {
        cat.carregar(List.of(
                produto("p1", "1001", "Café Pilão 500g", gtinCafe, "18,90", "UN"),
                produto("p2", "0015", "Queijo Minas Frescal", "", "45,00", "KG"),
                produto("p3", "2691", "Presunto Cozido", "", "13,70", "KG"),
                produto("p4", "3000", "Açúcar Cristal 1kg", ean("789200020020"), "4,50", "UN")));
    }

    @Test
    void codigoInternoEGtin() {
        assertEquals("p1", cat.ler("1001").produto().id());
        assertEquals("p1", cat.ler(gtinCafe).produto().id());
        assertEquals(0, BigDecimal.ONE.compareTo(cat.ler(gtinCafe).quantidade()));
        assertFalse(cat.ler("1001").balanca());
        assertNull(cat.ler("999999"));
        assertNull(cat.ler(""));
    }

    @Test
    void etiquetaDeBalanca() {
        // 2 + 2691 + 0000685 (R$ 6,85) com preco 13,70 -> 0,500 kg
        Catalogo.Leitura l = cat.ler(ean("226910000685"));
        assertNotNull(l);
        assertTrue(l.balanca());
        assertEquals("p3", l.produto().id());
        assertEquals(0, new BigDecimal("0.500").compareTo(l.quantidade()));
    }

    @Test
    void nomeParcialSemAcentoENaoSensivelACaixa() {
        assertEquals("p1", cat.buscar("cafe", 10).get(0).id());
        assertEquals("p1", cat.buscar("PILAO 500", 10).get(0).id());
        assertEquals("p4", cat.buscar("acucar", 10).get(0).id());
        assertEquals(1, cat.buscar("queijo frescal", 10).size());
        assertTrue(cat.buscar("inexistente", 10).isEmpty());
    }

    @Test
    void buscaPorCodigoEGtinTrazOExatoPrimeiro() {
        assertEquals("p2", cat.buscar("0015", 10).get(0).id());
        assertEquals("p1", cat.buscar(gtinCafe, 10).get(0).id());
        assertEquals(4, cat.buscar("", 10).size());
        assertEquals(2, cat.buscar("", 2).size());
    }

    @Test
    void nivelDoEstoque() {
        assertEquals(Produto.Nivel.SEM_ESTOQUE, produto("a", "a", "a", "", "1,00", "UN", "0", "1,00", "5", "50").nivel());
        assertEquals(Produto.Nivel.BAIXO, produto("a", "a", "a", "", "1,00", "UN", "3", "1,00", "5", "50").nivel());
        assertEquals(Produto.Nivel.NORMAL, produto("a", "a", "a", "", "1,00", "UN", "20", "1,00", "5", "50").nivel());
        assertEquals(Produto.Nivel.ACIMA_DO_MAXIMO, produto("a", "a", "a", "", "1,00", "UN", "80", "1,00", "5", "50").nivel());
        assertEquals(Produto.Nivel.NORMAL, produto("a", "a", "a", "", "1,00", "UN", "80", "1,00", "0", "0").nivel());
    }
}
