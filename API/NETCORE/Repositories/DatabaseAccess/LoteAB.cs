/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/LoteAB.cs
 * Objetivo: concentra comandos SQL do controle de validade por lote (ProdutoLotes) e do prazo
 *           padrão/alerta de validade por categoria.
 * Entradas esperadas: recebe conexão configurada e parâmetros normalizados; grava e lê do SQL Server.
 *
 * Fase 1: o lote é informativo. A venda não baixa lote; o saldo por lote é estimado no consumo
 * FEFO (ver LoteEstimador) a partir do estoque atual do produto.
 */
using HORUSPDV_API.Models.Produtos;
using HORUSPDV_API.Services.Shared;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

/// <summary>Lote + dados do produto/categoria necessários para estimar saldo e montar alertas.</summary>
public sealed class LoteLinha
{
    public string Id { get; set; } = string.Empty;
    public string ProdutoId { get; set; } = string.Empty;
    public string NumeroLote { get; set; } = string.Empty;
    public DateTime DataValidade { get; set; }
    public decimal QtdInicial { get; set; }
    public string Origem { get; set; } = string.Empty;
    public bool ValidadePadrao { get; set; }
    public DateTimeOffset CriadoEm { get; set; }

    public string ProductCode { get; set; } = string.Empty;
    public string ProductName { get; set; } = string.Empty;
    public decimal Estoque { get; set; }
    public decimal CustoUnitario { get; set; }
    public decimal PrecoVenda { get; set; }
    public int DiasAlertaProduto { get; set; }
    public string CategoriaNome { get; set; } = string.Empty;
    public int? DiasAlertaCategoria { get; set; }
}

public static class LoteEstimador
{
    /// <summary>
    /// Atribui o estoque atual do produto aos lotes assumindo consumo FEFO: o que vence primeiro
    /// sai primeiro, então o saldo que sobra está nos lotes de validade mais distante.
    /// Recebe os lotes de UM produto e devolve id do lote -> saldo estimado.
    /// </summary>
    public static Dictionary<string, decimal> Distribuir(IReadOnlyList<LoteLinha> lotesDoProduto)
    {
        var saldos = new Dictionary<string, decimal>();
        if (lotesDoProduto.Count == 0) return saldos;

        var restante = Math.Max(0m, lotesDoProduto[0].Estoque);
        foreach (var lote in lotesDoProduto
                     .OrderByDescending(item => item.DataValidade)
                     .ThenByDescending(item => item.CriadoEm))
        {
            var alocado = Math.Max(0m, Math.Min(lote.QtdInicial, restante));
            saldos[lote.Id] = alocado;
            restante -= alocado;
        }

        return saldos;
    }
}

public class LoteAB(Connection connection)
{
    private const string LinhasSql = """
        SELECT l.Id, l.ProdutoId, l.NumeroLote, l.DataValidade, l.QtdInicial, l.Origem, l.ValidadePadrao, l.CriadoEm,
               p.ProductCode, p.ProductName, p.ProductQnt, p.ProductUnitPrice, p.ProductSalePrice,
               p.DiasAlertaValidade AS DiasAlertaProduto,
               COALESCE(c.Nome, N'') AS CategoriaNome,
               COALESCE(c.DiasAlertaValidade, cp.DiasAlertaValidade) AS DiasAlertaCategoria
        FROM ProdutoLotes l
        JOIN Produtos p ON p.Id = l.ProdutoId AND p.CompanyId = l.CompanyId
        LEFT JOIN Categorias c ON c.Id = p.CategoriaId AND c.CompanyId = p.CompanyId
        LEFT JOIN Categorias cp ON cp.Id = c.CategoriaPaiId
        WHERE l.CompanyId = @CompanyId
          AND (@ProdutoId IS NULL OR l.ProdutoId = @ProdutoId)
        ORDER BY l.ProdutoId, l.DataValidade DESC, l.CriadoEm DESC;
        """;

