package br.com.quack.pdv.plataforma.sincronizacao.integracao;

import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.EMPRESA;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.esperar;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.SincronizacaoIT.contar;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.SincronizacaoIT.vender;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.plataforma.sincronizacao.envio.EnvioSincronizacao;
import br.com.quack.pdv.plataforma.sincronizacao.nuvem.DetectorNuvem;
import br.com.quack.pdv.plataforma.sincronizacao.tracking.ChangeTrackingAdmin;
import br.com.quack.pdv.venda.registro.app.VendaRegistroService;
import br.com.quack.pdv.venda.registro.domain.VendaRegistroResultado;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Quarentena "linha da nuvem sobrescrita na ressincronizacao completa" (D7, pendencia do grupo 4): a nuvem editou uma
 * linha de tabela LOJA e o detector ainda nao tinha passado quando o retrato completo da loja chegou. A linha da nuvem
 * e guardada com origem {@code ressincronizacao} antes de ser sobrescrita, e o alerta de quarentena e registrado.
 */
class ResincronizacaoQuarentenaIT {

    static AmbienteSync amb;

    @BeforeAll
    static void subir() {
        amb = AmbienteSync.iniciar("rq");
        SincronizacaoIT.vendas = amb.loja.getBean(VendaRegistroService.class);
        amb.jdbcLoja.update("UPDATE Produtos SET ProductQnt = 1000 WHERE CompanyId = ? AND ProductCode IN ('CT001','CT002')", EMPRESA);
        amb.esperarConvergencia(60_000);
    }

    @AfterAll
    static void descer() {
        if (amb != null) amb.close();
    }

    @Test
    void linhaDaNuvemSobrescritaNoRetratoCompletoVaiParaAQuarentenaComOrigemRessincronizacao() {
        JdbcTemplate loja = amb.jdbcLoja;
        JdbcTemplate nuvem = amb.jdbcNuvem;
        VendaRegistroResultado venda = vender("CT001", "1", "3", "3,00");
        esperar("venda na nuvem", 60_000, () -> contar(nuvem, "SELECT COUNT(*) FROM Vendas WHERE Id = ?", venda.vendaId()) == 1);
        amb.esperarConvergencia(30_000);
        String nomeDaLoja = loja.queryForObject("SELECT CustomerName FROM Vendas WHERE Id = ?", String.class, venda.vendaId());
        long versaoDe = loja.queryForObject("SELECT VersaoDe FROM SyncEstado WHERE Id = 1", Long.class);

        // o detector ainda nao viu a edicao da nuvem; a loja fica offline e perde a retencao
        var detector = amb.nuvem.getBean(DetectorNuvem.class);
        detector.setPausado(true);
        amb.cliente().setSemInternet(true);
        nuvem.update("UPDATE Vendas SET CustomerName = 'EDITADO NA NUVEM' WHERE Id = ?", venda.vendaId());
        loja.update("INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES ('cat-rq', ?, 'Rq', NULL, 94, 1)", EMPRESA);

        var admin = amb.loja.getBean(ChangeTrackingAdmin.class);
        var envio = amb.loja.getBean(EnvioSincronizacao.class);
        synchronized (envio) {
            admin.desligarTudo();
            admin.ligarTudo(ChangeTrackingAdmin.RETENCAO_LOJA_DIAS);
        }
        assertThat(loja.queryForObject("SELECT CHANGE_TRACKING_MIN_VALID_VERSION(OBJECT_ID(N'dbo.Vendas'))", Long.class))
                .isGreaterThan(versaoDe);

        amb.cliente().setSemInternet(false);
        amb.zerarBackoff();
        esperar("retrato completo aplicado", 120_000, () -> contar(nuvem, "SELECT COUNT(*) FROM Categorias WHERE Id = 'cat-rq'") == 1);
        amb.esperarConvergencia(60_000);

        assertThat(nuvem.queryForObject("SELECT CustomerName FROM Vendas WHERE Id = ?", String.class, venda.vendaId()))
                .as("a loja e a autoridade: o retrato sobrescreve").isEqualTo(nomeDaLoja);
        Map<String, Object> q = AmbienteSync.linha(nuvem, "SELECT Origem, Motivo, LinhaJson, ResolvidoEm FROM SyncQuarentena "
                + "WHERE CompanyId = ? AND Tabela = 'Vendas' AND Chave = ? AND Origem = 'ressincronizacao'", EMPRESA, venda.vendaId());
        assertThat(q).as("entrada da quarentena").isNotEmpty();
        assertThat((String) q.get("Motivo")).startsWith("Linha da nuvem sobrescrita na ressincronizacao completa");
        assertThat((String) q.get("LinhaJson")).contains("EDITADO NA NUVEM");
        assertThat(q.get("ResolvidoEm")).isNull();
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM SyncAlertas WHERE Motivo = 'quarentena' AND CompanyId = ?", EMPRESA)).isEqualTo(1);

        detector.setPausado(false);
        amb.esperarConvergencia(60_000);
        assertThat(amb.diferencas()).isEmpty();
        assertThat(loja.queryForObject("SELECT PausadaMotivo FROM SyncEstado WHERE Id = 1", String.class)).isNull();
    }
}
