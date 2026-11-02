package br.com.quack.pdv.usuario.web;

import java.util.List;

/** Corpo de POST/PUT /api/Usuario (campos ausentes viram texto vazio, como no .NET). */
public record UsuarioRequest(
        String companyId,
        String cpf,
        String name,
        String email,
        String phone,
        String role,
        String status,
        String password,
        List<String> perfisAdicionais) {

    public UsuarioRequest {
        companyId = nv(companyId);
        cpf = nv(cpf);
        name = nv(name);
        email = nv(email);
        phone = nv(phone);
        role = nv(role);
        status = nv(status);
        password = nv(password);
    }

    private static String nv(String s) {
        return s == null ? "" : s;
    }
}
