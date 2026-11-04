package br.com.quack.pdv.plataforma.sincronizacao.nuvem;

import br.com.quack.pdv.plataforma.sincronizacao.protocolo.BlocoTabela;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.ParteLote;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.SerializadorLote;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Recebe as partes de um lote (D4): confere empresa, idempotencia (aplicadoAte) e versao de partida, guarda as partes
 * em SyncLotePartes e, ao chegar a ultima, aplica tudo numa so transacao.
 */
@Component
@org.springframework.context.annotation.Lazy
@Profile("nuvem")
public class ReceptorLotes {

    private static final Logger LOG = LoggerFactory.getLogger(ReceptorLotes.class);

    /** Resultado HTTP: status, aplicadoAte (ou o esperado, no 409), se aplicou agora e a mensagem. */
    public record Resposta(int status, long aplicadoAte, boolean aplicado, String mensagem) {}

    private final JdbcTemplate jdbc;
    private final AplicadorLotes aplicador;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ReceptorLotes(JdbcTemplate jdbc, AplicadorLotes aplicador, PlatformTransactionManager tm, Clock clock) {
        this.jdbc = jdbc;
        this.aplicador = aplicador;
        this.tx = new TransactionTemplate(tm);
        this.clock = clock;
    }

    public Resposta receber(ServidorLoja servidor, byte[] gzip) {
        ParteLote parte;
        try {
            parte = SerializadorLote.deGzip(gzip);
        } catch (IllegalArgumentException e) {
            return new Resposta(400, 0, false, "Lote ilegivel.");
        }
        if (parte.companyId() == null || !parte.companyId().equals(servidor.companyId())) {
            return new Resposta(403, 0, false, "O token nao pertence a empresa do lote.");
        }
        String empresa = servidor.companyId();
        Long aplicado = aplicadoAte(empresa);
        Resposta pre = conferirVersao(parte, aplicado);
        if (pre != null) return pre;
        try {
            if (parte.parte() == 1 && parte.ultima()) {
                return tx.execute(s -> aplicar(servidor, parte, parte.blocos()));
            }
            guardarParte(empresa, parte, gzip);
            List<ParteLote> todas = partesCompletas(empresa, parte.loteId());
            if (todas == null) return new Resposta(200, aplicado == null ? 0 : aplicado, false, "Parte recebida.");
            List<BlocoTabela> blocos = new ArrayList<>();
            for (ParteLote p : todas) blocos.addAll(p.blocos());
            return tx.execute(s -> {
                Resposta r = aplicar(servidor, parte, blocos);
                jdbc.update("DELETE FROM dbo.SyncLotePartes WHERE CompanyId = ? AND LoteId = ?", empresa, parte.loteId());
                return r;
            });
        } catch (LoteRecusadoException e) {
            LOG.error("Lote {} recusado para {}: {}", parte.loteId(), empresa, e.getMessage());
            return new Resposta(422, aplicado == null ? 0 : aplicado, false, e.getMessage());
        } catch (TransientDataAccessException | DataAccessResourceFailureException e) {
            LOG.warn("Falha transitoria ao aplicar o lote {}: {}", parte.loteId(), e.getMessage());
            return new Resposta(503, aplicado == null ? 0 : aplicado, false, "Banco indisponivel; tente de novo.");
        } catch (DataAccessException e) {
            LOG.error("Erro ao aplicar o lote {} de {}: {}", parte.loteId(), empresa, e.getMostSpecificCause().getMessage());
            return new Resposta(422, aplicado == null ? 0 : aplicado, false,
                    "Erro ao aplicar o lote: " + resumo(e.getMostSpecificCause().getMessage()));
        }
    }

    /** Idempotencia e versao de partida. Devolve a resposta pronta ou nulo para seguir. */
    private Resposta conferirVersao(ParteLote p, Long aplicado) {
        if (p.completo() || aplicado == null) return null;
        boolean reenvio = p.de() == p.ate();
        if (p.ate() < aplicado || (p.ate() == aplicado && !reenvio)) {
            return new Resposta(200, aplicado, false, "Lote ja aplicado.");
        }
        if (p.de() != aplicado) return new Resposta(409, aplicado, false, "Versao de partida esperada: " + aplicado + ".");
        return null;
    }

