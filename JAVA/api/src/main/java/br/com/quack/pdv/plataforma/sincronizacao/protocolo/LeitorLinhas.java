package br.com.quack.pdv.plataforma.sincronizacao.protocolo;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Le colunas de um {@link ResultSet} como texto do fio (tipos e valores), comum ao envio e a carga inicial. */
public final class LeitorLinhas {

    private LeitorLinhas() {}

    /** Nome e tipo (pelo JDBC) de {@code nomes.size()} colunas consecutivas a partir de {@code offset}. */
    public static List<ColunaSync> tipos(ResultSetMetaData md, List<String> nomes, int offset) throws SQLException {
        List<ColunaSync> lista = new ArrayList<>();
        for (int i = 0; i < nomes.size(); i++) {
            int idx = offset + i + 1;
            lista.add(new ColunaSync(nomes.get(i), ConversorValor.tipoDeJdbc(md.getColumnType(idx), md.getColumnTypeName(idx))));
        }
        return List.copyOf(lista);
    }

    /** Valores da linha corrente, a partir da coluna {@code primeiro} (1-based). */
    public static List<String> valores(ResultSet rs, List<ColunaSync> colunas, int primeiro) throws SQLException {
        List<String> v = new ArrayList<>();
        for (int i = 0; i < colunas.size(); i++) v.add(ler(rs, primeiro + i, colunas.get(i).tipo()));
        return v;
    }

    public static String ler(ResultSet rs, int i, TipoColuna tipo) throws SQLException {
        Object valor = switch (tipo) {
            case TEXTO -> rs.getString(i);
            case INTEIRO -> rs.getLong(i);
            case DECIMAL -> rs.getBigDecimal(i);
            case REAL -> rs.getDouble(i);
            case BOOLEANO -> rs.getBoolean(i);
            case DATA -> rs.getObject(i, LocalDate.class);
            case HORA -> rs.getObject(i, LocalTime.class);
            case DATAHORA -> rs.getObject(i, LocalDateTime.class);
            case DATAHORA_OFFSET -> rs.getObject(i, OffsetDateTime.class);
            case BINARIO -> rs.getBytes(i);
        };
        if (rs.wasNull()) return null;
        if (valor instanceof BigDecimal bd) return bd.toPlainString();
        return ConversorValor.codificar(valor, tipo);
    }
}
