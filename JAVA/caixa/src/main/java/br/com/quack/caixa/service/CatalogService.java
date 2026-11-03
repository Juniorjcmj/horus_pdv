package br.com.quack.caixa.service;

import br.com.quack.caixa.api.ApiException;
import br.com.quack.caixa.api.CatalogApi;
import br.com.quack.caixa.api.dto.CompanyDto;
import br.com.quack.caixa.domain.BalancaBarcode;
import br.com.quack.caixa.domain.Company;
import br.com.quack.caixa.domain.Customer;
import br.com.quack.caixa.domain.Product;
import br.com.quack.caixa.domain.Promotion;
import br.com.quack.caixa.persistence.CacheStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Catálogo em memória: carrega da API e cai para o cache local quando a API está fora. */
public class CatalogService {

    public record LoadResult(int products, int customers, int promotions, boolean fromCache) {
    }

    private final CatalogApi api;
    private final CacheStore cache;
    private volatile List<Product> products = List.of();
    private volatile List<Customer> customers = List.of();
    private volatile List<Promotion> promotions = List.of();
    private volatile Company company;

    public CatalogService(CatalogApi api, CacheStore cache) {
        this.api = api;
        this.cache = cache;
    }

    /** Baixa tudo da API (e grava no cache); se a API estiver fora usa o cache. */
    public LoadResult load() {
        try {
            List<Product> p = api.produtos().stream().map(Product::fromDto).toList();
            List<Customer> c = api.clientes().stream().map(Customer::fromDto).toList();
            List<Promotion> pr = api.promocoesAtivas().stream().map(Promotion::fromDto).toList();
            Company co = null;
            try {
                CompanyDto dto = api.empresa();
                co = dto == null ? null : Company.fromDto(dto);
            } catch (ApiException e) {
                if (e.status() == 401) {
                    throw e;
                }
            }
            products = p;
            customers = c;
            promotions = pr;
            cache.saveProducts(p);
            cache.saveCustomers(c);
            cache.savePromotions(pr);
            if (co != null) {
                company = co;
                cache.saveCompany(co);
            } else {
                company = cache.company().orElse(null);
            }
            return new LoadResult(p.size(), c.size(), pr.size(), false);
        } catch (ApiException e) {
            if (e.status() == 401 || !e.isTransient()) {
                throw e;
            }
            var cp = cache.products();
            if (cp.isEmpty()) {
                throw e;
            }
            products = cp.get();
            customers = cache.customers().orElse(List.of());
            promotions = cache.promotions().orElse(List.of());
            company = cache.company().orElse(null);
            return new LoadResult(products.size(), customers.size(), promotions.size(), true);
        }
    }

    public List<Product> products() {
        return products;
    }

    public List<Customer> customers() {
        return customers;
    }

    public List<Promotion> promotions() {
        return promotions;
    }

    public Company company() {
        return company;
    }

    /** Filtra por nome, código, GTIN ou marca (até 50). */
    public List<Product> search(String text) {
        String q = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        List<Product> out = new ArrayList<>();
        if (q.isEmpty()) {
            return out;
        }
        for (Product p : products) {
            if (contains(p.name(), q) || contains(p.code(), q) || contains(p.gtin(), q) || contains(p.marca(), q)) {
                out.add(p);
                if (out.size() >= 50) {
                    break;
                }
            }
        }
        return out;
    }

    private static boolean contains(String s, String q) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(q);
    }

    public Product findExact(String text) {
        String t = text == null ? "" : text.trim();
        for (Product p : products) {
            if (p.code().equalsIgnoreCase(t) || (!p.gtin().isEmpty() && p.gtin().equalsIgnoreCase(t))) {
                return p;
            }
        }
        return null;
    }

    public Product findByCode(String code) {
        return BalancaBarcode.find(products, code);
    }

    public List<Customer> searchCustomers(String text) {
        String q = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        List<Customer> out = new ArrayList<>();
        for (Customer c : customers) {
            if (q.isEmpty() || contains(c.name(), q) || contains(c.document(), q)) {
                out.add(c);
            }
        }
        return out;
    }

    /** Reflete a baixa de estoque e o fiado na memória (o cache em disco e atualizado a parte). */
    public void applyLocalSale(String code, java.math.BigDecimal qty) {
        List<Product> out = new ArrayList<>();
        for (Product p : products) {
            out.add(p.code().equals(code) ? p.withStock(p.stock().subtract(qty).max(java.math.BigDecimal.ZERO)) : p);
        }
        products = out;
    }

    public void applyCustomerDebt(String customerId, java.math.BigDecimal debt) {
        List<Customer> out = new ArrayList<>();
        for (Customer c : customers) {
            out.add(c.id().equals(customerId) ? c.withDebt(debt) : c);
        }
        customers = out;
    }
}
