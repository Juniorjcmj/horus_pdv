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

    /** Trava de aplicacao por empresa, presa a transacao (liberada no commit/rollback). Espera ate 30 s. */
    private static final String SQL_TRAVA_VENDAS = """
            DECLARE @r INT;
            EXEC @r = sp_getapplock @Resource = ?, @LockMode = 'Exclusive', @LockOwner = 'Transaction',
                 @LockTimeout = 30000;
            SELECT @r;
            """;

    private final JdbcTemplate jdbc;

    public NumeradorDocumentos(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Primeiro passo de toda transacao que numera venda: serializa as vendas da empresa ANTES de qualquer trava de
     * produto, movimento de estoque ou do proprio numerador. Sem isso, a ordem das travas era baixa (produtos e
     * movimentos) -> MAX(SaleNumber) UPDLOCK/HOLDLOCK -> UPDATE de vinculo que varre EstoqueMovimentacoes da empresa:
     * quem segurava o numerador esperava as linhas ainda nao confirmadas de quem esperava o numerador (deadlock com 7
     * caixas). A .NET tem a mesma ordem e a mesma corrida; aqui a ordem global passa a ser trava da empresa primeiro.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void serializarVendas(String companyId) {
        // sp_getapplock com dono Transaction exige transacao aberta; com IMPLICIT_TRANSACTIONS (autocommit off do
        // driver) so uma instrucao sobre tabela abre a transacao, e um EXEC sozinho devolve -999.
        jdbc.execute("SELECT TOP 0 1 FROM Empresas");
        Integer codigo = jdbc.queryForObject(SQL_TRAVA_VENDAS, Integer.class, "quack:vendas:" + companyId);
        if (codigo == null || codigo < 0) {
            throw new org.springframework.dao.CannotAcquireLockException(
                    "Nao foi possivel obter a trava de numeracao de vendas da empresa (codigo " + codigo + ").");
        }
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

    /**
     * Recuperacao pos-restauracao: a sequencia fiscal (modelo 65) nunca fica abaixo do maior numero de NFC-e que a loja tem, e
     * ganha linha se faltar (senao {@link #proximoNumeroFiscal} criaria com 1 e repetiria numero). Devolve as linhas mexidas.
     */
    public int alinharSequenciaFiscalComDocumentos() {
        int criadas = jdbc.update("""
                INSERT INTO FiscalSequencias (CompanyId, Modelo, Serie, Ambiente, ProximoNumero, AtualizadoEm)
                SELECT d.CompanyId, d.Modelo, d.Serie, d.Ambiente, d.Maior + 1, SYSDATETIMEOFFSET()
                  FROM (SELECT CompanyId, Modelo, Serie, Ambiente, MAX(NumeroNf) AS Maior FROM DocumentosFiscais
                         WHERE Modelo = 65 GROUP BY CompanyId, Modelo, Serie, Ambiente) d
                 WHERE NOT EXISTS (SELECT 1 FROM FiscalSequencias f WHERE f.CompanyId = d.CompanyId
                       AND f.Modelo = d.Modelo AND f.Serie = d.Serie AND f.Ambiente = d.Ambiente)
                """);
        int avancadas = jdbc.update("""
                UPDATE f SET ProximoNumero = d.Maior + 1, AtualizadoEm = SYSDATETIMEOFFSET()
                  FROM FiscalSequencias f
                  JOIN (SELECT CompanyId, Modelo, Serie, Ambiente, MAX(NumeroNf) AS Maior FROM DocumentosFiscais
                         WHERE Modelo = 65 GROUP BY CompanyId, Modelo, Serie, Ambiente) d
                    ON f.CompanyId = d.CompanyId AND f.Modelo = d.Modelo AND f.Serie = d.Serie AND f.Ambiente = d.Ambiente
                 WHERE f.ProximoNumero <= d.Maior
                """);
        return criadas + avancadas;
    }
}
