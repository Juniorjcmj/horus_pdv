package br.com.quack.pdv.fiscal.fila.app;

import br.com.quack.pdv.plataforma.numeracao.NumeradorDocumentos;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Nota pendente com numero reservado (DocumentoFiscalAB.EnfileirarAsync): roda fora da transacao da venda, em
 * transacao propria. Uma unica NFC-e por venda; venda cancelada nao gera nota nova.
 */
@Service
public class PendenciaFiscal {

    private static final short MODELO_NFCE = 65;

    private final JdbcTemplate jdbc;
    private final NumeradorDocumentos numerador;
    private final TransactionTemplate tx;

    public PendenciaFiscal(JdbcTemplate jdbc, NumeradorDocumentos numerador,
                           PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.numerador = numerador;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Devolve o id da NFC-e da venda (a existente, ou a nova pendente). */
    public String enfileirar(String companyId, String vendaId) {
        return tx.execute(s -> gravar(companyId, vendaId));
    }

    private String gravar(String companyId, String vendaId) {
        List<String> status = jdbc.query("""
                SELECT COALESCE(Status, 'finalizada') FROM Vendas WITH (UPDLOCK, HOLDLOCK)
                 WHERE CompanyId = ? AND Id = ?;
                """, (rs, n) -> rs.getString(1), companyId, vendaId);
        if (status.isEmpty()) {
            throw new IllegalArgumentException("Venda não encontrada para a empresa.");
        }
        List<String> existente = jdbc.query("""
                SELECT TOP 1 Id FROM DocumentosFiscais WITH (UPDLOCK, HOLDLOCK)
                 WHERE CompanyId = ? AND VendaId = ? AND Modelo = 65;
                """, (rs, n) -> rs.getString(1), companyId, vendaId);
        if (!existente.isEmpty()) {
            return existente.getFirst();
        }
        if ("cancelada".equalsIgnoreCase(status.getFirst())) {
            throw new IllegalArgumentException("Uma venda cancelada não pode gerar nova NFC-e.");
        }
        int[] config = configFiscal(companyId);
        int ambiente = config[0];
        int serie = config[1];
        int numero = numerador.proximoNumeroFiscal(companyId, MODELO_NFCE, serie, (byte) ambiente);
        String id = "doc-" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO DocumentosFiscais
                    (Id, CompanyId, VendaId, Modelo, Serie, NumeroNf, Ambiente, Status, TpEmis)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1);
                """, id, companyId, vendaId, MODELO_NFCE, serie, numero, ambiente);
        return id;
    }

    /** ObterConfigFiscalAsync: {ambiente, serie}; padrao 2 e 2. */
    private int[] configFiscal(String companyId) {
        List<int[]> linhas = jdbc.query("SELECT AmbienteFiscal, ISNULL(SerieNfce, 2) FROM Empresas WHERE Id = ?;",
                (rs, n) -> {
                    Object amb = rs.getObject(1);
                    Object ser = rs.getObject(2);
                    return new int[] {amb == null ? 2 : ((Number) amb).intValue(),
                            ser == null ? 2 : ((Number) ser).intValue()};
                }, companyId);
        return linhas.isEmpty() ? new int[] {2, 2} : linhas.getFirst();
    }
}
