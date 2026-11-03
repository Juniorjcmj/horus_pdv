package br.com.quack.caixa.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** GET /api/Empresa: só os campos que o caixa usa no cabeçalho e no cupom. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CompanyDto(String fantasyName, String corporateName, String cnpj, String stateRegistration,
        String address, String number, String neighborhood, String city, String uf, String phone, String sacPhone,
        Integer ambienteFiscal) {
}
