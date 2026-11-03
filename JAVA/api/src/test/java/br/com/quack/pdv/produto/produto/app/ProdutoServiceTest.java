package br.com.quack.pdv.produto.produto.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.quack.pdv.estoque.registrador.EstoqueTipos;
import br.com.quack.pdv.estoque.registrador.MovimentoOrigem;
import br.com.quack.pdv.estoque.registrador.RegistradorEstoque;
import br.com.quack.pdv.produto.lote.app.LoteOperacoes;
import br.com.quack.pdv.produto.produto.web.ProdutoModel;
import br.com.quack.pdv.produto.produto.web.ProdutoPrecoRequest;
import br.com.quack.pdv.produto.produto.web.ProdutoRequest;
import br.com.quack.pdv.shared.NegocioException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionOperations;

@SuppressWarnings("unchecked")
class ProdutoServiceTest {

    private JdbcTemplate jdbc;
    private RegistradorEstoque registrador;
    private LoteOperacoes loteOperacoes;
    private Clock clock;
    private ProdutoService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        registrador = mock(RegistradorEstoque.class);
        loteOperacoes = mock(LoteOperacoes.class);
        clock = Clock.fixed(Instant.parse("2026-11-02T12:00:00Z"), ZoneOffset.UTC);
        service = new ProdutoService(jdbc, registrador, loteOperacoes, TransactionOperations.withoutTransaction(), clock);
    }

    private static ProdutoRequest reqMinima(String nome, String codigo, String qnt, String custo, String venda) {
        return new ProdutoRequest(
                "", "", nome, codigo, "", "", qnt, null, "0", custo, venda, "",
                null, null, null, null, false, 15, "UN", "1", "1", null, null, null,
                "0", "0", "0", "0", "0", "0", null, "0,00", "0,00", "0,00", "0", "0", "0", "0",
                "00000000", null, "5102", 0, "UN", "UN", "SEM GTIN", null, null, "0,00", "07", "07", null, null);
    }

    private static ProdutoDados dadosExemplo(String id, String code, String gtin, String supplier, BigDecimal qnt, BigDecimal custo, BigDecimal venda) {
        return new ProdutoDados(
                id, null, "", "", "Produto " + code, code, supplier, "",
                qnt, BigDecimal.ZERO, custo, venda, custo.multiply(qnt), venda.subtract(custo),
                null, null, null, null, false, 15, "UN", BigDecimal.ONE, BigDecimal.ONE,
                null, null, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, custo, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, "00000000", null, "5102", 0, "UN", "UN", gtin,
                null, null, BigDecimal.ZERO, "07", "07", null, null);
    }

    @Test
    void validacoesBasicasDeCadastro() {
        assertThatThrownBy(() -> service.criar("emp-1", reqMinima("  ", "C1", "1", "2,00", "3,00"), "u1", "Ana"))
                .isInstanceOf(NegocioException.class)
                .hasMessage("Descrição do produto é obrigatória.");

        assertThatThrownBy(() -> service.criar("emp-1", reqMinima("Item", "C1", "-1", "2,00", "3,00"), "u1", "Ana"))
                .isInstanceOf(NegocioException.class)
                .hasMessage("Quantidade do produto deve ser um número maior ou igual a zero.");

        assertThatThrownBy(() -> service.criar("emp-1", reqMinima("Item", "C1", "1", "0", "3,00"), "u1", "Ana"))
                .isInstanceOf(NegocioException.class)
                .hasMessage("Preco de custo deve ser maior que zero.");

        assertThatThrownBy(() -> service.criar("emp-1", reqMinima("Item", "C1", "1", "2,00", "0"), "u1", "Ana"))
                .isInstanceOf(NegocioException.class)
                .hasMessage("Preco de venda deve ser maior que zero.");
    }

    @Test
    void recusaCodigoRepetidoEGtinRepetidoEFornecedorInexistente() {
        ProdutoDados existente = dadosExemplo("pr-1", "CT001", "7891000100103", "Sol", BigDecimal.TEN, new BigDecimal("2"), new BigDecimal("3"));
        when(jdbc.query(any(PreparedStatementCreator.class), any(RowMapper.class)))
                .thenReturn(List.of(existente));

        assertThatThrownBy(() -> service.criar("emp-1", reqMinima("Novo", "ct001", "1", "2,00", "3,00"), "u1", "Ana"))
                .hasMessage("Já existe produto com este código.");

        ProdutoRequest comGtin = new ProdutoRequest(
                "", "", "Novo", "CT999", "", "", "1", null, "0", "2,00", "3,00", "",
                null, null, null, null, false, 15, "UN", "1", "1", null, null, null,
                "0", "0", "0", "0", "0", "0", null, "0,00", "0,00", "0,00", "0", "0", "0", "0",
                "00000000", null, "5102", 0, "UN", "UN", "7891000100103", null, null, "0,00", "07", "07", null, null);
        assertThatThrownBy(() -> service.criar("emp-1", comGtin, "u1", "Ana"))
                .hasMessage("Já existe produto com este GTIN (código de barras).");

        when(jdbc.query(any(PreparedStatementCreator.class), any(RowMapper.class)))
                .thenReturn(List.of(existente), List.of(0));
        ProdutoRequest fornFantasma = new ProdutoRequest(
                "", "", "Novo", "CT999", "Fantasma", "", "1", null, "0", "2,00", "3,00", "",
                null, null, null, null, false, 15, "UN", "1", "1", null, null, null,
                "0", "0", "0", "0", "0", "0", null, "0,00", "0,00", "0,00", "0", "0", "0", "0",
                "00000000", null, "5102", 0, "UN", "UN", "SEM GTIN", null, null, "0,00", "07", "07", null, null);
        assertThatThrownBy(() -> service.criar("emp-1", fornFantasma, "u1", "Ana"))
                .hasMessage("Fornecedor informado não está cadastrado.");
    }

    @Test
    void estoqueRelativoCalculaDeltaSobreSaldoAtualPeloRegistrador() {
        ProdutoDados atual = dadosExemplo("pr-1", "CT001", "SEM GTIN", "Fornecedor Historico", new BigDecimal("8"), new BigDecimal("2"), new BigDecimal("3"));
        // 1) obterDados, 2) listarDados, 3) SELECT ProductQnt bloqueado (=8), 4) obterDados final
        when(jdbc.query(any(PreparedStatementCreator.class), any(RowMapper.class)))
                .thenReturn(List.of(atual), List.of(atual), List.of(), List.of(new BigDecimal("8")), List.of(atual.comQuantidade(new BigDecimal("13"))));
        when(registrador.aplicar(eq("emp-1"), eq("pr-1"), eq(new BigDecimal("5")), eq(EstoqueTipos.ENTRADA), eq(null), any(MovimentoOrigem.class)))
                .thenReturn(Optional.of(new RegistradorEstoque.Saldos(new BigDecimal("8"), new BigDecimal("13"))));

        ProdutoRequest edicao = new ProdutoRequest(
                "", "", "Produto CT001", "", "Fornecedor Historico", "", "15", "10", "0", "2,00", "3,50", "",
                null, null, null, null, false, 15, "UN", "1", "1", null, null, null,
                "0", "0", "0", "0", "0", "0", null, "0,00", "0,00", "0,00", "0", "0", "0", "0",
                "00000000", null, "5102", 0, "UN", "UN", "SEM GTIN", null, null, "0,00", "07", "07", null, null);

        Optional<ProdutoModel> res = service.atualizar("emp-1", "pr-1", edicao, "u1", "Ana");
        assertThat(res).isPresent();
        assertThat(res.get().productQnt()).isEqualTo("13");
        verify(registrador).aplicar(eq("emp-1"), eq("pr-1"), eq(new BigDecimal("5")), eq(EstoqueTipos.ENTRADA), eq(null), any());
    }

    @Test
    void atualizarPrecoValidaEntradaConflito409EMarkupMaximo() {
        assertThatThrownBy(() -> service.atualizarPreco("emp-1", "pr-1", new ProdutoPrecoRequest("6,905", "2|3")))
                .hasMessage("Informe um preço de venda maior que zero, com até duas casas decimais e até R$ 1 bilhão.");

        assertThatThrownBy(() -> service.atualizarPreco("emp-1", "pr-1", new ProdutoPrecoRequest("6,90", "")))
                .hasMessage("Atualize a consulta do produto antes de salvar o preço.");

        ProdutoDados dbProd = dadosExemplo("pr-1", "CT001", "SEM GTIN", "", BigDecimal.TEN, new BigDecimal("2.0000"), new BigDecimal("3.0000"));
        when(jdbc.query(any(PreparedStatementCreator.class), any(RowMapper.class)))
                .thenReturn(List.of(dbProd));

        assertThatThrownBy(() -> service.atualizarPreco("emp-1", "pr-1", new ProdutoPrecoRequest("6,90", "2|2.5")))
                .isInstanceOfSatisfying(NegocioException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getMessage()).contains("mudou desde esta consulta");
                });

        ProdutoDados custoMinimo = dadosExemplo("pr-1", "CT001", "SEM GTIN", "", BigDecimal.TEN, new BigDecimal("0.0001"), new BigDecimal("1.0000"));
        when(jdbc.query(any(PreparedStatementCreator.class), any(RowMapper.class)))
                .thenReturn(List.of(custoMinimo));

        assertThatThrownBy(() -> service.atualizarPreco("emp-1", "pr-1", new ProdutoPrecoRequest("500,00", "0.0001|1")))
                .hasMessage("O markup calculado excede o limite de 99.999,9999%. Confira o custo e o preço de venda.");
    }

    @Test
    void ajustarEstoqueValidaCamposEAcionaLoteSeguro() {
        assertThatThrownBy(() -> service.ajustarEstoque("emp-1", "pr-1", "entrada", BigDecimal.ZERO, "Motivo", "u1", "Ana", null, null))
                .hasMessage("Quantidade deve ser maior que zero.");

        assertThatThrownBy(() -> service.ajustarEstoque("emp-1", "pr-1", "invalido", BigDecimal.ONE, "Motivo", "u1", "Ana", null, null))
                .hasMessage("Tipo deve ser 'entrada', 'saida', 'uso_interno' ou 'perda'.");

        assertThatThrownBy(() -> service.ajustarEstoque("emp-1", "pr-1", "entrada", BigDecimal.ONE, "  ", "u1", "Ana", null, null))
                .hasMessage("O motivo é obrigatório para toda movimentação de estoque.");

        assertThatThrownBy(() -> service.ajustarEstoque("emp-1", "pr-1", "entrada", BigDecimal.ONE, "Motivo", "u1", "Ana", "31/12/2026", null))
                .hasMessage("Data de validade inválida.");

        verify(registrador, never()).aplicar(any(), any(), any(), any(), any(), any());

        // Entrada valida chama registrador e loteOperacoes.registrarEntradaSegura
        when(jdbc.query(any(PreparedStatementCreator.class), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    var rs = mock(java.sql.ResultSet.class);
                    when(rs.getBigDecimal("ProductQnt")).thenReturn(new BigDecimal("3.0000"));
                    when(rs.getBigDecimal("ProductUnitPrice")).thenReturn(new BigDecimal("2.0000"));
                    return List.of(mapper.mapRow(rs, 0));
                });

        assertThatThrownBy(() -> service.ajustarEstoque("emp-1", "pr-1", "saida", new BigDecimal("5"), "Saida maior", "u1", "Ana", null, null))
                .hasMessage("Estoque insuficiente. Disponível: 3.0000, solicitado: 5.");

        when(registrador.aplicar(eq("emp-1"), eq("pr-1"), eq(new BigDecimal("4")), eq("entrada"), any(), any()))
                .thenReturn(Optional.of(new RegistradorEstoque.Saldos(new BigDecimal("3"), new BigDecimal("7"))));

        boolean ok = service.ajustarEstoque("emp-1", "pr-1", "entrada", new BigDecimal("4"), "Compra avulsa", "u1", "Ana", "2027-05-20", "L-10");
        assertThat(ok).isTrue();
        ArgumentCaptor<LocalDate> capData = ArgumentCaptor.forClass(LocalDate.class);
        verify(loteOperacoes).registrarEntradaSegura(
                eq("emp-1"), eq("pr-1"), capData.capture(), eq(new BigDecimal("4")), eq("L-10"), eq("ajuste"), eq("Ana"));
        assertThat(capData.getValue()).isEqualTo(LocalDate.of(2027, 5, 20));
    }
}
