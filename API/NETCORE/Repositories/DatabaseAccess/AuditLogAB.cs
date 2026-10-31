/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/AuditLogAB.cs
 * Objetivo: concentra a gravação e leitura da trilha de auditoria genérica (AuditLog).
 * Entradas esperadas: recebe conexão configurada, parâmetros normalizados e executa leitura/escrita no SQL Server.
 *
 * Tabela append-only por design — não existe (nem deve existir) um método de update/delete
 * aqui. Qualquer parte do sistema pode chamar RegistrarAsync; hoje só o módulo de caixa usa.
 */
using HORUSPDV_API.Repositories.DataAccess;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

/// <summary>Valores fixos de EventType usados hoje — mantém os literais num só lugar.</summary>
public static class AuditEventTypes
{
    public const string CaixaAbertura = "CaixaAbertura";
    public const string CaixaFechamento = "CaixaFechamento";
    public const string CaixaReforco = "CaixaReforco";
    public const string CaixaSangria = "CaixaSangria";
    public const string VendaBloqueada = "VendaBloqueada";
    public const string FiadoDebito = "FiadoDebito";
    public const string FiadoRecebimento = "FiadoRecebimento";
    public const string EstoqueRuptura = "EstoqueRuptura";
    public const string VendaCancelada = "VendaCancelada";
    public const string CancelamentoVendaNegado = "CancelamentoVendaNegado";
    public const string NfceNaoEmitida = "NfceNaoEmitida";
    public const string NfceCancelamentoPendente = "NfceCancelamentoPendente";
    public const string AlteracaoPreco = "AlteracaoPreco";
    public const string PrecoOfflineAbaixo = "PrecoOfflineAbaixo";
    public const string VendaCorrigida = "VendaCorrigida";
    public const string ClienteCriado = "ClienteCriado";
    public const string ClienteAtualizado = "ClienteAtualizado";
    public const string ClienteExcluido = "ClienteExcluido";
    public const string FiadoLimiteAlterado = "FiadoLimiteAlterado";
    public const string InventarioAberto = "InventarioAberto";
    public const string InventarioAplicado = "InventarioAplicado";
    public const string InventarioAplicacaoNegada = "InventarioAplicacaoNegada";
    public const string InventarioCancelado = "InventarioCancelado";
}

public class AuditLogAB(Connection connection)
{
    public async Task RegistrarAsync(
        string companyId,
        string userId,
        string userName,
        string eventType,
        string description,
        string? entityType = null,
        string? entityId = null,
        string? ip = null)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(
            """
            INSERT INTO AuditLog (CompanyId, UserId, UserName, EventType, EntityType, EntityId, Description, Ip)
            VALUES (@CompanyId, @UserId, @UserName, @EventType, @EntityType, @EntityId, @Description, @Ip);
            """,
            db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@UserId", userId);
        command.Parameters.AddWithValue("@UserName", userName);
        command.Parameters.AddWithValue("@EventType", eventType);
        command.Parameters.AddWithValue("@EntityType", (object?)entityType ?? DBNull.Value);
        command.Parameters.AddWithValue("@EntityId", (object?)entityId ?? DBNull.Value);
        command.Parameters.AddWithValue("@Description", description);
        command.Parameters.AddWithValue("@Ip", (object?)ip ?? DBNull.Value);
        await command.ExecuteNonQueryAsync();
    }

    public async Task RegistrarNaTransacaoAsync(SqlConnection db, SqlTransaction transaction, string companyId, string userName, string eventType, string description, string entityType, string entityId)
    {
        await using var command = new SqlCommand("INSERT INTO AuditLog (CompanyId, UserId, UserName, EventType, EntityType, EntityId, Description, Ip) VALUES (@CompanyId, '', @UserName, @EventType, @EntityType, @EntityId, @Description, NULL)", db, transaction);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@UserName", userName);
        command.Parameters.AddWithValue("@EventType", eventType);
        command.Parameters.AddWithValue("@EntityType", entityType);
        command.Parameters.AddWithValue("@EntityId", entityId);
        command.Parameters.AddWithValue("@Description", description);
        await command.ExecuteNonQueryAsync();
    }

    /// <summary>Grava na transação da operação (tudo ou nada), com usuário e IP. Descrição limitada a 500.</summary>
    public static async Task GravarNaTransacaoAsync(SqlConnection db, SqlTransaction transaction, string companyId,
        string userId, string userName, string eventType, string description, string entityType, string entityId,
        string? ip)
    {
        await using var command = new SqlCommand(
            "INSERT INTO AuditLog (CompanyId, UserId, UserName, EventType, EntityType, EntityId, Description, Ip) " +
            "VALUES (@CompanyId, @UserId, @UserName, @EventType, @EntityType, @EntityId, @Description, @Ip)",
            db, transaction);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@UserId", Corta(userId, 40));
        command.Parameters.AddWithValue("@UserName", Corta(userName, 180));
        command.Parameters.AddWithValue("@EventType", eventType);
        command.Parameters.AddWithValue("@EntityType", entityType);
        command.Parameters.AddWithValue("@EntityId", Corta(entityId, 80));
        command.Parameters.AddWithValue("@Description", Corta(description, 500));
        command.Parameters.AddWithValue("@Ip", string.IsNullOrWhiteSpace(ip) ? DBNull.Value : Corta(ip, 64));
        await command.ExecuteNonQueryAsync();
    }

    private static string Corta(string? value, int max) =>
        string.IsNullOrEmpty(value) ? string.Empty : value.Length <= max ? value : value[..max];

    /// <summary>Últimos registros da empresa — o filtro por período/tipo é feito no relatório (RelatorioAB), igual ao resto dos relatórios do sistema.</summary>
    public async Task<List<AuditLogAD>> ListarAsync(string companyId, int limite = 2000)
    {
        var sql = $"""
            SELECT TOP (@Limite) Id, OccurredAt, UserId, UserName, EventType, EntityType, EntityId, Description, Ip
            FROM AuditLog
            WHERE CompanyId = @CompanyId
            ORDER BY OccurredAt DESC;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@Limite", limite);
        await using var reader = await command.ExecuteReaderAsync();
        var rows = new List<AuditLogAD>();
        while (await reader.ReadAsync())
        {
            rows.Add(Map(reader));
        }

        return rows;
    }

    private static AuditLogAD Map(SqlDataReader reader) => new()
    {
        Id = reader.GetInt64(reader.GetOrdinal("Id")),
        OccurredAt = reader.GetDateTimeOffset(reader.GetOrdinal("OccurredAt")),
        UserId = ReadString(reader, "UserId"),
        UserName = ReadString(reader, "UserName"),
        EventType = ReadString(reader, "EventType"),
        EntityType = ReadNullableString(reader, "EntityType"),
        EntityId = ReadNullableString(reader, "EntityId"),
        Description = ReadString(reader, "Description"),
        Ip = ReadNullableString(reader, "Ip"),
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
}
