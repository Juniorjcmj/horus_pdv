package br.com.quack.pdv.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public final class Dinheiro {
    private Dinheiro() {}

    public static String formatar(BigDecimal valor) {
        DecimalFormat formato = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("pt-BR")));
        formato.setRoundingMode(RoundingMode.HALF_EVEN);
        return "R$ " + formato.format(valor);
    }
}
