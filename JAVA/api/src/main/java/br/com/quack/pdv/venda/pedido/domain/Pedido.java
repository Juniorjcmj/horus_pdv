package br.com.quack.pdv.venda.pedido.domain;

import br.com.quack.pdv.venda.registro.repo.VendaRepository.PedidoItem;
import java.time.OffsetDateTime;
import java.util.List;

/** Pedido lido do banco (PedidoAD). Status: 0 aberto, 1 finalizado, 2 cancelado. */
public record Pedido(String id, String orderNumber, String customerName, String customerCpf, String sellerName,
                     int status, OffsetDateTime createdAt, List<PedidoItem> itens) {

    public static final int ABERTO = 0;
    public static final int FINALIZADO = 1;
    public static final int CANCELADO = 2;

    public Pedido comItens(List<PedidoItem> novos) {
        return new Pedido(id, orderNumber, customerName, customerCpf, sellerName, status, createdAt, novos);
    }
}
