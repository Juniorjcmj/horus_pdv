package br.com.quack.pdv.plataforma.sincronizacao.integracao;

import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.EMPRESA;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.produto.importacao.ImportacaoProdutosService;
import br.com.quack.pdv.produto.importacao.PlanilhaSintetica;
import br.com.quack.pdv.produto.importacao.RelatorioImportacao;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Importacao de produtos no servidor da loja (perfil loja): produtos, categorias e movimentacoes chegam na nuvem pela
 * sincronizacao; a tabela de controle ImportacoesProduto (classe FORA) nao sobe.
 */
class ImportacaoLojaIT {

    static AmbienteSync amb;
    static JdbcTemplate loja;
    static JdbcTemplate nuvem;

    @BeforeAll
    static void subir() {
        amb = AmbienteSync.iniciar("imp");
        loja = amb.jdbcLoja;
        nuvem = amb.jdbcNuvem;
        amb.esperarConvergencia(60_000);
    }

    @AfterAll
    static void descer() {
        if (amb != null) amb.close();
    }

    @Test
    void produtosCategoriasEMovimentacoesChegamNaNuvemEOControleFicaNaLoja() {
        ImportacaoProdutosService servico = amb.loja.getBean(ImportacaoProdutosService.class);

        RelatorioImportacao r = servico.confirmar(EMPRESA, PlanilhaSintetica.amostra(), "usr-imp", "Gerente Importacao");

        assertThat(r.totais().novos()).isEqualTo(36);
        assertThat(loja.queryForObject("SELECT COUNT(*) FROM ImportacoesProduto WHERE CompanyId = ?", Integer.class, EMPRESA))
                .isEqualTo(26);

        amb.esperarConvergencia(180_000);

        assertThat(nuvem.queryForObject(
                        "SELECT COUNT(*) FROM Produtos WHERE CompanyId = ? AND ProductCode LIKE '9[0-9][0-9][0-9]'",
                        Integer.class, EMPRESA))
                .isEqualTo(36);
        assertThat(nuvem.queryForObject(
                        "SELECT ProductQnt FROM Produtos WHERE CompanyId = ? AND ProductCode = '9001'",
                        BigDecimal.class, EMPRESA))
                .isEqualByComparingTo("24");
        assertThat(nuvem.queryForObject(
                        "SELECT COUNT(*) FROM Categorias WHERE CompanyId = ? AND Nome LIKE 'CLASSE TESTE %'",
                        Integer.class, EMPRESA))
                .isEqualTo(2);
        assertThat(nuvem.queryForObject(
                        "SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE CompanyId = ? AND OrigemTipo = 'importacao'",
                        Integer.class, EMPRESA))
                .isEqualTo(26);
        // a tabela de controle e FORA do catalogo: nada dela na nuvem
        // o SQL Server compila o lote inteiro: so conta as linhas se a tabela existir na nuvem
        boolean existe = nuvem.queryForObject(
                "SELECT COUNT(*) FROM sys.tables WHERE name = N'ImportacoesProduto'", Integer.class) > 0;
        Integer naNuvem = existe
                ? nuvem.queryForObject("SELECT COUNT(*) FROM dbo.ImportacoesProduto", Integer.class) : 0;
        assertThat(naNuvem).isZero();
        assertThat(amb.diferencas()).isEmpty();
    }
}
