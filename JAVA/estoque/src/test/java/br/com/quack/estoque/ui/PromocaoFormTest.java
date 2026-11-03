package br.com.quack.estoque.ui;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.domain.DomainException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Regras do formulário de promoção (as mesmas da tela web) e o corpo enviado à API. */
class PromocaoFormTest {
    private static final OffsetDateTime INI = OffsetDateTime.parse("2026-11-02T08:00:00-03:00");
    private static final OffsetDateTime FIM = OffsetDateTime.parse("2026-11-09T22:00:00-03:00");

    private static Map<String, Object> corpo(String tipo, String percentual, String leva, String paga, String precoCombo,
            List<String> produtos, String categoria) {
        return PromocoesView.montarCorpo("Oferta", tipo, percentual, "", "", leva, paga, "3", precoCombo, "10", "3,50", INI, FIM,
                categoria, produtos, true);
    }

    @Test
    void montaCorpoPorTipo() {
        Map<String, Object> pct = corpo("desconto_percentual", "10", "", "", "", List.of("p1"), null);
        assertEquals(0, new BigDecimal("10").compareTo((BigDecimal) pct.get("valorDesconto")));
        assertNull(pct.get("precoFixo"));
        assertEquals("2026-11-02T08:00-03:00", pct.get("inicioVigencia"));
        assertEquals(List.of("p1"), pct.get("produtoIds"));

        Map<String, Object> leve = corpo("leve_x_pague_y", "", "3", "2", "", List.of("p1"), null);
        assertEquals(3, leve.get("quantidadeLeva"));
        assertEquals(2, leve.get("quantidadePaga"));

        Map<String, Object> combo = corpo("combo_quantidade", "", "", "", "10,00", List.of(), "cat-1");
        assertEquals(3, combo.get("quantidadeMinima"));
        assertEquals(0, new BigDecimal("10.00").compareTo((BigDecimal) combo.get("precoFixo")));
        assertEquals("cat-1", combo.get("categoriaId"));
    }

    @Test
    void recusaComoAWeb() {
        assertThrows(DomainException.class, () -> corpo("desconto_percentual", "10", "", "", "", List.of(), null),
                "sem produto nem categoria");
        assertThrows(DomainException.class, () -> corpo("desconto_percentual", "", "", "", "", List.of("p1"), null), "sem %");
        assertThrows(DomainException.class, () -> corpo("desconto_percentual", "100", "", "", "", List.of("p1"), null), "100%");
        assertThrows(DomainException.class, () -> corpo("leve_x_pague_y", "", "2", "2", "", List.of("p1"), null), "paga >= leva");
        assertThrows(DomainException.class, () -> PromocoesView.montarCorpo("Oferta", "preco_fixo", "", "", "4,99", "", "", "", "",
                "", "", FIM, INI, null, List.of("p1"), true), "fim antes do início");
        assertThrows(DomainException.class, () -> PromocoesView.montarCorpo(" ", "preco_fixo", "", "", "4,99", "", "", "", "", "",
                "", INI, FIM, null, List.of("p1"), true), "sem nome");
    }

    @Test
    void situacaoEDetalhe() throws Exception {
        var m = br.com.quack.estoque.persistence.JsonFiles.MAPPER;
        var j = m.readTree("{\"nome\":\"X\",\"tipo\":\"leve_x_pague_y\",\"quantidadeLeva\":3,\"quantidadePaga\":2,\"ativa\":true,"
                + "\"inicioVigencia\":\"2026-11-02T08:00:00-03:00\",\"fimVigencia\":\"2026-11-09T22:00:00-03:00\"}");
        assertEquals("Leve 3, pague 2", PromocoesView.detalhe(j));
        assertEquals("Agendada", PromocoesView.situacao(j, OffsetDateTime.parse("2026-11-01T10:00:00-03:00")));
        assertEquals("Ativa", PromocoesView.situacao(j, OffsetDateTime.parse("2026-11-05T10:00:00-03:00")));
        assertEquals("Encerrada", PromocoesView.situacao(j, OffsetDateTime.parse("2026-11-10T10:00:00-03:00")));
        ((com.fasterxml.jackson.databind.node.ObjectNode) j).put("ativa", false);
        assertEquals("Inativa", PromocoesView.situacao(j, OffsetDateTime.parse("2026-11-05T10:00:00-03:00")));
    }
}
