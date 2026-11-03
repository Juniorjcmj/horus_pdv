package br.com.quack.pdv.venda.caixa.app;

/** Entradas das operacoes de caixa (AbrirCaixaRequest, FecharCaixaRequest e RegistrarMovimentoCaixaRequest). */
public final class CaixaComandos {

    private CaixaComandos() {}

    public record Abrir(String eventId, String payloadHash, String openingAmount, String clientSessionId,
                        String openedAt) {}

    public record Fechar(String eventId, String payloadHash, String closingAmount, String note,
                         String differenceReason, String clientSessionId, String closedAt) {}

    public record Movimento(String eventId, String payloadHash, String tipo, String valor, String motivo) {}
}
