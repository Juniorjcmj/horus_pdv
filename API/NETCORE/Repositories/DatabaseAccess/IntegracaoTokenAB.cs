/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/IntegracaoTokenAB.cs
 * Objetivo: tokens de integração por empresa, usados por agentes de IA no servidor MCP (/mcp).
 *           Gera (o texto só sai uma vez), lista, revoga e valida. No banco fica apenas o SHA-256 do token.
 * Isolamento: toda operação administrativa é chaveada por CompanyId; a validação devolve a empresa do token.
 */
using System.Security.Cryptography;
using System.Text;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

/// <summary>Projeção de um token para listagem (nunca contém o segredo).</summary>
public sealed class IntegracaoTokenDto
{
    public string Id { get; set; } = string.Empty;
    public string Nome { get; set; } = string.Empty;
    public string TokenPrefix { get; set; } = string.Empty;
    public DateTimeOffset CreatedAt { get; set; }
    public string? CreatedBy { get; set; }
    public DateTimeOffset? LastUsedAt { get; set; }
    public DateTimeOffset? RevokedAt { get; set; }
    /// <summary>true = o agente pode editar produtos e ajustar estoque pelo MCP; false = somente leitura.</summary>
    public bool PermiteEscritaProdutos { get; set; }
}

/// <summary>Resultado da validação: token ativo, a empresa dele e a situação dessa empresa.</summary>
public sealed record IntegracaoTokenValidation(
    string TokenId,
    string CompanyId,
    string Nome,
    /// <summary>Situação da empresa do token ("aprovada" quando sem registro, como no login normal).</summary>
    string CompanyStatus,
    /// <summary>Token com permissão para editar produtos e ajustar estoque.</summary>
    bool PermiteEscritaProdutos = false);

public class IntegracaoTokenAB(Connection connection)
{
    /// <summary>Prefixo fixo: permite ao middleware distinguir token de integração de JWT de usuário.</summary>
    public const string TokenPrefixMarker = "qmcp_";

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
    public async Task<(IntegracaoTokenDto Token, string RawToken)> CreateAsync(
        string companyId,
        string nome,
        string? createdBy,
        bool permiteEscritaProdutos = false,
        CancellationToken cancellationToken = default)
    {
        var raw = GenerateRawToken();
        var dto = new IntegracaoTokenDto
        {
            Id = $"itk-{Guid.NewGuid():N}",
            Nome = nome,
            TokenPrefix = raw[..Math.Min(12, raw.Length)],
            CreatedAt = DateTimeOffset.UtcNow,
            CreatedBy = createdBy,
            PermiteEscritaProdutos = permiteEscritaProdutos
        };

        const string sql = """
            INSERT INTO IntegracaoTokens (Id, CompanyId, Nome, TokenHash, TokenPrefix, CreatedAt, CreatedBy, PermiteEscritaProdutos)
            VALUES (@Id, @CompanyId, @Nome, @TokenHash, @TokenPrefix, @CreatedAt, @CreatedBy, @PermiteEscritaProdutos);
            """;
        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        await using var cmd = new SqlCommand(sql, db);
        cmd.Parameters.AddWithValue("@Id", dto.Id);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        cmd.Parameters.AddWithValue("@Nome", nome);
        cmd.Parameters.AddWithValue("@TokenHash", HashToken(raw));
        cmd.Parameters.AddWithValue("@TokenPrefix", dto.TokenPrefix);
        cmd.Parameters.AddWithValue("@CreatedAt", dto.CreatedAt);
        cmd.Parameters.AddWithValue("@CreatedBy", (object?)createdBy ?? DBNull.Value);
        cmd.Parameters.AddWithValue("@PermiteEscritaProdutos", permiteEscritaProdutos);
        await cmd.ExecuteNonQueryAsync(cancellationToken);

        return (dto, raw);
    }

