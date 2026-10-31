package br.com.quack.caixa.domain;

import static br.com.quack.caixa.domain.TestData.product;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class BalancaBarcodeTest {

    /** Completa os 12 primeiros dígitos com o DV EAN-13. */
    static String ean(String first12) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int d = first12.charAt(i) - '0';
            sum += i % 2 == 0 ? d : d * 3;
        }
        return first12 + ((10 - sum % 10) % 10);
    }

    @Test
    void dvInvalidoRetornaNull() {
        String ok = ean("200015004520");
        String bad = ok.substring(0, 12) + ((ok.charAt(12) - '0' + 1) % 10);
        assertNull(BalancaBarcode.parse(bad, List.of()));
        assertNull(BalancaBarcode.parse("1234567890123", List.of()));
        assertNull(BalancaBarcode.parse("abc", null));
    }

    @Test
    void plu4DigitosModoPreco() {
        // 2 + 2691 + 0000685 (R$ 6,85)
        String code = ean("226910000685");
        Product p = product("2691", "13.70", "100", "KG");
        BalancaBarcode.Result r = BalancaBarcode.parse(code, List.of(p));
        assertNotNull(r);
        assertEquals("2691", r.productCode());
        assertEquals(BalancaBarcode.Mode.PRICE, r.mode());
        assertEquals(new BigDecimal("6.85"), r.totalPrice());
        assertEquals(new BigDecimal("0.500"), r.weightKg());
    }

    @Test
    void plu5DigitosModoPeso() {
        // 2 + 00015 + 00452 (452 g) + 0 ; preço alto torna o modo preço implausível? usa preço 0 para forçar peso
        String code = ean("200015004520");
        Product p = product("00015", "0", "100", "KG");
        BalancaBarcode.Result r = BalancaBarcode.parse(code, List.of(p));
        assertNotNull(r);
        assertEquals(BalancaBarcode.Mode.WEIGHT, r.mode());
        assertEquals(new BigDecimal("0.452"), r.weightKg());
    }

    @Test
    void fallbackSemProdutos() {
        // posições 5..7 = 000 -> 4 dígitos de código + preço total
        String preco = ean("212340001250");
        BalancaBarcode.Result a = BalancaBarcode.parse(preco, List.of());
        assertNotNull(a);
        assertEquals("1234", a.productCode());
        assertEquals(BalancaBarcode.Mode.PRICE, a.mode());
        assertEquals(new BigDecimal("12.50"), a.totalPrice());

        String peso = ean("200015004520");
        BalancaBarcode.Result b = BalancaBarcode.parse(peso, null);
        assertNotNull(b);
        assertEquals("00015", b.productCode());
        assertEquals(new BigDecimal("0.452"), b.weightKg());
        assertEquals(BalancaBarcode.Mode.WEIGHT, b.mode());
    }
}
