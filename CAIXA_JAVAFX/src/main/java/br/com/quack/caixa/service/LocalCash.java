package br.com.quack.caixa.service;

import br.com.quack.caixa.api.dto.CaixaStatusDto;
import br.com.quack.caixa.api.dto.VendaPayload;
import br.com.quack.caixa.domain.DomainException;
import br.com.quack.caixa.domain.Money;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Caixa sem conexão (porta de CashSessionRepository.ts): abre, movimenta e fecha o turno localmente e calcula
 * os totais com o último valor do servidor + vendas ainda na fila + sangrias/reforços. Funções puras sobre o
 * status em cache; quem chama grava o resultado e enfileira o evento.
 */
public final class LocalCash {
    private static final String DINHEIRO = "dinheiro";

    private LocalCash() {
    }

    /** Totais do turno quando o servidor não está disponível. */
    public record Summary(List<CaixaStatusDto.Breakdown> paymentBreakdown, String expectedCashAmount) {
    }

    /** Abertura offline: id "cx-offline-<millis>" (formato aceito pela API como sessão do cliente). */
    public static CaixaStatusDto open(CaixaStatusDto cached, String openingAmount, String operatorId,
            String operatorName, Instant now) {
        if (cached != null && cached.open()) {
            throw new DomainException("Já existe um caixa aberto localmente.");
        }
        String sessionId = "cx-offline-" + now.toEpochMilli();
        String opening = Money.formatBr(Money.parseBr(openingAmount));
        CaixaStatusDto.Session s = new CaixaStatusDto.Session(sessionId, "aberto", now.toString(), null, opening,
                "0,00", nz(operatorId), operatorName == null || operatorName.isBlank() ? "Operador" : operatorName, 0,
                opening, null, null, List.of(), List.of(), "", "");
        return new CaixaStatusDto("aberto", true, "", now.toString(), s, null);
    }

    /**
     * Sangria/reforço offline: exige caixa aberto no status local. Devolve o status BASE (para gravar no
     * cache); para mostrar, passe por {@link #live}. Gravar o "ao vivo" somaria as vendas da fila duas vezes.
     */
    public static CaixaStatusDto movement(CaixaStatusDto cached, String tipo, String valor, String motivo,
            String operatorName, Instant now) {
        if (cached == null || !cached.open() || cached.currentSession() == null) {
            throw new DomainException("Não é possível registrar movimento: nenhum caixa está aberto localmente.");
        }
        CaixaStatusDto.Session cur = cached.currentSession();
        List<CaixaStatusDto.Movement> movs = new ArrayList<>(nzList(cur.movimentos()));
        movs.add(new CaixaStatusDto.Movement("mov-local-" + now.toEpochMilli(), tipo,
                Money.formatBr(Money.parseBr(valor)), motivo, now.toString(),
                operatorName == null || operatorName.isBlank() ? "Operador" : operatorName));
        CaixaStatusDto.Session updated = copy(cur, movs, cur.paymentBreakdown());
        return new CaixaStatusDto("aberto", true, "", now.toString(), updated, null);
    }

    /** Fechamento offline: congela esperado, contado e diferença no lastSession. */
    public static CaixaStatusDto close(CaixaStatusDto cached, String closingAmount, String note,
            String differenceReason, String operatorName, Instant now, List<VendaPayload> pendingSales) {
        if (cached == null || !cached.open() || cached.currentSession() == null) {
            throw new DomainException("Você não possui caixa aberto para fechamento.");
        }
        CaixaStatusDto.Session cur = cached.currentSession();
        Summary sum = summary(cur, pendingSales);
        BigDecimal closing = Money.parseBr(closingAmount);
        BigDecimal diff = Money.round2(closing.subtract(Money.parseBr(sum.expectedCashAmount())));
        CaixaStatusDto.Session closed = new CaixaStatusDto.Session(cur.id(), "fechado", cur.openedAt(),
                now.toString(), cur.openingAmount(), Money.formatBr(closing), cur.operatorId(),
                cur.operatorName(), elapsedMinutes(cur.openedAt(), now), sum.expectedCashAmount(),
                Money.formatBr(diff), differenceReason == null || differenceReason.isBlank() ? null : differenceReason,
                nzList(cur.movimentos()), sum.paymentBreakdown(),
                operatorName == null || operatorName.isBlank() ? cur.operatorName() : operatorName, nz(note));
        return new CaixaStatusDto("fechado", false, "Caixa fechado", now.toString(), null, closed);
    }

