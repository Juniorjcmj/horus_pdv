/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/VendaCancelamentoAB.cs
 * Objetivo: trilha de auditoria dos cancelamentos de venda (tabela VendaCancelamentos, migração 55). Uma linha por
 *           tentativa, autorizada ou negada, com a fotografia da venda no momento (itens, pagamentos, cliente,
 *           notas fiscais e o turno de caixa). Append-only: só INSERT e SELECT.
 * Entradas esperadas: conexão/transação da operação de cancelamento ou conexão própria (tentativas negadas).
 */
using System.Text.Json;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

/// <summary>Fotografia da venda no momento do pedido de cancelamento.</summary>
public sealed record VendaCancelamentoSnapshot(
    string VendaId,
    string SaleNumber,
    string Status,
    decimal TotalVenda,
    string? ClienteNome,
    string? ClienteCpf,
    string? CaixaSessaoId,
    string? CaixaSessaoStatus,
    List<VendaCancelamentoSnapshot.Item> Itens,
    List<VendaCancelamentoSnapshot.Pagamento> Pagamentos,
    List<VendaCancelamentoSnapshot.Nota> Notas)
{
    public sealed record Item(string Codigo, string Nome, decimal Quantidade, decimal PrecoUnitario, decimal Total);

    public sealed record Pagamento(string Forma, decimal Valor, decimal? Recebido, decimal? Troco);

    public sealed record Nota(string Id, int Modelo, string? Numero, string? Serie, int Status, string? Chave);

    public string ToJson() => JsonSerializer.Serialize(new
    {
        itens = Itens,
        pagamentos = Pagamentos,
        notas = Notas,
    }, new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase });
}

/// <summary>Uma linha da trilha (o que gravar).</summary>
public sealed class VendaCancelamentoRegistro
{
    public required string CompanyId { get; init; }
    public required string Resultado { get; init; } // cancelada | negada
    public required string Via { get; init; } // venda | nfce | nfe
    public string? MotivoRecusa { get; init; }
    public string? SaleNumber { get; init; }
    public required string OperadorId { get; init; }
    public required string OperadorNome { get; init; }
    public string? SupervisorId { get; init; }
    public string? SupervisorNome { get; init; }
    public string? Justificativa { get; init; }
    public string? Terminal { get; init; }
    public string? Ip { get; init; }
    public int? ItensEstornados { get; init; }
    public decimal? FiadoEstornado { get; init; }
    public VendaCancelamentoSnapshot? Snapshot { get; init; }
}

/// <summary>Linha lida para o relatório de auditoria.</summary>
public sealed class VendaCancelamentoAuditoriaAD
{
    public string Id { get; set; } = string.Empty;
    public DateTimeOffset OcorridoEm { get; set; }
    public string Resultado { get; set; } = string.Empty;
    public string Via { get; set; } = string.Empty;
    public string? MotivoRecusa { get; set; }
    public string? SaleNumber { get; set; }
    public string OperadorId { get; set; } = string.Empty;
    public string OperadorNome { get; set; } = string.Empty;
    public string? SupervisorId { get; set; }
    public string? SupervisorNome { get; set; }
    public string? Justificativa { get; set; }
    public string? Terminal { get; set; }
    public string? Ip { get; set; }
    public string? CaixaSessaoId { get; set; }
    public string? CaixaSessaoStatus { get; set; }
    public decimal? TotalVenda { get; set; }
    public string? ClienteNome { get; set; }
    public string? ClienteCpf { get; set; }
    public int? ItensEstornados { get; set; }
    public decimal? FiadoEstornado { get; set; }
    public JsonElement? Snapshot { get; set; }
}

