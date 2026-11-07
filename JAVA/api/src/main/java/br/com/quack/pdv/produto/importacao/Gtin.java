package br.com.quack.pdv.produto.importacao;

/** Validacao de GTIN (EAN/UPC) pelo digito verificador GS1. */
final class Gtin {

    static final String SEM_GTIN = "SEM GTIN";

    private Gtin() {}

    /** Valido = so digitos, 8, 12, 13 ou 14 posicoes e digito verificador correto. */
    static boolean valido(String texto) {
        if (texto == null) {
            return false;
        }
        int n = texto.length();
        if (n != 8 && n != 12 && n != 13 && n != 14) {
            return false;
        }
        int soma = 0;
        for (int i = 0; i < n - 1; i++) {
            char c = texto.charAt(n - 2 - i);
            if (c < '0' || c > '9') {
                return false;
            }
            soma += (c - '0') * (i % 2 == 0 ? 3 : 1);
        }
        char dv = texto.charAt(n - 1);
        if (dv < '0' || dv > '9') {
            return false;
        }
        return (10 - soma % 10) % 10 == dv - '0';
    }
}