    /** Status em cache com tempo decorrido e totais "ao vivo" (loadCachedCashStatus). Só para exibir/vender. */
    public static CaixaStatusDto live(CaixaStatusDto cached, Instant now, List<VendaPayload> pendingSales) {
        if (cached == null || !cached.open() || cached.currentSession() == null) {
            return cached;
        }
        CaixaStatusDto.Session cur = cached.currentSession();
        Summary sum = summary(cur, pendingSales);
        CaixaStatusDto.Session s = new CaixaStatusDto.Session(cur.id(), cur.status(), cur.openedAt(), null,
                cur.openingAmount(), "0,00", cur.operatorId(), cur.operatorName(),
                elapsedMinutes(cur.openedAt(), now), sum.expectedCashAmount(), null, null, nzList(cur.movimentos()),
                sum.paymentBreakdown(), cur.closedByName(), cur.note());
        return new CaixaStatusDto("aberto", true, "", now.toString(), s, cached.lastSession());
    }

    /**
     * Base = total por forma que o servidor mandou da última vez (paymentBreakdown do cache)
     * + vendas ainda na fila feitas depois da abertura + reforços - sangrias.
     */
    static Summary summary(CaixaStatusDto.Session cur, List<VendaPayload> pendingSales) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (CaixaStatusDto.Breakdown b : nzList(cur.paymentBreakdown())) {
            totals.merge(key(b.paymentType()), parse(b.total()), BigDecimal::add);
        }
        Instant opened = parseInstant(cur.openedAt());
        for (VendaPayload sale : pendingSales == null ? List.<VendaPayload>of() : pendingSales) {
            Instant at = parseInstant(sale.occurredAt());
            if (opened != null && at != null && at.isBefore(opened)) {
                continue;
            }
            for (VendaPayload.Payment p : sale.payments()) {
                totals.merge(key(p.paymentType()), p.amount(), BigDecimal::add);
            }
        }
        BigDecimal reforcos = BigDecimal.ZERO;
        BigDecimal sangrias = BigDecimal.ZERO;
        for (CaixaStatusDto.Movement m : nzList(cur.movimentos())) {
            if ("reforco".equalsIgnoreCase(nz(m.tipo()))) {
                reforcos = reforcos.add(parse(m.valor()));
            } else if ("sangria".equalsIgnoreCase(nz(m.tipo()))) {
                sangrias = sangrias.add(parse(m.valor()));
            }
        }
        BigDecimal expected = Money.round2(parse(cur.openingAmount())
                .add(totals.getOrDefault(DINHEIRO, BigDecimal.ZERO)).add(reforcos).subtract(sangrias));
        List<CaixaStatusDto.Breakdown> out = new ArrayList<>();
        totals.forEach((k, v) -> out.add(new CaixaStatusDto.Breakdown(k, Money.formatBr(Money.round2(v)))));
        return new Summary(out, Money.formatBr(expected));
    }

    static long elapsedMinutes(String openedAt, Instant until) {
        Instant start = parseInstant(openedAt);
        return start == null ? 0 : Math.max(0, Duration.between(start, until).toMinutes());
    }

    private static CaixaStatusDto.Session copy(CaixaStatusDto.Session c, List<CaixaStatusDto.Movement> movs,
            List<CaixaStatusDto.Breakdown> breakdown) {
        return new CaixaStatusDto.Session(c.id(), c.status(), c.openedAt(), c.closedAt(), c.openingAmount(),
                c.closingAmount(), c.operatorId(), c.operatorName(), c.elapsedMinutes(), c.expectedCashAmount(),
                c.differenceAmount(), c.differenceReason(), movs, breakdown, c.closedByName(), c.note());
    }

    private static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (RuntimeException e) {
            try {
                return Instant.parse(iso);
            } catch (RuntimeException e2) {
                return null;
            }
        }
    }

    private static String key(String paymentType) {
        String k = nz(paymentType).trim().toLowerCase(Locale.ROOT);
        return k.isEmpty() ? "outros" : k;
    }

    private static BigDecimal parse(String v) {
        try {
            return v == null || v.isBlank() ? BigDecimal.ZERO : Money.parseBr(v);
        } catch (DomainException e) {
            return BigDecimal.ZERO;
        }
    }

    private static <T> List<T> nzList(List<T> l) {
        return l == null ? List.of() : l;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
