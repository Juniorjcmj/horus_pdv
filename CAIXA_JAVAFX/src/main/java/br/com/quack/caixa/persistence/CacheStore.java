package br.com.quack.caixa.persistence;

import br.com.quack.caixa.api.dto.CaixaStatusDto;
import br.com.quack.caixa.config.AppPaths;
import br.com.quack.caixa.domain.Company;
import br.com.quack.caixa.domain.Customer;
import br.com.quack.caixa.domain.Product;
import br.com.quack.caixa.domain.SaleOutcome;
import br.com.quack.caixa.domain.Promotion;
import com.fasterxml.jackson.databind.JavaType;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Cache local por empresa: produtos, clientes, promoções, empresa, status do caixa e último cupom. Fica no banco
 * criptografado ({@link LocalDb}), tabela cache.
 */
public final class CacheStore {
    private final Path root;
    private volatile String companyId;

    public CacheStore(AppPaths paths) {
        this.root = paths.root();
    }

    public void setCompanyId(String companyId) {
        this.companyId = companyId;
    }

    public String companyId() {
        return companyId;
    }

    private synchronized void write(String name, Object data) {
        try {
            LocalDb.at(root).cachePut(name, companyId, JsonFiles.MAPPER.writeValueAsString(data));
        } catch (IOException | RuntimeException e) {
            // cache é melhor esforço
            System.err.println("[cache] " + name + " não gravado: " + e.getMessage());
        }
    }

    private synchronized <T> Optional<T> read(String name, JavaType type) {
        try {
            Optional<LocalDb.CacheRow> row = LocalDb.at(root).cacheGet(name);
            if (row.isEmpty()) {
                return Optional.empty();
            }
            String stored = row.get().companyId();
            if (stored != null && companyId != null && !stored.equals(companyId)) {
                return Optional.empty();
            }
            T value = JsonFiles.MAPPER.readerFor(type).readValue(row.get().data());
            return Optional.ofNullable(value);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static JavaType t(Class<?> c) {
        return JsonFiles.MAPPER.getTypeFactory().constructType(c);
    }

    public void saveProducts(List<Product> v) {
        write("products", v);
    }

    public Optional<List<Product>> products() {
        return read("products", JsonFiles.listOf(Product.class));
    }

    public void saveCustomers(List<Customer> v) {
        write("customers", v);
    }

    public Optional<List<Customer>> customers() {
        return read("customers", JsonFiles.listOf(Customer.class));
    }

    public void savePromotions(List<Promotion> v) {
        write("promotions", v);
    }

    public Optional<List<Promotion>> promotions() {
        return read("promotions", JsonFiles.listOf(Promotion.class));
    }

    public void saveCompany(Company v) {
        write("company", v);
    }

    public Optional<Company> company() {
        return read("company", t(Company.class));
    }

    public void saveCaixaStatus(CaixaStatusDto v) {
        write("caixa-status", v);
    }

    public Optional<CaixaStatusDto> caixaStatus() {
        return read("caixa-status", t(CaixaStatusDto.class));
    }

    public void saveLastReceipt(SaleOutcome v) {
        write("last-receipt", v);
    }

    public Optional<SaleOutcome> lastReceipt() {
        return read("last-receipt", t(SaleOutcome.class));
    }

    /** Baixa o estoque local do produto pelo código. */
    public synchronized void decrementStock(String code, BigDecimal qty) {
        Optional<List<Product>> opt = products();
        if (opt.isEmpty()) {
            return;
        }
        List<Product> out = new ArrayList<>();
        for (Product p : opt.get()) {
            if (p.code().equals(code)) {
                out.add(p.withStock(p.stock().subtract(qty).max(BigDecimal.ZERO)));
            } else {
                out.add(p);
            }
        }
        saveProducts(out);
    }

    public synchronized void updateCustomerDebt(String customerId, BigDecimal newDebt) {
        Optional<List<Customer>> opt = customers();
        if (opt.isEmpty()) {
            return;
        }
        List<Customer> out = new ArrayList<>();
        for (Customer c : opt.get()) {
            out.add(c.id().equals(customerId) ? c.withDebt(newDebt) : c);
        }
        saveCustomers(out);
    }
}
