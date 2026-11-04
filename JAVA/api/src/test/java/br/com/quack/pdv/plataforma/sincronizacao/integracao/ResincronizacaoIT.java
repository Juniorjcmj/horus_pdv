package br.com.quack.pdv.plataforma.sincronizacao.integracao;

import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.EMPRESA;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.esperar;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.SincronizacaoIT.contar;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.SincronizacaoIT.vender;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.plataforma.sincronizacao.catalogo.CatalogoSincronizacao;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.TabelaSincronizada;
import br.com.quack.pdv.venda.registro.app.VendaRegistroService;
import br.com.quack.pdv.venda.registro.domain.VendaRegistroResultado;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Tarefa 2.4: loja que passa da retencao do Change Tracking envia o retrato completo; a nuvem substitui a replica e
 * exclui o que nao existe mais na loja. A retencao e reduzida a 1 minuto e a limpeza e forcada (sp_flush_*).
 */
class ResincronizacaoIT {

    static AmbienteSync amb;

    @BeforeAll
    static void subir() {
        amb = AmbienteSync.iniciar("b");
        // SincronizacaoIT.vender usa o servico do outro ambiente: aqui o servico e o desta classe
        SincronizacaoIT.vendas = amb.loja.getBean(VendaRegistroService.class);
        amb.jdbcLoja.update("UPDATE Produtos SET ProductQnt = 1000 WHERE CompanyId = ? AND ProductCode IN ('CT001','CT002')", EMPRESA);
        amb.esperarConvergencia(60_000);
    }

    @AfterAll
    static void descer() {
        if (amb != null) amb.close();
    }

    @Test
    void lojaForaDaRetencaoEnviaORetratoCompletoEAReplicaFecha() {
        JdbcTemplate loja = amb.jdbcLoja;
        JdbcTemplate nuvem = amb.jdbcNuvem;
        VendaRegistroResultado antiga = vender("CT001", "1", "3", "3,00");
        esperar("venda antiga na nuvem", 60_000, () -> contar(nuvem, "SELECT COUNT(*) FROM Vendas WHERE Id = ?", antiga.vendaId()) == 1);
        amb.esperarConvergencia(30_000);
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM VendaPagamentos WHERE VendaId = ?", antiga.vendaId())).isEqualTo(1);
        long versaoDe = loja.queryForObject("SELECT VersaoDe FROM SyncEstado WHERE Id = 1", Long.class);

        // loja sem internet; muda coisas que so o retrato completo consegue levar (a exclusao inclusive)
        amb.cliente().setSemInternet(true);
        VendaRegistroResultado nova = vender("CT002", "2", "3", "6,00");
        loja.update("DELETE FROM VendaPagamentos WHERE VendaId = ?", antiga.vendaId());
        loja.update("UPDATE Produtos SET ProductQnt = ProductQnt + 5 WHERE CompanyId = ? AND ProductCode = 'CT001'", EMPRESA);
        loja.update("INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES ('cat-ret', ?, 'Ret', NULL, 97, 1)", EMPRESA);

        // limpeza manual do tracking: desligar e religar zera o historico e leva a versao minima valida para a frente
        var admin = amb.loja.getBean(br.com.quack.pdv.plataforma.sincronizacao.tracking.ChangeTrackingAdmin.class);
        var envio = amb.loja.getBean(br.com.quack.pdv.plataforma.sincronizacao.envio.EnvioSincronizacao.class);
        synchronized (envio) { // nenhum ciclo de leitura SNAPSHOT durante o DDL
        admin.desligarTudo();
        admin.ligarTudo(br.com.quack.pdv.plataforma.sincronizacao.tracking.ChangeTrackingAdmin.RETENCAO_LOJA_DIAS);
        }
        Long minima = loja.queryForObject("SELECT CHANGE_TRACKING_MIN_VALID_VERSION(OBJECT_ID(N'dbo.Vendas'))", Long.class);
        assertThat(minima).as("versao minima valida depois da limpeza").isGreaterThan(versaoDe);
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM Vendas WHERE Id = ?", nova.vendaId())).isZero();

        amb.cliente().setSemInternet(false);
        amb.zerarBackoff();
        esperar("retrato completo aplicado", 120_000, () ->
                contar(nuvem, "SELECT COUNT(*) FROM Vendas WHERE Id = ?", nova.vendaId()) == 1
                        && contar(nuvem, "SELECT COUNT(*) FROM Categorias WHERE Id = 'cat-ret'") == 1);
        amb.esperarConvergencia(60_000);

        assertThat(contar(nuvem, "SELECT COUNT(*) FROM VendaPagamentos WHERE VendaId = ?", antiga.vendaId()))
                .as("excluida na loja durante a janela perdida").isZero();
        assertThat(amb.diferencas()).isEmpty();
        assertThat(loja.queryForObject("SELECT PausadaMotivo FROM SyncEstado WHERE Id = 1", String.class)).isNull();
    }

    private static String banco(JdbcTemplate j) {
        return "[" + j.queryForObject("SELECT DB_NAME()", String.class) + "]";
    }
}
