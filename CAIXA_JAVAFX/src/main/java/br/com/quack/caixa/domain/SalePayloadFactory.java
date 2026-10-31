package br.com.quack.caixa.domain;

import br.com.quack.caixa.api.dto.VendaPayload;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Monta o payload da venda uma única vez (ids e hash fixos para qualquer reenvio). */
public final class SalePayloadFactory {
    private SalePayloadFactory() {
    }

    public static VendaPayload create(List<CartItem> items, PaymentPlan plan, Customer customer, String typedCpf,
            String operatorName, String operatorId, String deviceId, Instant now) {
        List<VendaPayload.Item> pi = new ArrayList<>();
        for (CartItem i : items) {
            pi.add(new VendaPayload.Item(i.code(), i.name(), Money.round4(i.quantity()), Money.round2(i.unitPrice()),
                    Money.round2(i.desconto()), i.total(), i.promocaoId(), i.autorizacaoPrecoId()));
        }
        List<VendaPayload.Payment> pp = new ArrayList<>();
        for (PaymentPart p : plan.parts()) {
            pp.add(new VendaPayload.Payment(p.type().wire(), p.amount(), p.cashGiven(), p.change()));
        }
        String cpf;
        if (customer != null && customer.document() != null && !customer.document().isBlank()) {
            cpf = customer.document();
        } else if (typedCpf != null && !typedCpf.isBlank()) {
            cpf = typedCpf.trim();
        } else {
            cpf = "-";
        }
        String dev = deviceId == null ? "" : deviceId.replace("-", "");
        String devPart = (dev.length() >= 4 ? dev.substring(0, 4) : String.format("%-4s", dev).replace(' ', '0'))
                .toUpperCase(Locale.ROOT);
        String millis = String.valueOf(now.toEpochMilli());
        String tail = millis.length() > 6 ? millis.substring(millis.length() - 6) : millis;
        BigDecimal total = plan.total();
        VendaPayload draft = new VendaPayload(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "SALE_CREATED",
                now.toString(),
                "OFF-" + devPart + "-" + tail,
                null,
                false,
                customer != null ? customer.name() : "Consumidor",
                cpf,
                plan.headerPaymentType(),
                Money.formatBr(total),
                operatorName,
                operatorId,
                pi,
                pp);
        return draft.withHash(PayloadHash.compute(draft));
    }
}
