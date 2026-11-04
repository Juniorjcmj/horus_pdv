package br.com.quack.pdv.fiscal.fila.app;

import br.com.quack.pdv.security.SecretProtector;
import java.util.Base64;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * "A empresa tem emitente fiscal configurado?" (EmitenteFiscalStore.ObterAsync reduzido a existencia): certificado
 * PFX em base64 valido, senha do certificado, id do CSC e CSC, todos preenchidos. Nao usa o certificado nem fala com
 * a SEFAZ: a Fase 7 porta o resto.
 */
@Component
public class EmitenteConfigurado {

    private final JdbcTemplate jdbc;
    private final SecretProtector segredos;

    public EmitenteConfigurado(JdbcTemplate jdbc, SecretProtector segredos) {
        this.jdbc = jdbc;
        this.segredos = segredos;
    }

    public boolean existe(String companyId) {
        List<String[]> linhas = jdbc.query("""
                SELECT CertificadoPfxCifrado, CertificadoSenhaCifrada, CscId, CscCifrado
                FROM Empresas WHERE Id = ?;
                """, (rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)},
                companyId);
        if (linhas.isEmpty()) {
            return false;
        }
        String[] l = linhas.getFirst();
        String pfx = segredo(l[0]);
        String senha = segredo(l[1]);
        String cscId = l[2] == null ? "" : l[2];
        String csc = segredo(l[3]);
        if (pfx.isBlank() || senha.isBlank() || cscId.isBlank() || csc.isBlank()) {
            return false;
        }
        try {
            Base64.getDecoder().decode(pfx);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return true;
    }

    private String segredo(String valor) {
        if (valor == null || valor.isBlank()) return "";
        try {
            String claro = segredos.unprotect(valor);
            return claro == null ? "" : claro;
        } catch (RuntimeException e) {
            return "";
        }
    }
}