public class VendaCancelamentoAB(Connection connection)
{
    /// <summary>Lê a venda (itens, pagamentos, notas e turno) dentro da transação do cancelamento.</summary>
    public static async Task<VendaCancelamentoSnapshot?> CapturarAsync(
        SqlConnection db, SqlTransaction? transaction, string companyId, string? vendaId, string? saleNumber)
    {
        string id, number, status;
        decimal total;
        string? cliente, cpf, sessao;
        await using (var cmd = new SqlCommand(
            """
            SELECT Id, SaleNumber, ISNULL(Status, 'finalizada') AS Status, TotalAmount, CustomerName, CustomerCpf, CaixaSessaoId
            FROM Vendas
            WHERE CompanyId = @CompanyId AND (Id = @VendaId OR (@VendaId IS NULL AND SaleNumber = @SaleNumber));
            """, db, transaction))
        {
            cmd.Parameters.AddWithValue("@CompanyId", companyId);
            cmd.Parameters.AddWithValue("@VendaId", (object?)vendaId ?? DBNull.Value);
            cmd.Parameters.AddWithValue("@SaleNumber", (object?)saleNumber ?? DBNull.Value);
            await using var r = await cmd.ExecuteReaderAsync();
            if (!await r.ReadAsync()) return null;
            id = r.GetString(0);
            number = r.GetString(1);
            status = r.GetString(2);
            total = r.GetDecimal(3);
            cliente = r.IsDBNull(4) ? null : r.GetString(4);
            cpf = r.IsDBNull(5) ? null : r.GetString(5);
            sessao = r.IsDBNull(6) ? null : r.GetString(6);
        }

        var itens = new List<VendaCancelamentoSnapshot.Item>();
        await using (var cmd = new SqlCommand(
            """
            SELECT ISNULL(ProductCode, ''), ISNULL(ProductName, ''), ISNULL(Quantity, 0), ISNULL(UnitPrice, 0), ISNULL(ItemTotal, 0)
            FROM VendaItens WHERE VendaId = @VendaId;
            """, db, transaction))
        {
            cmd.Parameters.AddWithValue("@VendaId", id);
            await using var r = await cmd.ExecuteReaderAsync();
            while (await r.ReadAsync())
                itens.Add(new(r.GetString(0), r.GetString(1), r.GetDecimal(2), r.GetDecimal(3), r.GetDecimal(4)));
        }

        var pagamentos = new List<VendaCancelamentoSnapshot.Pagamento>();
        await using (var cmd = new SqlCommand(
            """
            SELECT ISNULL(PaymentType, ''), ISNULL(Amount, 0), CashGiven, ChangeAmount
            FROM VendaPagamentos WHERE CompanyId = @CompanyId AND VendaId = @VendaId ORDER BY CreatedAt;
            """, db, transaction))
        {
            cmd.Parameters.AddWithValue("@CompanyId", companyId);
            cmd.Parameters.AddWithValue("@VendaId", id);
            await using var r = await cmd.ExecuteReaderAsync();
            while (await r.ReadAsync())
                pagamentos.Add(new(r.GetString(0), r.GetDecimal(1),
                    r.IsDBNull(2) ? null : r.GetDecimal(2), r.IsDBNull(3) ? null : r.GetDecimal(3)));
        }

        var notas = new List<VendaCancelamentoSnapshot.Nota>();
        await using (var cmd = new SqlCommand(
            """
            SELECT Id, Modelo, CAST(NumeroNf AS NVARCHAR(20)), CAST(Serie AS NVARCHAR(10)), Status, ChaveAcesso
            FROM DocumentosFiscais WHERE CompanyId = @CompanyId AND VendaId = @VendaId;
            """, db, transaction))
        {
            cmd.Parameters.AddWithValue("@CompanyId", companyId);
            cmd.Parameters.AddWithValue("@VendaId", id);
            await using var r = await cmd.ExecuteReaderAsync();
            while (await r.ReadAsync())
                notas.Add(new(r.GetString(0), Convert.ToInt32(r.GetValue(1)), r.IsDBNull(2) ? null : r.GetString(2),
                    r.IsDBNull(3) ? null : r.GetString(3), Convert.ToInt32(r.GetValue(4)),
                    r.IsDBNull(5) ? null : r.GetString(5)));
        }

        string? sessaoStatus = null;
        if (!string.IsNullOrWhiteSpace(sessao))
        {
            await using var cmd = new SqlCommand(
                "SELECT CASE WHEN ClosedAt IS NULL THEN 'aberta' ELSE 'fechada' END FROM CaixaSessoes WHERE CompanyId = @CompanyId AND Id = @Id;",
                db, transaction);
            cmd.Parameters.AddWithValue("@CompanyId", companyId);
            cmd.Parameters.AddWithValue("@Id", sessao);
            sessaoStatus = await cmd.ExecuteScalarAsync() as string;
        }

        return new VendaCancelamentoSnapshot(id, number, status, total, cliente, cpf, sessao, sessaoStatus, itens,
            pagamentos, notas);
    }