    public async Task<List<IntegracaoTokenDto>> ListAsync(string companyId, CancellationToken cancellationToken = default)
    {
        const string sql = """
            SELECT Id, Nome, TokenPrefix, CreatedAt, CreatedBy, LastUsedAt, RevokedAt, PermiteEscritaProdutos
            FROM IntegracaoTokens
            WHERE CompanyId = @CompanyId
            ORDER BY CreatedAt DESC;
            """;
        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        await using var cmd = new SqlCommand(sql, db);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        await using var reader = await cmd.ExecuteReaderAsync(cancellationToken);
        var rows = new List<IntegracaoTokenDto>();
        while (await reader.ReadAsync(cancellationToken))
        {
            rows.Add(new IntegracaoTokenDto
            {
                Id = reader.GetString(0),
                Nome = reader.GetString(1),
                TokenPrefix = reader.GetString(2),
                CreatedAt = reader.GetDateTimeOffset(3),
                CreatedBy = reader.IsDBNull(4) ? null : reader.GetString(4),
                LastUsedAt = reader.IsDBNull(5) ? null : reader.GetDateTimeOffset(5),
                RevokedAt = reader.IsDBNull(6) ? null : reader.GetDateTimeOffset(6),
                PermiteEscritaProdutos = !reader.IsDBNull(7) && reader.GetBoolean(7)
            });
        }
        return rows;
    }

    /// <summary>Revoga um token da empresa. Retorna false se não existir (ou for de outra empresa).</summary>
    public async Task<bool> RevokeAsync(string companyId, string tokenId, string? revokedBy, CancellationToken cancellationToken = default)
    {
        const string sql = """
            UPDATE IntegracaoTokens
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

    /// <summary>Valida o token (existe e não revogado). Retorna null se for inválido.</summary>
    public async Task<IntegracaoTokenValidation?> ValidateAsync(string rawToken, CancellationToken cancellationToken = default)
    {
        const string sql = """
            SELECT t.Id, t.CompanyId, t.Nome, t.LastUsedAt,
                   (SELECT TOP 1 e.Status FROM Empresas e WHERE e.Id = t.CompanyId) AS CompanyStatus,
                   t.PermiteEscritaProdutos
            FROM IntegracaoTokens t
            WHERE t.TokenHash = @TokenHash AND t.RevokedAt IS NULL;
            """;

        IntegracaoTokenValidation? result = null;
        DateTimeOffset? lastUsed = null;

        await using (var db = await connection.OpenConnectionAsync(cancellationToken))
        await using (var cmd = new SqlCommand(sql, db))
        {
            cmd.Parameters.AddWithValue("@TokenHash", HashToken(rawToken));
            await using var reader = await cmd.ExecuteReaderAsync(cancellationToken);
            if (!await reader.ReadAsync(cancellationToken)) return null;

            lastUsed = reader.IsDBNull(3) ? null : reader.GetDateTimeOffset(3);
            result = new IntegracaoTokenValidation(
                TokenId: reader.GetString(0),
                CompanyId: reader.GetString(1),
                Nome: reader.GetString(2),
                CompanyStatus: reader.IsDBNull(4) ? "aprovada" : reader.GetString(4),
                PermiteEscritaProdutos: !reader.IsDBNull(5) && reader.GetBoolean(5));
        }

        // "Último uso" para o painel, gravado no máximo 1x por minuto (não escreve a cada requisição).
        if (lastUsed is null || DateTimeOffset.UtcNow - lastUsed.Value > LastUsedThrottle)
        {
            try
            {
                await using var db = await connection.OpenConnectionAsync(cancellationToken);
                await using var cmd = new SqlCommand("UPDATE IntegracaoTokens SET LastUsedAt = SYSDATETIMEOFFSET() WHERE Id = @Id;", db);
                cmd.Parameters.AddWithValue("@Id", result.TokenId);
                await cmd.ExecuteNonQueryAsync(cancellationToken);
            }
            catch
            {
                // informativo: falha ao registrar uso não pode bloquear o agente
            }
        }

        return result;
    }
}
