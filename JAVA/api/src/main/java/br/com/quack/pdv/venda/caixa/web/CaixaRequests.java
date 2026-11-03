package br.com.quack.pdv.venda.caixa.web;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Corpos das rotas de /api/Caixa (CaixaRequest.cs). Valores sao texto pt-BR e os padroes sao os do .NET; um null
 * explicito num campo nao anulavel e recusado como o ASP.NET (campo "... field is required.").
 */
public final class CaixaRequests {

    private CaixaRequests() {}

    public static class Abrir {
        public String eventId;
        public String payloadHash;
        public String openingAmount = "0,00";
        public String clientSessionId;
        public String openedAt;

        public Map<String, String> camposObrigatoriosAusentes() {
            Map<String, String> m = new LinkedHashMap<>();
            if (openingAmount == null) m.put("OpeningAmount", "The OpeningAmount field is required.");
            return m;
        }
    }

    public static class Fechar {
        public String eventId;
        public String payloadHash;
        public String closingAmount = "0,00";
        public String note = "";
        public String differenceReason;
        public String clientSessionId;
        public String closedAt;

        public Map<String, String> camposObrigatoriosAusentes() {
            Map<String, String> m = new LinkedHashMap<>();
            if (closingAmount == null) m.put("ClosingAmount", "The ClosingAmount field is required.");
            if (note == null) m.put("Note", "The Note field is required.");
            return m;
        }
    }

    public static class Movimento {
        public String eventId;
        public String payloadHash;
        public String tipo = "";
        public String valor = "0,00";
        public String motivo = "";

        public Map<String, String> camposObrigatoriosAusentes() {
            Map<String, String> m = new LinkedHashMap<>();
            if (tipo == null) m.put("Tipo", "The Tipo field is required.");
            if (valor == null) m.put("Valor", "The Valor field is required.");
            if (motivo == null) m.put("Motivo", "The Motivo field is required.");
            return m;
        }
    }
}
