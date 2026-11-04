package br.com.quack.pdv.plataforma.sincronizacao.tracking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Perfil {@code loja}, na subida: liga o isolamento snapshot (leitura consistente do envio, D4) e, se a loja ja foi
 * ativada (D5), garante o Change Tracking (repara um banco restaurado). Antes da ativacao o tracking fica desligado de
 * proposito: a carga inicial grava num banco vazio e nao pode gerar mudancas para subir. A ativacao (grupo 3) chama
 * {@link ChangeTrackingAdmin#ligarTudo(int)}.
 *
 * <p>Escolha (D3): um {@code ApplicationRunner} no perfil, e nao uma migracao Flyway. A migracao nao conhece o perfil
 * nem o momento da ativacao, e {@code ALTER DATABASE} exige execucao fora de transacao.
 */
@Component
@Profile("loja")
public class ChangeTrackingLoja implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ChangeTrackingLoja.class);

    private final ChangeTrackingAdmin admin;
    private final JdbcTemplate jdbc;

    public ChangeTrackingLoja(ChangeTrackingAdmin admin, JdbcTemplate jdbc) {
        this.admin = admin;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        admin.ligarSnapshot();
        if (lojaAtivada()) {
            int ligadas = admin.ligarTudo(ChangeTrackingAdmin.RETENCAO_LOJA_DIAS);
            if (ligadas > 0) LOG.warn("Change Tracking religado em {} tabelas da loja ativada.", ligadas);
        }
    }

    private boolean lojaAtivada() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.SyncEstado WHERE Id = 1 AND Ativada = 1", Integer.class);
        return n != null && n > 0;
    }
}
