/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/AutorizacaoPrecoAB.cs
 * Objetivo: autorização de gerente para alterar o preço de um produto no caixa. O gerente confirma com a
 *           senha (validada pelo controlador), a autorização fica gravada (produto, preço, quem autorizou) e a
 *           venda só aceita o preço alterado com uma autorização válida e de uso único.
 * Entradas esperadas: recebe conexão configurada e, para validar a venda, a conexão/transação da própria venda.
 */
using HORUSPDV_API.Models.Requests;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public class AutorizacaoPrecoAB(Connection connection)
{
    /// <summary>Tempo que a autorização vale para fechar a venda depois de dada a senha.</summary>
    private static readonly TimeSpan Validade = TimeSpan.FromHours(2);

    private const decimal Tolerancia = 0.005m;

    public async Task<(string Id, DateTimeOffset ExpiraEm)> CriarAsync(
        string companyId,
        string productCode,
        decimal precoTabela,
        decimal precoNovo,
        string supervisorId,
        string supervisorNome,
        string? operadorId,
        string? operadorNome,
        string? motivo,
        CancellationToken cancellationToken = default)
    {
        const string sql = """
            INSERT INTO AutorizacoesPreco
                (Id, CompanyId, ProductCode, PrecoTabela, PrecoNovo, SupervisorId, SupervisorNome,
                 OperadorId, OperadorNome, Motivo, ExpiraEm)
            VALUES
                (@Id, @CompanyId, @ProductCode, @PrecoTabela, @PrecoNovo, @SupervisorId, @SupervisorNome,
                 @OperadorId, @OperadorNome, @Motivo, @ExpiraEm);
            """;

        var id = $"ap-{Guid.NewGuid():N}";
        var expiraEm = DateTimeOffset.UtcNow.Add(Validade);

        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@Id", id);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@ProductCode", productCode);
        command.Parameters.AddWithValue("@PrecoTabela", precoTabela);
        command.Parameters.AddWithValue("@PrecoNovo", precoNovo);
        command.Parameters.AddWithValue("@SupervisorId", supervisorId);
        command.Parameters.AddWithValue("@SupervisorNome", supervisorNome);
        command.Parameters.AddWithValue("@OperadorId", string.IsNullOrWhiteSpace(operadorId) ? DBNull.Value : operadorId);
        command.Parameters.AddWithValue("@OperadorNome", string.IsNullOrWhiteSpace(operadorNome) ? DBNull.Value : operadorNome);
        command.Parameters.AddWithValue("@Motivo", string.IsNullOrWhiteSpace(motivo) ? DBNull.Value : motivo.Trim());
        command.Parameters.AddWithValue("@ExpiraEm", expiraEm);
        await command.ExecuteNonQueryAsync(cancellationToken);

        return (id, expiraEm);
    }

    /// <summary>
    /// Confere os preços de uma venda dentro da transação dela. Regras:
    ///  - preço igual ao cadastrado: nada a fazer;
    ///  - preço diferente COM autorização: a autorização precisa ser do mesmo produto e preço, não usada e
    ///    (em venda online) não expirada; é consumida aqui, na mesma transação da venda;
    ///  - preço MENOR que o cadastrado SEM autorização, em venda online: recusa (desconto manual precisa de gerente);
    ///  - preço maior que o cadastrado ou venda sincronizada do modo offline sem autorização: aceita (catálogo do
    ///    caixa pode estar defasado e o caixa offline não consegue validar senha).
    /// Retorna os ids das autorizações consumidas, para vincular à venda depois.
    /// </summary>
    public static async Task<List<string>> ValidarPrecosDaVendaAsync(
        SqlConnection db,
        SqlTransaction transaction,
        string companyId,
        IEnumerable<VendaItemRequest> itens,
        bool vendaOffline,
        CancellationToken cancellationToken = default)
    {
        var usadas = new List<string>();

        foreach (var item in itens)
        {
            if (item.UnitPrice <= 0) continue;

            var codigo = item.ProductCode.Trim();
            var precoTabela = await ObterPrecoTabelaAsync(db, transaction, companyId, codigo, cancellationToken);
            if (precoTabela is null) continue; // produto inexistente: a baixa de estoque já acusa

            if (Math.Abs(item.UnitPrice - precoTabela.Value) <= Tolerancia) continue;

            var nome = string.IsNullOrWhiteSpace(item.ProductName) ? codigo : item.ProductName.Trim();

            if (!string.IsNullOrWhiteSpace(item.AutorizacaoPrecoId))
            {
                var autorizacaoId = item.AutorizacaoPrecoId.Trim();
                await ConsumirAsync(db, transaction, companyId, autorizacaoId, codigo, item.UnitPrice, nome, vendaOffline, cancellationToken);
                usadas.Add(autorizacaoId);
                continue;
            }

            if (!vendaOffline && item.UnitPrice < precoTabela.Value)
            {
                throw new InvalidOperationException(
                    $"O preço de \"{nome}\" foi alterado (R$ {item.UnitPrice:N2} em vez de R$ {precoTabela.Value:N2}) " +
                    "sem autorização de gerente. Peça a senha do gerente para alterar o preço ou atualize o catálogo do caixa.");
            }
        }

        return usadas;
    }

    public static async Task VincularVendaAsync(
        SqlConnection db,
        SqlTransaction transaction,
        string companyId,
        IEnumerable<string> autorizacaoIds,
        string vendaId,
        CancellationToken cancellationToken = default)
    {
        foreach (var autorizacaoId in autorizacaoIds)
        {
            await using var command = new SqlCommand(
                "UPDATE AutorizacoesPreco SET VendaId = @VendaId WHERE Id = @Id AND CompanyId = @CompanyId;",
                db,
                transaction);
            command.Parameters.AddWithValue("@VendaId", vendaId);
            command.Parameters.AddWithValue("@Id", autorizacaoId);
            command.Parameters.AddWithValue("@CompanyId", companyId);
            await command.ExecuteNonQueryAsync(cancellationToken);
        }
    }

    private static async Task<decimal?> ObterPrecoTabelaAsync(
        SqlConnection db,
        SqlTransaction transaction,
        string companyId,
        string productCode,
        CancellationToken cancellationToken)
    {
        await using var command = new SqlCommand(
            "SELECT ProductSalePrice, ProductUnitPrice FROM Produtos WHERE CompanyId = @CompanyId AND ProductCode = @ProductCode;",
            db,
            transaction);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@ProductCode", productCode);
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        if (!await reader.ReadAsync(cancellationToken)) return null;

        var venda = reader.GetDecimal(reader.GetOrdinal("ProductSalePrice"));
        var custo = reader.GetDecimal(reader.GetOrdinal("ProductUnitPrice"));
        // Mesma regra de HistoricoVendasAB: sem preço de venda cadastrado, vale o custo.
        return venda > 0 ? venda : custo;
    }

    private static async Task ConsumirAsync(
        SqlConnection db,
        SqlTransaction transaction,
        string companyId,
        string autorizacaoId,
        string productCode,
        decimal preco,
        string nomeProduto,
        bool vendaOffline,
        CancellationToken cancellationToken)
    {
        string codigoAutorizado;
        decimal precoAutorizado;
        DateTimeOffset expiraEm;
        bool jaUsada;

        await using (var select = new SqlCommand(
            """
            SELECT ProductCode, PrecoNovo, ExpiraEm, UsadoEm
            FROM AutorizacoesPreco WITH (UPDLOCK, ROWLOCK)
            WHERE Id = @Id AND CompanyId = @CompanyId;
            """,
            db,
            transaction))
        {
            select.Parameters.AddWithValue("@Id", autorizacaoId);
            select.Parameters.AddWithValue("@CompanyId", companyId);
            await using var reader = await select.ExecuteReaderAsync(cancellationToken);
            if (!await reader.ReadAsync(cancellationToken))
            {
                throw new InvalidOperationException($"Autorização de preço de \"{nomeProduto}\" não encontrada.");
            }

            codigoAutorizado = reader.GetString(reader.GetOrdinal("ProductCode"));
            precoAutorizado = reader.GetDecimal(reader.GetOrdinal("PrecoNovo"));
            expiraEm = reader.GetDateTimeOffset(reader.GetOrdinal("ExpiraEm"));
            jaUsada = !reader.IsDBNull(reader.GetOrdinal("UsadoEm"));
        }

        if (!string.Equals(codigoAutorizado, productCode, StringComparison.OrdinalIgnoreCase)
            || Math.Abs(precoAutorizado - preco) > Tolerancia)
        {
            throw new InvalidOperationException(
                $"A autorização de preço apresentada não corresponde ao produto/preço de \"{nomeProduto}\".");
        }

        if (jaUsada)
        {
            throw new InvalidOperationException($"A autorização de preço de \"{nomeProduto}\" já foi utilizada em outra venda.");
        }

        // Venda sincronizada do modo offline pode chegar horas depois da senha dada: só a venda online expira.
        if (!vendaOffline && expiraEm < DateTimeOffset.UtcNow)
        {
            throw new InvalidOperationException(
                $"A autorização de preço de \"{nomeProduto}\" expirou. Peça a senha do gerente novamente.");
        }

        await using var update = new SqlCommand(
            "UPDATE AutorizacoesPreco SET UsadoEm = SYSDATETIMEOFFSET() WHERE Id = @Id AND CompanyId = @CompanyId AND UsadoEm IS NULL;",
            db,
            transaction);
        update.Parameters.AddWithValue("@Id", autorizacaoId);
        update.Parameters.AddWithValue("@CompanyId", companyId);
        if (await update.ExecuteNonQueryAsync(cancellationToken) != 1)
        {
            throw new InvalidOperationException($"A autorização de preço de \"{nomeProduto}\" já foi utilizada em outra venda.");
        }
    }
}
