/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/InventarioAB.cs
 * Objetivo: inventário de estoque (migração 56): abrir por escopo, registrar contagens (várias pessoas, vários
 *           locais), anular, pedir recontagem, calcular divergências, aplicar o acerto numa transação e cancelar.
 *           O acerto usa a DIFERENÇA no momento da contagem (contado - saldo na 1a contagem) somada ao saldo atual,
 *           para preservar vendas/entradas feitas depois da contagem. Contagens e eventos são append-only.
 * Entradas esperadas: companyId e usuário já autenticados pelo controller; senha do gerente validada fora daqui.
 */
using System.Text.Json;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

/// <summary>Quem está operando (usuário logado) e de onde (IP/terminal) — vai para a trilha.</summary>
public sealed record InventarioAtor(string Id, string Nome, string? Ip, string? Terminal);

/// <summary>Resultado de uma operação: sucesso com dados ou recusa com código estável (vira HTTP no controller).</summary>
public sealed record InventarioResultado(bool Sucesso, string Codigo, string Mensagem, object? Dados = null)
{
    public static InventarioResultado Ok(object? dados = null, string mensagem = "OK") => new(true, "OK", mensagem, dados);
    public static InventarioResultado Falha(string codigo, string mensagem, object? dados = null) => new(false, codigo, mensagem, dados);
}

public class InventarioAB(Connection connection, LoteAB loteAB, ILogger<InventarioAB> logger)
{
    public const string EmContagem = "em_contagem";
    public const string Aplicado = "aplicado";
    public const string Cancelado = "cancelado";

    // ------------------------------------------------------------------ abertura

    public async Task<InventarioResultado> AbrirAsync(
        string companyId, InventarioAtor ator, string descricao, string escopo,
        IReadOnlyList<string>? categoriaIds, IReadOnlyList<string>? produtoIds)
    {
        escopo = (escopo ?? "").Trim().ToLowerInvariant();
        if (escopo is not ("loja" or "categorias" or "produtos"))
            return InventarioResultado.Falha("ESCOPO_INVALIDO", "Escopo deve ser 'loja', 'categorias' ou 'produtos'.");
        descricao = (descricao ?? "").Trim();
        if (descricao.Length == 0) descricao = escopo == "loja" ? "Inventário geral" : $"Inventário por {escopo}";
        if (descricao.Length > 200) descricao = descricao[..200];
        if (escopo == "categorias" && (categoriaIds is null || categoriaIds.Count == 0))
            return InventarioResultado.Falha("ESCOPO_VAZIO", "Informe ao menos uma categoria.");
        if (escopo == "produtos" && (produtoIds is null || produtoIds.Count == 0))
            return InventarioResultado.Falha("ESCOPO_VAZIO", "Informe ao menos um produto.");

        await using var db = await connection.OpenConnectionAsync();
        await using var tx = (SqlTransaction)await db.BeginTransactionAsync();
        try
        {
            // Serializa aberturas da mesma empresa (checagem de sobreposição + numeração).
            await using (var lk = new SqlCommand(
                "DECLARE @r INT; EXEC @r = sp_getapplock @Resource = @Res, @LockMode = 'Exclusive', @LockOwner = 'Transaction', @LockTimeout = 10000; SELECT @r;",
                db, tx))
            {
                lk.Parameters.AddWithValue("@Res", $"inventario:{companyId}");
                var r = Convert.ToInt32(await lk.ExecuteScalarAsync());
                if (r < 0) return InventarioResultado.Falha("OCUPADO", "Outro inventário está sendo aberto. Tente novamente.");
            }

            var produtos = new List<(string Id, string Codigo, string Nome, string Unidade, decimal Custo)>();
            var sqlProdutos = escopo switch
            {
                "loja" => "SELECT p.Id, p.ProductCode, p.ProductName, p.UnidadeComercial, p.ProductUnitPrice FROM Produtos p WHERE p.CompanyId = @C",
                "categorias" => """
                    SELECT p.Id, p.ProductCode, p.ProductName, p.UnidadeComercial, p.ProductUnitPrice
                    FROM Produtos p
                    LEFT JOIN Categorias c ON c.Id = p.CategoriaId
                    WHERE p.CompanyId = @C AND (p.CategoriaId IN (SELECT value FROM OPENJSON(@J))
                                            OR c.CategoriaPaiId IN (SELECT value FROM OPENJSON(@J)))
                    """,
                _ => "SELECT p.Id, p.ProductCode, p.ProductName, p.UnidadeComercial, p.ProductUnitPrice FROM Produtos p WHERE p.CompanyId = @C AND p.Id IN (SELECT value FROM OPENJSON(@J))",
            };
            await using (var cmd = new SqlCommand(sqlProdutos, db, tx))
            {
                cmd.Parameters.AddWithValue("@C", companyId);
                if (escopo != "loja")
                    cmd.Parameters.AddWithValue("@J", JsonSerializer.Serialize(escopo == "categorias" ? categoriaIds : produtoIds));
                await using var r = await cmd.ExecuteReaderAsync();
                while (await r.ReadAsync())
                    produtos.Add((r.GetString(0), r.GetString(1), r.GetString(2), r.GetString(3), r.GetDecimal(4)));
            }

            if (produtos.Count == 0)
                return InventarioResultado.Falha("ESCOPO_VAZIO", "Nenhum produto encontrado no escopo informado.");

            // Sobreposição: inventário da loja toda em contagem bloqueia tudo; senão, qualquer produto em comum.
            await using (var ov = new SqlCommand(
                """
                SELECT TOP 1 i.Numero FROM Inventarios i
                WHERE i.CompanyId = @C AND i.Status = N'em_contagem'
                  AND (i.Escopo = N'loja' OR @Loja = 1
                       OR EXISTS (SELECT 1 FROM InventarioItens it WHERE it.InventarioId = i.Id
                                  AND it.ProdutoId IN (SELECT value FROM OPENJSON(@Ids))))
                ORDER BY i.Numero;
                """, db, tx))
            {
                ov.Parameters.AddWithValue("@C", companyId);
                ov.Parameters.AddWithValue("@Loja", escopo == "loja" ? 1 : 0);
                ov.Parameters.AddWithValue("@Ids", JsonSerializer.Serialize(produtos.Select(p => p.Id)));
                var aberto = await ov.ExecuteScalarAsync();
                if (aberto is not null and not DBNull)
                    return InventarioResultado.Falha("ESCOPO_SOBREPOSTO",
                        $"Já existe o inventário #{aberto} em contagem que se sobrepõe a este escopo.",
                        new { numeroAberto = Convert.ToInt32(aberto) });
            }

            int numero;
            await using (var nx = new SqlCommand("SELECT ISNULL(MAX(Numero), 0) + 1 FROM Inventarios WHERE CompanyId = @C;", db, tx))
            {
                nx.Parameters.AddWithValue("@C", companyId);
                numero = Convert.ToInt32(await nx.ExecuteScalarAsync());
            }

            var id = $"inv-{Guid.NewGuid():N}";
            await using (var ins = new SqlCommand(
                """
                INSERT INTO Inventarios (Id, CompanyId, Numero, Descricao, Escopo, EscopoJson, Status, AbertoPorId, AbertoPorNome)
                VALUES (@Id, @C, @N, @D, @E, @J, N'em_contagem', @U, @UN);
                """, db, tx))
            {
                ins.Parameters.AddWithValue("@Id", id);
                ins.Parameters.AddWithValue("@C", companyId);
                ins.Parameters.AddWithValue("@N", numero);
                ins.Parameters.AddWithValue("@D", descricao);
                ins.Parameters.AddWithValue("@E", escopo);
                ins.Parameters.AddWithValue("@J", escopo == "loja" ? DBNull.Value : JsonSerializer.Serialize(escopo == "categorias" ? categoriaIds : produtoIds));
                ins.Parameters.AddWithValue("@U", Corta(ator.Id, 40));
                ins.Parameters.AddWithValue("@UN", Corta(ator.Nome, 180));
                await ins.ExecuteNonQueryAsync();
            }

            await using (var itens = new SqlCommand(
                """
                INSERT INTO InventarioItens (InventarioId, ProdutoId, CompanyId, Codigo, Nome, Unidade, Custo)
                SELECT @Id, p.Id, @C, p.ProductCode, p.ProductName, p.UnidadeComercial, p.ProductUnitPrice
                FROM Produtos p WHERE p.CompanyId = @C AND p.Id IN (SELECT value FROM OPENJSON(@Ids));
                """, db, tx))
            {
                itens.Parameters.AddWithValue("@Id", id);
                itens.Parameters.AddWithValue("@C", companyId);
                itens.Parameters.AddWithValue("@Ids", JsonSerializer.Serialize(produtos.Select(p => p.Id)));
                await itens.ExecuteNonQueryAsync();
            }

            await EventoAsync(db, tx, id, companyId, "aberto", ator, null, null,
                $"Inventário #{numero} aberto ({escopo}, {produtos.Count} produtos): {descricao}");
            await AuditLogAB.GravarNaTransacaoAsync(db, tx, companyId, ator.Id, ator.Nome, AuditEventTypes.InventarioAberto,
                $"Inventário #{numero} aberto ({escopo}, {produtos.Count} produtos): {descricao}", "Inventarios", id, ator.Ip);

            await tx.CommitAsync();
            return InventarioResultado.Ok(new { id, numero, status = EmContagem, itens = produtos.Count }, "Inventário aberto.");
        }
        catch
        {
            await tx.RollbackAsync();
            throw;
        }
    }

