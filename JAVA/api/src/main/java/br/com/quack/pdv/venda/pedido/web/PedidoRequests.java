package br.com.quack.pdv.venda.pedido.web;

import br.com.quack.pdv.venda.registro.web.VendaRequests;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Corpos de /api/Pedido (PedidoRequest.cs). Null explicito num campo nao anulavel e recusado como o ASP.NET. */
public final class PedidoRequests {

    private PedidoRequests() {}

    public static class Criar {
        public String customerName = "";
        public String customerCpf = "";
        public List<VendaRequests.Item> items = new ArrayList<>();

        public Map<String, String> camposObrigatoriosAusentes() {
            Map<String, String> m = new LinkedHashMap<>();
            if (customerName == null) m.put("CustomerName", "The CustomerName field is required.");
            if (customerCpf == null) m.put("CustomerCpf", "The CustomerCpf field is required.");
            if (items == null) m.put("Items", "The Items field is required.");
            return m;
        }
    }

    public static class Finalizar {
        public String paymentType = "";
        public List<VendaRequests.Pagamento> payments = new ArrayList<>();

        public Map<String, String> camposObrigatoriosAusentes() {
            Map<String, String> m = new LinkedHashMap<>();
            if (paymentType == null) m.put("PaymentType", "The PaymentType field is required.");
            if (payments == null) m.put("Payments", "The Payments field is required.");
            return m;
        }
    }
}
