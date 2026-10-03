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
using Microsoft.Extensions.Logging;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

/// <summary>Lote + dados do produto/categoria necessários para estimar saldo e montar alertas.</summary>
public sealed class LoteLinha
{
    public string Id { get; set; } = string.Empty;
    public string ProdutoId { get; set; } = string.Empty;
    public string NumeroLote { get; set; } = string.Empty;
    public DateTime DataValidade { get; set; }
    public decimal QtdInicial { get; set; }
    /// <summary>Saldo real do lote (baixado pela venda, devolvido pelo cancelamento) — Fase 2.</summary>
    public decimal QtdAtual { get; set; }
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
    public string CategoriaId { get; set; } = string.Empty;
    public string CategoriaPaiId { get; set; } = string.Empty;
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

    /// <summary>
    /// Saldo por lote conforme o modo: 'ativo' usa o saldo real (QtdAtual, baixado pela venda); nos demais
    /// modos continua o saldo estimado FEFO da Fase 1.
    /// </summary>
    public static Dictionary<string, decimal> Saldos(IReadOnlyList<LoteLinha> lotesDoProduto, bool usarSaldoReal)
        => usarSaldoReal
            ? lotesDoProduto.ToDictionary(lote => lote.Id, lote => Math.Max(0m, lote.QtdAtual))
            : Distribuir(lotesDoProduto);
}

public class LoteAB(Connection connection, ILogger<LoteAB> logger)
{
    public const string ModoDesligado = "desligado";
    public const string ModoSombra = "sombra";
    public const string ModoAtivo = "ativo";

    public static bool ModoValido(string? modo)
        => modo is ModoDesligado or ModoSombra or ModoAtivo;

