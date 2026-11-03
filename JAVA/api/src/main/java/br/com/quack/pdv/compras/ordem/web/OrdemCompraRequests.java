package br.com.quack.pdv.compras.ordem.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public final class OrdemCompraRequests {

    private OrdemCompraRequests() {}

    public static class Pedido {
        public String supplierId = "";
        public String note;
        @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
        public OffsetDateTime previsaoEntrega;
        public String condicaoPagamento;
        public String formaPagamento;
        public BigDecimal valorFrete = BigDecimal.ZERO;
        public BigDecimal valorDesconto = BigDecimal.ZERO;
        public List<Item> items = new ArrayList<>();
    }

    public static class Item {
        public String productCode = "";
        public BigDecimal quantity = BigDecimal.ZERO;
        public BigDecimal unitCost = BigDecimal.ZERO;
    }

    public static class Receber {
        public List<ItemRecebido> itens = new ArrayList<>();
    }

    public static class ItemRecebido {
        public String productCode = "";
        public BigDecimal quantityReceived = BigDecimal.ZERO;
        @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
        public OffsetDateTime dataValidade;
        public String numeroLote;
    }

    public static class Cancelar {
        public String motivoCancelamento;
    }
}
