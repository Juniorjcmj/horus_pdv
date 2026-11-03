package br.com.quack.estoque.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvExportTest {
    @TempDir
    Path dir;

    @Test
    void separadorPontoEVirgulaEValoresBrasileiros() {
        String csv = Csv.build(List.of("Código", "Produto", "Faturamento", "Participação"),
                List.of(List.of("1001", "Café Pilão", Fmt.dec2(new BigDecimal("1234.5")), "61,54%"),
                        List.of("2002", "Refri; \"lata\"", Fmt.dec2(new BigDecimal("5")), "0,4%")));
        String[] linhas = csv.split("\r\n");
        assertEquals("Código;Produto;Faturamento;Participação", linhas[0]);
        assertEquals("1001;Café Pilão;1.234,50;61,54%", linhas[1]);
        assertEquals("2002;\"Refri; \"\"lata\"\"\";5,00;0,4%", linhas[2]);
    }

    @Test
    void negativosFicamEFormulasSaoNeutralizadas() {
        assertEquals("-16,00", Csv.campo("-16,00"));
        assertEquals("'=SOMA(A1)", Csv.campo("=SOMA(A1)"));
        assertEquals("'@cmd", Csv.campo("@cmd"));
        assertEquals("'-cmd", Csv.campo("-cmd"));
        assertEquals("", Csv.campo(null));
    }

    @Test
    void arquivoUtf8ComBom() throws Exception {
        Path f = dir.resolve("curva.csv");
        Csv.save(f, List.of("Produto", "Valor"), List.of(List.of("Açúcar", "4,50")));
        byte[] bytes = Files.readAllBytes(f);
        assertEquals((byte) 0xEF, bytes[0]);
        assertEquals((byte) 0xBB, bytes[1]);
        assertEquals((byte) 0xBF, bytes[2]);
        String texto = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertEquals("Produto;Valor\r\nAçúcar;4,50\r\n", texto);
    }

    @Test
    void formatosBrasileiros() {
        assertEquals("R$ 1.234,56", Fmt.money(new BigDecimal("1234.56")));
        assertEquals("1.234,56", Fmt.dec2(new BigDecimal("1234.56")));
        assertEquals("0,452", Fmt.qty(new BigDecimal("0.452")));
        assertEquals("12", Fmt.qty(new BigDecimal("12.000")));
        assertEquals("1234,5", Fmt.qtyApi(new BigDecimal("1234.50")));
        assertEquals("12,90", Fmt.moneyApi(new BigDecimal("12.9")));
        assertEquals("0,4167", Fmt.cost4Api(new BigDecimal("0.41666")));
        assertEquals(0, new BigDecimal("1234.56").compareTo(Fmt.parse("1.234,56")));
        assertEquals("31/10/2026 14:20", Fmt.dateTime("2026-10-31T14:20:00-03:00"));
        assertEquals("31/10/2026 11:20", Fmt.dateTimeUtc("2026-10-31T14:20:00"));
    }
}
