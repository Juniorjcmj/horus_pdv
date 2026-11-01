package br.com.quack.estoque;

import static br.com.quack.estoque.E2eSupport.log;
import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.api.ProdutoApi;
import br.com.quack.estoque.domain.Produto;
import br.com.quack.estoque.persistence.JsonFiles;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Edição de produto com a lista desatualizada (edição na tabela / ficha): mandando o estoque visto
 * (estoqueConsultado), a API aplica só a diferença digitada e não desfaz uma venda feita depois do carregamento.
 */
@EnabledIfSystemProperty(named = "quack.e2e.url", matches = ".+")
class EdicaoTabelaE2ETest {
    @TempDir
    static Path home;

    private static Produto buscar(E2eSupport e, String id) {
        return e.ctx.produtos.listar().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void edicaoComListaVelhaNaoDesfazVenda() {
        E2eSupport e = new E2eSupport(home);
        e.loginAdmin();
        ObjectNode b = JsonFiles.MAPPER.createObjectNode();
        b.put("productName", "Tabela Cerveja " + e.sufixo);
        b.put("productCode", "TAB-" + e.sufixo);
        b.put("productQnt", "10");
        b.put("productUnitPrice", "3,0000");
        b.put("productSalePrice", "5,00");
        b.put("unidadeComercial", "UN");
        b.put("gtin", "SEM GTIN");
        Produto visto = e.ctx.produtos.criar(b); // a "tela" carregou com 10

        // Outra pessoa vende 3 (simulado por uma saída) depois que a tela carregou.
        e.ctx.produtos.ajustar(visto.id(), "saida", new BigDecimal("3"), "venda simulada", null, null);
        assertEquals(0, new BigDecimal("7").compareTo(buscar(e, visto.id()).estoque()));
        int movsAntes = e.ctx.produtos.movimentacoes(visto.id()).size();

        // 1) Troca só o nome com a lista velha (estoque visto = 10): o saldo continua 7 e não há movimentação nova.
        ObjectNode nome = visto.raw().deepCopy();
        nome.put("estoqueConsultado", nome.path("productQnt").asText());
        nome.put("productName", "Tabela Cerveja Lata " + e.sufixo);
        Produto r1 = e.ctx.produtos.atualizar(visto.id(), nome);
        assertEquals("Tabela Cerveja Lata " + e.sufixo, r1.nome());
        assertEquals(0, new BigDecimal("7").compareTo(buscar(e, visto.id()).estoque()), "venda preservada");
        assertEquals(movsAntes, e.ctx.produtos.movimentacoes(visto.id()).size(), "sem movimentação ao mudar só o nome");

        // 2) Na tela velha o usuário muda o estoque de 10 para 12 (+2): resultado 7 + 2 = 9.
        ObjectNode qtd = visto.raw().deepCopy();
        qtd.put("estoqueConsultado", qtd.path("productQnt").asText());
        qtd.put("productQnt", "12");
        e.ctx.produtos.atualizar(visto.id(), qtd);
        assertEquals(0, new BigDecimal("9").compareTo(buscar(e, visto.id()).estoque()), "só a diferença digitada");
        List<ProdutoApi.Movimentacao> movs = e.ctx.produtos.movimentacoes(visto.id());
        assertEquals(movsAntes + 1, movs.size());
        log("edicao com lista velha: saldo 7 preservado; +2 digitado -> 9 (" + movs.get(0).tipo() + " "
                + movs.get(0).quantidade() + ")");

        // 3) Categoria pela tabela: a resposta já vem com o nome da categoria (a API relê o produto do banco).
        var cat = e.ctx.cadastros.categorias().stream().filter(c -> c.ativa()).findFirst().orElseThrow();
        ObjectNode comCategoria = buscar(e, visto.id()).raw().deepCopy();
        comCategoria.put("estoqueConsultado", comCategoria.path("productQnt").asText());
        comCategoria.put("categoriaId", cat.id());
        Produto r3 = e.ctx.produtos.atualizar(visto.id(), comCategoria);
        assertEquals(cat.id(), r3.categoriaId());
        assertEquals(cat.nome(), r3.categoriaNome(), "resposta com o nome da categoria");
        assertEquals(cat.id(), buscar(e, visto.id()).categoriaId(), "categoria gravada");
        assertEquals(0, new BigDecimal("9").compareTo(buscar(e, visto.id()).estoque()), "categoria não mexe no estoque");
        log("categoria salva: " + r3.categoriaNome());
    }
}
