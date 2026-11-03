package br.com.quack.pdv.plataforma.numeracao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ponto unico de numeracao de documentos (D4): numero da venda, numero fiscal e numero do inventario. So numera dentro
 * da transacao de quem grava (MANDATORY). No modo loja (fase 4b) so o servidor da loja numera as empresas dele, e esse
 * controle entra aqui. O SQL e as dicas de bloqueio sao os do .NET (NextSaleNumberAsync, AlocarProximoNumeroAsync).
 */
@Component
public class NumeradorDocumentos {

    /** Base do contador de vendas da empresa principal (o .NET inicia nesse valor + 1). */
    private static final int BASE_EMPRESA_PRINCIPAL = 15039;

    private static final String EMPRESA_PRINCIPAL = "empresa-principal";

    private static final String SQL_PROXIMA_VENDA = """
            SELECT CONVERT(NVARCHAR(30), ISNULL(MAX(TRY_CONVERT(INT, SaleNumber)), ?) + 1)
            FROM Vendas WITH (UPDLOCK, HOLDLOCK)
            WHERE CompanyId = ?
            """;

    private static final String SQL_GARANTIR_SEQUENCIA = """
            IF NOT EXISTS (
                SELECT 1 FROM FiscalSequencias
                 WHERE CompanyId = ? AND Modelo = ? AND Serie = ? AND Ambiente = ?)
                INSERT INTO FiscalSequencias (CompanyId, Modelo, Serie, Ambiente, ProximoNumero)
                VALUES (?, ?, ?, ?, 1)
            """;

    private static final String SQL_ALOCAR_FISCAL = """
            UPDATE FiscalSequencias WITH (UPDLOCK, HOLDLOCK)
               SET ProximoNumero = ProximoNumero + 1,
                   AtualizadoEm = SYSDATETIMEOFFSET()
             OUTPUT DELETED.ProximoNumero
             WHERE CompanyId = ? AND Modelo = ? AND Serie = ? AND Ambiente = ?
            """;

    private static final String SQL_PROXIMO_INVENTARIO =
            "SELECT ISNULL(MAX(Numero), 0) + 1 FROM Inventarios WHERE CompanyId = ?";

    private final JdbcTemplate jdbc;

    public NumeradorDocumentos(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Proximo numero de venda da empresa, como texto (o .NET guarda SaleNumber em NVARCHAR). */
    @Transactional(propagation = Propagation.MANDATORY)
    public String proximoNumeroVenda(String companyId) {
        int base = EMPRESA_PRINCIPAL.equals(companyId) ? BASE_EMPRESA_PRINCIPAL : 0;
        String proximo = jdbc.queryForObject(SQL_PROXIMA_VENDA, String.class, base, companyId);
        return proximo == null || proximo.isBlank() ? Integer.toString(base + 1) : proximo;
    }

    /** Aloca o proximo numero da sequencia fiscal (cria a linha com 1 se nao existir) e devolve o numero alocado. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int proximoNumeroFiscal(String companyId, short modelo, int serie, byte ambiente) {
        jdbc.update(SQL_GARANTIR_SEQUENCIA,
                companyId, modelo, serie, ambiente, companyId, modelo, serie, ambiente);
        Integer alocado = jdbc.queryForObject(SQL_ALOCAR_FISCAL, Integer.class, companyId, modelo, serie, ambiente);
        if (alocado == null) {
            throw new IllegalStateException("Sequencia fiscal nao encontrada para a empresa.");
        }
        return alocado;
    }

    /** Proximo numero de inventario da empresa (maior numero + 1). */
    @Transactional(propagation = Propagation.MANDATORY)
    public int proximoNumeroInventario(String companyId) {
        Integer numero = jdbc.queryForObject(SQL_PROXIMO_INVENTARIO, Integer.class, companyId);
        return numero == null ? 1 : numero;
    }
}
