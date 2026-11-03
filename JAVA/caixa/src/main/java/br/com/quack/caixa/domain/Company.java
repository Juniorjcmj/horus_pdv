package br.com.quack.caixa.domain;

import br.com.quack.caixa.api.dto.CompanyDto;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Dados da empresa para cabeçalho e cupom. Campos novos podem vir nulos de um cache antigo: use os
 * acessores com "nz" (ex.: {@link #phoneOrSac()}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Company(String fantasyName, String corporateName, String cnpj, String address,
        String stateRegistration, String street, String number, String neighborhood, String city, String uf,
        String phone, String sacPhone) {

    public static Company fromDto(CompanyDto d) {
        StringBuilder a = new StringBuilder();
        append(a, d.address());
        append(a, d.number());
        append(a, d.neighborhood());
        append(a, d.city());
        if (d.uf() != null && !d.uf().isBlank()) {
            a.append(a.length() > 0 ? "/" : "").append(d.uf());
        }
        return new Company(nz(d.fantasyName()), nz(d.corporateName()), nz(d.cnpj()), a.toString(),
                nz(d.stateRegistration()), nz(d.address()), nz(d.number()), nz(d.neighborhood()), nz(d.city()),
                nz(d.uf()), nz(d.phone()), nz(d.sacPhone()));
    }

    private static void append(StringBuilder sb, String part) {
        if (part != null && !part.isBlank()) {
            sb.append(sb.length() > 0 ? ", " : "").append(part.trim());
        }
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }

    @JsonIgnore
    public String displayName() {
        return nz(fantasyName).isBlank() ? nz(corporateName) : fantasyName;
    }

    /** "Rua, número, bairro" (como no cupom da web). */
    @JsonIgnore
    public String streetLine() {
        StringBuilder a = new StringBuilder();
        append(a, street);
        append(a, number);
        append(a, neighborhood);
        return a.length() == 0 ? nz(address) : a.toString();
    }

    /** "Cidade - UF". */
    @JsonIgnore
    public String cityLine() {
        String c = nz(city);
        String u = nz(uf);
        return c.isBlank() ? u : u.isBlank() ? c : c + " - " + u;
    }

    @JsonIgnore
    public String phoneOrSac() {
        return !nz(phone).isBlank() ? phone : nz(sacPhone);
    }
}
