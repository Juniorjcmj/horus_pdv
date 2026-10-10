/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/MapeamentoProdutoFornecedorAB.cs
 * Objetivo: gerencia a persistência do De-Para de produtos de fornecedores para produtos locais.
 *           Permite que códigos do fornecedor (ex.: presunto caixa/peça cProd 1042) fiquem
 *           permanentemente associados ao produto cadastrado na loja (ex.: presunto balança kg).
 * Isolamento: multi-tenant por CompanyId.
 */
using HORUSPDV_API.Repositories;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public class MapeamentoProdutoFornecedorAB(Connection connection)
{
    public async Task<string?> ObterProdutoMapeadoIdAsync(
        string companyId,
        string fornecedorCnpj,
        string codigoProdutoFornecedor,
        string? gtinFornecedor = null,
        CancellationToken cancellationToken = default)
    {
        var cnpjLimpo = new string((fornecedorCnpj ?? string.Empty).Where(char.IsDigit).ToArray());
        if (string.IsNullOrWhiteSpace(cnpjLimpo) || string.IsNullOrWhiteSpace(codigoProdutoFornecedor))
        {
            return null;
        }

        const string sql = @"
            SELECT TOP 1 ProdutoId
            FROM MapeamentoProdutoFornecedor
            WHERE CompanyId = @CompanyId
              AND FornecedorCnpj = @FornecedorCnpj
              AND (
                    CodigoProdutoFornecedor = @CodigoProdutoFornecedor
                    OR (@GtinFornecedor IS NOT NULL AND GtinFornecedor = @GtinFornecedor)
                  )
            ORDER BY UpdatedAt DESC;";

        await using var conn = await connection.OpenLeaseAsync(cancellationToken);
        await using var cmd = connection.CreateCommand(sql, conn);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        cmd.Parameters.AddWithValue("@FornecedorCnpj", cnpjLimpo);
        cmd.Parameters.AddWithValue("@CodigoProdutoFornecedor", codigoProdutoFornecedor.Trim());
        cmd.Parameters.AddWithValue("@GtinFornecedor", string.IsNullOrWhiteSpace(gtinFornecedor) || gtinFornecedor == "SEM GTIN" ? DBNull.Value : gtinFornecedor.Trim());

        var result = await cmd.ExecuteScalarAsync(cancellationToken);
        return result as string;
    }

    public async Task SalvarMapeamentoAsync(
        string companyId,
        string fornecedorCnpj,
        string codigoProdutoFornecedor,
        string? gtinFornecedor,
        string? descricaoFornecedor,
        string produtoId,
        CancellationToken cancellationToken = default)
    {
        var cnpjLimpo = new string((fornecedorCnpj ?? string.Empty).Where(char.IsDigit).ToArray());
        if (string.IsNullOrWhiteSpace(cnpjLimpo) ||
            string.IsNullOrWhiteSpace(codigoProdutoFornecedor) ||
            string.IsNullOrWhiteSpace(produtoId))
        {
            return;
        }

        const string sql = @"
            MERGE INTO MapeamentoProdutoFornecedor AS target
            USING (
                SELECT @CompanyId AS CompanyId,
                       @FornecedorCnpj AS FornecedorCnpj,
                       @CodigoProdutoFornecedor AS CodigoProdutoFornecedor
            ) AS source
            ON target.CompanyId = source.CompanyId
               AND target.FornecedorCnpj = source.FornecedorCnpj
               AND target.CodigoProdutoFornecedor = source.CodigoProdutoFornecedor
            WHEN MATCHED THEN
                UPDATE SET ProdutoId = @ProdutoId,
                           GtinFornecedor = @GtinFornecedor,
                           DescricaoFornecedor = @DescricaoFornecedor,
                           UpdatedAt = SYSUTCDATETIME()
            WHEN NOT MATCHED THEN
                INSERT (Id, CompanyId, FornecedorCnpj, CodigoProdutoFornecedor, GtinFornecedor, DescricaoFornecedor, ProdutoId, CreatedAt, UpdatedAt)
                VALUES (@Id, @CompanyId, @FornecedorCnpj, @CodigoProdutoFornecedor, @GtinFornecedor, @DescricaoFornecedor, @ProdutoId, SYSUTCDATETIME(), SYSUTCDATETIME());";

        await using var conn = await connection.OpenLeaseAsync(cancellationToken);
        await using var cmd = connection.CreateCommand(sql, conn);
        cmd.Parameters.AddWithValue("@Id", $"map-{Guid.NewGuid():N}");
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        cmd.Parameters.AddWithValue("@FornecedorCnpj", cnpjLimpo);
        cmd.Parameters.AddWithValue("@CodigoProdutoFornecedor", codigoProdutoFornecedor.Trim());
        cmd.Parameters.AddWithValue("@GtinFornecedor", string.IsNullOrWhiteSpace(gtinFornecedor) || gtinFornecedor == "SEM GTIN" ? DBNull.Value : gtinFornecedor.Trim());
        cmd.Parameters.AddWithValue("@DescricaoFornecedor", string.IsNullOrWhiteSpace(descricaoFornecedor) ? DBNull.Value : descricaoFornecedor.Trim());
        cmd.Parameters.AddWithValue("@ProdutoId", produtoId.Trim());

        await cmd.ExecuteNonQueryAsync(cancellationToken);
    }
}
