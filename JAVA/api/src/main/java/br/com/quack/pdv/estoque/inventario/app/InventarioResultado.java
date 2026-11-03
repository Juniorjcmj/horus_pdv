package br.com.quack.pdv.estoque.inventario.app;

/** Resultado de uma operacao: sucesso com dados ou recusa com codigo estavel (o controller converte em HTTP). */
public record InventarioResultado(boolean sucesso, String codigo, String mensagem, Object dados) {

    public static InventarioResultado ok(Object dados, String mensagem) {
        return new InventarioResultado(true, "OK", mensagem, dados);
    }

    public static InventarioResultado falha(String codigo, String mensagem) {
        return new InventarioResultado(false, codigo, mensagem, null);
    }

    public static InventarioResultado falha(String codigo, String mensagem, Object dados) {
        return new InventarioResultado(false, codigo, mensagem, dados);
    }
}
