/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/EmpresaTemaAB.cs
 * Objetivo: persistência do tema (cores de acento) por empresa — cor do sistema e cor da frente de
 *           caixa (PDV). A Cloud é a autoridade; os terminais herdam. Camada aditiva, isolada por
 *           CompanyId. Não toca nenhuma tabela existente.
 */
using HORUSPDV_API.Repositories;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

/// <summary>Cores de acento de uma empresa (hex #rrggbb) — nulo = usa o padrão do sistema.</summary>
public sealed class EmpresaTemaDto
{
    public string CompanyId { get; set; } = string.Empty;
    public string? SystemAccent { get; set; }
    public string? PdvAccent { get; set; }
    public DateTime UpdatedAt { get; set; }
}

public class EmpresaTemaAB(Connection connection)
{
    public async Task<EmpresaTemaDto?> GetByCompanyAsync(string companyId, CancellationToken cancellationToken = default)
    {
        const string sql = @"
            SELECT CompanyId, SystemAccent, PdvAccent, UpdatedAt
            FROM EmpresaTema
            WHERE CompanyId = @CompanyId;";

        await using var conn = await connection.OpenConnectionAsync(cancellationToken);
        await using var cmd = new SqlCommand(sql, conn);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);

        await using var reader = await cmd.ExecuteReaderAsync(cancellationToken);
        if (!await reader.ReadAsync(cancellationToken))
        {
            return null;
        }

        return new EmpresaTemaDto
        {
            CompanyId = reader.GetString(0),
            SystemAccent = reader.IsDBNull(1) ? null : reader.GetString(1),
            PdvAccent = reader.IsDBNull(2) ? null : reader.GetString(2),
            UpdatedAt = reader.GetDateTime(3)
        };
    }

    public async Task<EmpresaTemaDto> UpsertAsync(
        string companyId,
        string? systemAccent,
        string? pdvAccent,
        string? updatedBy,
        CancellationToken cancellationToken = default)
    {
        const string sql = @"
            MERGE EmpresaTema AS target
            USING (SELECT @CompanyId AS CompanyId) AS source
            ON target.CompanyId = source.CompanyId
            WHEN MATCHED THEN
                UPDATE SET SystemAccent = @SystemAccent, PdvAccent = @PdvAccent,
                           UpdatedAt = SYSUTCDATETIME(), UpdatedBy = @UpdatedBy
            WHEN NOT MATCHED THEN
                INSERT (CompanyId, SystemAccent, PdvAccent, UpdatedAt, UpdatedBy)
                VALUES (@CompanyId, @SystemAccent, @PdvAccent, SYSUTCDATETIME(), @UpdatedBy);";

        await using (var conn = await connection.OpenConnectionAsync(cancellationToken))
        await using (var cmd = new SqlCommand(sql, conn))
        {
            cmd.Parameters.AddWithValue("@CompanyId", companyId);
            cmd.Parameters.AddWithValue("@SystemAccent", (object?)systemAccent ?? DBNull.Value);
            cmd.Parameters.AddWithValue("@PdvAccent", (object?)pdvAccent ?? DBNull.Value);
            cmd.Parameters.AddWithValue("@UpdatedBy", (object?)updatedBy ?? DBNull.Value);
            await cmd.ExecuteNonQueryAsync(cancellationToken);
        }

        return (await GetByCompanyAsync(companyId, cancellationToken))!;
    }
}