    /// <summary>Grava a linha da trilha na transação informada (cancelamento autorizado: tudo ou nada).</summary>
    public static async Task RegistrarAsync(SqlConnection db, SqlTransaction? transaction, VendaCancelamentoRegistro r)
    {
        var s = r.Snapshot;
        await using var cmd = new SqlCommand(
            """
            INSERT INTO VendaCancelamentos
                (Id, CompanyId, Resultado, Via, MotivoRecusa, VendaId, SaleNumber, OperadorId, OperadorNome,
                 SupervisorId, SupervisorNome, Justificativa, Terminal, Ip, CaixaSessaoId, CaixaSessaoStatus,
                 TotalVenda, ClienteNome, ClienteCpf, ItensEstornados, FiadoEstornado, Snapshot)
            VALUES
                (@Id, @CompanyId, @Resultado, @Via, @MotivoRecusa, @VendaId, @SaleNumber, @OperadorId, @OperadorNome,
                 @SupervisorId, @SupervisorNome, @Justificativa, @Terminal, @Ip, @CaixaSessaoId, @CaixaSessaoStatus,
                 @TotalVenda, @ClienteNome, @ClienteCpf, @ItensEstornados, @FiadoEstornado, @Snapshot);
            """, db, transaction);
        cmd.Parameters.AddWithValue("@Id", $"vc-{Guid.NewGuid():N}");
        cmd.Parameters.AddWithValue("@CompanyId", r.CompanyId);
        cmd.Parameters.AddWithValue("@Resultado", r.Resultado);
        cmd.Parameters.AddWithValue("@Via", r.Via);
        cmd.Parameters.AddWithValue("@MotivoRecusa", Db(Truncar(r.MotivoRecusa, 500)));
        cmd.Parameters.AddWithValue("@VendaId", Db(s?.VendaId));
        cmd.Parameters.AddWithValue("@SaleNumber", Db(s?.SaleNumber ?? Truncar(r.SaleNumber, 40)));
        cmd.Parameters.AddWithValue("@OperadorId", r.OperadorId);
        cmd.Parameters.AddWithValue("@OperadorNome", Truncar(r.OperadorNome, 180) ?? "");
        cmd.Parameters.AddWithValue("@SupervisorId", Db(Truncar(r.SupervisorId, 40)));
        cmd.Parameters.AddWithValue("@SupervisorNome", Db(Truncar(r.SupervisorNome, 180)));
        cmd.Parameters.AddWithValue("@Justificativa", Db(Truncar(r.Justificativa, 500)));
        cmd.Parameters.AddWithValue("@Terminal", Db(Truncar(r.Terminal, 80)));
        cmd.Parameters.AddWithValue("@Ip", Db(Truncar(r.Ip, 64)));
        cmd.Parameters.AddWithValue("@CaixaSessaoId", Db(s?.CaixaSessaoId));
        cmd.Parameters.AddWithValue("@CaixaSessaoStatus", Db(s?.CaixaSessaoStatus));
        cmd.Parameters.AddWithValue("@TotalVenda", s is null ? DBNull.Value : s.TotalVenda);
        cmd.Parameters.AddWithValue("@ClienteNome", Db(s?.ClienteNome));
        cmd.Parameters.AddWithValue("@ClienteCpf", Db(s?.ClienteCpf));
        cmd.Parameters.AddWithValue("@ItensEstornados", r.ItensEstornados is null ? DBNull.Value : r.ItensEstornados.Value);
        cmd.Parameters.AddWithValue("@FiadoEstornado", r.FiadoEstornado is null ? DBNull.Value : r.FiadoEstornado.Value);
        cmd.Parameters.AddWithValue("@Snapshot", Db(s?.ToJson()));
        await cmd.ExecuteNonQueryAsync();
    }

