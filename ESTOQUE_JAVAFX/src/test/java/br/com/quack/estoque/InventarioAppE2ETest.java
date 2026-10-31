package br.com.quack.estoque;

import static br.com.quack.estoque.E2eSupport.falha;
import static br.com.quack.estoque.E2eSupport.log;
import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.api.InventarioApi;
import br.com.quack.estoque.domain.Catalogo;
import br.com.quack.estoque.domain.Produto;
import br.com.quack.estoque.persistence.JsonFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Inventário de ponta a ponta como o aplicativo faz: lê o código no catálogo (código interno, GTIN e etiqueta de balança),
 * conta, vende durante a contagem, aplica com a senha do gerente e confere saldos e trilha.
 */
@EnabledIfSystemProperty(named = "quack.e2e.url", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InventarioAppE2ETest {
    @TempDir
    static Path home;
    @TempDir
    static Path homeEstoquista;

    E2eSupport e;

    private Produto criar(String nome, String codigo, String qtd, String custo, String venda, String un, String gtin) {
        ObjectNode b = JsonFiles.MAPPER.createObjectNode();
        b.put("productName", nome + " " + e.sufixo);
        b.put("productCode", codigo);
        b.put("productQnt", qtd);
        b.put("productUnitPrice", custo);
        b.put("productSalePrice", venda);
        b.put("unidadeComercial", un);
        b.put("unidadeTributavel", un);
        b.put("gtin", gtin);
        return e.ctx.produtos.criar(b);
    }

    @BeforeAll
    void preparar() {
        e = new E2eSupport(home);
        e.loginAdmin();
        for (JsonNode aberto : e.ctx.inventarios.listar("em_contagem")) {
            e.ctx.inventarios.cancelar(aberto.get("id").asText(), "limpeza do teste", null);
        }
    }

    @Test
    void inventarioCompletoPeloApp() {
        InventarioApi inv = e.ctx.inventarios;
        // A: UN com GTIN valido; B: KG com PLU de balança de 4 dígitos (prefixo 2 + PLU + preço); C: UN sem GTIN
        String plu = String.format("%04d", Math.abs(System.nanoTime() % 9000) + 1000);
        String gtinA = TestData.ean("789" + String.format("%09d", Math.abs(System.nanoTime() % 1_000_000_000L)));
        Produto a = criar("App Arroz", "APA-" + e.sufixo, "20", "4,00", "9,00", "UN", gtinA);
        Produto b = criar("App Queijo", plu, "8", "30,00", "50,00", "KG", "");
        Produto c = criar("App Feijão", "APC-" + e.sufixo, "5", "2,00", "6,00", "UN", "");
        e.ctx.garantirCatalogo(true);
        Catalogo cat = e.ctx.catalogo;

        JsonNode r = inv.abrir("E2E pelo app", "produtos", null, List.of(a.id(), b.id(), c.id()), e.ctx.terminal());
        String id = r.get("id").asText();
        assertEquals(3, r.get("itens").asInt());

        // 1) bipa o GTIN de A (quantidade padrão 1) três vezes -> 3; depois o código interno de A mais 15 -> 18
        for (int i = 0; i < 3; i++) {
            Catalogo.Leitura l = cat.ler(gtinA);
            assertNotNull(l);
            assertEquals(a.id(), l.produto().id());
            inv.contar(id, l.produto().id(), null, l.quantidade(), "Prateleira", e.ctx.terminal());
        }
        Catalogo.Leitura porCodigo = cat.ler(a.codigo());
        InventarioApi.Registro ra = inv.contar(id, porCodigo.produto().id(), null, new BigDecimal("15"), "Depósito", e.ctx.terminal());
        assertEquals(0, new BigDecimal("18").compareTo(ra.totalContado()), "3 bipes + 15 do depósito");

        // 2) etiqueta de balança de B: 2 + PLU + 0000685 (R$ 6,85 a R$ 50,00/kg = 0,137 kg)
        String balanca = TestData.ean("2" + plu + "0000685");
        Catalogo.Leitura lb = cat.ler(balanca);
        assertNotNull(lb, "etiqueta de balança reconhecida");
        assertTrue(lb.balanca());
        assertEquals(b.id(), lb.produto().id());
        assertEquals(0, new BigDecimal("0.137").compareTo(lb.quantidade()), "peso da etiqueta = " + lb.quantidade());
        InventarioApi.Registro rb = inv.contar(id, lb.produto().id(), null, lb.quantidade(), "Câmara", e.ctx.terminal());
        assertEquals(0, new BigDecimal("0.137").compareTo(rb.totalContado()));
        // UN não aceita fração, mas KG sim
        assertEquals(400, falha(() -> inv.contar(id, a.id(), null, new BigDecimal("0.5"), null, null)).status());

        // 3) código desconhecido: o app não registra nada (nem chega à API)
        assertNull(cat.ler("0000000000000"));

        // 4) venda de 2 unidades de A durante a contagem; recontagem pedida de C pelo gerente
        e.vender(a, 2);
        assertEquals(18, e.produto(a.id()).estoque().intValue());
        inv.contar(id, c.id(), null, new BigDecimal("4"), "Loja", null);

        // estoquista: vê divergências sem valores e não aplica
        AppContext est = e.comoEstoquista(homeEstoquista);
        List<InventarioApi.Item> vistoPeloEstoquista = est.inventarios.itens(id, null);
        assertTrue(vistoPeloEstoquista.stream().allMatch(i -> i.valorDiferenca() == null && i.custo().signum() == 0), "sem valores");
        assertEquals(403, falha(() -> est.inventarios.aplicar(id, est.session.user().id(), "Estoq@12345", "tentativa do estoquista", false, null)).status());
        est.inventarios.contar(id, c.id(), null, BigDecimal.ONE, "Loja", null); // estoquista também conta
        assertEquals(403, falha(() -> est.inventarios.recontar(id, List.of(c.id()))).status());
        assertEquals(403, falha(() -> est.inventarios.trilha(id)).status());

        // 5) divergências: A sistema 20, contado 18 (-2 un, -8,00); B contado 0,137 de 8 kg; C 5 contados
        InventarioApi.Item ia = inv.itens(id, "divergentes").stream().filter(i -> i.produtoId().equals(a.id())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("-2").compareTo(ia.diferenca()));
        assertEquals(0, new BigDecimal("-8.00").compareTo(ia.valorDiferenca()));

        // 6) aplica: A = 18 (a venda de 2 já está no saldo 18: 18 + (18 - 20) = 16), B = 0,137; C conforme contado
        JsonNode ap = inv.aplicar(id, e.adminId(), E2eSupport.ADMIN_SENHA, "acerto do teste pelo app", false, e.ctx.terminal());
        assertEquals(2, ap.get("ajustados").asInt(), "A e B mudam; C confere com o sistema e nao gera movimento");
        assertEquals(16, e.produto(a.id()).estoque().intValue(), "saldo atual 18 + (contado 18 - sistema 20)");
        assertEquals(0, new BigDecimal("0.137").compareTo(e.produto(b.id()).estoque()), "B ajustado para o peso contado: 8 + (0,137 - 8)");
        assertEquals(5, e.produto(c.id()).estoque().intValue(), "C: contado 4 + 1 = 5, igual ao sistema");

        JsonNode trilha = inv.trilha(id);
        assertEquals("aplicado", trilha.get("inventario").get("inventario").get("status").asText());
        assertTrue(trilha.get("contagens").size() >= 7);
        log("inventario pelo app ok: " + ap);
    }
}
