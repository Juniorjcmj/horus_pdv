package br.com.quack.caixa.domain;

public enum PaymentType {
    DINHEIRO("dinheiro", "Dinheiro"),
    PIX("pix", "PIX"),
    DEBITO("debito", "Cartão Débito"),
    CREDITO("credito", "Cartão Crédito"),
    FIADO("fiado", "Fiado / A Prazo");

    private final String wire;
    private final String label;

    PaymentType(String wire, String label) {
        this.wire = wire;
        this.label = label;
    }

    public String wire() {
        return wire;
    }

    public String label() {
        return label;
    }

    public static PaymentType fromWire(String w) {
        for (PaymentType t : values()) {
            if (t.wire.equalsIgnoreCase(w)) {
                return t;
            }
        }
        return DINHEIRO;
    }
}