    public async Task<List<LoteLinha>> ListarLinhasAsync(string companyId, string? produtoId = null, CancellationToken cancellationToken = default)
    {
        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        await using var command = new SqlCommand(LinhasSql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@ProdutoId", (object?)produtoId ?? DBNull.Value);
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);

        var rows = new List<LoteLinha>();
        while (await reader.ReadAsync(cancellationToken))
        {
            rows.Add(new LoteLinha
            {
                Id = ReadString(reader, "Id"),
                ProdutoId = ReadString(reader, "ProdutoId"),
                NumeroLote = ReadString(reader, "NumeroLote"),
                DataValidade = reader.GetDateTime(reader.GetOrdinal("DataValidade")).Date,
                QtdInicial = ReadDecimal(reader, "QtdInicial"),
                Origem = ReadString(reader, "Origem"),
                ValidadePadrao = reader.GetBoolean(reader.GetOrdinal("ValidadePadrao")),
                CriadoEm = reader.GetDateTimeOffset(reader.GetOrdinal("CriadoEm")),
                ProductCode = ReadString(reader, "ProductCode"),
                ProductName = ReadString(reader, "ProductName"),
                Estoque = ReadDecimal(reader, "ProductQnt"),
                CustoUnitario = ReadDecimal(reader, "ProductUnitPrice"),
                PrecoVenda = ReadDecimal(reader, "ProductSalePrice"),
                DiasAlertaProduto = ReadInt(reader, "DiasAlertaProduto"),
                CategoriaNome = ReadString(reader, "CategoriaNome"),
                DiasAlertaCategoria = ReadNullableInt(reader, "DiasAlertaCategoria"),
            });
        }

        return rows;
    }

    /// <summary>Produtos que controlam validade e têm estoque, mas ainda não possuem nenhum lote.</summary>
    public async Task<int> ContarProdutosSemLoteAsync(string companyId, CancellationToken cancellationToken = default)
    {
        const string sql = """
            SELECT COUNT(*)
            FROM Produtos p
            WHERE p.CompanyId = @CompanyId
              AND p.ControlaValidade = 1
              AND p.ProductQnt > 0
              AND NOT EXISTS (SELECT 1 FROM ProdutoLotes l WHERE l.CompanyId = p.CompanyId AND l.ProdutoId = p.Id);
            """;

        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        var value = await command.ExecuteScalarAsync(cancellationToken);
        return (value is null or DBNull) ? 0 : Convert.ToInt32(value);
    }

    /// <summary>Prazo padrão de validade (dias) da categoria do produto ou, na falta, da categoria-pai.</summary>
    public async Task<int?> ObterPrazoPadraoDiasAsync(string companyId, string produtoId, CancellationToken cancellationToken = default)
    {
        const string sql = """
            SELECT COALESCE(c.PrazoPadraoValidadeDias, cp.PrazoPadraoValidadeDias)
            FROM Produtos p
            LEFT JOIN Categorias c ON c.Id = p.CategoriaId AND c.CompanyId = p.CompanyId
            LEFT JOIN Categorias cp ON cp.Id = c.CategoriaPaiId
            WHERE p.Id = @ProdutoId AND p.CompanyId = @CompanyId;
            """;

        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@ProdutoId", produtoId);
        var value = await command.ExecuteScalarAsync(cancellationToken);
        return (value is null or DBNull) ? null : Convert.ToInt32(value);
    }

