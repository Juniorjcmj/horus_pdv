package br.com.quack.estoque.domain;

import java.util.Optional;

/**
 * Perfis do aplicativo (design D9). O servidor continua sendo a autoridade; aqui só decidimos o que mostrar.
 * Caixa, atendente e demais perfis não entram.
 */
public enum Perfil {
    ADMINISTRADOR, GERENTE, ESTOQUISTA;

    public static Optional<Perfil> de(String role) {
        if (role == null) {
            return Optional.empty();
        }
        return switch (role.trim().toLowerCase()) {
            case "administrador" -> Optional.of(ADMINISTRADOR);
            case "gerente" -> Optional.of(GERENTE);
            case "estoquista" -> Optional.of(ESTOQUISTA);
            default -> Optional.empty();
        };
    }

    public boolean gerencia() {
        return this != ESTOQUISTA;
    }

    /** Preço de custo, margem e valores (R$) das divergências. */
    public boolean verCustos() {
        return gerencia();
    }

    public boolean aplicarOuCancelarInventario() {
        return gerencia();
    }

    public boolean cadastrarProdutos() {
        return gerencia();
    }

    public boolean cadastrarCategoriasEFornecedores() {
        return gerencia();
    }

    public boolean estornarEntrada() {
        return gerencia();
    }

    public boolean gerarPedidoCompra() {
        return gerencia();
    }

    /** Curva ABC, parado e perdas (valores). Crítico, validade e reposição (só quantidades) são liberados. */
    public boolean relatoriosDeValor() {
        return gerencia();
    }

    public String rotulo() {
        return switch (this) {
            case ADMINISTRADOR -> "Administrador";
            case GERENTE -> "Gerente";
            case ESTOQUISTA -> "Estoquista";
        };
    }
}
