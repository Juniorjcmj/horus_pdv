package br.com.quack.pdv.plataforma.sincronizacao.alertas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Le o {@code backup-estado.json} que o script de backup do instalador escreve (D11/D12). Formato combinado com o
 * grupo 7: {@code {"ultimoBackupEm": "<ISO-8601>", "sucesso": true|false, "erro": "<texto ou nulo>"}}.
 */
public final class EstadoBackup {

    private static final ObjectMapper JSON = new ObjectMapper();

    public record Estado(String ultimoBackupEm, boolean sucesso, String erro) {}

    private EstadoBackup() {}

    /** Vazio se o caminho nao esta configurado, o arquivo nao existe ou esta ilegivel. */
    public static Optional<Estado> ler(String caminho) {
        if (caminho == null || caminho.isBlank()) return Optional.empty();
        Path p = Path.of(caminho);
        if (!Files.isRegularFile(p)) return Optional.empty();
        try {
            JsonNode n = JSON.readTree(Files.readString(p));
            String erro = n.path("erro").isNull() ? null : n.path("erro").asText(null);
            return Optional.of(new Estado(n.path("ultimoBackupEm").asText(null), n.path("sucesso").asBoolean(false), erro));
        } catch (IOException e) {
            return Optional.of(new Estado(null, false, "Arquivo de estado do backup ilegivel: " + e.getMessage()));
        }
    }
}
