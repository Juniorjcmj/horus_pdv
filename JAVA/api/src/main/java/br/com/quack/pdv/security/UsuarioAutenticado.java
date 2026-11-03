package br.com.quack.pdv.security;

import java.util.List;

/** Usuario da requisicao (equivale ao AuthenticatedUser da API .NET). */
public record UsuarioAutenticado(
        String id,
        String companyId,
        String sessionId,
        String name,
        String email,
        String role,
        List<String> perfisAdicionais) {

    public UsuarioAutenticado {
        perfisAdicionais = perfisAdicionais == null ? List.of() : List.copyOf(perfisAdicionais);
    }

    public UsuarioAutenticado comPerfisAdicionais(List<String> perfis) {
        return new UsuarioAutenticado(id, companyId, sessionId, name, email, role, perfis);
    }

    /** O usuario tem este perfil como principal ou adicional (sem diferenciar maiusculas). */
    public boolean temPerfil(String perfil) {
        if (role != null && role.equalsIgnoreCase(perfil)) return true;
        return perfisAdicionais.stream().anyMatch(p -> p.equalsIgnoreCase(perfil));
    }

    public boolean temAcessoFinanceiro() {
        return "administrador".equalsIgnoreCase(role) || "financeiro".equalsIgnoreCase(role) || temPerfil("financeiro");
    }
}