    public async Task<string> InserirAsync(
        string companyId,
        string produtoId,
        DateTime dataValidade,
        decimal quantidade,
        string? numeroLote,
        string origem,
        bool validadePadrao,
        string? criadoPorNome,
        CancellationToken cancellationToken = default)
    {
        const string sql = """
            INSERT INTO ProdutoLotes (Id, CompanyId, ProdutoId, NumeroLote, DataValidade, QtdInicial, Origem, ValidadePadrao, CriadoPorNome)
            VALUES (@Id, @CompanyId, @ProdutoId, @NumeroLote, @DataValidade, @QtdInicial, @Origem, @ValidadePadrao, @CriadoPorNome);
            """;

        var id = $"lt-{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}-{Guid.NewGuid().ToString("N")[..8]}";

        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@Id", id);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@ProdutoId", produtoId);
        command.Parameters.AddWithValue("@NumeroLote", string.IsNullOrWhiteSpace(numeroLote) ? DBNull.Value : numeroLote.Trim());
        command.Parameters.AddWithValue("@DataValidade", dataValidade.Date);
        command.Parameters.AddWithValue("@QtdInicial", quantidade);
        command.Parameters.AddWithValue("@Origem", origem);
        command.Parameters.AddWithValue("@ValidadePadrao", validadePadrao);
        command.Parameters.AddWithValue("@CriadoPorNome", string.IsNullOrWhiteSpace(criadoPorNome) ? DBNull.Value : criadoPorNome.Trim());
        await command.ExecuteNonQueryAsync(cancellationToken);
        return id;
    }

    /// <summary>
    /// Registra o lote de uma entrada de estoque (nota, compra ou manual). Com data informada usa ela;
    /// sem data, sugere pelo prazo padrão da categoria. Sem data e sem prazo padrão, não cria lote.
    /// Depois sincroniza Produtos.DataValidade com o lote que vence primeiro e ainda tem saldo.
    /// Retorna true quando um lote foi criado.
    /// </summary>
    public async Task<bool> RegistrarEntradaAsync(
        string companyId,
        string produtoId,
        DateTime? dataValidade,
        decimal quantidade,
        string? numeroLote,
        string origem,
        string? criadoPorNome,
        CancellationToken cancellationToken = default)
    {
        if (quantidade <= 0) return false;

        DateTime data;
        var validadePadrao = false;
        if (dataValidade.HasValue)
        {
            data = dataValidade.Value.Date;
        }
        else
        {
            var prazo = await ObterPrazoPadraoDiasAsync(companyId, produtoId, cancellationToken);
            if (prazo is null or <= 0) return false;
            data = HorusDateTime.NowDateTime.Date.AddDays(prazo.Value);
            validadePadrao = true;
        }

        await InserirAsync(companyId, produtoId, data, quantidade, numeroLote, origem, validadePadrao, criadoPorNome, cancellationToken);
        await SincronizarValidadeProdutoAsync(companyId, produtoId, cancellationToken);
        return true;
    }

    /// <summary>
    /// Mantém Produtos.DataValidade (usada pelo PDV e pelos relatórios atuais) igual à validade do
    /// lote mais próximo do vencimento que ainda tem saldo estimado, e marca o produto como controlado.
    /// </summary>
    public async Task SincronizarValidadeProdutoAsync(string companyId, string produtoId, CancellationToken cancellationToken = default)
    {
        var linhas = await ListarLinhasAsync(companyId, produtoId, cancellationToken);
        if (linhas.Count == 0) return;

        var saldos = LoteEstimador.Distribuir(linhas);
        var comSaldo = linhas.Where(linha => saldos.GetValueOrDefault(linha.Id) > 0).ToList();
        if (comSaldo.Count == 0) return;

        const string sql = """
            UPDATE Produtos
               SET DataValidade = @DataValidade,
                   ControlaValidade = 1
             WHERE Id = @ProdutoId AND CompanyId = @CompanyId;
            """;

        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@ProdutoId", produtoId);
        command.Parameters.AddWithValue("@DataValidade", comSaldo.Min(linha => linha.DataValidade).Date);
        await command.ExecuteNonQueryAsync(cancellationToken);
    }

    public async Task<List<CategoriaValidadeModel>> ListarCategoriasValidadeAsync(string companyId, CancellationToken cancellationToken = default)
    {
        const string sql = """
            SELECT Id, Nome, CategoriaPaiId, PrazoPadraoValidadeDias, DiasAlertaValidade
            FROM Categorias
            WHERE CompanyId = @CompanyId AND Ativa = 1
            ORDER BY Ordem, Nome;
            """;

        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);

        var rows = new List<CategoriaValidadeModel>();
        while (await reader.ReadAsync(cancellationToken))
        {
            var paiOrdinal = reader.GetOrdinal("CategoriaPaiId");
            rows.Add(new CategoriaValidadeModel
            {
                CategoriaId = ReadString(reader, "Id"),
                Nome = ReadString(reader, "Nome"),
                CategoriaPaiId = reader.IsDBNull(paiOrdinal) ? null : reader.GetString(paiOrdinal).Trim(),
                PrazoPadraoDias = ReadNullableInt(reader, "PrazoPadraoValidadeDias"),
                DiasAlerta = ReadNullableInt(reader, "DiasAlertaValidade"),
            });
        }

        return rows;
    }

    public async Task<bool> SalvarCategoriaValidadeAsync(string companyId, string categoriaId, int? prazoPadraoDias, int? diasAlerta, CancellationToken cancellationToken = default)
    {
        const string sql = """
            UPDATE Categorias
               SET PrazoPadraoValidadeDias = @Prazo,
                   DiasAlertaValidade = @Alerta
             WHERE Id = @Id AND CompanyId = @CompanyId;
            """;

        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@Id", categoriaId);
        command.Parameters.AddWithValue("@Prazo", (object?)prazoPadraoDias ?? DBNull.Value);
        command.Parameters.AddWithValue("@Alerta", (object?)diasAlerta ?? DBNull.Value);
        return await command.ExecuteNonQueryAsync(cancellationToken) > 0;
    }

    private static string ReadString(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? string.Empty : reader.GetString(ordinal).Trim();
    }

    private static decimal ReadDecimal(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? 0m : reader.GetDecimal(ordinal);
    }

    private static int ReadInt(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? 0 : Convert.ToInt32(reader.GetValue(ordinal));
    }

    private static int? ReadNullableInt(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? null : Convert.ToInt32(reader.GetValue(ordinal));
    }
}
