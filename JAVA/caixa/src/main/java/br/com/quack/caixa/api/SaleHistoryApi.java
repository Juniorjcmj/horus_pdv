package br.com.quack.caixa.api;

import br.com.quack.caixa.api.dto.VendaPayload;
import br.com.quack.caixa.domain.DomainException;
import br.com.quack.caixa.domain.Money;
import br.com.quack.caixa.domain.SaleOutcome;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Venda já registrada (mesmas rotas da web): GET /api/HistoricoVendas/{venda} (detalhe) e
 * POST /api/HistoricoVendas/{venda}/corrigir (correção pelo financeiro: remove produtos e gera nova venda).
 */
public class SaleHistoryApi {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String productCode, String productName, BigDecimal quantity, String unitPrice,
            BigDecimal desconto, String itemTotal, String promocaoId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payment(String paymentType, BigDecimal amount, BigDecimal cashGiven, BigDecimal changeAmount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SaleDetail(String vendaId, String saleNumber, String status, String customerName,
            String customerCpf, String paymentType, String totalAmount, String operatorName, String saleDate,
            List<Item> items, List<Payment> payments) {

        /** O histórico pode trazer linhas repetidas por item: uma por código, como na web. */
        public List<Item> uniqueItems() {
            Map<String, Item> seen = new LinkedHashMap<>();
            for (Item i : items == null ? List.<Item>of() : items) {
                seen.putIfAbsent(i.productCode(), i);
            }
            return new ArrayList<>(seen.values());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CorrectResult(String vendaOriginalNumber, String novaVendaNumber, String corrigidaEm,
            String financeiroNome, int itensRemovidos, String novoTotal, boolean fiscalQueued) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CancelResult(String saleNumber, String canceladoEm, String supervisorNome, int itensEstornados,
            BigDecimal fiadoEstornado) {
    }

    private final ApiClient api;

    public SaleHistoryApi(ApiClient api) {
        this.api = api;
    }

    public SaleDetail details(String saleNumber) {
        return api.send("GET", "/api/HistoricoVendas/" + enc(saleNumber), null, ApiClient.LIST_TIMEOUT,
                SaleDetail.class);
    }

    /**
     * Cancela a venda inteira (POST /api/HistoricoVendas/{venda}/cancelar), autorizada pela senha do gerente. O
     * servidor estorna estoque e fiado e grava a trilha completa (inclusive tentativas negadas). Venda com nota
     * autorizada volta 409 com data.codigo = NOTA_AUTORIZADA: o cancelamento tem de ir pela NFC-e (SEFAZ).
     */
    public CancelResult cancel(String saleNumber, String supervisorId, String supervisorPassword, String justificativa,
            String terminal) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("supervisorId", supervisorId);
        b.put("supervisorPassword", supervisorPassword);
        b.put("justificativa", justificativa);
        b.put("terminal", terminal);
        return api.send("POST", "/api/HistoricoVendas/" + enc(saleNumber) + "/cancelar", b, ApiClient.SALE_TIMEOUT,
                CancelResult.class);
    }

    /** Código da recusa do cancelamento (NOTA_AUTORIZADA, NOTA_PENDENTE, JA_CANCELADA...), ou "". */
    public static String cancelCode(Throwable t) {
        if (t instanceof ApiException e && e.data() != null) {
            return e.data().path("codigo").asText("");
        }
        return "";
    }

    /**
     * Correção autorizada pela senha de um gerente/administrador (o gerente fica como responsável). Sem gerente,
     * o servidor exige perfil Financeiro no usuário logado.
     */
    public CorrectResult correct(String saleNumber, java.util.Collection<String> produtosParaRemover,
            String justificativa, String supervisorId, String supervisorPassword) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("produtosParaRemover", new ArrayList<>(new LinkedHashSet<>(produtosParaRemover)));
        b.put("justificativa", justificativa);
        if (supervisorId != null && !supervisorId.isBlank()) {
            b.put("supervisorId", supervisorId);
            b.put("supervisorPassword", supervisorPassword == null ? "" : supervisorPassword);
        }
        return api.send("POST", "/api/HistoricoVendas/" + enc(saleNumber) + "/corrigir", b, ApiClient.SALE_TIMEOUT,
                CorrectResult.class);
    }

    /** Monta o resultado de venda a partir do detalhe do servidor (para o cupom/DANFE da venda nova). */
    public static SaleOutcome toOutcome(SaleDetail d, boolean emitirFiscal) {
        List<VendaPayload.Item> items = new ArrayList<>();
        for (Item i : d.items() == null ? List.<Item>of() : d.items()) {
            items.add(new VendaPayload.Item(i.productCode(), i.productName(), i.quantity(), money(i.unitPrice()),
                    i.desconto() == null ? BigDecimal.ZERO : i.desconto(), money(i.itemTotal()), i.promocaoId()));
        }
        List<VendaPayload.Payment> pays = new ArrayList<>();
        for (Payment p : d.payments() == null ? List.<Payment>of() : d.payments()) {
            boolean cash = "dinheiro".equalsIgnoreCase(p.paymentType());
            BigDecimal given = cash ? p.cashGiven() : null;
            BigDecimal change = cash ? p.changeAmount() : null;
            // Recebido e troco precisam fechar com o valor (recebido = valor + troco). Vendas geradas por
            // correção antigas gravaram um recebido proporcional com troco zero: no cupom vale o próprio valor.
            if (cash && given != null && p.amount() != null) {
                BigDecimal c = change == null ? BigDecimal.ZERO : change;
                if (given.subtract(c).compareTo(p.amount()) != 0) {
                    given = p.amount();
                    change = BigDecimal.ZERO;
                }
            }
            pays.add(new VendaPayload.Payment(p.paymentType(), p.amount(), given, change));
        }
        VendaPayload payload = new VendaPayload(null, null, "SALE_CREATED", isoFromSaleDate(d.saleDate()), null, null,
                false, d.customerName(), d.customerCpf(), d.paymentType(), d.totalAmount(), d.operatorName(), null,
                items, pays);
        return new SaleOutcome(payload, d.saleNumber(), false, emitirFiscal);
    }

    /** "31/10/2026 03:11:27" (horário de Brasília) -> ISO-8601. */
    static String isoFromSaleDate(String saleDate) {
        try {
            return LocalDateTime.parse(saleDate, DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))
                    .atZone(ZoneId.of("America/Sao_Paulo")).toInstant().toString();
        } catch (RuntimeException e) {
            return saleDate;
        }
    }

    private static BigDecimal money(String v) {
        try {
            return v == null || v.isBlank() ? BigDecimal.ZERO : Money.parseBr(v);
        } catch (DomainException e) {
            return BigDecimal.ZERO;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
