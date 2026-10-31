package br.com.quack.caixa.service;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.caixa.api.AuthApi;
import br.com.quack.caixa.api.dto.CaixaStatusDto;
import br.com.quack.caixa.api.dto.VendaPayload;
import br.com.quack.caixa.domain.DomainException;
import br.com.quack.caixa.domain.PasswordHash;
import br.com.quack.caixa.persistence.OfflineUserStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Login offline e caixa sem conexão (portas de UserRepository.ts e CashSessionRepository.ts). */
class OfflineModeTest {
    @TempDir
    Path tmp;

    private static final AuthApi.User ANA = new AuthApi.User("op1", "Ana", "Ana@Loja.com", "caixa", "emp1");

    @Test
    void hashAntigoIgualAoDaWebContinuaValendo() {
        // Formato antigo: PBKDF2-SHA256, 100.000 iterações, salt "horus-pdv-pwd-salt:<email>".
        String a = PasswordHash.compute(" Ana@Loja.com ", "segredo");
        assertEquals(64, a.length());
        assertEquals(a, PasswordHash.compute("ana@loja.com", "segredo"));
        assertNotEquals(a, PasswordHash.compute("bia@loja.com", "segredo"));
        assertTrue(PasswordHash.isLegacy(a));
        assertTrue(PasswordHash.matches("ana@loja.com", "segredo", a));
        assertFalse(PasswordHash.matches("ana@loja.com", "errada", a));
    }

    @Test
    void hashNovoTemSaltAleatorio() {
        String a = PasswordHash.create("segredo");
        String b = PasswordHash.create("segredo");
        assertTrue(a.startsWith("pbkdf2-sha256$310000$"));
        assertNotEquals(a, b, "mesma senha gera hashes diferentes");
        assertFalse(PasswordHash.isLegacy(a));
        assertTrue(PasswordHash.matches("qualquer@email", "segredo", a));
        assertFalse(PasswordHash.matches("qualquer@email", "segredo2", a));
        assertFalse(PasswordHash.matches("x", "segredo", "pbkdf2-sha256$1$00$00"), "iterações baixas recusadas");
    }

    @Test
    void loginOfflineAposPrimeiroLoginOnline() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-01T12:00:00Z"));
        OfflineUserStore store = new OfflineUserStore(tmp, now::get);

        DomainException semCadastro = assertThrows(DomainException.class,
                () -> store.authenticate("ana@loja.com", "segredo"));
        assertTrue(semCadastro.getMessage().startsWith("Usuário não cadastrado para acesso offline"));

        store.saveAfterOnlineLogin(ANA, "segredo");
        AuthApi.User u = store.authenticate("ANA@loja.com", "segredo");
        assertEquals("op1", u.id());
        assertEquals("emp1", u.companyId());

        assertEquals("Senha incorreta.",
                assertThrows(DomainException.class, () -> store.authenticate("ana@loja.com", "errada")).getMessage());

