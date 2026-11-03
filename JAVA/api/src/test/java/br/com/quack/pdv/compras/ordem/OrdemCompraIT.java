package br.com.quack.pdv.compras.ordem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.quack.pdv.BancoIT;
import br.com.quack.pdv.compras.ordem.app.OrdemCompraService;
import br.com.quack.pdv.compras.ordem.web.OrdemCompraRequests;
import br.com.quack.pdv.contrato.EstadoDoBanco;
import br.com.quack.pdv.security.UsuarioAutenticado;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@SpringBootTest
class OrdemCompraIT extends BancoIT {
    @Autowired OrdemCompraService service;
    @Autowired JdbcTemplate jdbc;
    private final UsuarioAutenticado usuario =
            new UsuarioAutenticado(
                    "usr-ct-gerente",
                    "empresa-principal",
                    "s",
                    "Gil Gerente",
                    "g@teste",
                    "gerente",
                    List.of());

    @BeforeEach
    void seed() {
        EstadoDoBanco.por(urlDoBanco("quack"), SQL.getUsername(), SQL.getPassword()).restaurar();
    }

    private OrdemCompraRequests.Receber recebimento(String quantidade) {
        var r = new OrdemCompraRequests.Receber();
        var i = new OrdemCompraRequests.ItemRecebido();
        i.productCode = "CT001";
        i.quantityReceived = new BigDecimal(quantidade);
        i.dataValidade = OffsetDateTime.parse("2027-06-01T00:00:00-03:00");
        i.numeroLote = "OC-IT";
        r.itens.add(i);
        return r;
    }

    @Test
    void parcialSeguidoDoComplementoSomaSaldoMovimentosCustoELotes() {
        jdbc.update(
                "UPDATE OrdemCompraItens SET UnitCost=3.25 WHERE OrdemCompraId='oc-ct-pendente'");
        assertThat(service.receber("empresa-principal", "40", recebimento("4"), usuario))
                .isEqualTo(3);
        assertThat(saldo()).isEqualByComparingTo("14");
        assertThat(service.receber("empresa-principal", "40", recebimento("6"), usuario))
                .isEqualTo(1);
        assertThat(saldo()).isEqualByComparingTo("20");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT ProductUnitPrice FROM Produtos WHERE Id='pr-ct-1'",
                                BigDecimal.class))
                .isEqualByComparingTo("3.25");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT SUM(Quantidade) FROM EstoqueMovimentacoes WHERE"
                                    + " ProdutoId='pr-ct-1' AND OrigemTipo='pedido_compra' AND"
                                    + " OrigemId='40'",
                                BigDecimal.class))
                .isEqualByComparingTo("10");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT SUM(QtdInicial) FROM ProdutoLotes WHERE ProdutoId='pr-ct-1'"
                                    + " AND Origem='compra'",
                                BigDecimal.class))
                .isEqualByComparingTo("10");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT QuantityReceived FROM OrdemCompraItens WHERE"
                                    + " OrdemCompraId='oc-ct-pendente'",
                                BigDecimal.class))
                .isEqualByComparingTo("10");
        assertThatThrownBy(
                        () -> service.receber("empresa-principal", "40", recebimento("1"), usuario))
                .hasMessage("Ordem de compra não encontrada ou já finalizada/cancelada.");
        assertThat(saldo()).isEqualByComparingTo("20");
    }

    private BigDecimal saldo() {
        return jdbc.queryForObject(
                "SELECT ProductQnt FROM Produtos WHERE Id='pr-ct-1'", BigDecimal.class);
    }
}
