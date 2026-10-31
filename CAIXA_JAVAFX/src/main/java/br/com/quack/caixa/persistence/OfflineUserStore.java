package br.com.quack.caixa.persistence;

import br.com.quack.caixa.api.AuthApi;
import br.com.quack.caixa.domain.DomainException;
import br.com.quack.caixa.domain.PasswordHash;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Credenciais para o login offline (porta de UserRepository.ts): só o hash PBKDF2 da senha (salt aleatório), nunca
 * a senha. Fica no banco criptografado ({@link LocalDb}, tabela offline_users).
 *
 * <p>O acesso offline vale por até 7 dias desde o último login online. O prazo pode ser reduzido
 * ({@link #setMaxOfflineDays(int)}, chave offlineMaxDays do config), nunca aumentado. Quando o servidor recusa a
 * senha (senha trocada, usuário desativado), {@link #forget(String)} apaga o acesso offline desse usuário.
 */
public class OfflineUserStore {
    public static final int DEFAULT_MAX_OFFLINE_DAYS = 7;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LocalUser(String id, String companyId, String email, String name, String role, String passwordHash,
            String lastOnlineLoginAt, int maxOfflineDays) {
    }

    private final Path root;
    private final Supplier<Instant> clock;
    private volatile int maxOfflineDays = DEFAULT_MAX_OFFLINE_DAYS;

    public OfflineUserStore(Path root) {
        this(root, Instant::now);
    }

    public OfflineUserStore(Path root, Supplier<Instant> clock) {
        this.root = root;
        this.clock = clock;
    }

    /** Prazo do acesso offline em dias, entre 1 e 7 (valores fora disso ficam no limite). */
    public void setMaxOfflineDays(int days) {
        this.maxOfflineDays = Math.max(1, Math.min(DEFAULT_MAX_OFFLINE_DAYS, days));
    }

    public int maxOfflineDays() {
        return maxOfflineDays;
    }

    /** Guarda (ou atualiza) o usuário após um login online bem-sucedido. */
    public synchronized void saveAfterOnlineLogin(AuthApi.User user, String password) {
        if (user == null || user.email() == null || user.email().isBlank() || password == null || password.isEmpty()) {
            return;
        }
        String email = normalize(user.email());
        LocalUser record = new LocalUser(user.id(), nz(user.companyId()), email, nz(user.name()),
                user.role() == null || user.role().isBlank() ? "operador" : user.role(),
                PasswordHash.create(password), clock.get().toString(), maxOfflineDays);
        try {
            LocalDb db = LocalDb.at(root);
            // mesmo id com outro e-mail (e-mail trocado no servidor): remove o registro antigo
            for (LocalUser u : load()) {
                if (!u.email().equals(email) && u.id() != null && u.id().equals(user.id())) {
                    db.userDelete(u.email());
                }
            }
            db.userPut(email, JsonFiles.MAPPER.writeValueAsString(record));
        } catch (JsonProcessingException | RuntimeException e) {
            // Falha local não invalida o login online: só o acesso offline fica indisponível.
            System.err.println("Não foi possível preparar o acesso offline: " + e.getMessage());
        }
    }

    /** Apaga o acesso offline do usuário (o servidor recusou a senha ou o usuário). */
    public synchronized void forget(String email) {
        try {
            LocalDb.at(root).userDelete(normalize(email));
        } catch (RuntimeException e) {
            System.err.println("Não foi possível remover o acesso offline: " + e.getMessage());
        }
    }

    /** Autentica sem internet. Mensagens iguais às do caixa web. */
    public synchronized AuthApi.User authenticate(String email, String password) {
        String normalized = normalize(email);
        LocalUser local = load().stream().filter(u -> u.email().equals(normalized)).findFirst().orElse(null);
        if (local == null) {
            throw new DomainException(
                    "Usuário não cadastrado para acesso offline neste terminal. Faça o primeiro login online.");
        }
        Instant last;
        try {
            last = Instant.parse(local.lastOnlineLoginAt());
        } catch (RuntimeException e) {
            last = Instant.EPOCH;
        }
        int limit = local.maxOfflineDays() > 0 ? Math.min(local.maxOfflineDays(), maxOfflineDays) : maxOfflineDays;
        long minutes = Duration.between(last, clock.get()).toMinutes();
        double elapsedDays = minutes / (60.0 * 24);
        // relógio voltado para antes do último login online também não vale (evita estender o prazo)
        if (minutes < -5 || elapsedDays > limit) {
            throw new DomainException("Período máximo de operação offline excedido ("
                    + (int) Math.floor(Math.abs(elapsedDays)) + "/" + limit
                    + " dias). Conecte-se à internet para revalidar seu acesso.");
        }
        if (!PasswordHash.matches(normalized, password, local.passwordHash())) {
            throw new DomainException("Senha incorreta.");
        }
        return new AuthApi.User(local.id(), local.name(), local.email(), local.role(), local.companyId());
    }

    private List<LocalUser> load() {
        List<LocalUser> out = new ArrayList<>();
        try {
            for (String body : LocalDb.at(root).users()) {
                try {
                    out.add(JsonFiles.MAPPER.readValue(body, LocalUser.class));
                } catch (JsonProcessingException ignored) {
                    // registro ilegível: refeito no próximo login online
                }
            }
        } catch (RuntimeException e) {
            System.err.println("Acesso offline indisponível: " + e.getMessage());
        }
        return out;
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
