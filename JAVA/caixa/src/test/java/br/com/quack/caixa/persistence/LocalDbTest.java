package br.com.quack.caixa.persistence;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.caixa.api.AuthApi;
import br.com.quack.caixa.api.dto.VendaPayload;
import br.com.quack.caixa.config.AppPaths;
import br.com.quack.caixa.domain.PasswordHash;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Banco local criptografado: migração dos JSON antigos, adulteração, cópia da fila e chave perdida. */
class LocalDbTest {
    @TempDir
    Path tmp;

    private static VendaPayload sale(String eventId, String unit) {
        BigDecimal v = new BigDecimal(unit);
        return new VendaPayload("cs-" + eventId, eventId, "SALE_CREATED", "2026-01-01T00:00:00Z", "OFF-1", "hash",
                true, "Maria", "123.456.789-09", "dinheiro", v.toPlainString().replace('.', ','), "Ana", "op",
                List.of(new VendaPayload.Item("A", "Cafe", BigDecimal.ONE, v, BigDecimal.ZERO, v, null)),
                List.of(new VendaPayload.Payment("dinheiro", v, v, BigDecimal.ZERO)));
    }

    private static String raw(Path f) throws Exception {
        return new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1);
    }

    @Test
    void migraOsJsonAntigosEApagaOsArquivosEmTextoAberto() throws Exception {
        // Pasta como a versão anterior deixava.
        Path pending = Files.createDirectories(tmp.resolve("outbox/pending"));
        Path failed = Files.createDirectories(tmp.resolve("outbox/failed"));
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        JsonFiles.writeAtomic(pending.resolve("1000-CASH_OPEN-ev0.json"),
                br.com.quack.caixa.api.dto.CashEvent.open("ev0", "h0", "cx-offline-1", "2026-01-01T00:00:00Z",
                        "100,00"));
        JsonFiles.writeAtomic(pending.resolve("1001-e1.json"), sale("e1", "18.90"));
        JsonFiles.writeAtomic(pending.resolve("1002-e2.json"), sale("e2", "5.00"));
        Files.writeString(pending.resolve("1003-e3.json"), "{ quebrado");
        var falha = JsonFiles.MAPPER.createObjectNode();
        falha.set("payload", JsonFiles.MAPPER.valueToTree(sale("e9", "1.00")));
        falha.put("error", "Estoque insuficiente");
        falha.put("failedAt", "2026-01-01T01:00:00Z");
        JsonFiles.writeAtomic(failed.resolve("0999-e9.json"), falha);
        var produtos = JsonFiles.MAPPER.createObjectNode();
        produtos.put("companyId", "emp1");
        produtos.set("data", JsonFiles.MAPPER.readTree(
                "[{\"code\":\"1001\",\"name\":\"Cafe\",\"price\":18.90,\"stock\":10}]"));
        JsonFiles.writeAtomic(cache.resolve("products.json"), produtos);
        String hashAntigo = PasswordHash.compute("ana@loja.com", "segredo");
        Files.writeString(tmp.resolve("users.json"), "[{\"id\":\"op1\",\"companyId\":\"emp1\",\"email\":\"ana@loja.com\","
                + "\"name\":\"Ana\",\"role\":\"caixa\",\"passwordHash\":\"" + hashAntigo + "\","
                + "\"lastOnlineLoginAt\":\"" + java.time.Instant.now() + "\",\"maxOfflineDays\":7}]");

        OutboxStore outbox = new OutboxStore(new AppPaths(tmp));
        List<OutboxStore.Entry> fila = outbox.listPending();
        assertEquals(3, fila.size(), "abertura + 2 vendas (o arquivo quebrado foi para as falhas)");
        assertEquals("CASH_OPEN", fila.get(0).cash().eventType(), "a ordem antiga é mantida");
        assertEquals("e1", fila.get(1).payload().eventId());
        assertEquals("e2", fila.get(2).payload().eventId());
        assertTrue(outbox.hasPendingCash());
        assertEquals(2, outbox.failedCount());
        assertTrue(outbox.listFailed().stream().anyMatch(f -> "Estoque insuficiente".equals(f.error())
                && f.payload() != null && f.payload().eventId().equals("e9")));

        CacheStore c = new CacheStore(new AppPaths(tmp));
        c.setCompanyId("emp1");
        assertEquals("Cafe", c.products().orElseThrow().get(0).name());
        c.setCompanyId("emp2");
        assertTrue(c.products().isEmpty(), "cache de outra empresa não aparece");

        // Hash antigo migrado continua entrando offline.
        AuthApi.User u = new OfflineUserStore(tmp).authenticate("ana@loja.com", "segredo");
        assertEquals("op1", u.id());

        // Nenhum JSON em texto aberto sobrou.
        assertFalse(Files.exists(tmp.resolve("users.json")));
        assertFalse(Files.exists(tmp.resolve("outbox")));
        assertFalse(Files.exists(tmp.resolve("cache")));

        LocalDb.closeAll();
        for (String f : new String[] {"quack.db", "fila-copia.db"}) {
            String bytes = raw(tmp.resolve(f));
            assertFalse(bytes.contains("123.456.789-09"), f + " sem CPF em texto");
            assertFalse(bytes.contains("Maria"), f + " sem nome de cliente em texto");
        }
    }

    @Test
    void bancoAdulteradoEhDescartadoEAFilaVoltaDaCopia() throws Exception {
        OutboxStore outbox = new OutboxStore(new AppPaths(tmp));
        outbox.enqueue(sale("e1", "18.90"));
        outbox.enqueue(sale("e2", "5.00"));
        LocalDb.closeAll();

        // Alguém mexe no arquivo do banco (ex.: tenta trocar um preço).
        Path db = tmp.resolve("quack.db");
        byte[] bytes = Files.readAllBytes(db);
        for (int i = 0; i < 64; i++) {
            bytes[i] ^= 0x5A;
        }
        Files.write(db, bytes);

        List<OutboxStore.Entry> fila = new OutboxStore(new AppPaths(tmp)).listPending();
        assertEquals(List.of("e1", "e2"), fila.stream().map(e -> e.payload().eventId()).toList());
        assertTrue(LocalDb.drainNotices().stream().anyMatch(n -> n.contains("recuperada")));
        try (Stream<Path> s = Files.list(tmp)) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().startsWith("quack.db.ilegivel-")),
                    "o arquivo adulterado fica guardado à parte");
        }
    }

    @Test
    void removerEFalharAtualizamACopia() {
        OutboxStore outbox = new OutboxStore(new AppPaths(tmp));
        String a = outbox.enqueue(sale("e1", "1.00"));
        String b = outbox.enqueue(sale("e2", "2.00"));
        outbox.remove(a);
        outbox.moveToFailed(b, "Recusada");
        LocalDb.closeAll();

        // Perde o banco principal: a cópia não pode ressuscitar a venda já enviada.
        assertTrue(tmp.resolve("quack.db").toFile().delete());
        OutboxStore again = new OutboxStore(new AppPaths(tmp));
        assertEquals(0, again.pendingCount());
        assertEquals(1, again.failedCount());
    }

    @Test
    void chavePerdidaGuardaOBancoAntigoECriaOutro() throws Exception {
        new OutboxStore(new AppPaths(tmp)).enqueue(sale("e1", "1.00"));
        LocalDb.closeAll();
        Files.delete(tmp.resolve(DeviceKey.FILE_NAME));

        assertEquals(0, new OutboxStore(new AppPaths(tmp)).pendingCount());
        assertTrue(LocalDb.drainNotices().stream().anyMatch(n -> n.contains("chave")));
        try (Stream<Path> s = Files.list(tmp)) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().startsWith("quack.db.ilegivel-")));
        }
    }

    @Test
    void chaveProtegidaPeloWindows() throws Exception {
        new OutboxStore(new AppPaths(tmp)).pendingCount();
        byte[] key = Files.readAllBytes(tmp.resolve(DeviceKey.FILE_NAME));
        if (DeviceKey.isWindows()) {
            assertEquals("QKDPAPI1", new String(key, 0, 8, StandardCharsets.US_ASCII));
            assertTrue(key.length > 40, "blob DPAPI, não a chave crua");
        }
    }
}
