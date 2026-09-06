/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/CaixaAB.cs
 * Objetivo: concentra comandos SQL e persistência de abertura, fechamento e status de caixa.
 * Entradas esperadas: recebe conexão configurada, parâmetros normalizados e executa leitura/escrita no SQL Server.
 */
using HORUSPDV_API.Repositories.DataAccess;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public class CaixaAB(Connection connection)
{
    public async Task<List<CaixaSessionAD>> ListarSessoesAsync(string companyId)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(
            """
            SELECT Id, OpenedAt, ClosedAt, OpeningAmount, ClosingAmount, OperatorName, ClosedByName, Note,
                   ExpectedCashAmount, DifferenceAmount, DifferenceReason
            FROM CaixaSessoes
            WHERE CompanyId = @CompanyId
            ORDER BY OpenedAt DESC;
            """,
            db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        await using var reader = await command.ExecuteReaderAsync();
        var rows = new List<CaixaSessionAD>();
        while (await reader.ReadAsync())
        {
            rows.Add(Map(reader));
        }

        return rows;
    }

    public async Task<CaixaSessionAD?> ObterSessaoAbertaAsync(string companyId)
        => (await ListarSessoesAsync(companyId)).FirstOrDefault(item => item.ClosedAt is null);

    public async Task AbrirAsync(
        string id,
        string companyId,
        DateTimeOffset openedAt,
        decimal openingAmount,
        string operatorId,
        string operatorName)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(
            """
            INSERT INTO CaixaSessoes
                (Id, CompanyId, OpenedAt, OpeningAmount, ClosingAmount, OperatorId, OperatorName, ClosedById, ClosedByName, Note)
            VALUES
                (@Id, @CompanyId, @OpenedAt, @OpeningAmount, 0, @OperatorId, @OperatorName, N'', N'', N'');
            """,
            db);
        command.Parameters.AddWithValue("@Id", id);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@OpenedAt", openedAt);
        command.Parameters.AddWithValue("@OpeningAmount", openingAmount);
        command.Parameters.AddWithValue("@OperatorId", operatorId);
        command.Parameters.AddWithValue("@OperatorName", operatorName);
        await command.ExecuteNonQueryAsync();
    }

    public async Task FecharAsync(
        string id,
        string companyId,
        DateTimeOffset closedAt,
        decimal closingAmount,
        string closedById,
        string closedByName,
        string note,
        decimal expectedCashAmount,
        decimal differenceAmount,
        string? differenceReason)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(
            """
            UPDATE CaixaSessoes
               SET ClosedAt = @ClosedAt,
                   ClosingAmount = @ClosingAmount,
                   ClosedById = @ClosedById,
                   ClosedByName = @ClosedByName,
                   Note = @Note,
                   ExpectedCashAmount = @ExpectedCashAmount,
                   DifferenceAmount = @DifferenceAmount,
                   DifferenceReason = @DifferenceReason
             WHERE Id = @Id AND CompanyId = @CompanyId;
            """,
            db);
        command.Parameters.AddWithValue("@ClosedAt", closedAt);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@ClosingAmount", closingAmount);
        command.Parameters.AddWithValue("@ClosedById", closedById);
        command.Parameters.AddWithValue("@ClosedByName", closedByName);
        command.Parameters.AddWithValue("@Note", note);
        command.Parameters.AddWithValue("@ExpectedCashAmount", expectedCashAmount);
        command.Parameters.AddWithValue("@DifferenceAmount", differenceAmount);
        command.Parameters.AddWithValue("@DifferenceReason", (object?)differenceReason ?? DBNull.Value);
        command.Parameters.AddWithValue("@Id", id);
        await command.ExecuteNonQueryAsync();
    }

    /// <summary>Soma as vendas da empresa por forma de pagamento entre duas datas — usado na conferência do fechamento de caixa.</summary>
    public async Task<Dictionary<string, decimal>> ObterTotaisPorFormaPagamentoAsync(
        string companyId, DateTimeOffset desde, DateTimeOffset ate)
    {
        const string sql = """
            SELECT Combined.PaymentType, SUM(Combined.Amount) AS Total
            FROM (
                SELECT vp.PaymentType, vp.Amount
                FROM VendaPagamentos vp
                INNER JOIN Vendas v ON v.Id = vp.VendaId AND v.CompanyId = vp.CompanyId
                WHERE vp.CompanyId = @CompanyId AND v.SaleDate >= @Desde AND v.SaleDate <= @Ate

                UNION ALL

                SELECT v.PaymentType, v.TotalAmount AS Amount
                FROM Vendas v
                WHERE v.CompanyId = @CompanyId AND v.SaleDate >= @Desde AND v.SaleDate <= @Ate
                  AND NOT EXISTS (SELECT 1 FROM VendaPagamentos vp WHERE vp.VendaId = v.Id)
            ) Combined
            GROUP BY Combined.PaymentType;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@Desde", desde);
        command.Parameters.AddWithValue("@Ate", ate);
        await using var reader = await command.ExecuteReaderAsync();
        var totals = new Dictionary<string, decimal>(StringComparer.OrdinalIgnoreCase);
        while (await reader.ReadAsync())
        {
            var paymentType = ReadString(reader, "PaymentType");
            var total = reader.IsDBNull(reader.GetOrdinal("Total")) ? 0m : reader.GetDecimal(reader.GetOrdinal("Total"));
            totals[paymentType] = total;
        }

        return totals;
    }

    public async Task RegistrarMovimentoAsync(string companyId, CaixaMovimentoAD movimento)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(
            """
            INSERT INTO CaixaMovimentos (Id, CompanyId, CaixaSessaoId, Tipo, Valor, Motivo, CreatedAt, OperatorId, OperatorName)
            VALUES (@Id, @CompanyId, @CaixaSessaoId, @Tipo, @Valor, @Motivo, @CreatedAt, @OperatorId, @OperatorName);
            """,
            db);
        command.Parameters.AddWithValue("@Id", movimento.Id);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@CaixaSessaoId", movimento.CaixaSessaoId);
        command.Parameters.AddWithValue("@Tipo", (byte)movimento.Tipo);
        command.Parameters.AddWithValue("@Valor", movimento.Valor);
        command.Parameters.AddWithValue("@Motivo", movimento.Motivo);
        command.Parameters.AddWithValue("@CreatedAt", movimento.CreatedAt);
        command.Parameters.AddWithValue("@OperatorId", movimento.OperatorId);
        command.Parameters.AddWithValue("@OperatorName", movimento.OperatorName);
        await command.ExecuteNonQueryAsync();
    }

    public async Task<List<CaixaMovimentoAD>> ListarMovimentosAsync(string companyId, string caixaSessaoId)
    {
        const string sql = """
            SELECT Id, CaixaSessaoId, Tipo, Valor, Motivo, CreatedAt, OperatorId, OperatorName
            FROM CaixaMovimentos
            WHERE CompanyId = @CompanyId AND CaixaSessaoId = @CaixaSessaoId
            ORDER BY CreatedAt ASC;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@CaixaSessaoId", caixaSessaoId);
        await using var reader = await command.ExecuteReaderAsync();
        var rows = new List<CaixaMovimentoAD>();
        while (await reader.ReadAsync())
        {
            rows.Add(MapMovimento(reader));
        }

        return rows;
    }

    private static CaixaMovimentoAD MapMovimento(SqlDataReader reader) => new()
    {
        Id = ReadString(reader, "Id"),
        CaixaSessaoId = ReadString(reader, "CaixaSessaoId"),
        Tipo = (TipoMovimentoCaixa)reader.GetByte(reader.GetOrdinal("Tipo")),
        Valor = reader.GetDecimal(reader.GetOrdinal("Valor")),
        Motivo = ReadString(reader, "Motivo"),
        CreatedAt = reader.GetDateTimeOffset(reader.GetOrdinal("CreatedAt")),
        OperatorId = ReadString(reader, "OperatorId"),
        OperatorName = ReadString(reader, "OperatorName"),
    };

    private static CaixaSessionAD Map(SqlDataReader reader) => new()
    {
        Id = ReadString(reader, "Id"),
        OpenedAt = reader.GetDateTimeOffset(reader.GetOrdinal("OpenedAt")),
        ClosedAt = reader.IsDBNull(reader.GetOrdinal("ClosedAt"))
            ? null
            : reader.GetDateTimeOffset(reader.GetOrdinal("ClosedAt")),
        OpeningAmount = reader.GetDecimal(reader.GetOrdinal("OpeningAmount")),
        ClosingAmount = reader.GetDecimal(reader.GetOrdinal("ClosingAmount")),
        OperatorName = ReadString(reader, "OperatorName"),
        ClosedByName = ReadString(reader, "ClosedByName"),
        Note = ReadString(reader, "Note"),
        ExpectedCashAmount = ReadNullableDecimal(reader, "ExpectedCashAmount"),
        DifferenceAmount = ReadNullableDecimal(reader, "DifferenceAmount"),
        DifferenceReason = ReadNullableString(reader, "DifferenceReason"),
    };

    private static string ReadString(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? string.Empty : reader.GetString(ordinal);
    }

    private static string? ReadNullableString(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? null : reader.GetString(ordinal);
    }

    private static decimal? ReadNullableDecimal(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? null : reader.GetDecimal(ordinal);
    }
}
