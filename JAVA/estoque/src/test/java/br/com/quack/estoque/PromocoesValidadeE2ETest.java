package br.com.quack.estoque;

import static br.com.quack.estoque.E2eSupport.log;
import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.api.LoteApi;
import br.com.quack.estoque.domain.Produto;
import br.com.quack.estoque.persistence.JsonFiles;
import br.com.quack.estoque.ui.PromocoesView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Telas Promoções e Validade: os mesmos fluxos da versão web contra a API real. */
@EnabledIfSystemProperty(named = "quack.e2e.url", matches = ".+")
class PromocoesValidadeE2ETest {
    @TempDir
    static Path home;

    private static Produto produto(E2eSupport e, String nome, String codigo) {
        ObjectNode n = JsonFiles.MAPPER.createObjectNode();
        n.put("productName", nome + " " + e.sufixo);
        n.put("productCode", codigo + "-" + e.sufixo);
        n.put("productQnt", "20");
        n.put("productUnitPrice", "2,0000");
        n.put("productSalePrice", "5,00");
        n.put("unidadeComercial", "UN");
        n.put("controlaValidade", true);
        return e.ctx.produtos.criar(n);
    }

    private static JsonNode achar(JsonNode lista, String id) {
        for (JsonNode j : lista) {
            if (j.path("id").asText().equals(id)) {
                return j;
            }
        }
        return null;
    }

    @Test
    void promocaoCriarEditarDesativarResultadoExcluir() {
        E2eSupport e = new E2eSupport(home);
        e.loginAdmin();
        Produto p = produto(e, "Promo Cerveja", "PRM");
        OffsetDateTime ini = OffsetDateTime.now().minusMinutes(5);
        Map<String, Object> corpo = PromocoesView.montarCorpo("Festival " + e.sufixo, "leve_x_pague_y", "", "", "", "3", "2", "", "",
                "", "", ini, ini.plusDays(7), null, List.of(p.id()), true);
        JsonNode criada = e.ctx.promocoes.salvar(null, corpo);
        String id = criada.path("id").asText();
        assertFalse(id.isBlank(), "promoção criada");

        JsonNode lida = achar(e.ctx.promocoes.listar(), id);
        assertNotNull(lida, "aparece na lista");
        assertEquals("Leve 3, pague 2", PromocoesView.detalhe(lida));
        assertEquals("Ativa", PromocoesView.situacao(lida, OffsetDateTime.now()));

        // editar: vira 15% de desconto
        Map<String, Object> edit = PromocoesView.montarCorpo("Festival " + e.sufixo + " v2", "desconto_percentual", "15", "", "", "",
                "", "", "", "", "", ini, ini.plusDays(7), null, List.of(p.id()), true);
        e.ctx.promocoes.salvar(id, edit);
        lida = achar(e.ctx.promocoes.listar(), id);
        assertEquals("15,00% de desconto", PromocoesView.detalhe(lida));

        e.ctx.promocoes.definirAtiva(id, false);
        lida = achar(e.ctx.promocoes.listar(), id);
        assertEquals("Inativa", PromocoesView.situacao(lida, OffsetDateTime.now()));

        JsonNode r = e.ctx.promocoes.resultado(id);
        assertNotNull(r, "desempenho");
        assertEquals(0, r.path("quantidadeVendas").asInt());

        e.ctx.promocoes.excluir(id);
        assertNull(achar(e.ctx.promocoes.listar(), id), "excluída");
        log("promoção " + id + ": criada, editada, desativada, desempenho e excluída");
    }

    @Test
    void validadeRegistrarAlertaConsultaPerdaECategoria() {
        E2eSupport e = new E2eSupport(home);
        e.loginAdmin();
        Produto p = produto(e, "Validade Iogurte", "VAL");
        String vence = LocalDate.now().plusDays(2).toString();
        e.ctx.lotes.registrar(p.id(), vence, new BigDecimal("6"), "L-" + e.sufixo);

        JsonNode alertas = e.ctx.lotes.alertas();
        JsonNode lote = null;
        for (JsonNode j : alertas.path("itens")) {
            if (j.path("produtoId").asText().equals(p.id()) && ("L-" + e.sufixo).equals(j.path("numeroLote").asText())) {
                lote = j;
            }
        }
        assertNotNull(lote, "lote que vence em 2 dias aparece nos alertas");
        log("alerta: faixa " + lote.path("faixa").asText() + " · qtd " + lote.path("qtdEstimada").asText() + " · risco "
                + lote.path("valorEmRisco").asText());

        JsonNode consulta = e.ctx.lotes.consulta(new LoteApi.Filtro(p.codigo(), null, null, null, null, false, null, 1, 25));
        assertTrue(consulta.path("total").asInt() >= 1, "consulta acha o lote pelo código");

        // baixar perda (como o botão da tela): estoque do produto cai
        BigDecimal antes = e.produto(p.id()).estoque();
        e.ctx.produtos.ajustar(p.id(), "perda", new BigDecimal("2"), "Perda por vencimento (teste)", null, null);
        assertEquals(0, antes.subtract(new BigDecimal("2")).compareTo(e.produto(p.id()).estoque()), "perda baixou o estoque");

        // prazos por categoria: grava e lê de volta
        String catId = e.ctx.cadastros.salvarCategoria(null, "Validade Cat " + e.sufixo, null, true);
        e.ctx.lotes.salvarCategoria(catId, 30, 7);
        JsonNode cat = null;
        for (JsonNode c : e.ctx.lotes.categorias()) {
            if (c.path("categoriaId").asText().equals(catId)) {
                cat = c;
            }
        }
        assertNotNull(cat);
        assertEquals(30, cat.path("prazoPadraoDias").asInt());
        assertEquals(7, cat.path("diasAlerta").asInt());

        // baixa por lote: lê o modo (sem mudar a configuração da base de teste)
        assertTrue(List.of("desligado", "sombra", "ativo").contains(e.ctx.lotes.fefo().path("modo").asText()));
    }
}