    private const string LinhasSql = """
        SELECT l.Id, l.ProdutoId, l.NumeroLote, l.DataValidade, l.QtdInicial, ISNULL(l.QtdAtual, 0) AS QtdAtual,
               l.Origem, l.ValidadePadrao, l.CriadoEm,
               p.ProductCode, p.ProductName, p.ProductQnt, p.ProductUnitPrice, p.ProductSalePrice,
               p.DiasAlertaValidade AS DiasAlertaProduto,
               COALESCE(c.Nome, N'') AS CategoriaNome,
               c.Id AS CategoriaId,
               c.CategoriaPaiId AS CategoriaPaiId,
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
                QtdAtual = ReadDecimal(reader, "QtdAtual"),
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
                CategoriaId = ReadString(reader, "CategoriaId"),
                CategoriaPaiId = ReadString(reader, "CategoriaPaiId"),
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
            INSERT INTO ProdutoLotes (Id, CompanyId, ProdutoId, NumeroLote, DataValidade, QtdInicial, QtdAtual, Origem, ValidadePadrao, CriadoPorNome)
            VALUES (@Id, @CompanyId, @ProdutoId, @NumeroLote, @DataValidade, @QtdInicial, @QtdInicial, @Origem, @ValidadePadrao, @CriadoPorNome);
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

        var modo = await ObterModoAsync(companyId, cancellationToken);
        var saldos = LoteEstimador.Saldos(linhas, modo == ModoAtivo);
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

    // -----------------------------------------------------------------------
    // Fase 2 — baixa FEFO por lote
    // -----------------------------------------------------------------------

    /// <summary>Modo da baixa por lote da empresa. Sem configuração gravada vale 'sombra'.</summary>
    public async Task<string> ObterModoAsync(string companyId, CancellationToken cancellationToken = default)
    {
        const string sql = "SELECT Modo FROM LoteConfig WHERE CompanyId = @CompanyId;";

        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        var value = await command.ExecuteScalarAsync(cancellationToken);
        var modo = (value is null or DBNull) ? null : Convert.ToString(value)?.Trim().ToLowerInvariant();
        return ModoValido(modo) ? modo! : ModoSombra;
    }

    public async Task DefinirModoAsync(string companyId, string modo, CancellationToken cancellationToken = default)
    {
        const string sql = """
            UPDATE LoteConfig SET Modo = @Modo, AtualizadoEm = SYSDATETIMEOFFSET() WHERE CompanyId = @CompanyId;
            IF @@ROWCOUNT = 0
                INSERT INTO LoteConfig (CompanyId, Modo) VALUES (@CompanyId, @Modo);
            """;

        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@Modo", modo);
        await command.ExecuteNonQueryAsync(cancellationToken);
    }

    /// <summary>
    /// Baixa FEFO de uma venda: para cada produto, tira a quantidade dos lotes que vencem primeiro e
    /// registra quanto saiu de cada um (para estornar no lote certo no cancelamento).
    /// "Seguro": roda DEPOIS do commit da venda, em transação própria, e NUNCA lança exceção — qualquer
    /// falha é só registrada no log e a venda (já gravada) não é afetada.
    /// </summary>
    public async Task ConsumirVendaSeguroAsync(
        string companyId,
        string vendaId,
        IEnumerable<(string ProductCode, decimal Quantity)> itens,
        CancellationToken cancellationToken = default)
    {
        try
        {
            if (await ObterModoAsync(companyId, cancellationToken) == ModoDesligado) return;

            var agrupados = itens
                .Where(item => !string.IsNullOrWhiteSpace(item.ProductCode) && item.Quantity > 0)
                .GroupBy(item => item.ProductCode.Trim(), StringComparer.OrdinalIgnoreCase)
                .Select(grupo => (Codigo: grupo.Key, Quantidade: grupo.Sum(item => item.Quantity)))
                .ToList();
            if (agrupados.Count == 0) return;

            var afetados = new List<string>();
            await using (var db = await connection.OpenConnectionAsync(cancellationToken))
            await using (var transaction = (SqlTransaction)await db.BeginTransactionAsync(cancellationToken))
            {
                try
                {
                    foreach (var (codigo, quantidade) in agrupados)
                    {
                        var produtoId = await ObterProdutoIdPorCodigoAsync(db, transaction, companyId, codigo, cancellationToken);
                        if (produtoId is null) continue;

                        if (await ConsumirNosLotesAsync(db, transaction, companyId, produtoId, quantidade, "venda", vendaId, cancellationToken))
                        {
                            afetados.Add(produtoId);
                        }
                    }

                    await transaction.CommitAsync(cancellationToken);
                }
                catch
                {
                    await transaction.RollbackAsync(cancellationToken);
                    throw;
                }
            }

            foreach (var produtoId in afetados.Distinct())
            {
                await SincronizarValidadeProdutoAsync(companyId, produtoId, cancellationToken);
            }
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Falha na baixa FEFO por lote da venda {VendaId}. A venda não foi afetada.", vendaId);
        }
    }

    /// <summary>Baixa FEFO de uma saída manual de estoque (perda, avaria). Seguro: nunca lança exceção.</summary>
    public async Task ConsumirAjusteSeguroAsync(
        string companyId,
        string produtoId,
        decimal quantidade,
        CancellationToken cancellationToken = default)
    {
        try
        {
            if (quantidade <= 0 || await ObterModoAsync(companyId, cancellationToken) == ModoDesligado) return;

            var consumiu = false;
            await using (var db = await connection.OpenConnectionAsync(cancellationToken))
            await using (var transaction = (SqlTransaction)await db.BeginTransactionAsync(cancellationToken))
            {
                try
                {
                    consumiu = await ConsumirNosLotesAsync(
                        db, transaction, companyId, produtoId, quantidade, "ajuste", $"aj-{Guid.NewGuid():N}", cancellationToken);
                    await transaction.CommitAsync(cancellationToken);
                }
                catch
                {
                    await transaction.RollbackAsync(cancellationToken);
                    throw;
                }
            }

            if (consumiu)
            {
                await SincronizarValidadeProdutoAsync(companyId, produtoId, cancellationToken);
            }
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Falha na baixa FEFO por lote do ajuste de estoque do produto {ProdutoId}.", produtoId);
        }
    }

    /// <summary>
    /// Devolve aos lotes de origem o que uma venda cancelada/estornada tirou deles. Idempotente (cada
    /// registro é marcado como estornado) e seguro: nunca lança exceção. Vendas anteriores à Fase 2 não
    /// têm registro de consumo e, portanto, não mexem em lote.
    /// </summary>
    public async Task EstornarVendaSeguroAsync(string companyId, string vendaId, CancellationToken cancellationToken = default)
    {
        try
        {
            var afetados = new List<string>();
            await using (var db = await connection.OpenConnectionAsync(cancellationToken))
            await using (var transaction = (SqlTransaction)await db.BeginTransactionAsync(cancellationToken))
            {
                try
                {
                    var consumos = new List<(string Id, string ProdutoId, string? LoteId, decimal Quantidade)>();
                    await using (var select = new SqlCommand(
                        """
                        SELECT Id, ProdutoId, LoteId, Quantidade
                        FROM LoteConsumos WITH (UPDLOCK, ROWLOCK)
                        WHERE CompanyId = @CompanyId AND RefId = @RefId AND Origem = N'venda' AND Estornado = 0;
                        """,
                        db,
                        transaction))
                    {
                        select.Parameters.AddWithValue("@CompanyId", companyId);
                        select.Parameters.AddWithValue("@RefId", vendaId);
                        await using var reader = await select.ExecuteReaderAsync(cancellationToken);
                        while (await reader.ReadAsync(cancellationToken))
                        {
                            var loteOrdinal = reader.GetOrdinal("LoteId");
                            consumos.Add((
                                reader.GetString(reader.GetOrdinal("Id")),
                                reader.GetString(reader.GetOrdinal("ProdutoId")),
                                reader.IsDBNull(loteOrdinal) ? null : reader.GetString(loteOrdinal),
                                reader.GetDecimal(reader.GetOrdinal("Quantidade"))));
                        }
                    }

                    foreach (var consumo in consumos)
                    {
                        if (consumo.LoteId is not null)
                        {
                            await using var devolve = new SqlCommand(
                                "UPDATE ProdutoLotes SET QtdAtual = ISNULL(QtdAtual, 0) + @Quantidade WHERE Id = @LoteId AND CompanyId = @CompanyId;",
                                db,
                                transaction);
                            devolve.Parameters.AddWithValue("@Quantidade", consumo.Quantidade);
                            devolve.Parameters.AddWithValue("@LoteId", consumo.LoteId);
                            devolve.Parameters.AddWithValue("@CompanyId", companyId);
                            await devolve.ExecuteNonQueryAsync(cancellationToken);
                            afetados.Add(consumo.ProdutoId);
                        }

                        await using var marca = new SqlCommand(
                            "UPDATE LoteConsumos SET Estornado = 1 WHERE Id = @Id;",
                            db,
                            transaction);
                        marca.Parameters.AddWithValue("@Id", consumo.Id);
                        await marca.ExecuteNonQueryAsync(cancellationToken);
                    }

                    await transaction.CommitAsync(cancellationToken);
                }
                catch
                {
                    await transaction.RollbackAsync(cancellationToken);
                    throw;
                }
            }

            foreach (var produtoId in afetados.Distinct())
            {
                await SincronizarValidadeProdutoAsync(companyId, produtoId, cancellationToken);
            }
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Falha ao estornar a baixa por lote da venda {VendaId}.", vendaId);
        }
    }

    private static async Task<string?> ObterProdutoIdPorCodigoAsync(
        SqlConnection db,
        SqlTransaction transaction,
        string companyId,
        string productCode,
        CancellationToken cancellationToken)
    {
        await using var command = new SqlCommand(
            "SELECT Id FROM Produtos WHERE CompanyId = @CompanyId AND ProductCode = @ProductCode;",
            db,
            transaction);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@ProductCode", productCode);
        var value = await command.ExecuteScalarAsync(cancellationToken);
        return (value is null or DBNull) ? null : Convert.ToString(value);
    }

    /// <summary>
    /// Tira a quantidade dos lotes do produto em ordem FEFO (validade mais próxima primeiro) e grava o
    /// consumo. O que nenhum lote cobre fica registrado como "sem lote" (LoteId nulo). Produto sem
    /// nenhum lote não gera registro. Idempotente por (origem, referência, produto). Retorna true quando
    /// registrou algo.
    /// </summary>
    private static async Task<bool> ConsumirNosLotesAsync(
        SqlConnection db,
        SqlTransaction transaction,
        string companyId,
        string produtoId,
        decimal quantidade,
        string origem,
        string refId,
        CancellationToken cancellationToken)
    {
        await using (var jaFeito = new SqlCommand(
            "SELECT COUNT(1) FROM LoteConsumos WHERE CompanyId = @CompanyId AND RefId = @RefId AND ProdutoId = @ProdutoId AND Origem = @Origem;",
            db,
            transaction))
        {
            jaFeito.Parameters.AddWithValue("@CompanyId", companyId);
            jaFeito.Parameters.AddWithValue("@RefId", refId);
            jaFeito.Parameters.AddWithValue("@ProdutoId", produtoId);
            jaFeito.Parameters.AddWithValue("@Origem", origem);
            if (Convert.ToInt32(await jaFeito.ExecuteScalarAsync(cancellationToken)) > 0) return false;
        }

        var lotes = new List<(string Id, decimal Saldo)>();
        await using (var select = new SqlCommand(
            """
            SELECT Id, ISNULL(QtdAtual, 0) AS QtdAtual
            FROM ProdutoLotes WITH (UPDLOCK, ROWLOCK)
            WHERE CompanyId = @CompanyId AND ProdutoId = @ProdutoId AND ISNULL(QtdAtual, 0) > 0
            ORDER BY DataValidade ASC, CriadoEm ASC;
            """,
            db,
            transaction))
        {
            select.Parameters.AddWithValue("@CompanyId", companyId);
            select.Parameters.AddWithValue("@ProdutoId", produtoId);
            await using var reader = await select.ExecuteReaderAsync(cancellationToken);
            while (await reader.ReadAsync(cancellationToken))
            {
                lotes.Add((reader.GetString(reader.GetOrdinal("Id")), reader.GetDecimal(reader.GetOrdinal("QtdAtual"))));
            }
        }

        if (lotes.Count == 0)
        {
            // Produto sem nenhum lote cadastrado: não há o que baixar nem o que registrar.
            await using var temLote = new SqlCommand(
                "SELECT COUNT(1) FROM ProdutoLotes WHERE CompanyId = @CompanyId AND ProdutoId = @ProdutoId;",
                db,
                transaction);
            temLote.Parameters.AddWithValue("@CompanyId", companyId);
            temLote.Parameters.AddWithValue("@ProdutoId", produtoId);
            if (Convert.ToInt32(await temLote.ExecuteScalarAsync(cancellationToken)) == 0) return false;
        }

        var restante = quantidade;
        foreach (var (loteId, saldo) in lotes)
        {
            if (restante <= 0) break;

            var tirar = Math.Min(saldo, restante);
            await using (var baixa = new SqlCommand(
                "UPDATE ProdutoLotes SET QtdAtual = ISNULL(QtdAtual, 0) - @Quantidade WHERE Id = @LoteId AND CompanyId = @CompanyId;",
                db,
                transaction))
            {
                baixa.Parameters.AddWithValue("@Quantidade", tirar);
                baixa.Parameters.AddWithValue("@LoteId", loteId);
                baixa.Parameters.AddWithValue("@CompanyId", companyId);
                await baixa.ExecuteNonQueryAsync(cancellationToken);
            }

            await InserirConsumoAsync(db, transaction, companyId, refId, origem, produtoId, loteId, tirar, cancellationToken);
            restante -= tirar;
        }

        if (restante > 0)
        {
            await InserirConsumoAsync(db, transaction, companyId, refId, origem, produtoId, null, restante, cancellationToken);
        }

        return true;
    }

    private static async Task InserirConsumoAsync(
        SqlConnection db,
        SqlTransaction transaction,
        string companyId,
        string refId,
        string origem,
        string produtoId,
        string? loteId,
        decimal quantidade,
        CancellationToken cancellationToken)
    {
        await using var command = new SqlCommand(
            """
            INSERT INTO LoteConsumos (Id, CompanyId, RefId, Origem, ProdutoId, LoteId, Quantidade)
            VALUES (@Id, @CompanyId, @RefId, @Origem, @ProdutoId, @LoteId, @Quantidade);
            """,
            db,
            transaction);
        command.Parameters.AddWithValue("@Id", $"lc-{Guid.NewGuid():N}");
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@RefId", refId);
        command.Parameters.AddWithValue("@Origem", origem);
        command.Parameters.AddWithValue("@ProdutoId", produtoId);
        command.Parameters.AddWithValue("@LoteId", (object?)loteId ?? DBNull.Value);
        command.Parameters.AddWithValue("@Quantidade", quantidade);
        await command.ExecuteNonQueryAsync(cancellationToken);
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
