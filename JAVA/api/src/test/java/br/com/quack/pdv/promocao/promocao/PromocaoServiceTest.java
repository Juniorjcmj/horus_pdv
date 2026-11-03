package br.com.quack.pdv.promocao.promocao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.quack.pdv.cadastro.categoria.repo.CategoriaRepository;
import br.com.quack.pdv.promocao.promocao.app.PromocaoService;
import br.com.quack.pdv.promocao.promocao.web.PromocaoModels.Promocao;
import br.com.quack.pdv.promocao.promocao.web.PromocaoRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

class PromocaoServiceTest {
    private final CategoriaRepository categorias = mock(CategoriaRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Clock clock =
            Clock.fixed(Instant.parse("2026-11-03T15:00:00Z"), ZoneId.of("America/Sao_Paulo"));
    private final PromocaoService service =
            new PromocaoService(jdbc, categorias, clock, mock(PlatformTransactionManager.class));

    private PromocaoRequest pedido(String tipo) {
        var r = new PromocaoRequest();
        r.nome = "Promo";
        r.tipo = tipo;
        r.valorDesconto = BigDecimal.TEN;
        r.precoFixo = BigDecimal.ONE;
        r.quantidadeLeva = 3;
        r.quantidadePaga = 2;
        r.quantidadeMinima = 2;
        r.inicioVigencia = OffsetDateTime.parse("2026-11-03T12:00:00-03:00");
        r.fimVigencia = r.inicioVigencia.plusHours(1);
        r.produtoIds.add("pr-ct-1");
        return r;
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "desconto_percentual",
                "desconto_valor",
                "preco_fixo",
                "leve_x_pague_y",
                "combo_quantidade",
                "preco_atacado"
            })
    void aceitaCadaTipoComSeusParametros(String tipo) {
        service.validar("empresa", pedido(tipo));
        verifyNoInteractions(jdbc, categorias);
    }

    @ParameterizedTest
    @CsvSource({
        "desconto_percentual,0,Desconto percentual deve ser maior que 0 e no máximo 100%.",
        "desconto_percentual,101,Desconto percentual deve ser maior que 0 e no máximo 100%.",
        "desconto_valor,0,Valor de desconto deve ser maior que zero.",
        "preco_fixo,0,Preço fixo deve ser maior que zero."
    })
    void recusaValoresForaDoLimite(String tipo, String valor, String mensagem) {
        var r = pedido(tipo);
        r.valorDesconto = new BigDecimal(valor);
        r.precoFixo = new BigDecimal(valor);
        assertThatThrownBy(() -> service.validar("e", r)).hasMessage(mensagem);
    }

    @Test
    void validaCamposComunsAntesDeConsultarCategoria() {
        var r = pedido("invalido");
        r.nome = " ";
        assertThatThrownBy(() -> service.validar("e", r))
                .hasMessage("O nome da promoção é obrigatório.");
        r.nome = "P";
        assertThatThrownBy(() -> service.validar("e", r))
                .hasMessageStartingWith("Tipo de promoção 'invalido' é inválido.");
        r.tipo = "desconto_percentual";
        r.fimVigencia = r.inicioVigencia;
        assertThatThrownBy(() -> service.validar("e", r))
                .hasMessage("A data final de vigência deve ser posterior à data inicial.");
        verifyNoInteractions(categorias, jdbc);
    }

    @Test
    void validaLevaPagaESeusLimites() {
        var r = pedido("leve_x_pague_y");
        r.quantidadeLeva = null;
        assertThatThrownBy(() -> service.validar("e", r))
                .hasMessage("Quantidade 'leva' e 'paga' devem ser maiores que zero.");
        r.quantidadeLeva = 2;
        assertThatThrownBy(() -> service.validar("e", r))
                .hasMessage("A quantidade 'leva' deve ser maior que a quantidade 'paga'.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"combo_quantidade", "preco_atacado"})
    void comboEAtacadoExigemMinimoEPrecoOuDesconto(String tipo) {
        var r = pedido(tipo);
        r.quantidadeMinima = 1;
        assertThatThrownBy(() -> service.validar("e", r))
                .hasMessage("Quantidade mínima deve ser maior que 1.");
        r.quantidadeMinima = 2;
        r.precoFixo = null;
        r.valorDesconto = null;
        assertThatThrownBy(() -> service.validar("e", r))
                .hasMessage(
                        "Informe um preço unitário promocional ou valor de desconto para o"
                            + " combo/atacado.");
        r.valorDesconto = BigDecimal.ONE;
        service.validar("e", r);
        r.valorDesconto = null;
        r.precoFixo = BigDecimal.ONE;
        service.validar("e", r);
    }

    @Test
    void exigeEscopoECategoriaExistenteNoContratoDoRepositorio() {
        var r = pedido("desconto_valor");
        r.produtoIds.clear();
        assertThatThrownBy(() -> service.validar("e", r))
                .hasMessage(
                        "A promoção deve estar vinculada a pelo menos um produto ou a uma"
                            + " categoria.");
        r.categoriaId = " cat ";
        when(categorias.obter("e", "cat")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.validar("e", r))
                .hasMessage("Categoria informada não foi encontrada.");
        when(categorias.obter("e", "cat"))
                .thenReturn(Optional.of(mock(CategoriaRepository.Leitura.class)));
        service.validar("e", r);
        verifyNoInteractions(jdbc);
    }

    @Test
    void vigenciaIncluiLimitesEMesmoInstanteEmOutroFuso() {
        var inicio = OffsetDateTime.now(clock);
        var fim = inicio.plusHours(1);
        var p =
                new Promocao(
                        "p",
                        "e",
                        "P",
                        "preco_fixo",
                        null,
                        BigDecimal.ONE,
                        null,
                        null,
                        null,
                        inicio,
                        fim,
                        true,
                        null,
                        null,
                        "u",
                        inicio,
                        List.of());
        assertThat(PromocaoService.vigente(p, inicio)).isTrue();
        assertThat(PromocaoService.vigente(p, fim)).isTrue();
        assertThat(
                        PromocaoService.vigente(
                                p, inicio.withOffsetSameInstant(java.time.ZoneOffset.UTC)))
                .isTrue();
        assertThat(PromocaoService.vigente(p, inicio.minusNanos(100))).isFalse();
        assertThat(PromocaoService.vigente(p, fim.plusNanos(100))).isFalse();
    }
}
