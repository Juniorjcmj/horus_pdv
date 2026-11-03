package br.com.quack.pdv.security;

/** Validacao de CNPJ por digitos verificadores (igual ao IsValidCnpj do .NET). */
public final class CnpjValidator {

    private static final int[] F1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
    private static final int[] F2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

    private CnpjValidator() {}

    public static String somenteDigitos(String valor) {
        if (valor == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : valor.toCharArray()) {
            if (Character.isDigit(c)) sb.append(c);
        }
        return sb.toString();
    }

    public static boolean valido(String bruto) {
        String cnpj = somenteDigitos(bruto);
        if (cnpj.length() != 14 || cnpj.chars().allMatch(c -> c == cnpj.charAt(0))) return false;
        String base12 = cnpj.substring(0, 12);
        int d1 = digito(base12, F1);
        int d2 = digito(base12 + d1, F2);
        return cnpj.endsWith("" + d1 + d2);
    }

    private static int digito(String base, int[] fatores) {
        int soma = 0;
        for (int i = 0; i < fatores.length; i++) soma += (base.charAt(i) - '0') * fatores[i];
        int resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }
}
