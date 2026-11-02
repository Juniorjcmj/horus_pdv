package br.com.quack.pdv.auditoria.app;

import br.com.quack.pdv.auditoria.domain.AuditLog;
import br.com.quack.pdv.auditoria.repo.AuditLogRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Grava eventos na tabela AuditLog (append-only), cortando os textos nos limites das colunas. */
@Service
public class AuditoriaService {

    private final AuditLogRepository repo;
    private final Clock clock;

    public AuditoriaService(AuditLogRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    @Transactional
    public AuditLog registrar(String companyId, String userId, String userName, String eventType, String description,
                              String entityType, String entityId, String ip) {
        AuditLog log = new AuditLog();
        log.setCompanyId(companyId);
        log.setOccurredAt(OffsetDateTime.now(clock));
        log.setUserId(corta(userId, 40));
        log.setUserName(corta(userName, 180));
        log.setEventType(eventType);
        log.setEntityType(entityType);
        log.setEntityId(entityId == null ? null : corta(entityId, 80));
        log.setDescription(corta(description, 500));
        log.setIp(ip == null || ip.isBlank() ? null : corta(ip, 64));
        return repo.save(log);
    }

    static String corta(String valor, int max) {
        if (valor == null || valor.isEmpty()) return "";
        return valor.length() <= max ? valor : valor.substring(0, max);
    }
}
