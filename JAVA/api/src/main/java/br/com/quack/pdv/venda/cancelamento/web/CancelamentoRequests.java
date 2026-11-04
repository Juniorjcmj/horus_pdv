package br.com.quack.pdv.venda.cancelamento.web;

import java.util.ArrayList;
import java.util.List;

/** Corpos de cancelar e corrigir (CancelamentoVendaRequest.cs e CorrecaoVendaRequest.cs). */
public final class CancelamentoRequests {

    private CancelamentoRequests() {}

    public static class Cancelamento {
        public String supervisorId = "";
        public String supervisorPassword = "";
        public String justificativa = "";
        public String terminal;
    }

    public static class Correcao {
        public List<String> produtosParaRemover = new ArrayList<>();
        public String justificativa = "";
        public String supervisorId;
        public String supervisorPassword;
    }
}
