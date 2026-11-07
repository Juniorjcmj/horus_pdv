package br.com.quack.pdv.produto.importacao;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Normalizacao de textos da planilha (chaves de cabecalho e de categoria). */
final class Textos {

    private static final Pattern MARCAS = Pattern.compile("\\p{M}+");
    private static final Pattern ESPACOS = Pattern.compile("[\\s\\u00A0]+");

    private Textos() {}

    /** Remove acentos, colapsa espacos e apara as pontas. */
    static String semAcento(String texto) {
        if (texto == null) {
            return "";
        }
        String sem = MARCAS.matcher(Normalizer.normalize(texto, Normalizer.Form.NFD)).replaceAll("");
        return ESPACOS.matcher(sem).replaceAll(" ").trim();
    }

    /** Chave de comparacao de categoria: sem acento, maiusculo, espacos internos colapsados. */
    static String chaveCategoria(String nome) {
        return semAcento(nome).toUpperCase(Locale.ROOT);
    }

    /** Chave de coluna do cabecalho: sem acento, minuscula, espacos normalizados. */
    static String chaveColuna(String nome) {
        return semAcento(nome).toLowerCase(Locale.ROOT);
    }

    static String vazioParaNulo(String texto) {
        if (texto == null) {
            return null;
        }
        String t = texto.strip();
        return t.isEmpty() ? null : t;
    }

    static String cortar(String texto, int max) {
        if (texto == null) {
            return null;
        }
        return texto.length() <= max ? texto : texto.substring(0, max).stripTrailing();
    }
}
