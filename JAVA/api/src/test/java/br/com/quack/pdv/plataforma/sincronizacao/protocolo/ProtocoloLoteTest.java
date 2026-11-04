package br.com.quack.pdv.plataforma.sincronizacao.protocolo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProtocoloLoteTest {

    @Test
    void cadaTipoDoEsquemaVoltaIgual() {
        assertThat(ida(new BigDecimal("12.3400"), TipoColuna.DECIMAL)).isEqualTo(new BigDecimal("12.3400"));
        assertThat(((BigDecimal) ida(new BigDecimal("0.10"), TipoColuna.DECIMAL)).scale()).isEqualTo(2);
        assertThat(ida(new BigDecimal("0E-7"), TipoColuna.DECIMAL)).isEqualTo(BigDecimal.ZERO.setScale(7));
        assertThat(ida(123456789012L, TipoColuna.INTEIRO)).isEqualTo(123456789012L);
        assertThat(ida((short) 7, TipoColuna.INTEIRO)).isEqualTo(7L);
        assertThat(ida(true, TipoColuna.BOOLEANO)).isEqualTo(true);
        assertThat(ida(false, TipoColuna.BOOLEANO)).isEqualTo(false);
        assertThat(ida(1.5d, TipoColuna.REAL)).isEqualTo(1.5d);
        assertThat(ida("acentuação \"aspas\" \n quebra", TipoColuna.TEXTO)).isEqualTo("acentuação \"aspas\" \n quebra");
        assertThat(ida(LocalDate.of(2026, 11, 4), TipoColuna.DATA)).isEqualTo(LocalDate.of(2026, 11, 4));
        assertThat(ida(LocalTime.of(10, 0), TipoColuna.HORA)).isEqualTo(LocalTime.of(10, 0));
        LocalDateTime dt = LocalDateTime.of(2026, 11, 4, 10, 30, 0, 123_456_700);
        assertThat(ida(dt, TipoColuna.DATAHORA)).isEqualTo(dt);
        assertThat(ida(LocalDateTime.of(2026, 1, 1, 0, 0), TipoColuna.DATAHORA)).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0));
        OffsetDateTime off = OffsetDateTime.parse("2026-11-04T10:30:00.1234567-03:00");
        assertThat(ida(off, TipoColuna.DATAHORA_OFFSET)).isEqualTo(off);
        assertThat((byte[]) ida(new byte[] {0, 1, (byte) 255, 127}, TipoColuna.BINARIO)).containsExactly(0, 1, -1, 127);
        assertThat(ida(null, TipoColuna.TEXTO)).isNull();
        assertThat(ida(null, TipoColuna.BINARIO)).isNull();
    }

    @Test
    void tiposJdbcMapeados() {
        assertThat(ConversorValor.tipoDeJdbc(Types.NVARCHAR, "nvarchar")).isEqualTo(TipoColuna.TEXTO);
        assertThat(ConversorValor.tipoDeJdbc(ConversorValor.SQL_DATETIMEOFFSET, "datetimeoffset"))
                .isEqualTo(TipoColuna.DATAHORA_OFFSET);
        assertThat(ConversorValor.tipoDeJdbc(Types.TIMESTAMP, "datetime2")).isEqualTo(TipoColuna.DATAHORA);
        assertThat(ConversorValor.tipoDeJdbc(Types.VARBINARY, "varbinary")).isEqualTo(TipoColuna.BINARIO);
        assertThatThrownBy(() -> ConversorValor.tipoDeJdbc(Types.STRUCT, "struct"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parteSobreviveAoGzip() {
        BlocoTabela b = new BlocoTabela("Produtos",
                List.of(new ColunaSync("Id", TipoColuna.TEXTO), new ColunaSync("Preco", TipoColuna.DECIMAL)),
                List.of(List.of("p1", "9.90"), java.util.Arrays.asList("p2", null)),
                List.of(new ColunaSync("Id", TipoColuna.TEXTO)), List.of(List.of("p9")));
        ParteLote p = new ParteLote("lote1", "emp", 10, 20, false, 1, true, List.of(b));
        ParteLote volta = SerializadorLote.deGzip(SerializadorLote.paraGzip(p));
        assertThat(volta).isEqualTo(p);
    }

    @Test
    void gzipInvalidoViraErroDeArgumento() {
        assertThatThrownBy(() -> SerializadorLote.deGzip(new byte[] {1, 2, 3}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loteDivididoEmTresPartesComUltimaMarcada() {
        List<ParteLote> partes = new ArrayList<>();
        EmpacotadorPartes e = new EmpacotadorPartes("L", 5, 9, false, 2, Long.MAX_VALUE, partes::add);
        List<ColunaSync> cols = List.of(new ColunaSync("Id", TipoColuna.TEXTO));
        e.adicionarLinha("Categorias", cols, List.of("a"));
        e.adicionarLinha("Categorias", cols, List.of("b"));
        e.adicionarLinha("Produtos", cols, List.of("c"));
        e.adicionarLinha("Produtos", cols, List.of("d"));
        e.adicionarExclusao("Vendas", cols, List.of("v1"));
        e.concluir();

        assertThat(partes).hasSize(3);
        assertThat(partes).extracting(ParteLote::parte).containsExactly(1, 2, 3);
        assertThat(partes).extracting(ParteLote::ultima).containsExactly(false, false, true);
        assertThat(partes).allSatisfy(p -> {
            assertThat(p.loteId()).isEqualTo("L");
            assertThat(p.de()).isEqualTo(5);
            assertThat(p.ate()).isEqualTo(9);
        });
        assertThat(partes.get(0).blocos()).extracting(BlocoTabela::tabela).containsExactly("Categorias");
        assertThat(partes.get(1).blocos().get(0).linhas()).containsExactly(List.of("c"), List.of("d"));
        assertThat(partes.get(2).blocos().get(0).exclusoes()).containsExactly(List.of("v1"));
        // cada parte cabe no fio e volta igual
        for (ParteLote p : partes) assertThat(SerializadorLote.deGzip(SerializadorLote.paraGzip(p))).isEqualTo(p);
    }

    @Test
    void limiteDeBytesDivideEUmaLinhaGrandeFicaSozinha() {
        List<ParteLote> partes = new ArrayList<>();
        EmpacotadorPartes e = new EmpacotadorPartes("L", 0, 1, false, 1000, 100, partes::add);
        List<ColunaSync> cols = List.of(new ColunaSync("X", TipoColuna.TEXTO));
        e.adicionarLinha("T", cols, List.of("x".repeat(500)));
        e.adicionarLinha("T", cols, List.of("y"));
        e.concluir();
        assertThat(partes).hasSize(2);
    }

    @Test
    void loteVazioGeraUmaParteVaziaUltima() {
        List<ParteLote> partes = new ArrayList<>();
        EmpacotadorPartes e = new EmpacotadorPartes("L", 0, 1, true, 10, 1000, partes::add);
        assertThat(e.vazio()).isTrue();
        e.concluir();
        assertThat(partes).hasSize(1);
        assertThat(partes.getFirst().ultima()).isTrue();
        assertThat(partes.getFirst().blocos()).isEmpty();
    }

    private static Object ida(Object valor, TipoColuna tipo) {
        String texto = ConversorValor.codificar(valor, tipo);
        // passa pelo JSON de verdade
        BlocoTabela b = new BlocoTabela("T", List.of(new ColunaSync("c", tipo)),
                List.of(java.util.Collections.singletonList(texto)), List.of(), List.of());
        ParteLote p = SerializadorLote.deGzip(SerializadorLote.paraGzip(
                new ParteLote("l", "e", 0, 1, false, 1, true, List.of(b))));
        return ConversorValor.decodificar(p.blocos().get(0).linhas().get(0).get(0), tipo);
    }
}
