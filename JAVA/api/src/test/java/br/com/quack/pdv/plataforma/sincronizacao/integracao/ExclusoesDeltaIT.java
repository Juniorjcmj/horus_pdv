package br.com.quack.pdv.plataforma.sincronizacao.integracao;

import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.EMPRESA;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.esperar;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.SincronizacaoIT.contar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.quack.pdv.plataforma.sincronizacao.carga.AtivacaoNuvem;
import br.com.quack.pdv.plataforma.sincronizacao.carga.CargaDelta;
import br.com.quack.pdv.plataforma.sincronizacao.carga.LeitorCargaNuvem;
import br.com.quack.pdv.plataforma.sincronizacao.nuvem.DetectorNuvem;
import br.com.quack.pdv.plataforma.sincronizacao.nuvem.ServidorLoja;
import br.com.quack.pdv.shared.NegocioException;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Pendencias do grupo 3 resolvidas com o espelho: o delta da carga e a ativacao so tratam exclusoes cuja empresa a nuvem
 * conhece (nao vazam a chave de linha de outra empresa e nao repetem o delta a toa).
 */
class ExclusoesDeltaIT {

    static AmbienteSync amb;
    static JdbcTemplate loja;
    static JdbcTemplate nuvem;
    static LeitorCargaNuvem leitor;
    static DetectorNuvem detector;

    @BeforeAll
    static void subir() {
        amb = AmbienteSync.iniciar("del");
        loja = amb.jdbcLoja;
        nuvem = amb.jdbcNuvem;
        leitor = amb.nuvem.getBean(LeitorCargaNuvem.class);
        detector = amb.nuvem.getBean(DetectorNuvem.class);
        amb.esperarConvergencia(60_000);
    }

    @AfterAll
    static void descer() {
        if (amb != null) amb.close();
    }

    static List<List<String>> exclusoesDeCategorias(CargaDelta d) {
        return d.blocos().stream().filter(b -> b.tabela().equals("Categorias")).flatMap(b -> b.exclusoes().stream()).toList();
    }

    /** Categoria criada pela loja, ja na nuvem e conhecida no espelho; o detector fica pausado para o teste conduzir. */
    static void categoriaConhecida(String id) {
        loja.update("INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (?, ?, ?, NULL, 90, 1)", id, EMPRESA, "Nome " + id);
        esperar("categoria na nuvem", 30_000, () -> contar(nuvem, "SELECT COUNT(*) FROM Categorias WHERE Id = ?", id) == 1);
        amb.esperarConvergencia(60_000);
        detector.setPausado(true);
    }

    static String outraEmpresa() {
        return nuvem.queryForObject("SELECT TOP 1 Id FROM Empresas WHERE Id <> ?", String.class, EMPRESA);
    }

    @Test
    void deltaNaoVazaExclusaoDeOutraEmpresaMasTrazAConhecida() {
        categoriaConhecida("cat-del-ok");
        try {
            String outra = outraEmpresa();
            long desde = leitor.versaoAtual();
            nuvem.update("INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES ('cat-outra', ?, 'Outra', NULL, 91, 1)", outra);
            nuvem.update("DELETE FROM Categorias WHERE Id = 'cat-outra'");
            assertThat(exclusoesDeCategorias(leitor.delta(EMPRESA, desde))).as("chave de outra empresa nao sai").isEmpty();

            nuvem.update("DELETE FROM Categorias WHERE Id = 'cat-del-ok'");
            assertThat(exclusoesDeCategorias(leitor.delta(EMPRESA, desde))).as("a excluida da empresa, conhecida pelo espelho, sai")
                    .containsExactly(List.of("cat-del-ok"));
        } finally {
            detector.setPausado(false);
        }
    }

    @Test
    void ativarIgnoraExclusaoDesconhecidaEExigeRepetirSoParaAConhecida() {
        categoriaConhecida("cat-del-2");
        try {
            AtivacaoNuvem ativacao = amb.nuvem.getBean(AtivacaoNuvem.class);
            ServidorLoja servidor = new ServidorLoja("srv-it", EMPRESA);
            long aplicado = nuvem.queryForObject("SELECT AplicadoAte FROM SyncEstadoNuvem WHERE CompanyId = ?", Long.class, EMPRESA);
            long desde = leitor.versaoAtual();
            nuvem.update("INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES ('cat-outra-2', ?, 'Outra', NULL, 92, 1)", outraEmpresa());
            nuvem.update("DELETE FROM Categorias WHERE Id = 'cat-outra-2'");
            assertThat(ativacao.ativar(servidor, desde, aplicado).aplicadoAte()).isEqualTo(aplicado); // sem 409

            long desde2 = leitor.versaoAtual();
            nuvem.update("DELETE FROM Categorias WHERE Id = 'cat-del-2'");
            assertThatThrownBy(() -> ativacao.ativar(servidor, desde2, aplicado)).isInstanceOf(NegocioException.class);
        } finally {
            detector.setPausado(false);
        }
    }
}
