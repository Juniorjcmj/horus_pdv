package br.com.quack.pdv.estoque.inventario.domain;

import java.io.Serializable;
import java.util.Objects;

public class InventarioItemId implements Serializable {
    public String inventarioId;
    public String produtoId;

    public InventarioItemId() {}

    public InventarioItemId(String inventarioId, String produtoId) {
        this.inventarioId = inventarioId;
        this.produtoId = produtoId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InventarioItemId that)) return false;
        return Objects.equals(inventarioId, that.inventarioId) && Objects.equals(produtoId, that.produtoId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(inventarioId, produtoId);
    }
}

