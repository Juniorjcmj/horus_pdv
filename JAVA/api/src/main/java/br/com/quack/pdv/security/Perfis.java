package br.com.quack.pdv.security;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Nomes de perfis e normalizacao dos perfis adicionais (igual ao HorusRoles da API .NET). */
public final class Perfis {

    public static final String ADMINISTRADOR = "administrador";
    public static final String GERENTE = "gerente";
    public static final String ATENDENTE = "atendente";
    public static final String CAIXA = "caixa";
    public static final String ESTOQUISTA = "estoquista";
    public static final String FINANCEIRO = "financeiro";

    /** Perfis que podem ser dados como adicionais (lista fechada). */
    private static final Set<String> ADICIONAIS_VALIDOS = Set.of(FINANCEIRO);

    private Perfis() {}

    /** "financeiro, x" (coluna) para so os perfis validos, sem repeticao, em minusculas. */
    public static List<String> parseAdicionais(String bruto) {
        if (bruto == null || bruto.isBlank()) return List.of();
        return normalizarAdicionais(Arrays.stream(bruto.split(",")).filter(s -> !s.isEmpty()).toList());
    }

    public static List<String> normalizarAdicionais(List<String> perfis) {
        if (perfis == null) return List.of();
        return perfis.stream()
                .filter(p -> p != null)
                .map(p -> p.trim().toLowerCase(Locale.ROOT))
                .filter(ADICIONAIS_VALIDOS::contains)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    public static String juntar(List<String> perfis) {
        return String.join(",", normalizarAdicionais(perfis));
    }

    public static boolean ehGerenteOuAdmin(String role) {
        return ADMINISTRADOR.equalsIgnoreCase(role) || GERENTE.equalsIgnoreCase(role);
    }
}
