/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/GatewayTokenAB.cs
 * Objetivo: tokens por loja do Local Gateway (Gateway → Cloud). Gera (o texto só sai uma vez), lista,
 *           revoga e valida. No banco fica apenas o SHA-256 do token.
 * Isolamento: toda operação administrativa é chaveada por CompanyId; a validação devolve a empresa do token.
 */
using System.Security.Cryptography;
using System.Text;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

/// <summary>Projeção de um token para listagem (nunca contém o segredo).</summary>
public sealed class GatewayTokenDto
{
    public string Id { get; set; } = string.Empty;
    public string? StoreId { get; set; }
    public string Nome { get; set; } = string.Empty;
    public string TokenPrefix { get; set; } = string.Empty;
    public DateTimeOffset CreatedAt { get; set; }
    public string? CreatedBy { get; set; }
    public DateTimeOffset? LastUsedAt { get; set; }
    public DateTimeOffset? RevokedAt { get; set; }
}

/// <summary>Resultado da validação: token ativo e, se informado, o operador (ativo, da mesma empresa).</summary>
public sealed record GatewayTokenValidation(
    string TokenId,
    string CompanyId,
    string? StoreId,
    string? OperatorId,
    string? OperatorName,
    string? OperatorEmail,
    string? OperatorRole,
    /// <summary>Situação da empresa do token ("aprovada" quando sem registro, como no login normal).</summary>
    string CompanyStatus);

public class GatewayTokenAB(Connection connection)
{
    /// <summary>Prefixo fixo: permite ao middleware distinguir token de Gateway de JWT de usuário.</summary>
    public const string TokenPrefixMarker = "qgw_";

    private static readonly TimeSpan LastUsedThrottle = TimeSpan.FromMinutes(1);

