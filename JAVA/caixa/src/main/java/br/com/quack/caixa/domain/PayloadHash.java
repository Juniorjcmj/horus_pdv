package br.com.quack.caixa.domain;

import br.com.quack.caixa.api.dto.VendaPayload;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** SHA-256 do JSON canônico (mesma ordem de campos de cryptoHash.ts / HorusPayloadHash.cs). */
public final class PayloadHash {
    private PayloadHash() {
    }

    public static String canonicalJson(VendaPayload p) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"customerName\":").append(str(p.customerName())).append(',');
        sb.append("\"customerCpf\":").append(str(p.customerCpf())).append(',');
        sb.append("\"paymentType\":").append(str(p.paymentType())).append(',');
        sb.append("\"totalAmount\":").append(Money.jsNumber(Money.parseBrOrZero(p.totalAmount()))).append(',');
        sb.append("\"operatorName\":").append(str(p.operatorName())).append(',');
        sb.append("\"items\":[");
        List<VendaPayload.Item> items = p.items().stream()
                .sorted(Comparator.comparing((VendaPayload.Item i) -> nz(i.productCode()),
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
        for (int i = 0; i < items.size(); i++) {
            VendaPayload.Item it = items.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{');
            sb.append("\"productCode\":").append(str(it.productCode())).append(',');
            sb.append("\"quantity\":").append(Money.jsNumber(Money.round4(nzd(it.quantity())))).append(',');
            sb.append("\"unitPrice\":").append(Money.jsNumber(Money.round2(nzd(it.unitPrice())))).append(',');
            sb.append("\"desconto\":").append(Money.jsNumber(Money.round2(nzd(it.desconto())))).append(',');
            sb.append("\"promocaoId\":").append(str(it.promocaoId()));
            sb.append('}');
        }
        sb.append("],\"payments\":[");
        List<VendaPayload.Payment> pays = p.payments().stream()
                .sorted(Comparator.comparing((VendaPayload.Payment x) -> nz(x.paymentType()),
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
        for (int i = 0; i < pays.size(); i++) {
            VendaPayload.Payment pay = pays.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{');
            sb.append("\"paymentType\":").append(str(pay.paymentType())).append(',');
            sb.append("\"amount\":").append(Money.jsNumber(Money.round2(nzd(pay.amount())))).append(',');
            sb.append("\"cashGiven\":").append(Money.jsNumber(Money.round2(nzd(pay.cashGiven())))).append(',');
            sb.append("\"changeAmount\":").append(Money.jsNumber(Money.round2(nzd(pay.changeAmount()))));
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    public static String compute(VendaPayload p) {
        return sha256Hex(canonicalJson(p));
    }

    public static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Hash do movimento de caixa: {tipo(minúsculo), valor, motivo}. */
    public static String cashMovement(String tipo, String valor, String motivo) {
        String json = "{\"tipo\":" + str(nz(tipo).trim().toLowerCase()) + ",\"valor\":"
                + Money.jsNumber(Money.parseBrOrZero(valor)) + ",\"motivo\":" + str(nz(motivo).trim()) + "}";
        return sha256Hex(json);
    }

    public static String cashOpen(String openingAmount) {
        return sha256Hex("{\"openingAmount\":" + Money.jsNumber(Money.parseBrOrZero(openingAmount)) + "}");
    }

    public static String cashClose(String closingAmount, String note, String differenceReason) {
        return sha256Hex("{\"closingAmount\":" + Money.jsNumber(Money.parseBrOrZero(closingAmount)) + ",\"note\":"
                + str(nz(note).trim()) + ",\"differenceReason\":" + str(nz(differenceReason).trim()) + "}");
    }

    private static String str(String s) {
        return "\"" + new String(JsonStringEncoder.getInstance().quoteAsString(nz(s).trim())) + "\"";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static BigDecimal nzd(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
