package br.com.quack.pdv.estoque.inventario.web;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Corpos das rotas de /api/Inventario (mesmos nomes e padroes do InventarioRequests.cs). */
public final class InventarioRequests {

    private InventarioRequests() {}

    public static class Abrir {
        public String descricao;
        public String escopo = "loja";
        public List<String> categoriaIds;
        public List<String> produtoIds;
        public String terminal;
    }

    public static class Contagem {
        public String produtoId;
        public String codigo;
        public BigDecimal quantidade = BigDecimal.ZERO;
        public String local;
        public String terminal;
    }

    public static class Anular {
        public String motivo = "";
    }

    public static class Recontar {
        public List<String> produtoIds = new ArrayList<>();
    }

    public static class Aplicar {
        public String supervisorId = "";
        public String supervisorPassword = "";
        public String justificativa = "";
        public boolean zerarNaoContados;
        public String terminal;
    }

    public static class Cancelar {
        public String motivo = "";
        public String terminal;
    }
}
