package br.com.quack.pdv.produto.importacao;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Leitura de numeros no formato brasileiro ({@code R$ 1.234,56}, {@code 0,5}). */
final class NumerosBr {

    private static final Pattern NUMERO = Pattern.compile("^-?\\d+(\\.\\d+)?$");

    private NumerosBr() {}

    /** Valor que nao e numero. */
    static final class NumeroInvalido extends RuntimeException {
        NumeroInvalido(String texto) {
            super(texto, null, false, false);
        }
    }

    /** Celula vazia vale zero; {@code R$}, espacos e ponto de milhar saem; a virgula vira ponto decimal. */
    static BigDecimal ler(String celula) {
        if (celula == null) {
            return BigDecimal.ZERO;
        }
        String t = celula.replace("R$", "").replace(" ", "").replace(" ", "").replace("\t", "");
        if (t.isEmpty()) {
            return BigDecimal.ZERO;
        }
        t = t.replace(".", "").replace(',', '.');
        if (!NUMERO.matcher(t).matches()) {
            throw new NumeroInvalido(celula);
        }
        return new BigDecimal(t);
    }
}
