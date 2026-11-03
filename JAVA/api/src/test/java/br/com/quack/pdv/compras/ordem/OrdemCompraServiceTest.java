package br.com.quack.pdv.compras.ordem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;

import br.com.quack.pdv.cadastro.fornecedor.repo.FornecedorRepository;
import br.com.quack.pdv.compras.ordem.app.OrdemCompraService;
import br.com.quack.pdv.compras.ordem.web.OrdemCompraModels.Item;
import br.com.quack.pdv.compras.ordem.web.OrdemCompraRequests;
import br.com.quack.pdv.produto.lote.app.LoteOperacoes;
import br.com.quack.pdv.produto.produto.app.ProdutoService;
import br.com.quack.pdv.security.UsuarioAutenticado;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

class OrdemCompraServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ProdutoService produtos = mock(ProdutoService.class);
    private final LoteOperacoes lotes = mock(LoteOperacoes.class);
    private final OrdemCompraService service =
            spy(
                    new OrdemCompraService(
                            jdbc,
                            produtos,
                            mock(FornecedorRepository.class),
                            lotes,
                            Clock.systemUTC(),
                            mock(PlatformTransactionManager.class)));
    private final UsuarioAutenticado usuario =
            new UsuarioAutenticado("u", "e", "s", "Gerente", "g@teste", "gerente", List.of());

    private Item item(String pedido, String recebido) {
        return new Item(
                "CT001",
                "Produto",
                new BigDecimal(pedido),
                BigDecimal.ONE,
                BigDecimal.TEN,
                new BigDecimal(recebido),
                null);
    }

    @Test
    void situacaoRefleteTodosParcialOuNenhumRecebido() {
        assertThat(OrdemCompraService.calcularStatus(List.of(item("10", "0")))).isZero();
        assertThat(OrdemCompraService.calcularStatus(List.of(item("10", "4")))).isEqualTo(3);
        assertThat(OrdemCompraService.calcularStatus(List.of(item("10", "10")))).isEqualTo(1);
        assertThat(OrdemCompraService.calcularStatus(List.of(item("10", "12"), item("5", "0"))))
                .isEqualTo(3);
    }

    @Test
    void pedidoSemItensNaoIniciaGravacoes() {
        assertThatThrownBy(() -> service.criar("e", new OrdemCompraRequests.Pedido(), usuario))
                .hasMessage("Ordem de compra sem itens.");
        verifyNoInteractions(jdbc, produtos, lotes);
    }

    @Test
    void recebimentoVazioNaoConsultaNemAlteraEstoque() {
        assertThatThrownBy(
                        () ->
                                service.receber(
                                        "e", "40", new OrdemCompraRequests.Receber(), usuario))
                .hasMessage("Nenhum item para receber.");
        verifyNoInteractions(jdbc, produtos, lotes);
    }

    @Test
    void ordemInexistenteNaoDaEntrada() {
        var r = new OrdemCompraRequests.Receber();
        r.itens.add(new OrdemCompraRequests.ItemRecebido());
        doReturn(Optional.empty()).when(service).obter("e", "40");
        assertThatThrownBy(() -> service.receber("e", "40", r, usuario))
                .hasMessage("Ordem de compra não encontrada ou já finalizada/cancelada.");
        verifyNoInteractions(jdbc, produtos, lotes);
    }
}
