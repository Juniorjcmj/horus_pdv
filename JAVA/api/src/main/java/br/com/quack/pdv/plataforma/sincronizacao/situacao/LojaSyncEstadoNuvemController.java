package br.com.quack.pdv.plataforma.sincronizacao.situacao;

import br.com.quack.pdv.plataforma.sincronizacao.nuvem.ServidorLoja;
import br.com.quack.pdv.shared.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Relato periodico de estado da loja (M4): o servidor da loja conta, a cada minuto, o que so ele sabe (atraso do envio,
 * backup, NFC-e pendentes, pausa, versao). A nuvem guarda so o ultimo relato por empresa em {@code SyncEstadoLoja}.
 * Autenticado pelo {@code ServidorLojaFilter} (servidor ativo). Nao toca em comandos nem em lotes.
 */
@RestController
@RequestMapping("api/loja-sync")
@Profile("nuvem")
public class LojaSyncEstadoNuvemController {

    /** Tamanho maximo do corpo do relato. */
    static final int LIMITE_BYTES = 4096;
    static final int LIMITE_ERRO = 500;
    private static final int LIMITE_VERSAO = 40;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public LojaSyncEstadoNuvemController(@Lazy JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("estado")
    public ResponseEntity<ApiResponse<Object>> estado(@RequestBody byte[] corpo, HttpServletRequest req) {
        if (!(req.getAttribute(ServidorLoja.ATRIBUTO) instanceof ServidorLoja servidor)) {
            return ResponseEntity.status(401).body(ApiResponse.erro("Token do servidor invalido ou revogado."));
        }
        if (corpo.length > LIMITE_BYTES) {
            return ResponseEntity.status(413).body(ApiResponse.erro("Relato maior que o permitido."));
        }
        JsonNode r;
        try {
            r = JSON.readTree(corpo);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(ApiResponse.erro("Relato ilegivel."));
        }
        if (r == null || !r.isObject()) return ResponseEntity.badRequest().body(ApiResponse.erro("Relato ilegivel."));
        try {
            jdbc.update("""
                    MERGE dbo.SyncEstadoLoja AS a USING (SELECT ? AS CompanyId) AS s ON a.CompanyId = s.CompanyId
                    WHEN MATCHED THEN UPDATE SET RecebidoEm = SYSDATETIMEOFFSET(), GeradoEm = ?, AtrasoSegundos = ?, Pausada = ?,
                         UltimoBackupEm = ?, BackupSucesso = ?, BackupErro = ?, NfcePendentes = ?, VersaoPrograma = ?
                    WHEN NOT MATCHED THEN INSERT (CompanyId, RecebidoEm, GeradoEm, AtrasoSegundos, Pausada, UltimoBackupEm,
                         BackupSucesso, BackupErro, NfcePendentes, VersaoPrograma)
                         VALUES (s.CompanyId, SYSDATETIMEOFFSET(), ?, ?, ?, ?, ?, ?, ?, ?);
                    """,
                    servidor.companyId(),
                    data(r, "geradoEm"), longo(r, "atrasoSegundos"), booleano(r, "pausada"), data(r, "ultimoBackupEm"),
                    booleano(r, "backupSucesso"), texto(r, "backupErro", LIMITE_ERRO), inteiro(r, "nfcePendentes"),
                    texto(r, "versaoPrograma", LIMITE_VERSAO),
                    data(r, "geradoEm"), longo(r, "atrasoSegundos"), booleano(r, "pausada"), data(r, "ultimoBackupEm"),
                    booleano(r, "backupSucesso"), texto(r, "backupErro", LIMITE_ERRO), inteiro(r, "nfcePendentes"),
                    texto(r, "versaoPrograma", LIMITE_VERSAO));
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(ApiResponse.erro("Data do relato invalida."));
        }
        return ResponseEntity.ok(ApiResponse.ok("Relato recebido."));
    }

    private static OffsetDateTime data(JsonNode r, String campo) {
        JsonNode n = r.path(campo);
        return n.isMissingNode() || n.isNull() || n.asText().isBlank() ? null : OffsetDateTime.parse(n.asText());
    }

    private static Long longo(JsonNode r, String campo) {
        JsonNode n = r.path(campo);
        return n.isNumber() ? n.asLong() : null;
    }

    private static Integer inteiro(JsonNode r, String campo) {
        JsonNode n = r.path(campo);
        return n.isNumber() ? n.asInt() : null;
    }

    private static Boolean booleano(JsonNode r, String campo) {
        JsonNode n = r.path(campo);
        return n.isBoolean() ? n.asBoolean() : null;
    }

    private static String texto(JsonNode r, String campo, int max) {
        JsonNode n = r.path(campo);
        if (n.isMissingNode() || n.isNull()) return null;
        String t = n.asText();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
