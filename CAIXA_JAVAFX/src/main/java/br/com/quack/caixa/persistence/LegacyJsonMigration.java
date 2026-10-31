package br.com.quack.caixa.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Migração única dos arquivos JSON das versões anteriores (users.json, cache/*.json, outbox/pending e
 * outbox/failed) para o banco criptografado. Tudo entra numa transação; os JSON em texto aberto só são apagados
 * depois de conferido que cada item da fila está no banco. Se algo falhar, os arquivos ficam onde estão e a
 * migração tenta de novo na próxima abertura.
 */
final class LegacyJsonMigration {
    private static final String CASH_MARK = "-CASH_";

    private LegacyJsonMigration() {
    }

    static void run(LocalDb db, Path root) {
        Path users = root.resolve("users.json");
        Path cacheDir = root.resolve("cache");
        Path pendingDir = root.resolve("outbox").resolve("pending");
        Path failedDir = root.resolve("outbox").resolve("failed");
        if (!Files.exists(users) && !Files.isDirectory(cacheDir) && !Files.isDirectory(pendingDir)
                && !Files.isDirectory(failedDir)) {
            return;
        }
        try {
            List<LocalDb.OutboxRow> outbox = new ArrayList<>();
            List<Path> outboxFiles = new ArrayList<>();
            for (Path f : list(failedDir)) {
                String name = f.getFileName().toString();
                if (name.endsWith(".json")) {
                    JsonNode node = JsonFiles.MAPPER.readTree(f.toFile());
                    JsonNode payload = node.get("payload");
                    outbox.add(new LocalDb.OutboxRow(name, kind(name),
                            payload == null || payload.isNull() ? "{}" : payload.toString(), "failed",
                            node.path("error").asText(""), node.path("failedAt").asText("")));
                    outboxFiles.add(f);
                } else if (name.endsWith(".bad")) {
                    // conteúdo ilegível de versões antigas: guarda o texto como veio, só para consulta
                    outbox.add(new LocalDb.OutboxRow(name, kind(name),
                            Files.readString(f, StandardCharsets.UTF_8), "failed", "Arquivo ilegível", ""));
                    outboxFiles.add(f);
                }
            }
            for (Path f : list(pendingDir)) {
                String name = f.getFileName().toString();
                if (name.endsWith(".json")) {
                    LocalDb.OutboxRow row;
                    try {
                        JsonNode node = JsonFiles.MAPPER.readTree(f.toFile());
                        row = new LocalDb.OutboxRow(name, kind(name), node.toString(), "pending", null, null);
                    } catch (IOException bad) {
                        // arquivo danificado: vai para as falhas (como antes), com o texto original
                        row = new LocalDb.OutboxRow(name, kind(name), Files.readString(f, StandardCharsets.UTF_8),
                                "failed", "Arquivo ilegível: " + bad.getMessage(), java.time.Instant.now().toString());
                    }
                    outbox.add(row);
                    outboxFiles.add(f);
                }
            }

            Map<String, LocalDb.CacheRow> caches = new LinkedHashMap<>();
            List<Path> cacheFiles = new ArrayList<>();
            for (Path f : list(cacheDir)) {
                String name = f.getFileName().toString();
                if (name.endsWith(".json")) {
                    try {
                        JsonNode node = JsonFiles.MAPPER.readTree(f.toFile());
                        JsonNode cid = node.get("companyId");
                        JsonNode data = node.get("data");
                        if (data != null && !data.isNull()) {
                            caches.put(name.substring(0, name.length() - 5),
                                    new LocalDb.CacheRow(cid == null || cid.isNull() ? null : cid.asText(),
                                            data.toString()));
                        }
                    } catch (IOException ignored) {
                        // cache ilegível: o caixa baixa de novo
                    }
                }
                cacheFiles.add(f);
            }

            Map<String, String> offlineUsers = new LinkedHashMap<>();
            if (Files.exists(users)) {
                try {
                    for (JsonNode u : JsonFiles.MAPPER.readTree(users.toFile())) {
                        String email = u.path("email").asText("").trim().toLowerCase(Locale.ROOT);
                        if (!email.isEmpty()) {
                            offlineUsers.put(email, u.toString());
                        }
                    }
                } catch (IOException ignored) {
                    // acesso offline é refeito no próximo login online
                }
            }

            db.importLegacy(outbox, caches, offlineUsers);

            for (LocalDb.OutboxRow r : outbox) {
                if (!db.outboxContains(r.name())) {
                    throw new IOException("item " + r.name() + " não confirmado no banco");
                }
            }
            for (Path f : outboxFiles) {
                Files.deleteIfExists(f);
            }
            for (Path f : cacheFiles) {
                Files.deleteIfExists(f);
            }
            Files.deleteIfExists(users);
            deleteIfEmpty(pendingDir);
            deleteIfEmpty(failedDir);
            deleteIfEmpty(root.resolve("outbox"));
            deleteIfEmpty(cacheDir);
            if (!outbox.isEmpty() || !caches.isEmpty() || !offlineUsers.isEmpty()) {
                System.err.println("[banco local] arquivos antigos migrados: " + outbox.size() + " item(ns) da fila, "
                        + caches.size() + " cache(s), " + offlineUsers.size() + " acesso(s) offline.");
            }
        } catch (IOException | RuntimeException e) {
            // Mantém os JSON: nada se perde, tenta de novo na próxima abertura.
            System.err.println("[banco local] migração dos arquivos antigos adiada: " + e.getMessage());
        }
    }

    private static String kind(String fileName) {
        return fileName.contains(CASH_MARK) ? "cash" : "sale";
    }

    private static List<Path> list(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static void deleteIfEmpty(Path dir) {
        try (Stream<Path> s = Files.isDirectory(dir) ? Files.list(dir) : Stream.empty()) {
            if (Files.isDirectory(dir) && s.findAny().isEmpty()) {
                Files.delete(dir);
            }
        } catch (IOException ignored) {
            // fica a pasta vazia
        }
    }
}
