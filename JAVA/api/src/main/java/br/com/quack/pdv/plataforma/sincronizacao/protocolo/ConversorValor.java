package br.com.quack.pdv.plataforma.sincronizacao.protocolo;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Base64;

/** Codifica e decodifica valores de coluna para o fio (texto), preservando escala decimal, data/hora e binario. */
public final class ConversorValor {

    /** microsoft.sql.Types.DATETIMEOFFSET. */
    public static final int SQL_DATETIMEOFFSET = -155;
    /** microsoft.sql.Types.GUID. */
    public static final int SQL_GUID = -145;

    private ConversorValor() {}

    public static TipoColuna tipoDeJdbc(int sqlType, String nomeDoTipo) {
        switch (sqlType) {
            case Types.BIGINT, Types.INTEGER, Types.SMALLINT, Types.TINYINT: return TipoColuna.INTEIRO;
            case Types.BIT, Types.BOOLEAN: return TipoColuna.BOOLEANO;
            case Types.DECIMAL, Types.NUMERIC: return TipoColuna.DECIMAL;
            case Types.FLOAT, Types.DOUBLE, Types.REAL: return TipoColuna.REAL;
            case Types.DATE: return TipoColuna.DATA;
            case Types.TIME: return TipoColuna.HORA;
            case Types.TIMESTAMP: return TipoColuna.DATAHORA;
            case SQL_DATETIMEOFFSET: return TipoColuna.DATAHORA_OFFSET;
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY: return TipoColuna.BINARIO;
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                 Types.CLOB, Types.NCLOB, SQL_GUID: return TipoColuna.TEXTO;
            default: throw new IllegalArgumentException("Tipo SQL nao suportado na sincronizacao: " + nomeDoTipo);
        }
    }

    /** Valor lido do JDBC (ja no tipo Java certo para o tipo) para texto. */
    public static String codificar(Object v, TipoColuna tipo) {
        if (v == null) return null;
        return switch (tipo) {
            case TEXTO -> v.toString();
            case INTEIRO -> Long.toString(((Number) v).longValue());
            case DECIMAL -> ((BigDecimal) v).toPlainString();
            case REAL -> Double.toString(((Number) v).doubleValue());
            case BOOLEANO -> ((Boolean) v) ? "1" : "0";
            case DATA, HORA, DATAHORA, DATAHORA_OFFSET -> v.toString();
            case BINARIO -> Base64.getEncoder().encodeToString((byte[]) v);
        };
    }

    /** Texto do fio para o objeto Java que o JDBC aceita como parametro. */
    public static Object decodificar(String s, TipoColuna tipo) {
        if (s == null) return null;
        return switch (tipo) {
            case TEXTO -> s;
            case INTEIRO -> Long.valueOf(s);
            case DECIMAL -> new BigDecimal(s);
            case REAL -> Double.valueOf(s);
            case BOOLEANO -> "1".equals(s) || "true".equalsIgnoreCase(s);
            case DATA -> LocalDate.parse(s);
            case HORA -> LocalTime.parse(s);
            case DATAHORA -> LocalDateTime.parse(s);
            case DATAHORA_OFFSET -> OffsetDateTime.parse(s);
            case BINARIO -> Base64.getDecoder().decode(s);
        };
    }

    /** Liga um valor decodificado ao parametro {@code i} (nulo com o tipo SQL certo da coluna). */
    public static void bind(PreparedStatement ps, int i, Object v, TipoColuna tipo) throws SQLException {
        if (v == null) ps.setNull(i, tipoNulo(tipo)); else ps.setObject(i, v);
    }

    public static int tipoNulo(TipoColuna t) {
        return switch (t) {
            case TEXTO -> Types.NVARCHAR;
            case INTEIRO -> Types.BIGINT;
            case DECIMAL -> Types.DECIMAL;
            case REAL -> Types.DOUBLE;
            case BOOLEANO -> Types.BIT;
            case DATA -> Types.DATE;
            case HORA -> Types.TIME;
            case DATAHORA -> Types.TIMESTAMP;
            case DATAHORA_OFFSET -> SQL_DATETIMEOFFSET;
            case BINARIO -> Types.VARBINARY;
        };
    }
}
