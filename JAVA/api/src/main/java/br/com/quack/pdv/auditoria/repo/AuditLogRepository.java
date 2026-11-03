package br.com.quack.pdv.auditoria.repo;

import br.com.quack.pdv.auditoria.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}
