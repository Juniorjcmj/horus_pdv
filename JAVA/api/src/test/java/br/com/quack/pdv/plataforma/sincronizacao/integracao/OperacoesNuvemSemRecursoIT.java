package br.com.quack.pdv.plataforma.sincronizacao.integracao;

import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.EMPRESA;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.esperar;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.SincronizacaoIT.contar;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Loja com jar antigo (nao anuncia {@code operacao-estoque}): a nuvem nao cria comando {@code operacao} e a gravacao
 * continua indo para a quarentena, sem erro de comando na loja (spec "Compatibilidade de versoes").
 */
class OperacoesNuvemSemRecursoIT {

    static AmbienteSync amb;

    @BeforeAll
    static void subir() {
        amb = AmbienteSync.iniciarComExtrasNaLoja("opold", "--quack.sync.recursos=");
        amb.esperarConvergencia(60_000);
    }

    @AfterAll
    static void descer() {
        if (amb != null) amb.close();
    }

    @Test
    void semOCabecalhoARecursosFicaNulaEANotaVaiParaAQuarentena() throws Exception {
        Thread.sleep(4_000); // pelo menos um long-poll de comandos
        assertThat(amb.jdbcNuvem.queryForObject("SELECT Recursos FROM SyncServidores WHERE CompanyId = ?", String.class, EMPRESA))
                .isNull();
        String produto = amb.jdbcNuvem.queryForObject("SELECT Id FROM Produtos WHERE CompanyId = ? AND ProductCode = 'CT001'",
                String.class, EMPRESA);
        BigDecimal ant = amb.jdbcNuvem.queryForObject("SELECT ProductQnt FROM Produtos WHERE Id = ?", BigDecimal.class, produto);
        amb.jdbcNuvem.execute("INSERT INTO EstoqueMovimentacoes (Id, CompanyId, ProdutoId, Tipo, Quantidade, EstoqueAnterior, EstoqueNovo, "
                + "Motivo, UsuarioId, UsuarioNome, CriadoEm, CustoUnitario, OrigemTipo, OrigemId, DepositoId) VALUES ('mov-old', '" + EMPRESA
                + "', '" + produto + "', 'entrada', 1, " + ant.toPlainString() + ", " + ant.add(BigDecimal.ONE).toPlainString()
                + ", N'x', 'u1', N'Painel', SYSUTCDATETIME(), 1, 'nfe', 'x', 'loja')");
        esperar("quarentena da movimentacao", 30_000, () -> contar(amb.jdbcNuvem,
                "SELECT COUNT(*) FROM SyncQuarentena WHERE Tabela = 'EstoqueMovimentacoes' AND Chave = 'mov-old'") > 0);
        assertThat(contar(amb.jdbcNuvem, "SELECT COUNT(*) FROM SyncComandos WHERE Tipo = 'operacao'")).isZero();
    }
}
