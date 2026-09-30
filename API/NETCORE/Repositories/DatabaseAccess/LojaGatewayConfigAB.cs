/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/LojaGatewayConfigAB.cs
 * Objetivo: persistência do endereço LAN do Local Gateway (Quack Gateway) por empresa/loja.
 *           A Cloud é a autoridade: o admin grava o endereço e os terminais o leem enquanto online,
 *           usando-o no fallback offline. Camada aditiva — não toca nenhuma tabela existente.
 * Isolamento: toda operação é chaveada por CompanyId (unidade multi-tenant da nuvem).
 */
using HORUSPDV_API.Repositories;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

/// <summary>Projeção do endereço do Gateway de uma loja (nunca contém segredo).</summary>
public sealed class LojaGatewayConfigDto
{
    public string CompanyId { get; set; } = string.Empty;
    public string? StoreId { get; set; }
    public string GatewayUrl { get; set; } = string.Empty;
    public bool Enabled { get; set; }
    public DateTime UpdatedAt { get; set; }
}

public class LojaGatewayConfigAB(Connection connection)
{
    /// <summary>Lê o endereço do Gateway da empresa; null quando não cadastrado.</summary>
    public async Task<LojaGatewayConfigDto?> GetByCompanyAsync(string companyId, CancellationToken cancellationToken = default)
    {
        const string sql = @"
            SELECT CompanyId, StoreId, GatewayUrl, Enabled, UpdatedAt
            FROM LojaGatewayConfig
            WHERE CompanyId = @CompanyId;";

        await using var conn = await connection.OpenConnectionAsync(cancellationToken);
        await using var cmd = new SqlCommand(sql, conn);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);

        await using var reader = await cmd.ExecuteReaderAsync(cancellationToken);
        if (!await reader.ReadAsync(cancellationToken))
        {
            return null;
        }

        return new LojaGatewayConfigDto
        {
            CompanyId = reader.GetString(0),
            StoreId = reader.IsDBNull(1) ? null : reader.GetString(1),
            GatewayUrl = reader.GetString(2),
            Enabled = reader.GetBoolean(3),
            UpdatedAt = reader.GetDateTime(4)
        };
    }

    /// <summary>Cria ou atualiza o endereço do Gateway da empresa (upsert idempotente).</summary>
    public async Task<LojaGatewayConfigDto> UpsertAsync(
        string companyId,
        string gatewayUrl,
        bool enabled,
        string? storeId,
        string? updatedBy,
        CancellationToken cancellationToken = default)
    {
        const string sql = @"
            MERGE LojaGatewayConfig AS target
            USING (SELECT @CompanyId AS CompanyId) AS source
            ON target.CompanyId = source.CompanyId
            WHEN MATCHED THEN
                UPDATE SET GatewayUrl = @GatewayUrl, Enabled = @Enabled, StoreId = @StoreId,
                           UpdatedAt = SYSUTCDATETIME(), UpdatedBy = @UpdatedBy
            WHEN NOT MATCHED THEN
                INSERT (CompanyId, StoreId, GatewayUrl, Enabled, UpdatedAt, UpdatedBy)
                VALUES (@CompanyId, @StoreId, @GatewayUrl, @Enabled, SYSUTCDATETIME(), @UpdatedBy);";

        await using (var conn = await connection.OpenConnectionAsync(cancellationToken))
        await using (var cmd = new SqlCommand(sql, conn))
        {
            cmd.Parameters.AddWithValue("@CompanyId", companyId);
            cmd.Parameters.AddWithValue("@GatewayUrl", gatewayUrl);
            cmd.Parameters.AddWithValue("@Enabled", enabled);
            cmd.Parameters.AddWithValue("@StoreId", (object?)storeId ?? DBNull.Value);
            cmd.Parameters.AddWithValue("@UpdatedBy", (object?)updatedBy ?? DBNull.Value);
            await cmd.ExecuteNonQueryAsync(cancellationToken);
        }

        return (await GetByCompanyAsync(companyId, cancellationToken))!;
    }
}