    private Resposta aplicar(ServidorLoja servidor, ParteLote parte, List<BlocoTabela> blocos) {
        String empresa = servidor.companyId();
        // serializa com o detector periodico (mesma trava); dono Transaction exige que a transacao ja tenha aberto
        jdbc.execute("SELECT TOP 0 1 FROM Empresas");
        Integer codigo = jdbc.queryForObject("""
                DECLARE @r INT;
                EXEC @r = sp_getapplock @Resource = ?, @LockMode = 'Exclusive', @LockOwner = 'Transaction', @LockTimeout = 30000;
                SELECT @r;
                """, Integer.class, DetectorNuvem.recurso(empresa));
        if (codigo == null || codigo < 0) {
            throw new org.springframework.dao.CannotAcquireLockException("Trava da sincronizacao da empresa indisponivel (" + codigo + ").");
        }
        // trava a linha de estado da empresa e confere de novo (duas requisicoes em paralelo)
        List<Long> atual = jdbc.query(
                "SELECT AplicadoAte FROM dbo.SyncEstadoNuvem WITH (UPDLOCK, HOLDLOCK) WHERE CompanyId = ?",
                (rs, i) -> rs.getLong(1), empresa);
        Long aplicado = atual.isEmpty() ? null : atual.getFirst();
        Resposta pre = conferirVersao(parte, aplicado);
        if (pre != null) return pre;
        AplicadorLotes.Resumo r = aplicador.aplicar(empresa, blocos, parte.completo());
        OffsetDateTime agora = OffsetDateTime.now(clock);
        if (atual.isEmpty()) {
            jdbc.update("""
                    INSERT INTO dbo.SyncEstadoNuvem (CompanyId, ServidorId, AplicadoAte, UltimoLoteId, UltimaSincronizacaoEm, AtualizadoEm)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, empresa, servidor.id(), parte.ate(), parte.loteId(), agora, agora);
        } else {
            jdbc.update("""
                    UPDATE dbo.SyncEstadoNuvem SET ServidorId = ?, AplicadoAte = ?, UltimoLoteId = ?,
                           UltimaSincronizacaoEm = ?, AtualizadoEm = ? WHERE CompanyId = ?
                    """, servidor.id(), parte.ate(), parte.loteId(), agora, agora, empresa);
        }
        LOG.info("Lote {} de {} aplicado ate {} ({}).", parte.loteId(), empresa, parte.ate(), r);
        return new Resposta(200, parte.ate(), true, "Lote aplicado.");
    }

    private Long aplicadoAte(String empresa) {
        List<Long> l = jdbc.query("SELECT AplicadoAte FROM dbo.SyncEstadoNuvem WHERE CompanyId = ?",
                (rs, i) -> rs.getLong(1), empresa);
        return l.isEmpty() ? null : l.getFirst();
    }

    private void guardarParte(String empresa, ParteLote p, byte[] gzip) {
        tx.executeWithoutResult(s -> {
            // um servidor por empresa, um lote por vez: partes de outro lote sao restos de uma tentativa abandonada
            jdbc.update("DELETE FROM dbo.SyncLotePartes WHERE CompanyId = ? AND LoteId <> ?", empresa, p.loteId());
            jdbc.update("DELETE FROM dbo.SyncLotePartes WHERE CompanyId = ? AND LoteId = ? AND Parte = ?",
                    empresa, p.loteId(), p.parte());
            jdbc.update("""
                    INSERT INTO dbo.SyncLotePartes (CompanyId, LoteId, Parte, TotalPartes, VersaoDe, VersaoAte, Tipo, Payload)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, empresa, p.loteId(), p.parte(), p.ultima() ? p.parte() : 0, p.de(), p.ate(),
                    p.completo() ? "completo" : "incremental", gzip);
        });
    }

    /** As partes do lote em ordem, ou nulo se ainda faltam. */
    private List<ParteLote> partesCompletas(String empresa, String loteId) {
        Integer total = jdbc.queryForObject(
                "SELECT MAX(TotalPartes) FROM dbo.SyncLotePartes WHERE CompanyId = ? AND LoteId = ?", Integer.class, empresa, loteId);
        Integer qtd = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dbo.SyncLotePartes WHERE CompanyId = ? AND LoteId = ?", Integer.class, empresa, loteId);
        if (total == null || total == 0 || qtd == null || qtd != total.intValue()) return null;
        return jdbc.query("SELECT Payload FROM dbo.SyncLotePartes WHERE CompanyId = ? AND LoteId = ? ORDER BY Parte",
                (rs, i) -> SerializadorLote.deGzip(rs.getBytes(1)), empresa, loteId);
    }

    private static String resumo(String s) {
        if (s == null) return "";
        return s.length() <= 300 ? s : s.substring(0, 300);
    }
}