        now.set(now.get().plus(Duration.ofDays(8)));
        DomainException expirou = assertThrows(DomainException.class,
                () -> store.authenticate("ana@loja.com", "segredo"));
        assertTrue(expirou.getMessage().startsWith("Período máximo de operação offline excedido (8/7 dias)"));
    }

    @Test
    void naoGuardaASenhaNemOEmailEmTexto() throws Exception {
        new OfflineUserStore(tmp).saveAfterOnlineLogin(ANA, "segredo-123");
        br.com.quack.caixa.persistence.LocalDb.closeAll();
        assertFalse(java.nio.file.Files.exists(tmp.resolve("users.json")));
        byte[] db = java.nio.file.Files.readAllBytes(tmp.resolve("quack.db"));
        String raw = new String(db, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("segredo-123"));
        assertFalse(raw.contains("ana@loja.com"), "banco criptografado");
        assertFalse(raw.startsWith("SQLite format 3"), "não é um SQLite em texto aberto");
    }

    @Test
    void senhaRecusadaNoServidorApagaOAcessoOffline() {
        OfflineUserStore store = new OfflineUserStore(tmp);
        store.saveAfterOnlineLogin(ANA, "segredo");
        assertEquals("op1", store.authenticate("ana@loja.com", "segredo").id());
        store.forget("ANA@loja.com ");
        assertThrows(DomainException.class, () -> store.authenticate("ana@loja.com", "segredo"));
    }

    @Test
    void prazoOfflineSoDiminui() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-01T12:00:00Z"));
        OfflineUserStore store = new OfflineUserStore(tmp, now::get);
        store.setMaxOfflineDays(30);
        assertEquals(7, store.maxOfflineDays(), "não passa de 7 dias");
        store.setMaxOfflineDays(2);
        store.saveAfterOnlineLogin(ANA, "segredo");
        now.set(now.get().plus(Duration.ofDays(3)));
        DomainException e = assertThrows(DomainException.class, () -> store.authenticate("ana@loja.com", "segredo"));
        assertTrue(e.getMessage().contains("(3/2 dias)"), e.getMessage());

        // relógio voltado para antes do último login online também bloqueia
        now.set(Instant.parse("2026-09-20T12:00:00Z"));
        assertThrows(DomainException.class, () -> store.authenticate("ana@loja.com", "segredo"));
    }

    @Test
    void caixaOfflineAbreMovimentaEFecha() {
        Instant t0 = Instant.parse("2026-10-31T10:00:00Z");
        CaixaStatusDto aberto = LocalCash.open(null, "100,00", "op1", "Ana", t0);
        assertTrue(aberto.canSell());
        assertTrue(aberto.currentSession().id().matches("^cx-[A-Za-z0-9-]{1,36}$"), "formato aceito pela API");
        assertEquals("100,00", aberto.currentSession().expectedCashAmount());

        assertThrows(DomainException.class, () -> LocalCash.open(aberto, "50,00", "op1", "Ana", t0));

        CaixaStatusDto comSangria = LocalCash.movement(aberto, "Sangria", "30,00", "Banco", "Ana",
                t0.plusSeconds(60));
        CaixaStatusDto comReforco = LocalCash.movement(comSangria, "Reforco", "10,00", "Troco", "Ana",
                t0.plusSeconds(120));
        assertEquals(2, comReforco.currentSession().movimentos().size());

        // Venda em dinheiro (25,00) e em PIX (40,00) ainda na fila, depois da abertura.
        List<VendaPayload> fila = List.of(sale("2026-10-31T10:05:00Z", "dinheiro", "25.00"),
                sale("2026-10-31T10:06:00Z", "pix", "40.00"), sale("2026-10-31T09:00:00Z", "dinheiro", "999.00"));
        CaixaStatusDto vivo = LocalCash.live(comReforco, t0.plusSeconds(600), fila);
        // 100 + 25 (dinheiro) + 10 (reforço) - 30 (sangria) = 105; a venda das 09:00 é de antes da abertura.
        assertEquals("105,00", vivo.currentSession().expectedCashAmount());
        assertEquals(10, vivo.currentSession().elapsedMinutes());
        assertTrue(vivo.currentSession().paymentBreakdown().stream()
                .anyMatch(b -> b.paymentType().equals("pix") && b.total().equals("40,00")));

        // Gravar só o status base: recalcular não pode somar a fila duas vezes.
        assertEquals("105,00", LocalCash.live(comReforco, t0.plusSeconds(700), fila).currentSession()
                .expectedCashAmount());

        CaixaStatusDto fechado = LocalCash.close(comReforco, "100,00", "", "Faltou troco", "Ana",
                t0.plusSeconds(900), fila);
        assertFalse(fechado.canSell());
        assertEquals("105,00", fechado.lastSession().expectedCashAmount());
        assertEquals("-5,00", fechado.lastSession().differenceAmount());
        assertEquals(aberto.currentSession().id(), fechado.lastSession().id());
        assertThrows(DomainException.class, () -> LocalCash.movement(fechado, "Sangria", "1,00", "x", "Ana",
                t0.plusSeconds(901)));
    }

    private static VendaPayload sale(String at, String type, String amount) {
        BigDecimal v = new BigDecimal(amount);
        return new VendaPayload("c" + at, "e" + at, "SALE_CREATED", at, "OFF-1", "h", true, "Consumidor", "-", type,
                v.toPlainString().replace('.', ','), "Ana", "op1",
                List.of(new VendaPayload.Item("1", "X", BigDecimal.ONE, v, BigDecimal.ZERO, v, null)),
                List.of(new VendaPayload.Payment(type, v, null, null)));
    }
}