    public static string HashToken(string rawToken)
        => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(rawToken.Trim()))).ToLowerInvariant();

    private static string GenerateRawToken()
    {
        var bytes = RandomNumberGenerator.GetBytes(32);
        var body = Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');
        return TokenPrefixMarker + body;
    }

    /// <summary>Gera um token novo. Retorna o registro e o token em texto (exibir uma única vez).</summary>
    public async Task<(GatewayTokenDto Token, string RawToken)> CreateAsync(
        string companyId,
        string nome,
        string? storeId,
        string? createdBy,
        CancellationToken cancellationToken = default)
    {
        var raw = GenerateRawToken();
        var dto = new GatewayTokenDto
        {
            Id = $"gwt-{Guid.NewGuid():N}",
            StoreId = storeId,
            Nome = nome,
            TokenPrefix = raw[..Math.Min(12, raw.Length)],
            CreatedAt = DateTimeOffset.UtcNow,
            CreatedBy = createdBy
        };

        const string sql = """
            INSERT INTO GatewayTokens (Id, CompanyId, StoreId, Nome, TokenHash, TokenPrefix, CreatedAt, CreatedBy)
            VALUES (@Id, @CompanyId, @StoreId, @Nome, @TokenHash, @TokenPrefix, @CreatedAt, @CreatedBy);
            """;
        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        await using var cmd = new SqlCommand(sql, db);
        cmd.Parameters.AddWithValue("@Id", dto.Id);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        cmd.Parameters.AddWithValue("@StoreId", (object?)storeId ?? DBNull.Value);
        cmd.Parameters.AddWithValue("@Nome", nome);
        cmd.Parameters.AddWithValue("@TokenHash", HashToken(raw));
        cmd.Parameters.AddWithValue("@TokenPrefix", dto.TokenPrefix);
        cmd.Parameters.AddWithValue("@CreatedAt", dto.CreatedAt);
        cmd.Parameters.AddWithValue("@CreatedBy", (object?)createdBy ?? DBNull.Value);
        await cmd.ExecuteNonQueryAsync(cancellationToken);

        return (dto, raw);
    }

    public async Task<List<GatewayTokenDto>> ListAsync(string companyId, CancellationToken cancellationToken = default)
    {
        const string sql = """
            SELECT Id, StoreId, Nome, TokenPrefix, CreatedAt, CreatedBy, LastUsedAt, RevokedAt
            FROM GatewayTokens
            WHERE CompanyId = @CompanyId
            ORDER BY CreatedAt DESC;
            """;
        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        await using var cmd = new SqlCommand(sql, db);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        await using var reader = await cmd.ExecuteReaderAsync(cancellationToken);
        var rows = new List<GatewayTokenDto>();
        while (await reader.ReadAsync(cancellationToken))
        {
            rows.Add(new GatewayTokenDto
            {
                Id = reader.GetString(0),
                StoreId = reader.IsDBNull(1) ? null : reader.GetString(1),
                Nome = reader.GetString(2),
                TokenPrefix = reader.GetString(3),
                CreatedAt = reader.GetDateTimeOffset(4),
                CreatedBy = reader.IsDBNull(5) ? null : reader.GetString(5),
                LastUsedAt = reader.IsDBNull(6) ? null : reader.GetDateTimeOffset(6),
                RevokedAt = reader.IsDBNull(7) ? null : reader.GetDateTimeOffset(7)
            });
        }
        return rows;
    }

    /// <summary>Revoga um token da empresa. Retorna false se não existir (ou for de outra empresa).</summary>
    public async Task<bool> RevokeAsync(string companyId, string tokenId, string? revokedBy, CancellationToken cancellationToken = default)
    {
        const string sql = """
            UPDATE GatewayTokens
            SET RevokedAt = COALESCE(RevokedAt, SYSDATETIMEOFFSET()),
                RevokedBy = COALESCE(RevokedBy, @RevokedBy)
            WHERE Id = @Id AND CompanyId = @CompanyId;
            """;
        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        await using var cmd = new SqlCommand(sql, db);
        cmd.Parameters.AddWithValue("@Id", tokenId);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        cmd.Parameters.AddWithValue("@RevokedBy", (object?)revokedBy ?? DBNull.Value);
        return await cmd.ExecuteNonQueryAsync(cancellationToken) > 0;
    }

    /// <summary>
    /// Valida o token (existe e não revogado) e, se operatorId vier, o operador: Status "ativo" e da MESMA
    /// empresa do token. Uma consulta só. Retorna null se o token for inválido; se o operador for inválido,
    /// retorna a validação com OperatorId = null (o middleware decide recusar).
    /// </summary>
    public async Task<GatewayTokenValidation?> ValidateAsync(
        string rawToken,
        string? operatorId,
        CancellationToken cancellationToken = default)
    {
        const string sql = """
            SELECT t.Id, t.CompanyId, t.StoreId, t.LastUsedAt,
                   u.Id AS OperatorId, u.Name, u.Email, u.Role,
                   (SELECT TOP 1 e.Status FROM Empresas e WHERE e.Id = t.CompanyId) AS CompanyStatus
            FROM GatewayTokens t
            LEFT JOIN Usuarios u
                   ON @OperatorId IS NOT NULL AND u.Id = @OperatorId AND u.CompanyId = t.CompanyId AND u.Status = 'ativo'
            WHERE t.TokenHash = @TokenHash AND t.RevokedAt IS NULL;
            """;

        GatewayTokenValidation? result = null;
        DateTimeOffset? lastUsed = null;

        await using (var db = await connection.OpenConnectionAsync(cancellationToken))
        await using (var cmd = new SqlCommand(sql, db))
        {
            cmd.Parameters.AddWithValue("@TokenHash", HashToken(rawToken));
            cmd.Parameters.AddWithValue("@OperatorId", string.IsNullOrWhiteSpace(operatorId) ? DBNull.Value : operatorId.Trim());
            await using var reader = await cmd.ExecuteReaderAsync(cancellationToken);
            if (!await reader.ReadAsync(cancellationToken)) return null;

            lastUsed = reader.IsDBNull(3) ? null : reader.GetDateTimeOffset(3);
            result = new GatewayTokenValidation(
                TokenId: reader.GetString(0),
                CompanyId: reader.GetString(1),
                StoreId: reader.IsDBNull(2) ? null : reader.GetString(2),
                OperatorId: reader.IsDBNull(4) ? null : reader.GetString(4),
                OperatorName: reader.IsDBNull(5) ? null : reader.GetString(5),
                OperatorEmail: reader.IsDBNull(6) ? null : reader.GetString(6),
                OperatorRole: reader.IsDBNull(7) ? null : reader.GetString(7),
                CompanyStatus: reader.IsDBNull(8) ? "aprovada" : reader.GetString(8));
        }

        // "Último uso" para o painel, gravado no máximo 1x por minuto (não escreve a cada requisição).
        if (lastUsed is null || DateTimeOffset.UtcNow - lastUsed.Value > LastUsedThrottle)
        {
            try
            {
                await using var db = await connection.OpenConnectionAsync(cancellationToken);
                await using var cmd = new SqlCommand("UPDATE GatewayTokens SET LastUsedAt = SYSDATETIMEOFFSET() WHERE Id = @Id;", db);
                cmd.Parameters.AddWithValue("@Id", result.TokenId);
                await cmd.ExecuteNonQueryAsync(cancellationToken);
            }
            catch
            {
                // informativo: falha ao registrar uso não pode bloquear o Gateway
            }
        }

        return result;
    }
}