    /// <summary>
    /// Tentativa negada (senha errada, gerente inválido, venda já cancelada...): grava em conexão própria, com a
    /// fotografia da venda quando ela existe. Nunca lança: a recusa ao operador não pode virar erro 500.
    /// </summary>
    public async Task RegistrarNegadaAsync(VendaCancelamentoRegistro registro, string? vendaId = null,
        string? documentoFiscalId = null)
    {
        try
        {
            await using var db = await connection.OpenConnectionAsync();
            if (vendaId is null && !string.IsNullOrWhiteSpace(documentoFiscalId))
            {
                await using var doc = new SqlCommand(
                    "SELECT VendaId FROM DocumentosFiscais WHERE CompanyId = @CompanyId AND (Id = @Id OR ChaveAcesso = @Id);", db);
                doc.Parameters.AddWithValue("@CompanyId", registro.CompanyId);
                doc.Parameters.AddWithValue("@Id", documentoFiscalId);
                vendaId = await doc.ExecuteScalarAsync() as string;
            }
            var snapshot = registro.Snapshot ?? await CapturarAsync(db, null, registro.CompanyId, vendaId, registro.SaleNumber);
            var supervisorNome = registro.SupervisorNome;
            if (supervisorNome is null && !string.IsNullOrWhiteSpace(registro.SupervisorId))
            {
                // Senha errada: o gerente não foi validado, mas o nome de quem foi escolhido ajuda na auditoria.
                await using var nome = new SqlCommand(
                    "SELECT Name FROM Usuarios WHERE CompanyId = @CompanyId AND Id = @Id;", db);
                nome.Parameters.AddWithValue("@CompanyId", registro.CompanyId);
                nome.Parameters.AddWithValue("@Id", registro.SupervisorId);
                supervisorNome = await nome.ExecuteScalarAsync() as string;
            }
            registro = new VendaCancelamentoRegistro
            {
                CompanyId = registro.CompanyId,
                Resultado = registro.Resultado,
                Via = registro.Via,
                MotivoRecusa = registro.MotivoRecusa,
                SaleNumber = snapshot?.SaleNumber ?? registro.SaleNumber,
                OperadorId = registro.OperadorId,
                OperadorNome = registro.OperadorNome,
                SupervisorId = registro.SupervisorId,
                SupervisorNome = supervisorNome,
                Justificativa = registro.Justificativa,
                Terminal = registro.Terminal,
                Ip = registro.Ip,
            };
            await RegistrarAsync(db, null, new VendaCancelamentoRegistro
            {
                CompanyId = registro.CompanyId,
                Resultado = "negada",
                Via = registro.Via,
                MotivoRecusa = registro.MotivoRecusa,
                SaleNumber = registro.SaleNumber,
                OperadorId = registro.OperadorId,
                OperadorNome = registro.OperadorNome,
                SupervisorId = registro.SupervisorId,
                SupervisorNome = registro.SupervisorNome,
                Justificativa = registro.Justificativa,
                Terminal = registro.Terminal,
                Ip = registro.Ip,
                Snapshot = snapshot,
            });
            await using var audit = new SqlCommand(
                """
                INSERT INTO AuditLog (CompanyId, UserId, UserName, EventType, EntityType, EntityId, Description, Ip)
                VALUES (@CompanyId, @UserId, @UserName, @EventType, 'Vendas', @EntityId, @Description, @Ip);
                """, db);
            audit.Parameters.AddWithValue("@CompanyId", registro.CompanyId);
            audit.Parameters.AddWithValue("@UserId", Truncar(registro.OperadorId, 40) ?? "");
            audit.Parameters.AddWithValue("@UserName", Truncar(registro.OperadorNome, 180) ?? "");
            audit.Parameters.AddWithValue("@EventType", AuditEventTypes.CancelamentoVendaNegado);
            audit.Parameters.AddWithValue("@EntityId", Db(snapshot?.VendaId ?? Truncar(registro.SaleNumber, 80)));
            audit.Parameters.AddWithValue("@Description", Truncar(
                $"Cancelamento da venda #{registro.SaleNumber} negado: {registro.MotivoRecusa}" +
                (string.IsNullOrWhiteSpace(registro.SupervisorNome ?? registro.SupervisorId) ? "" : $" Gerente informado: {registro.SupervisorNome ?? registro.SupervisorId}.") +
                (string.IsNullOrWhiteSpace(registro.Terminal) ? "" : $" Terminal: {registro.Terminal}."), 500)!);
            audit.Parameters.AddWithValue("@Ip", Db(Truncar(registro.Ip, 64)));
            await audit.ExecuteNonQueryAsync();
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"[auditoria] tentativa negada de cancelamento não registrada: {ex.Message}");
        }
    }

    /// <summary>Trilha completa para o relatório (autorizadas e negadas), mais recentes primeiro.</summary>
    public async Task<List<VendaCancelamentoAuditoriaAD>> ListarAsync(
        string companyId, DateTimeOffset? de, DateTimeOffset? ate, string? saleNumber = null)
    {
        const string sql = """
            SELECT TOP 2000 Id, OcorridoEm, Resultado, Via, MotivoRecusa, SaleNumber, OperadorId, OperadorNome,
                   SupervisorId, SupervisorNome, Justificativa, Terminal, Ip, CaixaSessaoId, CaixaSessaoStatus,
                   TotalVenda, ClienteNome, ClienteCpf, ItensEstornados, FiadoEstornado, Snapshot
            FROM VendaCancelamentos
            WHERE CompanyId = @CompanyId
              AND (@De IS NULL OR OcorridoEm >= @De)
              AND (@Ate IS NULL OR OcorridoEm < @Ate)
              AND (@SaleNumber IS NULL OR SaleNumber = @SaleNumber)
            ORDER BY OcorridoEm DESC;
            """;
        await using var db = await connection.OpenConnectionAsync();
        await using var cmd = new SqlCommand(sql, db);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        cmd.Parameters.Add("@De", System.Data.SqlDbType.DateTimeOffset).Value = de.HasValue ? de.Value : DBNull.Value;
        cmd.Parameters.Add("@Ate", System.Data.SqlDbType.DateTimeOffset).Value = ate.HasValue ? ate.Value : DBNull.Value;
        cmd.Parameters.AddWithValue("@SaleNumber", Db(saleNumber));
        await using var r = await cmd.ExecuteReaderAsync();
        var list = new List<VendaCancelamentoAuditoriaAD>();
        while (await r.ReadAsync())
        {
            string? S(int i) => r.IsDBNull(i) ? null : r.GetString(i);
            var snapshot = S(20);
            list.Add(new VendaCancelamentoAuditoriaAD
            {
                Id = r.GetString(0),
                OcorridoEm = r.GetDateTimeOffset(1),
                Resultado = r.GetString(2),
                Via = r.GetString(3),
                MotivoRecusa = S(4),
                SaleNumber = S(5),
                OperadorId = r.GetString(6),
                OperadorNome = r.GetString(7),
                SupervisorId = S(8),
                SupervisorNome = S(9),
                Justificativa = S(10),
                Terminal = S(11),
                Ip = S(12),
                CaixaSessaoId = S(13),
                CaixaSessaoStatus = S(14),
                TotalVenda = r.IsDBNull(15) ? null : r.GetDecimal(15),
                ClienteNome = S(16),
                ClienteCpf = S(17),
                ItensEstornados = r.IsDBNull(18) ? null : r.GetInt32(18),
                FiadoEstornado = r.IsDBNull(19) ? null : r.GetDecimal(19),
                Snapshot = snapshot is null ? null : JsonDocument.Parse(snapshot).RootElement.Clone(),
            });
        }

        return list;
    }

    private static object Db(string? value) => string.IsNullOrWhiteSpace(value) ? DBNull.Value : value;

    private static string? Truncar(string? value, int max) =>
        value is null ? null : value.Length <= max ? value : value[..max];
}
