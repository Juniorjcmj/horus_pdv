package br.com.quack.pdv.plataforma.sincronizacao.catalogo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** Le o esquema do banco corrente pelas views de catalogo do SQL Server (so schema dbo). */
public class EsquemaJdbc implements EsquemaBanco {

    private final JdbcTemplate jdbc;

    public EsquemaJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Set<String>> colunas() {
        Map<String, Set<String>> mapa = new LinkedHashMap<>();
        jdbc.query("""
                SELECT t.name, c.name FROM sys.tables t JOIN sys.columns c ON c.object_id = t.object_id
                 WHERE SCHEMA_NAME(t.schema_id) = 'dbo' ORDER BY t.name, c.column_id
                """, rs -> {
            mapa.computeIfAbsent(rs.getString(1), k -> new LinkedHashSet<>()).add(rs.getString(2));
        });
        return mapa;
    }

    @Override
    public Map<String, List<String>> chavesPrimarias() {
        Map<String, List<String>> mapa = new LinkedHashMap<>();
        jdbc.query("""
                SELECT t.name, c.name
                  FROM sys.indexes i
                  JOIN sys.tables t ON t.object_id = i.object_id
                  JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
                  JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
                 WHERE i.is_primary_key = 1 AND SCHEMA_NAME(t.schema_id) = 'dbo'
                 ORDER BY t.name, ic.key_ordinal
                """, rs -> {
            mapa.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
        });
        return mapa;
    }

    @Override
    public List<String[]> chavesEstrangeiras() {
        return jdbc.query("""
                SELECT OBJECT_NAME(fk.parent_object_id), OBJECT_NAME(fk.referenced_object_id)
                  FROM sys.foreign_keys fk
                 WHERE fk.parent_object_id <> fk.referenced_object_id
                """, (rs, i) -> new String[] {rs.getString(1), rs.getString(2)});
    }
}
