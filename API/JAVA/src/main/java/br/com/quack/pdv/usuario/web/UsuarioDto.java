package br.com.quack.pdv.usuario.web;

import br.com.quack.pdv.security.Perfis;
import br.com.quack.pdv.usuario.domain.Usuario;
import java.util.List;

/** Usuario devolvido pela API (sem hash de senha). Mesmos campos do SecurityUserDto do .NET. */
public record UsuarioDto(
        String id,
        String companyId,
        String cpf,
        String name,
        String email,
        String phone,
        String role,
        String status,
        String createdAt,
        String lastLoginAt,
        boolean mustChangePassword,
        List<String> perfisAdicionais) {

    public static UsuarioDto de(Usuario u) {
        return new UsuarioDto(u.getId(), u.getCompanyId(), u.getCpf(), u.getName(), u.getEmail(), u.getPhone(),
                u.getRole(), u.getStatus(), u.getCreatedAt(), u.getLastLoginAt(), u.isMustChangePassword(),
                Perfis.parseAdicionais(u.getPerfisAdicionais()));
    }
}
