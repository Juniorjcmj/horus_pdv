package br.com.quack.pdv.plataforma.sincronizacao.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.BancoIT;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** O catalogo cobre o esquema real do V1 (59 tabelas da .NET), e a ordem respeita sys.foreign_keys. */
@SpringBootTest
class CatalogoSchemaIT extends BancoIT {

    @Autowired JdbcTemplate jdbc;

    @Test
    void catalogoCoerenteComOEsquemaDoFlyway() {
        List<String> problemas = GuardaCatalogo.verificar(
                CatalogoSincronizacao.tabelas(), CatalogoSincronizacao.ordemDeclarada(), new EsquemaJdbc(jdbc));
        assertThat(problemas).isEmpty();
    }

    @Test
    void ordemDeAplicacaoRespeitaTodasAsChavesEstrangeirasDoBanco() {
        List<String> ordem = CatalogoSincronizacao.ordemDeAplicacao().stream().map(TabelaSincronizada::nome).toList();
        List<String[]> fks = jdbc.query(
                "SELECT OBJECT_NAME(parent_object_id), OBJECT_NAME(referenced_object_id) FROM sys.foreign_keys",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)});
        assertThat(fks).isNotEmpty();
        int verificadas = 0;
        for (String[] fk : fks) {
            if (fk[0].equals(fk[1])) continue; // autorreferencia (Categorias): a ordem das linhas resolve na aplicacao
            int filha = ordem.indexOf(fk[0]);
            int mae = ordem.indexOf(fk[1]);
            if (filha < 0 || mae < 0) continue;
            verificadas++;
            assertThat(mae).as(fk[0] + " -> " + fk[1]).isLessThan(filha);
        }
        assertThat(verificadas).isGreaterThan(10);
    }

    @Test
    void sincronizaTodaTabelaDoEsquemaQueNaoEForaESoEssas() {
        List<String> noBanco = jdbc.queryForList("SELECT name FROM sys.tables", String.class);
        for (TabelaSincronizada t : CatalogoSincronizacao.tabelas().values()) {
            assertThat(noBanco).as(t.nome()).contains(t.nome());
        }
        assertThat(CatalogoSincronizacao.ordemDeAplicacao()).allMatch(TabelaSincronizada::sincroniza);
    }
}