    // ------------------------------------------------------------------ consulta

    public async Task<List<object>> ListarAsync(string companyId, string? status)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var cmd = new SqlCommand(
            """
            SELECT TOP 200 i.Id, i.Numero, i.Descricao, i.Escopo, i.Status, i.AbertoPorNome, i.AbertoEm, i.AplicadoEm, i.CanceladoEm,
                   (SELECT COUNT(*) FROM InventarioItens it WHERE it.InventarioId = i.Id) AS Itens,
                   (SELECT COUNT(*) FROM InventarioItens it WHERE it.InventarioId = i.Id AND it.Contado IS NOT NULL) AS Contados
            FROM Inventarios i
            WHERE i.CompanyId = @C AND (@S IS NULL OR i.Status = @S)
            ORDER BY i.Numero DESC;
            """, db);
        cmd.Parameters.AddWithValue("@C", companyId);
        cmd.Parameters.AddWithValue("@S", string.IsNullOrWhiteSpace(status) ? DBNull.Value : status.Trim());
        var list = new List<object>();
        await using var r = await cmd.ExecuteReaderAsync();
        while (await r.ReadAsync())
            list.Add(new
            {
                id = r.GetString(0), numero = r.GetInt32(1), descricao = r.GetString(2), escopo = r.GetString(3),
                status = r.GetString(4), abertoPor = r.GetString(5), abertoEm = r.GetDateTimeOffset(6),
                aplicadoEm = r.IsDBNull(7) ? (DateTimeOffset?)null : r.GetDateTimeOffset(7),
                canceladoEm = r.IsDBNull(8) ? (DateTimeOffset?)null : r.GetDateTimeOffset(8),
                itens = r.GetInt32(9), contados = r.GetInt32(10),
            });
        return list;
    }

    public async Task<object?> ObterAsync(string companyId, string id, bool comValores)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var cmd = new SqlCommand(
            """
            SELECT Id, Numero, Descricao, Escopo, EscopoJson, Status, AbertoPorNome, AbertoEm, AplicadoPorNome, AplicadoEm,
                   SupervisorNome, Justificativa, ZerarNaoContados, CanceladoPorNome, CanceladoEm, MotivoCancelamento,
                   TotalSobra, TotalFalta, TotalLiquido
            FROM Inventarios WHERE CompanyId = @C AND Id = @Id;
            """, db);
        cmd.Parameters.AddWithValue("@C", companyId);
        cmd.Parameters.AddWithValue("@Id", id);
        await using var r = await cmd.ExecuteReaderAsync();
        if (!await r.ReadAsync()) return null;
        string? S(int i) => r.IsDBNull(i) ? null : r.GetString(i);
        DateTimeOffset? D(int i) => r.IsDBNull(i) ? null : r.GetDateTimeOffset(i);
        decimal? M(int i) => r.IsDBNull(i) ? null : r.GetDecimal(i);
        var header = new
        {
            id = r.GetString(0), numero = r.GetInt32(1), descricao = r.GetString(2), escopo = r.GetString(3),
            escopoJson = S(4), status = r.GetString(5), abertoPor = S(6), abertoEm = D(7),
            aplicadoPor = S(8), aplicadoEm = D(9), supervisor = S(10), justificativa = S(11),
            zerarNaoContados = r.GetBoolean(12), canceladoPor = S(13), canceladoEm = D(14), motivoCancelamento = S(15),
            totalSobra = comValores ? M(16) : null, totalFalta = comValores ? M(17) : null, totalLiquido = comValores ? M(18) : null,
        };
        await r.CloseAsync();

        var itens = await LerItensAsync(db, companyId, id, null);
        var contados = itens.Count(i => i.Contado is not null);
        var resumo = new
        {
            itens = itens.Count,
            contados,
            naoContados = itens.Count(i => i.Contado is null && !i.Recontar),
            recontar = itens.Count(i => i.Recontar),
            divergentes = itens.Count(i => i.Contado is not null && i.Contado != i.SaldoNa1aContagem),
            sobra = comValores ? itens.Where(i => i.ValorDiferenca > 0).Sum(i => i.ValorDiferenca ?? 0) : (decimal?)null,
            falta = comValores ? itens.Where(i => i.ValorDiferenca < 0).Sum(i => i.ValorDiferenca ?? 0) : (decimal?)null,
            liquido = comValores ? itens.Sum(i => i.ValorDiferenca ?? 0) : (decimal?)null,
        };
        return new { inventario = header, resumo };
    }

    /// <summary>Linha de divergência/contagem de um produto do inventário.</summary>
    public sealed record ItemLinha(
        string ProdutoId, string Codigo, string Nome, string Unidade, decimal Custo, decimal? SaldoNa1aContagem,
        decimal? Contado, decimal? Diferenca, decimal? ValorDiferenca, bool Recontar,
        DateTimeOffset? PrimeiraContagemEm, DateTimeOffset? UltimaContagemEm,
        decimal? SaldoAntes, decimal? Delta, decimal? SaldoDepois, decimal? ValorDelta, decimal? PisoAplicado);

    private static async Task<List<ItemLinha>> LerItensAsync(SqlConnection db, string companyId, string id, string? filtro, SqlTransaction? tx = null)
    {
        await using var cmd = new SqlCommand(
            """
            SELECT ProdutoId, Codigo, Nome, Unidade, Custo, SaldoNa1aContagem, Contado, Recontar,
                   PrimeiraContagemEm, UltimaContagemEm, SaldoAntes, Delta, SaldoDepois, ValorDelta, PisoAplicado
            FROM InventarioItens WHERE CompanyId = @C AND InventarioId = @Id ORDER BY Nome;
            """, db, tx);
        cmd.Parameters.AddWithValue("@C", companyId);
        cmd.Parameters.AddWithValue("@Id", id);
        var list = new List<ItemLinha>();
        await using var r = await cmd.ExecuteReaderAsync();
        while (await r.ReadAsync())
        {
            decimal? N(int i) => r.IsDBNull(i) ? null : r.GetDecimal(i);
            DateTimeOffset? D(int i) => r.IsDBNull(i) ? null : r.GetDateTimeOffset(i);
            var custo = r.GetDecimal(4);
            var saldo = N(5);
            var contado = N(6);
            decimal? dif = contado is null ? null : contado - (saldo ?? 0);
            list.Add(new ItemLinha(r.GetString(0), r.GetString(1), r.GetString(2), r.GetString(3), custo, saldo, contado,
                dif, dif is null ? null : Math.Round(dif.Value * custo, 2), r.GetBoolean(7), D(8), D(9),
                N(10), N(11), N(12), N(13), N(14)));
        }

        return filtro switch
        {
            "divergentes" => list.Where(i => i.Contado is not null && i.Diferenca != 0).ToList(),
            "nao_contados" => list.Where(i => i.Contado is null && !i.Recontar).ToList(),
            "recontar" => list.Where(i => i.Recontar).ToList(),
            "contados" => list.Where(i => i.Contado is not null).ToList(),
            _ => list,
        };
    }

    public async Task<List<ItemLinha>?> ItensAsync(string companyId, string id, string? filtro, bool comValores)
    {
        await using var db = await connection.OpenConnectionAsync();
        if (!await ExisteAsync(db, companyId, id)) return null;
        var itens = await LerItensAsync(db, companyId, id, filtro?.Trim().ToLowerInvariant());
        return comValores ? itens : itens.Select(i => i with { Custo = 0, ValorDiferenca = null, ValorDelta = null }).ToList();
    }

    private static async Task<bool> ExisteAsync(SqlConnection db, string companyId, string id)
    {
        await using var cmd = new SqlCommand("SELECT COUNT(*) FROM Inventarios WHERE CompanyId = @C AND Id = @Id;", db);
        cmd.Parameters.AddWithValue("@C", companyId);
        cmd.Parameters.AddWithValue("@Id", id);
        return Convert.ToInt32(await cmd.ExecuteScalarAsync()) > 0;
    }

    /// <summary>Contagens de um produto (ou do inventário todo) — as do próprio usuário para a lista do app.</summary>
    public async Task<List<object>> ContagensAsync(string companyId, string id, string? usuarioId, string? produtoId, int limite = 50)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var cmd = new SqlCommand(
            """
            SELECT TOP (@L) c.Id, c.ProdutoId, it.Codigo, it.Nome, c.Quantidade, c.Local, c.UsuarioNome, c.Terminal,
                   c.SaldoSistema, c.CriadoEm, c.AnuladaPorNome, c.AnuladaEm, c.AnuladaMotivo, c.UsuarioId
            FROM InventarioContagens c
            JOIN InventarioItens it ON it.InventarioId = c.InventarioId AND it.ProdutoId = c.ProdutoId
            WHERE c.CompanyId = @C AND c.InventarioId = @Id
              AND (@U IS NULL OR c.UsuarioId = @U) AND (@P IS NULL OR c.ProdutoId = @P)
            ORDER BY c.CriadoEm DESC;
            """, db);
        cmd.Parameters.AddWithValue("@L", Math.Clamp(limite, 1, 2000));
        cmd.Parameters.AddWithValue("@C", companyId);
        cmd.Parameters.AddWithValue("@Id", id);
        cmd.Parameters.AddWithValue("@U", (object?)usuarioId ?? DBNull.Value);
        cmd.Parameters.AddWithValue("@P", (object?)produtoId ?? DBNull.Value);
        var list = new List<object>();
        await using var r = await cmd.ExecuteReaderAsync();
        while (await r.ReadAsync())
        {
            string? S(int i) => r.IsDBNull(i) ? null : r.GetString(i);
            list.Add(new
            {
                id = r.GetString(0), produtoId = r.GetString(1), codigo = r.GetString(2), nome = r.GetString(3),
                quantidade = r.GetDecimal(4), local = S(5), usuario = r.GetString(6), terminal = S(7),
                saldoSistema = r.GetDecimal(8), criadoEm = r.GetDateTimeOffset(9),
                anuladaPor = S(10), anuladaEm = r.IsDBNull(11) ? (DateTimeOffset?)null : r.GetDateTimeOffset(11),
                anuladaMotivo = S(12), usuarioId = r.GetString(13),
            });
        }
        return list;
    }

    // ------------------------------------------------------------------ contagem

    public async Task<InventarioResultado> ContarAsync(
        string companyId, string inventarioId, InventarioAtor ator, string? produtoId, string? codigo,
        decimal quantidade, string? local)
    {
        if (quantidade < 0) return InventarioResultado.Falha("QUANTIDADE_INVALIDA", "A quantidade contada não pode ser negativa.");

        await using var db = await connection.OpenConnectionAsync();
        await using var tx = (SqlTransaction)await db.BeginTransactionAsync();
        try
        {
            var status = await StatusComLockAsync(db, tx, companyId, inventarioId);
            if (status is null) return InventarioResultado.Falha("NAO_ENCONTRADO", "Inventário não encontrado.");
            if (status != EmContagem)
                return InventarioResultado.Falha("INVENTARIO_ENCERRADO", $"O inventário está {Rotulo(status)} e não aceita contagens.");

            // Resolve o produto: id direto, ou código interno / GTIN.
            if (string.IsNullOrWhiteSpace(produtoId))
            {
                if (string.IsNullOrWhiteSpace(codigo))
                    return InventarioResultado.Falha("PRODUTO_OBRIGATORIO", "Informe o produto ou o código.");
                await using var find = new SqlCommand(
                    "SELECT TOP 1 Id FROM Produtos WHERE CompanyId = @C AND (ProductCode = @K OR (Gtin = @K AND Gtin <> N'SEM GTIN'));", db, tx);
                find.Parameters.AddWithValue("@C", companyId);
                find.Parameters.AddWithValue("@K", codigo.Trim());
                produtoId = await find.ExecuteScalarAsync() as string;
                if (produtoId is null)
                    return InventarioResultado.Falha("CODIGO_DESCONHECIDO", $"Nenhum produto com o código '{codigo.Trim()}'.");
            }

            string nome, unidade, cod;
            await using (var it = new SqlCommand(
                "SELECT Nome, Unidade, Codigo FROM InventarioItens WITH (UPDLOCK, ROWLOCK) WHERE InventarioId = @I AND ProdutoId = @P;", db, tx))
            {
                it.Parameters.AddWithValue("@I", inventarioId);
                it.Parameters.AddWithValue("@P", produtoId);
                await using var r = await it.ExecuteReaderAsync();
                if (!await r.ReadAsync())
                    return InventarioResultado.Falha("FORA_DO_ESCOPO", "O produto está fora do escopo deste inventário.");
                nome = r.GetString(0);
                unidade = r.GetString(1);
                cod = r.GetString(2);
            }

            if (string.Equals(unidade.Trim(), "UN", StringComparison.OrdinalIgnoreCase) && quantidade != decimal.Truncate(quantidade))
                return InventarioResultado.Falha("QUANTIDADE_INVALIDA", "Produto em UN não aceita quantidade fracionada.");

            decimal saldo;
            await using (var p = new SqlCommand(
                "SELECT ProductQnt FROM Produtos WITH (UPDLOCK, ROWLOCK) WHERE Id = @P AND CompanyId = @C;", db, tx))
            {
                p.Parameters.AddWithValue("@P", produtoId);
                p.Parameters.AddWithValue("@C", companyId);
                saldo = Convert.ToDecimal(await p.ExecuteScalarAsync());
            }

            var contagemId = $"ic-{Guid.NewGuid():N}";
            await using (var ins = new SqlCommand(
                """
                INSERT INTO InventarioContagens (Id, InventarioId, CompanyId, ProdutoId, Quantidade, Local, UsuarioId, UsuarioNome, Terminal, Ip, SaldoSistema)
                VALUES (@Id, @I, @C, @P, @Q, @L, @U, @UN, @T, @Ip, @S);
                """, db, tx))
            {
                ins.Parameters.AddWithValue("@Id", contagemId);
                ins.Parameters.AddWithValue("@I", inventarioId);
                ins.Parameters.AddWithValue("@C", companyId);
                ins.Parameters.AddWithValue("@P", produtoId);
                ins.Parameters.AddWithValue("@Q", quantidade);
                ins.Parameters.AddWithValue("@L", Db(Corta(local, 80)));
                ins.Parameters.AddWithValue("@U", Corta(ator.Id, 40));
                ins.Parameters.AddWithValue("@UN", Corta(ator.Nome, 180));
                ins.Parameters.AddWithValue("@T", Db(Corta(ator.Terminal, 80)));
                ins.Parameters.AddWithValue("@Ip", Db(Corta(ator.Ip, 64)));
                ins.Parameters.AddWithValue("@S", saldo);
                await ins.ExecuteNonQueryAsync();
            }

            decimal total;
            await using (var up = new SqlCommand(
                """
                UPDATE InventarioItens
                   SET Contado = ISNULL(Contado, 0) + @Q,
                       SaldoNa1aContagem = ISNULL(SaldoNa1aContagem, @S),
                       PrimeiraContagemEm = ISNULL(PrimeiraContagemEm, SYSDATETIMEOFFSET()),
                       UltimaContagemEm = SYSDATETIMEOFFSET(),
                       Recontar = 0
                 WHERE InventarioId = @I AND ProdutoId = @P;
                SELECT Contado FROM InventarioItens WHERE InventarioId = @I AND ProdutoId = @P;
                """, db, tx))
            {
                up.Parameters.AddWithValue("@Q", quantidade);
                up.Parameters.AddWithValue("@S", saldo);
                up.Parameters.AddWithValue("@I", inventarioId);
                up.Parameters.AddWithValue("@P", produtoId);
                total = Convert.ToDecimal(await up.ExecuteScalarAsync());
            }

            await tx.CommitAsync();
            return InventarioResultado.Ok(new
            {
                contagemId, produtoId, codigo = cod, nome, unidade, quantidade, totalContado = total, local,
            }, "Contagem registrada.");
        }
        catch
        {
            await tx.RollbackAsync();
            throw;
        }
    }

    public async Task<InventarioResultado> AnularContagemAsync(
        string companyId, string inventarioId, string contagemId, InventarioAtor ator, string motivo, bool apenasPropria)
    {
        if (string.IsNullOrWhiteSpace(motivo))
            return InventarioResultado.Falha("MOTIVO_OBRIGATORIO", "Informe o motivo da anulação.");

        await using var db = await connection.OpenConnectionAsync();
        await using var tx = (SqlTransaction)await db.BeginTransactionAsync();
        try
        {
            var status = await StatusComLockAsync(db, tx, companyId, inventarioId);
            if (status is null) return InventarioResultado.Falha("NAO_ENCONTRADO", "Inventário não encontrado.");
            if (status != EmContagem)
                return InventarioResultado.Falha("INVENTARIO_ENCERRADO", $"O inventário está {Rotulo(status)}.");

            string produtoId, donoId;
            bool jaAnulada;
            await using (var c = new SqlCommand(
                "SELECT ProdutoId, UsuarioId, CASE WHEN AnuladaEm IS NULL THEN 0 ELSE 1 END FROM InventarioContagens WHERE Id = @Id AND InventarioId = @I AND CompanyId = @C;", db, tx))
            {
                c.Parameters.AddWithValue("@Id", contagemId);
                c.Parameters.AddWithValue("@I", inventarioId);
                c.Parameters.AddWithValue("@C", companyId);
                await using var r = await c.ExecuteReaderAsync();
                if (!await r.ReadAsync()) return InventarioResultado.Falha("NAO_ENCONTRADO", "Contagem não encontrada.");
                produtoId = r.GetString(0);
                donoId = r.GetString(1);
                jaAnulada = r.GetInt32(2) == 1;
            }

            if (jaAnulada) return InventarioResultado.Falha("JA_ANULADA", "Esta contagem já foi anulada.");
            if (apenasPropria && donoId != ator.Id)
                return InventarioResultado.Falha("SEM_PERMISSAO", "Você só pode anular os seus próprios lançamentos.");

            await using (var an = new SqlCommand(
                "UPDATE InventarioContagens SET AnuladaPorId = @U, AnuladaPorNome = @UN, AnuladaEm = SYSDATETIMEOFFSET(), AnuladaMotivo = @M WHERE Id = @Id AND AnuladaEm IS NULL;", db, tx))
            {
                an.Parameters.AddWithValue("@U", Corta(ator.Id, 40));
                an.Parameters.AddWithValue("@UN", Corta(ator.Nome, 180));
                an.Parameters.AddWithValue("@M", Corta(motivo.Trim(), 300));
                an.Parameters.AddWithValue("@Id", contagemId);
                await an.ExecuteNonQueryAsync();
            }

            await RecalcularItemAsync(db, tx, inventarioId, produtoId);
            await tx.CommitAsync();
            return InventarioResultado.Ok(new { contagemId, produtoId }, "Contagem anulada.");
        }
        catch
        {
            await tx.RollbackAsync();
            throw;
        }
    }

    /// <summary>Refaz Contado/SaldoNa1aContagem a partir das contagens válidas (null se não sobrou nenhuma).</summary>
    private static async Task RecalcularItemAsync(SqlConnection db, SqlTransaction tx, string inventarioId, string produtoId)
    {
        await using var cmd = new SqlCommand(
            """
            DECLARE @soma DECIMAL(15,4), @n INT, @saldo DECIMAL(15,4), @primeira DATETIMEOFFSET, @ultima DATETIMEOFFSET;
            SELECT @soma = SUM(Quantidade), @n = COUNT(*), @ultima = MAX(CriadoEm)
              FROM InventarioContagens WHERE InventarioId = @I AND ProdutoId = @P AND AnuladaEm IS NULL;
            SELECT TOP 1 @saldo = SaldoSistema, @primeira = CriadoEm
              FROM InventarioContagens WHERE InventarioId = @I AND ProdutoId = @P AND AnuladaEm IS NULL ORDER BY CriadoEm;
            UPDATE InventarioItens
               SET Contado = CASE WHEN @n = 0 THEN NULL ELSE @soma END,
                   SaldoNa1aContagem = CASE WHEN @n = 0 THEN NULL ELSE @saldo END,
                   PrimeiraContagemEm = CASE WHEN @n = 0 THEN NULL ELSE @primeira END,
                   UltimaContagemEm = CASE WHEN @n = 0 THEN NULL ELSE @ultima END
             WHERE InventarioId = @I AND ProdutoId = @P;
            """, db, tx);
        cmd.Parameters.AddWithValue("@I", inventarioId);
        cmd.Parameters.AddWithValue("@P", produtoId);
        await cmd.ExecuteNonQueryAsync();
    }

    /// <summary>
    /// Pede nova contagem: anula as contagens válidas do produto (sem apagar), zera a base da diferença e marca
    /// "recontar" até chegar uma nova contagem.
    /// </summary>
    public async Task<InventarioResultado> RecontarAsync(string companyId, string inventarioId, InventarioAtor ator, IReadOnlyList<string> produtoIds)
    {
        if (produtoIds.Count == 0) return InventarioResultado.Falha("PRODUTO_OBRIGATORIO", "Informe os produtos a recontar.");
        await using var db = await connection.OpenConnectionAsync();
        await using var tx = (SqlTransaction)await db.BeginTransactionAsync();
        try
        {
            var status = await StatusComLockAsync(db, tx, companyId, inventarioId);
            if (status is null) return InventarioResultado.Falha("NAO_ENCONTRADO", "Inventário não encontrado.");
            if (status != EmContagem)
                return InventarioResultado.Falha("INVENTARIO_ENCERRADO", $"O inventário está {Rotulo(status)}.");

            var ids = JsonSerializer.Serialize(produtoIds);
            int n;
            await using (var an = new SqlCommand(
                """
                UPDATE InventarioContagens
                   SET AnuladaPorId = @U, AnuladaPorNome = @UN, AnuladaEm = SYSDATETIMEOFFSET(), AnuladaMotivo = N'Recontagem solicitada'
                 WHERE InventarioId = @I AND AnuladaEm IS NULL AND ProdutoId IN (SELECT value FROM OPENJSON(@Ids));
                UPDATE InventarioItens
                   SET Recontar = 1, Contado = NULL, SaldoNa1aContagem = NULL, PrimeiraContagemEm = NULL, UltimaContagemEm = NULL
                 WHERE InventarioId = @I AND ProdutoId IN (SELECT value FROM OPENJSON(@Ids));
                SELECT @@ROWCOUNT;
                """, db, tx))
            {
                an.Parameters.AddWithValue("@U", Corta(ator.Id, 40));
                an.Parameters.AddWithValue("@UN", Corta(ator.Nome, 180));
                an.Parameters.AddWithValue("@I", inventarioId);
                an.Parameters.AddWithValue("@Ids", ids);
                n = Convert.ToInt32(await an.ExecuteScalarAsync());
            }

            if (n == 0) return InventarioResultado.Falha("FORA_DO_ESCOPO", "Nenhum dos produtos pertence ao inventário.");
            await EventoAsync(db, tx, inventarioId, companyId, "recontagem", ator, null, null,
                $"Recontagem pedida para {n} produto(s): {string.Join(", ", produtoIds.Take(10))}");
            await tx.CommitAsync();
            return InventarioResultado.Ok(new { produtos = n }, "Recontagem solicitada.");
        }
        catch
        {
            await tx.RollbackAsync();
            throw;
        }
    }

    // ------------------------------------------------------------------ aplicação

    public async Task<InventarioResultado> AplicarAsync(
        string companyId, string inventarioId, InventarioAtor ator, string supervisorId, string supervisorNome,
        string justificativa, bool zerarNaoContados)
    {
        var lotes = new List<(string ProdutoId, decimal Delta)>();
        InventarioResultado resultado;

        await using (var db = await connection.OpenConnectionAsync())
        await using (var tx = (SqlTransaction)await db.BeginTransactionAsync())
        {
            try
            {
                int numero;
                string status;
                await using (var h = new SqlCommand(
                    "SELECT Numero, Status FROM Inventarios WITH (UPDLOCK, ROWLOCK) WHERE Id = @Id AND CompanyId = @C;", db, tx))
                {
                    h.Parameters.AddWithValue("@Id", inventarioId);
                    h.Parameters.AddWithValue("@C", companyId);
                    await using var r = await h.ExecuteReaderAsync();
                    if (!await r.ReadAsync()) return InventarioResultado.Falha("NAO_ENCONTRADO", "Inventário não encontrado.");
                    numero = r.GetInt32(0);
                    status = r.GetString(1);
                }

                if (status != EmContagem)
                    return InventarioResultado.Falha(status == Aplicado ? "JA_APLICADO" : "INVENTARIO_ENCERRADO",
                        status == Aplicado ? "Este inventário já foi aplicado." : "Este inventário foi cancelado e não pode ser aplicado.");

                var itens = await LerItensAsync(db, companyId, inventarioId, null, tx);
                decimal sobra = 0, falta = 0;
                var ajustados = 0;
                var motivo = Corta($"Inventário #{numero}: {justificativa.Trim()}", 500);

                foreach (var item in itens)
                {
                    if (item.Recontar) continue; // aguardando nova contagem: nunca é ajustado
                    var contado = item.Contado;
                    if (contado is null && !zerarNaoContados) continue;

                    decimal atual, custoAtual;
                    await using (var p = new SqlCommand(
                        "SELECT ProductQnt, ProductUnitPrice FROM Produtos WITH (UPDLOCK, ROWLOCK) WHERE Id = @P AND CompanyId = @C;", db, tx))
                    {
                        p.Parameters.AddWithValue("@P", item.ProdutoId);
                        p.Parameters.AddWithValue("@C", companyId);
                        await using var r = await p.ExecuteReaderAsync();
                        if (!await r.ReadAsync()) continue;
                        atual = r.GetDecimal(0);
                        custoAtual = r.GetDecimal(1);
                    }

                    // Contado: diferença sobre o saldo da 1a contagem (preserva vendas/entradas posteriores).
                    // Não contado zerado: leva o saldo atual a zero.
                    var delta = contado is null ? -atual : contado.Value - (item.SaldoNa1aContagem ?? 0);
                    var novo = atual + delta;
                    decimal piso = 0;
                    if (novo < 0) { piso = -novo; novo = 0; }
                    var deltaReal = novo - atual;
                    var valor = Math.Round(deltaReal * item.Custo, 2);

                    if (deltaReal != 0)
                    {
                        await using (var up = new SqlCommand(
                            "UPDATE Produtos SET ProductQnt = @Q, TotalPriceOnProduct = @T WHERE Id = @P AND CompanyId = @C;", db, tx))
                        {
                            up.Parameters.AddWithValue("@Q", novo);
                            up.Parameters.AddWithValue("@T", custoAtual * novo);
                            up.Parameters.AddWithValue("@P", item.ProdutoId);
                            up.Parameters.AddWithValue("@C", companyId);
                            await up.ExecuteNonQueryAsync();
                        }

                        await using (var mv = new SqlCommand(
                            """
                            INSERT INTO EstoqueMovimentacoes (Id, CompanyId, ProdutoId, Tipo, Quantidade, EstoqueAnterior, EstoqueNovo, Motivo, UsuarioId, UsuarioNome)
                            VALUES (@Id, @C, @P, N'inventario', @Q, @A, @N, @M, @U, @UN);
                            """, db, tx))
                        {
                            mv.Parameters.AddWithValue("@Id", $"mov-{Guid.NewGuid():N}");
                            mv.Parameters.AddWithValue("@C", companyId);
                            mv.Parameters.AddWithValue("@P", item.ProdutoId);
                            mv.Parameters.AddWithValue("@Q", Math.Abs(deltaReal));
                            mv.Parameters.AddWithValue("@A", atual);
                            mv.Parameters.AddWithValue("@N", novo);
                            mv.Parameters.AddWithValue("@M", motivo);
                            mv.Parameters.AddWithValue("@U", Corta(supervisorId, 36));
                            mv.Parameters.AddWithValue("@UN", Corta($"{supervisorNome} (inventário)", 200));
                            await mv.ExecuteNonQueryAsync();
                        }

                        lotes.Add((item.ProdutoId, deltaReal));
                        ajustados++;
                    }

                    if (valor > 0) sobra += valor; else falta += valor;

                    await using (var ui = new SqlCommand(
                        """
                        UPDATE InventarioItens
                           SET SaldoAntes = @A, Delta = @D, SaldoDepois = @N, ValorDelta = @V, PisoAplicado = @Pi,
                               Contado = ISNULL(Contado, 0)
                         WHERE InventarioId = @I AND ProdutoId = @P;
                        """, db, tx))
                    {
                        ui.Parameters.AddWithValue("@A", atual);
                        ui.Parameters.AddWithValue("@D", deltaReal);
                        ui.Parameters.AddWithValue("@N", novo);
                        ui.Parameters.AddWithValue("@V", valor);
                        ui.Parameters.AddWithValue("@Pi", piso > 0 ? piso : DBNull.Value);
                        ui.Parameters.AddWithValue("@I", inventarioId);
                        ui.Parameters.AddWithValue("@P", item.ProdutoId);
                        await ui.ExecuteNonQueryAsync();
                    }
                }

                await using (var fin = new SqlCommand(
                    """
                    UPDATE Inventarios
                       SET Status = N'aplicado', AplicadoPorId = @U, AplicadoPorNome = @UN, AplicadoEm = SYSDATETIMEOFFSET(),
                           SupervisorId = @S, SupervisorNome = @SN, Justificativa = @J, ZerarNaoContados = @Z,
                           TotalSobra = @So, TotalFalta = @Fa, TotalLiquido = @Li
                     WHERE Id = @Id;
                    """, db, tx))
                {
                    fin.Parameters.AddWithValue("@U", Corta(ator.Id, 40));
                    fin.Parameters.AddWithValue("@UN", Corta(ator.Nome, 180));
                    fin.Parameters.AddWithValue("@S", Corta(supervisorId, 40));
                    fin.Parameters.AddWithValue("@SN", Corta(supervisorNome, 180));
                    fin.Parameters.AddWithValue("@J", Corta(justificativa.Trim(), 500));
                    fin.Parameters.AddWithValue("@Z", zerarNaoContados);
                    fin.Parameters.AddWithValue("@So", sobra);
                    fin.Parameters.AddWithValue("@Fa", falta);
                    fin.Parameters.AddWithValue("@Li", sobra + falta);
                    fin.Parameters.AddWithValue("@Id", inventarioId);
                    await fin.ExecuteNonQueryAsync();
                }

                var desc = $"Inventário #{numero} aplicado por {supervisorNome}: {ajustados} produto(s) ajustado(s), " +
                           $"sobra {sobra:N2}, falta {falta:N2}, líquido {sobra + falta:N2}. {justificativa.Trim()}";
                await EventoAsync(db, tx, inventarioId, companyId, "aplicado", ator, supervisorId, supervisorNome, desc);
                await AuditLogAB.GravarNaTransacaoAsync(db, tx, companyId, ator.Id, ator.Nome,
                    AuditEventTypes.InventarioAplicado, desc, "Inventarios", inventarioId, ator.Ip);

                await tx.CommitAsync();
                resultado = InventarioResultado.Ok(new
                {
                    id = inventarioId, numero, ajustados, totalSobra = sobra, totalFalta = falta, totalLiquido = sobra + falta,
                }, "Inventário aplicado.");
            }
            catch
            {
                await tx.RollbackAsync();
                throw;
            }
        }

        // Lotes (validade): rotinas "seguras" com transação própria, depois do acerto de saldo.
        foreach (var (produtoId, delta) in lotes)
        {
            try
            {
                if (delta > 0) await loteAB.RegistrarEntradaAsync(companyId, produtoId, null, delta, null, "inventario", ator.Nome);
                else await loteAB.ConsumirAjusteSeguroAsync(companyId, produtoId, -delta);
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "Falha ao refletir o inventário nos lotes do produto {ProdutoId}.", produtoId);
            }
        }

        return resultado;
    }

    public async Task<InventarioResultado> CancelarAsync(string companyId, string inventarioId, InventarioAtor ator, string motivo)
    {
        if (string.IsNullOrWhiteSpace(motivo) || motivo.Trim().Length < 3)
            return InventarioResultado.Falha("MOTIVO_OBRIGATORIO", "Informe o motivo do cancelamento.");

        await using var db = await connection.OpenConnectionAsync();
        await using var tx = (SqlTransaction)await db.BeginTransactionAsync();
        try
        {
            string status;
            int numero;
            await using (var h = new SqlCommand(
                "SELECT Numero, Status FROM Inventarios WITH (UPDLOCK, ROWLOCK) WHERE Id = @Id AND CompanyId = @C;", db, tx))
            {
                h.Parameters.AddWithValue("@Id", inventarioId);
                h.Parameters.AddWithValue("@C", companyId);
                await using var r = await h.ExecuteReaderAsync();
                if (!await r.ReadAsync()) return InventarioResultado.Falha("NAO_ENCONTRADO", "Inventário não encontrado.");
                numero = r.GetInt32(0);
                status = r.GetString(1);
            }

            if (status != EmContagem)
                return InventarioResultado.Falha("INVENTARIO_ENCERRADO", $"O inventário já está {Rotulo(status)}.");

            await using (var up = new SqlCommand(
                """
                UPDATE Inventarios SET Status = N'cancelado', CanceladoPorId = @U, CanceladoPorNome = @UN,
                       CanceladoEm = SYSDATETIMEOFFSET(), MotivoCancelamento = @M WHERE Id = @Id;
                """, db, tx))
            {
                up.Parameters.AddWithValue("@U", Corta(ator.Id, 40));
                up.Parameters.AddWithValue("@UN", Corta(ator.Nome, 180));
                up.Parameters.AddWithValue("@M", Corta(motivo.Trim(), 500));
                up.Parameters.AddWithValue("@Id", inventarioId);
                await up.ExecuteNonQueryAsync();
            }

            var desc = $"Inventário #{numero} cancelado: {motivo.Trim()}";
            await EventoAsync(db, tx, inventarioId, companyId, "cancelado", ator, null, null, desc);
            await AuditLogAB.GravarNaTransacaoAsync(db, tx, companyId, ator.Id, ator.Nome,
                AuditEventTypes.InventarioCancelado, desc, "Inventarios", inventarioId, ator.Ip);
            await tx.CommitAsync();
            return InventarioResultado.Ok(new { id = inventarioId, numero }, "Inventário cancelado.");
        }
        catch
        {
            await tx.RollbackAsync();
            throw;
        }
    }

    /// <summary>Tentativa de aplicação negada (senha errada etc.): conexão própria, nunca lança.</summary>
    public async Task RegistrarNegadaAsync(string companyId, string inventarioId, InventarioAtor ator,
        string? supervisorId, string motivo)
    {
        try
        {
            await using var db = await connection.OpenConnectionAsync();
            string? supervisorNome = null;
            if (!string.IsNullOrWhiteSpace(supervisorId))
            {
                await using var n = new SqlCommand("SELECT Name FROM Usuarios WHERE CompanyId = @C AND Id = @Id;", db);
                n.Parameters.AddWithValue("@C", companyId);
                n.Parameters.AddWithValue("@Id", supervisorId.Trim());
                supervisorNome = await n.ExecuteScalarAsync() as string;
            }

            int? numero = null;
            await using (var h = new SqlCommand("SELECT Numero FROM Inventarios WHERE CompanyId = @C AND Id = @Id;", db))
            {
                h.Parameters.AddWithValue("@C", companyId);
                h.Parameters.AddWithValue("@Id", inventarioId);
                var v = await h.ExecuteScalarAsync();
                if (v is not null and not DBNull) numero = Convert.ToInt32(v);
            }

            if (numero is null) return;
            var desc = Corta($"Aplicação do inventário #{numero} negada: {motivo}" +
                             (string.IsNullOrWhiteSpace(supervisorNome ?? supervisorId) ? "" : $" Gerente informado: {supervisorNome ?? supervisorId}.") +
                             (string.IsNullOrWhiteSpace(ator.Terminal) ? "" : $" Terminal: {ator.Terminal}."), 500);
            await EventoAsync(db, null, inventarioId, companyId, "aplicacao_negada", ator, supervisorId, supervisorNome, desc);
            await using var audit = new SqlCommand(
                """
                INSERT INTO AuditLog (CompanyId, UserId, UserName, EventType, EntityType, EntityId, Description, Ip)
                VALUES (@C, @U, @UN, @T, N'Inventarios', @E, @D, @Ip);
                """, db);
            audit.Parameters.AddWithValue("@C", companyId);
            audit.Parameters.AddWithValue("@U", Corta(ator.Id, 40));
            audit.Parameters.AddWithValue("@UN", Corta(ator.Nome, 180));
            audit.Parameters.AddWithValue("@T", AuditEventTypes.InventarioAplicacaoNegada);
            audit.Parameters.AddWithValue("@E", inventarioId);
            audit.Parameters.AddWithValue("@D", desc);
            audit.Parameters.AddWithValue("@Ip", Db(Corta(ator.Ip, 64)));
            await audit.ExecuteNonQueryAsync();
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Tentativa negada de aplicação do inventário {Id} não registrada.", inventarioId);
        }
    }

    // ------------------------------------------------------------------ trilha

    public async Task<object?> TrilhaAsync(string companyId, string inventarioId)
    {
        await using var db = await connection.OpenConnectionAsync();
        if (!await ExisteAsync(db, companyId, inventarioId)) return null;

        var eventos = new List<object>();
        await using (var e = new SqlCommand(
            """
            SELECT Tipo, OcorridoEm, UsuarioNome, SupervisorNome, Ip, Terminal, Descricao
            FROM InventarioEventos WHERE CompanyId = @C AND InventarioId = @I ORDER BY OcorridoEm;
            """, db))
        {
            e.Parameters.AddWithValue("@C", companyId);
            e.Parameters.AddWithValue("@I", inventarioId);
            await using var r = await e.ExecuteReaderAsync();
            while (await r.ReadAsync())
            {
                string? S(int i) => r.IsDBNull(i) ? null : r.GetString(i);
                eventos.Add(new
                {
                    tipo = r.GetString(0), ocorridoEm = r.GetDateTimeOffset(1), usuario = r.GetString(2),
                    gerente = S(3), ip = S(4), terminal = S(5), descricao = S(6),
                });
            }
        }

        var contagens = await ContagensAsync(companyId, inventarioId, null, null, 2000);
        var itens = (await LerItensAsync(db, companyId, inventarioId, null))
            .Where(i => i.SaldoAntes is not null || i.Contado is not null).ToList();
        var inventario = await ObterAsync(companyId, inventarioId, true);
        return new { inventario, eventos, contagens, itens };
    }

    // ------------------------------------------------------------------ helpers

    private static async Task<string?> StatusComLockAsync(SqlConnection db, SqlTransaction tx, string companyId, string id)
    {
        await using var cmd = new SqlCommand(
            "SELECT Status FROM Inventarios WITH (UPDLOCK, ROWLOCK) WHERE Id = @Id AND CompanyId = @C;", db, tx);
        cmd.Parameters.AddWithValue("@Id", id);
        cmd.Parameters.AddWithValue("@C", companyId);
        return await cmd.ExecuteScalarAsync() as string;
    }

    private static async Task EventoAsync(SqlConnection db, SqlTransaction? tx, string inventarioId, string companyId,
        string tipo, InventarioAtor ator, string? supervisorId, string? supervisorNome, string? descricao)
    {
        await using var cmd = new SqlCommand(
            """
            INSERT INTO InventarioEventos (Id, InventarioId, CompanyId, Tipo, UsuarioId, UsuarioNome, SupervisorId, SupervisorNome, Ip, Terminal, Descricao)
            VALUES (@Id, @I, @C, @T, @U, @UN, @S, @SN, @Ip, @Te, @D);
            """, db, tx);
        cmd.Parameters.AddWithValue("@Id", $"ie-{Guid.NewGuid():N}");
        cmd.Parameters.AddWithValue("@I", inventarioId);
        cmd.Parameters.AddWithValue("@C", companyId);
        cmd.Parameters.AddWithValue("@T", tipo);
        cmd.Parameters.AddWithValue("@U", Corta(ator.Id, 40));
        cmd.Parameters.AddWithValue("@UN", Corta(ator.Nome, 180));
        cmd.Parameters.AddWithValue("@S", Db(Corta(supervisorId, 40)));
        cmd.Parameters.AddWithValue("@SN", Db(Corta(supervisorNome, 180)));
        cmd.Parameters.AddWithValue("@Ip", Db(Corta(ator.Ip, 64)));
        cmd.Parameters.AddWithValue("@Te", Db(Corta(ator.Terminal, 80)));
        cmd.Parameters.AddWithValue("@D", Db(Corta(descricao, 500)));
        await cmd.ExecuteNonQueryAsync();
    }

    private static string Rotulo(string status) => status switch
    {
        Aplicado => "aplicado",
        Cancelado => "cancelado",
        _ => "em contagem",
    };

    private static object Db(string? value) => string.IsNullOrWhiteSpace(value) ? DBNull.Value : value;

    private static string Corta(string? value, int max) =>
        value is null ? "" : value.Length <= max ? value : value[..max];
}
