package br.com.quack.pdv.banco;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.BancoIT;
import br.com.quack.pdv.cadastro.categoria.repo.CategoriaRepository;
import br.com.quack.pdv.compras.ordem.repo.OrdemCompraItemRepository;
import br.com.quack.pdv.compras.ordem.repo.OrdemCompraRepository;
import br.com.quack.pdv.contrato.EstadoDoBanco;
import br.com.quack.pdv.estoque.inventario.domain.InventarioItemId;
import br.com.quack.pdv.estoque.inventario.repo.InventarioEventoRepository;
import br.com.quack.pdv.estoque.inventario.repo.InventarioItemRepository;
import br.com.quack.pdv.estoque.inventario.repo.InventarioRepository;
import br.com.quack.pdv.estoque.movimentacao.repo.EstoqueMovimentacaoRepository;
import br.com.quack.pdv.produto.entrada.repo.NotaEntradaRepository;
import br.com.quack.pdv.produto.lote.repo.LoteConfigRepository;
import br.com.quack.pdv.produto.lote.repo.ProdutoLoteRepository;
import br.com.quack.pdv.produto.produto.repo.ProdutoRepository;
import br.com.quack.pdv.promocao.promocao.repo.PromocaoProdutoRepository;
import br.com.quack.pdv.promocao.promocao.repo.PromocaoRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("it")
class ProdutosEstoqueRepositoriosIT {

    static {
        BancoIT.criarBanco("cadastros");
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> BancoIT.urlDoBanco("cadastros"));
        r.add("spring.datasource.username", BancoIT.SQL::getUsername);
        r.add("spring.datasource.password", BancoIT.SQL::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ProdutoRepository produtos;
    @Autowired EstoqueMovimentacaoRepository movimentacoes;
    @Autowired ProdutoLoteRepository lotes;
    @Autowired LoteConfigRepository loteConfigs;
    @Autowired NotaEntradaRepository notasEntrada;
    @Autowired InventarioRepository inventarios;
    @Autowired InventarioItemRepository inventarioItens;
    @Autowired InventarioEventoRepository inventarioEventos;
    @Autowired OrdemCompraRepository ordensCompra;
    @Autowired OrdemCompraItemRepository ordemCompraItens;
    @Autowired PromocaoRepository promocoes;
    @Autowired PromocaoProdutoRepository promocaoProdutos;
    @Autowired CategoriaRepository categorias;

    @BeforeEach
    void seed() {
        EstadoDoBanco.por(BancoIT.urlDoBanco("cadastros"), BancoIT.SQL.getUsername(), BancoIT.SQL.getPassword())
                .restaurar();
    }

    @Test
    void leProdutosDoSeedComAcentosEDecimaisEEntidadesDaFase3() {
        var iogurte = produtos.findByIdAndCompanyId("pr-ct-val", "empresa-principal").orElseThrow();
        assertThat(iogurte.productName).isEqualTo("Iogurte Grego Açaí 170g");
        assertThat(iogurte.productDescription).isEqualTo("Produto perecível com acentuação");
        assertThat(iogurte.productUnitPrice).isEqualByComparingTo("3.4567");
        assertThat(iogurte.productSalePrice).isEqualByComparingTo("5.9000");
        assertThat(iogurte.productQnt).isEqualByComparingTo("12.0000");
        assertThat(iogurte.totalPriceOnProduct).isEqualByComparingTo("41.48");
        assertThat(iogurte.dataValidade).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(iogurte.controlaValidade).isTrue();

        var pao = produtos.findByIdAndCompanyId("pr-ct-val2", "empresa-principal").orElseThrow();
        assertThat(pao.productName).isEqualTo("Pão de Forma Integral");

        var catPereciveis = categorias.findById("cat-ct-pereciveis").orElseThrow();
        assertThat(catPereciveis.prazoPadraoValidadeDias).isEqualTo(30);
        assertThat(catPereciveis.diasAlertaValidade).isEqualTo(10);

        assertThat(movimentacoes.findByCompanyIdAndProdutoIdOrderByCriadoEmDesc("empresa-principal", "pr-ct-1"))
                .hasSize(1);
        assertThat(movimentacoes.findByCompanyIdAndProdutoIdOrderByCriadoEmDesc("empresa-principal", "pr-ct-val"))
                .hasSize(1);
        assertThat(lotes.findByCompanyIdAndProdutoIdOrderByDataValidadeDescCriadoEmDesc("empresa-principal", "pr-ct-val"))
                .hasSize(3);
        assertThat(loteConfigs.findById("empresa-principal").orElseThrow().modo).isEqualTo("sombra");

        var nota = notasEntrada.findByIdAndCompanyId("ne-ct-xml", "empresa-principal").orElseThrow();
        assertThat(nota.chaveAcesso).hasSize(44);
        assertThat(nota.xmlOriginal).isNotEmpty();
        assertThat(nota.comprovanteOriginal).isNotEmpty();

        var inv = inventarios.findByIdAndCompanyId("inv-ct-aberto", "empresa-principal").orElseThrow();
        assertThat(inv.status).isEqualTo("em_contagem");
        assertThat(inventarioItens.findById(new InventarioItemId("inv-ct-aberto", "pr-ct-inv"))).isPresent();
        assertThat(inventarioEventos.findByInventarioIdOrderByOcorridoEmAsc("inv-ct-aberto")).hasSize(1);

        var oc = ordensCompra.findByIdAndCompanyId("oc-ct-pendente", "empresa-principal").orElseThrow();
        assertThat(oc.orderNumber).isEqualTo("40");
        assertThat(ordemCompraItens.findByOrdemCompraIdOrderByIdAsc("oc-ct-pendente")).hasSize(1);

        assertThat(promocoes.findByIdAndCompanyId("prm-ct-perc", "empresa-principal")).isPresent();
        assertThat(promocaoProdutos.findByPromocaoId("prm-ct-perc")).hasSize(1);
    }

    @Test
    void jpaNaoAtualizaSaldoNemTotalEmEstoqueDoProduto() {
        var iogurte = produtos.findByIdAndCompanyId("pr-ct-val", "empresa-principal").orElseThrow();
        iogurte.productName = "Iogurte Grego Açaí 170g Renomeado";
        iogurte.productQnt = new BigDecimal("999.0000");
        iogurte.totalPriceOnProduct = new BigDecimal("9999.99");
        produtos.saveAndFlush(iogurte);

        var row = jdbc.queryForMap("SELECT ProductName, ProductQnt, TotalPriceOnProduct FROM Produtos WHERE Id='pr-ct-val'");
        assertThat(row.get("ProductName")).isEqualTo("Iogurte Grego Açaí 170g Renomeado");
        assertThat((BigDecimal) row.get("ProductQnt")).isEqualByComparingTo("12.0000");
        assertThat((BigDecimal) row.get("TotalPriceOnProduct")).isEqualByComparingTo("41.48");
    }
}

