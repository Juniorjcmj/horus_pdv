package br.com.quack.caixa.service;

import br.com.quack.caixa.api.ApiException;
import br.com.quack.caixa.api.VendaApi;
import br.com.quack.caixa.api.dto.CaixaStatusDto;
import br.com.quack.caixa.api.dto.VendaPayload;
import br.com.quack.caixa.domain.CartItem;
import br.com.quack.caixa.domain.Customer;
import br.com.quack.caixa.domain.DomainException;
import br.com.quack.caixa.domain.PaymentPlan;
import br.com.quack.caixa.domain.SaleOutcome;
import br.com.quack.caixa.domain.SalePayloadFactory;
import br.com.quack.caixa.domain.SaleValidator;
import br.com.quack.caixa.persistence.CacheStore;
import br.com.quack.caixa.persistence.OutboxStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/** Confirma a venda: online -> API; falha transitória ou offline -> fila em disco (D10). */
public class SaleService {
    private final VendaApi vendaApi;
    private final Supplier<CaixaService.StatusResult> statusSupplier;
    private final OutboxStore outbox;
    private final CacheStore cache;
    private final CatalogService catalog;

    public SaleService(VendaApi vendaApi, Supplier<CaixaService.StatusResult> statusSupplier, OutboxStore outbox,
            CacheStore cache, CatalogService catalog) {
        this.vendaApi = vendaApi;
        this.statusSupplier = statusSupplier;
        this.outbox = outbox;
        this.cache = cache;
        this.catalog = catalog;
    }

    public SaleOutcome confirm(List<CartItem> items, PaymentPlan plan, Customer customer, String cpf,
            String operatorName, String operatorId, String deviceId) {
        CaixaService.StatusResult st = statusSupplier.get();
        CaixaStatusDto status = st == null ? null : st.status();
        if (status == null) {
            throw new DomainException("Abra o caixa antes de vender.");
        }
        if (!status.canSell()) {
            String reason = status.blockReason();
            throw new DomainException(reason == null || reason.isBlank() ? "Abra o caixa antes de vender." : reason);
        }
        VendaPayload payload = SalePayloadFactory.create(items, plan, customer, cpf, operatorName, operatorId,
                deviceId, Instant.now());
        SaleValidator.validate(payload);

        SaleOutcome outcome;
        BigDecimal fiadoAtual = null;
        if (st.offline()) {
            outcome = enqueue(payload);
        } else {
            try {
                VendaApi.Result r = vendaApi.register(payload);
                fiadoAtual = r.fiadoSaldoAtual();
                outcome = new SaleOutcome(payload, r.saleNumber(), false, r.emitirFiscal());
            } catch (ApiException e) {
                if (e.isTransient()) {
                    outcome = enqueue(payload);
                } else {
                    throw new DomainException(e.getMessage());
                }
            }
        }
        return afterSale(items, plan, customer, outcome, fiadoAtual);
    }

    private SaleOutcome enqueue(VendaPayload payload) {
        VendaPayload queued = payload.asOfflineResend();
        outbox.enqueue(queued);
        return new SaleOutcome(queued, queued.offlineReference(), true, false);
    }

    /** Baixa estoque/fiado no cache, completa o resultado (unidades e saldo do fiado) e guarda o último cupom. */
    private SaleOutcome afterSale(List<CartItem> items, PaymentPlan plan, Customer customer, SaleOutcome outcome,
            BigDecimal fiadoAtual) {
        java.util.Map<String, String> units = new java.util.LinkedHashMap<>();
        BigDecimal fiadoSaldo = null;
        for (CartItem i : items) {
            units.put(i.code(), i.unit());
            cache.decrementStock(i.code(), i.quantity());
            catalog.applyLocalSale(i.code(), i.quantity());
        }
        if (customer != null) {
            BigDecimal fiadoVenda = BigDecimal.ZERO;
            for (var p : plan.parts()) {
                if (p.type() == br.com.quack.caixa.domain.PaymentType.FIADO) {
                    fiadoVenda = fiadoVenda.add(p.amount());
                }
            }
            BigDecimal newDebt = fiadoAtual != null ? fiadoAtual : customer.debt().add(fiadoVenda);
            cache.updateCustomerDebt(customer.id(), newDebt);
            catalog.applyCustomerDebt(customer.id(), newDebt);
            if (fiadoVenda.signum() > 0) {
                fiadoSaldo = newDebt;
            }
        }
        SaleOutcome full = outcome.withExtras(units, fiadoSaldo);
        cache.saveLastReceipt(full);
        return full;
    }
}
