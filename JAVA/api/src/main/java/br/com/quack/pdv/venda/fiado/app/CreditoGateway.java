package br.com.quack.pdv.venda.fiado.app;

import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leituras do credito controlado pelo Gateway (GatewayCreditAB: RequireLegacy, RequireUnblocked, Delegated). A
 * concessao e o consumo completos sao da Fase 5; aqui so o que a venda a prazo precisa.
 */
@Component
public class CreditoGateway {

    private final JdbcTemplate jdbc;

    public CreditoGateway(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Empresa com credito controlado pelo Gateway nao aceita fiado comum. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void exigirFiadoLegado(String companyId, String storeId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM GatewayCreditStores WHERE CompanyId=? "
                + "AND State<>'DISABLED' AND (? IS NULL OR StoreId=?)", Integer.class, companyId, storeId, storeId);
        if (n != null && n > 0) {
            throw new IllegalArgumentException("GATEWAY_AUTHORIZATION_REQUIRED");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void exigirNaoBloqueado(String companyId, String customerId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM CustomerCreditPolicies WHERE CompanyId=? "
                + "AND CustomerId=? AND Blocked=1", Integer.class, companyId, customerId);
        if (n != null && n > 0) {
            throw new IllegalArgumentException("CUSTOMER_CREDIT_BLOCKED");
        }
    }

    /** Credito delegado a Gateways ainda nao consumido (em reais). */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal delegado(String companyId, String customerId) {
        BigDecimal centavos = jdbc.queryForObject("SELECT COALESCE(SUM(AmountCents-ConsumedCents),0) FROM "
                + "GatewayCreditGrants WHERE CompanyId=? AND CustomerId=? AND Closed=0", BigDecimal.class,
                companyId, customerId);
        return (centavos == null ? BigDecimal.ZERO : centavos).divide(BigDecimal.valueOf(100));
    }
}
