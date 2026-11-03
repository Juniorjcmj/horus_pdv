package br.com.quack.pdv.venda.caixa.domain;

import java.util.Locale;
import java.util.OptionalInt;

/**
 * Enum {@code TipoMovimentoCaixa : byte} do .NET (Reforco = 1, Sangria = 2) com o mesmo {@code Enum.TryParse(ignoreCase)}:
 * aceita o nome sem diferenciar maiusculas, qualquer inteiro de 0 a 255 (mesmo sem nome) e listas separadas por virgula
 * (valores combinados por OU). Um valor sem nome volta como o proprio numero em {@link #nome(int)}.
 */
public final class TipoMovimentoCaixa {

    public static final int REFORCO = 1;
    public static final int SANGRIA = 2;

    private TipoMovimentoCaixa() {}

    public static OptionalInt tentarLer(String texto) {
        if (texto == null) return OptionalInt.empty();
        String t = trim(texto);
        if (t.isEmpty()) return OptionalInt.empty();
        int valor = 0;
        for (String parte : t.split(",", -1)) {
            OptionalInt item = lerItem(trim(parte));
            if (item.isEmpty()) return OptionalInt.empty();
            valor |= item.getAsInt();
        }
        return OptionalInt.of(valor);
    }

    /** ToString() do enum: o nome quando e um valor definido, senao o numero. */
    public static String nome(int tipo) {
        return switch (tipo) {
            case REFORCO -> "Reforco";
            case SANGRIA -> "Sangria";
            default -> Integer.toString(tipo);
        };
    }

    private static OptionalInt lerItem(String item) {
        if (item.isEmpty()) return OptionalInt.empty();
        if ("reforco".equals(item.toLowerCase(Locale.ROOT))) return OptionalInt.of(REFORCO);
        if ("sangria".equals(item.toLowerCase(Locale.ROOT))) return OptionalInt.of(SANGRIA);
        if (item.matches("[+-]?[0-9]+")) {
            try {
                int numero = Integer.parseInt(item.startsWith("+") ? item.substring(1) : item);
                if (numero >= 0 && numero <= 255) return OptionalInt.of(numero);
            } catch (NumberFormatException ignorado) {
                return OptionalInt.empty();
            }
        }
        return OptionalInt.empty();
    }

    private static String trim(String s) {
        return br.com.quack.pdv.venda.registro.HashPayload.trim(s);
    }
}
