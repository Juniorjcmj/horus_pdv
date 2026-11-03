package br.com.quack.caixa.api;

import br.com.quack.caixa.api.dto.CompanyDto;
import br.com.quack.caixa.api.dto.CustomerDto;
import br.com.quack.caixa.api.dto.ProductDto;
import br.com.quack.caixa.api.dto.PromotionDto;
import br.com.quack.caixa.persistence.JsonFiles;
import java.util.List;

public class CatalogApi {
    private final ApiClient api;

    public CatalogApi(ApiClient api) {
        this.api = api;
    }

    public List<ProductDto> produtos() {
        List<ProductDto> r = api.send("GET", "/api/Produto", null, ApiClient.LIST_TIMEOUT,
                JsonFiles.listOf(ProductDto.class));
        return r == null ? List.of() : r;
    }

    public List<CustomerDto> clientes() {
        List<CustomerDto> r = api.send("GET", "/api/Cliente", null, ApiClient.LIST_TIMEOUT,
                JsonFiles.listOf(CustomerDto.class));
        return r == null ? List.of() : r;
    }

    public List<PromotionDto> promocoesAtivas() {
        List<PromotionDto> r = api.send("GET", "/api/Promocao/ativas", null, ApiClient.LIST_TIMEOUT,
                JsonFiles.listOf(PromotionDto.class));
        return r == null ? List.of() : r;
    }

    public CompanyDto empresa() {
        return api.send("GET", "/api/Empresa", null, ApiClient.READ_TIMEOUT, CompanyDto.class);
    }
}
