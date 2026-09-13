/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/FiadoAB.cs
 * Objetivo: acesso a dados e persistência para controle de fiado, conta corrente e histórico de movimentações.
 */
using HORUSPDV_API.Repositories.DataAccess;
using HORUSPDV_API.Services.Shared;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public class FiadoAB(Connection connection)
{
    public async Task<FiadoMovimentoAD> RegistrarDebitoAsync(
        string companyId,
        string clienteId,
        decimal valor,
        string? vendaId,
        string operadorNome)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var transaction = (SqlTransaction)await db.BeginTransactionAsync();

        try
        {
            var result = await RegistrarDebitoAsync(db, transaction, companyId, clienteId, valor, vendaId, operadorNome);
            await transaction.CommitAsync();
            return result;
        }
        catch
        {
            await transaction.RollbackAsync();
            throw;
        }
    }

    public async Task<FiadoMovimentoAD> RegistrarDebitoAsync(
        SqlConnection db,
        SqlTransaction transaction,
        string companyId,
        string clienteId,
        decimal valor,
        string? vendaId,
        string operadorNome)
    {
        if (valor <= 0)
        {
            throw new InvalidOperationException("Valor da compra a prazo deve ser maior que zero.");
        }

        const string selectSql = """
            SELECT CustomerName, Document, LimiteCredito, SaldoDevedor
            FROM Clientes WITH (UPDLOCK, ROWLOCK)
            WHERE Id = @ClienteId AND CompanyId = @CompanyId;
            """;

        await using var selectCmd = new SqlCommand(selectSql, db, transaction);
        selectCmd.Parameters.AddWithValue("@ClienteId", clienteId);
        selectCmd.Parameters.AddWithValue("@CompanyId", companyId);

        string customerName;
        string customerDoc;
        decimal limiteCredito;
        decimal saldoAnterior;

        await using (var reader = await selectCmd.ExecuteReaderAsync())
        {
            if (!await reader.ReadAsync())
            {
                throw new InvalidOperationException($"Cliente {clienteId} não encontrado.");
            }

            customerName = reader.GetString(reader.GetOrdinal("CustomerName"));
            customerDoc = reader.GetString(reader.GetOrdinal("Document"));
            limiteCredito = reader.GetDecimal(reader.GetOrdinal("LimiteCredito"));
            saldoAnterior = reader.GetDecimal(reader.GetOrdinal("SaldoDevedor"));
        }

        if (limiteCredito > 0 && (saldoAnterior + valor) > limiteCredito)
        {
            var disponivel = Math.Max(0, limiteCredito - saldoAnterior);
            throw new InvalidOperationException(
                $"Limite de crédito excedido para {customerName}. Limite: R$ {limiteCredito:N2}, Saldo devedor: R$ {saldoAnterior:N2}, Disponível: R$ {disponivel:N2}, Compra: R$ {valor:N2}.");
        }

        var saldoAtual = saldoAnterior + valor;
        var now = HorusDateTime.Now;
        var movId = $"fm-{Guid.NewGuid():N}";

        const string updateSql = """
            UPDATE Clientes
               SET SaldoDevedor = @SaldoAtual
             WHERE Id = @ClienteId AND CompanyId = @CompanyId;
            """;

        await using (var updateCmd = new SqlCommand(updateSql, db, transaction))
        {
            updateCmd.Parameters.AddWithValue("@SaldoAtual", saldoAtual);
            updateCmd.Parameters.AddWithValue("@ClienteId", clienteId);
            updateCmd.Parameters.AddWithValue("@CompanyId", companyId);
            await updateCmd.ExecuteNonQueryAsync();
        }

        const string insertSql = """
            INSERT INTO FiadoMovimentos
                (Id, CompanyId, ClienteId, Tipo, Valor, SaldoAnterior, SaldoAtual, VendaId, FormaPagamento, Observacao, OperadorNome, CriadoEm)
            VALUES
                (@Id, @CompanyId, @ClienteId, 1, @Valor, @SaldoAnterior, @SaldoAtual, @VendaId, NULL, @Observacao, @OperadorNome, @CriadoEm);
            """;

        await using (var insertCmd = new SqlCommand(insertSql, db, transaction))
        {
            insertCmd.Parameters.AddWithValue("@Id", movId);
            insertCmd.Parameters.AddWithValue("@CompanyId", companyId);
            insertCmd.Parameters.AddWithValue("@ClienteId", clienteId);
            insertCmd.Parameters.AddWithValue("@Valor", valor);
            insertCmd.Parameters.AddWithValue("@SaldoAnterior", saldoAnterior);
            insertCmd.Parameters.AddWithValue("@SaldoAtual", saldoAtual);
            insertCmd.Parameters.AddWithValue("@VendaId", (object?)vendaId ?? DBNull.Value);
            insertCmd.Parameters.AddWithValue("@Observacao", "Compra a prazo (fiado)");
            insertCmd.Parameters.AddWithValue("@OperadorNome", string.IsNullOrWhiteSpace(operadorNome) ? "Operador" : operadorNome);
            insertCmd.Parameters.AddWithValue("@CriadoEm", now);
            await insertCmd.ExecuteNonQueryAsync();
        }

        return new FiadoMovimentoAD
        {
            Id = movId,
            CompanyId = companyId,
            ClienteId = clienteId,
            ClienteNome = customerName,
            ClienteDocument = customerDoc,
            Tipo = 1,
            Valor = valor,
            SaldoAnterior = saldoAnterior,
            SaldoAtual = saldoAtual,
            VendaId = vendaId,
            FormaPagamento = null,
            Observacao = "Compra a prazo (fiado)",
            OperadorNome = operadorNome,
            CriadoEm = now
        };
    }

    public async Task<FiadoMovimentoAD> RegistrarCreditoAsync(
        string companyId,
        string clienteId,
        decimal valor,
        string formaPagamento,
        string? observacao,
        string operadorNome)
    {
        if (valor <= 0)
        {
            throw new InvalidOperationException("Valor do pagamento recebido deve ser maior que zero.");
        }

        await using var db = await connection.OpenConnectionAsync();
        await using var transaction = (SqlTransaction)await db.BeginTransactionAsync();

        try
        {
            const string selectSql = """
                SELECT CustomerName, Document, SaldoDevedor
                FROM Clientes WITH (UPDLOCK, ROWLOCK)
                WHERE Id = @ClienteId AND CompanyId = @CompanyId;
                """;

            await using var selectCmd = new SqlCommand(selectSql, db, transaction);
            selectCmd.Parameters.AddWithValue("@ClienteId", clienteId);
            selectCmd.Parameters.AddWithValue("@CompanyId", companyId);

            string customerName;
            string customerDoc;
            decimal saldoAnterior;

            await using (var reader = await selectCmd.ExecuteReaderAsync())
            {
                if (!await reader.ReadAsync())
                {
                    throw new InvalidOperationException($"Cliente {clienteId} não encontrado.");
                }

                customerName = reader.GetString(reader.GetOrdinal("CustomerName"));
                customerDoc = reader.GetString(reader.GetOrdinal("Document"));
                saldoAnterior = reader.GetDecimal(reader.GetOrdinal("SaldoDevedor"));
            }

            if (valor > saldoAnterior + 0.009m)
            {
                throw new InvalidOperationException(
                    $"Valor do recebimento (R$ {valor:N2}) não pode ser maior que o saldo devedor (R$ {saldoAnterior:N2}).");
            }

            var saldoAtual = Math.Max(0, saldoAnterior - valor);
            var now = HorusDateTime.Now;
            var movId = $"fm-{Guid.NewGuid():N}";

            const string updateSql = """
                UPDATE Clientes
                   SET SaldoDevedor = @SaldoAtual
                 WHERE Id = @ClienteId AND CompanyId = @CompanyId;
                """;

            await using (var updateCmd = new SqlCommand(updateSql, db, transaction))
            {
                updateCmd.Parameters.AddWithValue("@SaldoAtual", saldoAtual);
                updateCmd.Parameters.AddWithValue("@ClienteId", clienteId);
                updateCmd.Parameters.AddWithValue("@CompanyId", companyId);
                await updateCmd.ExecuteNonQueryAsync();
            }

            const string insertSql = """
                INSERT INTO FiadoMovimentos
                    (Id, CompanyId, ClienteId, Tipo, Valor, SaldoAnterior, SaldoAtual, VendaId, FormaPagamento, Observacao, OperadorNome, CriadoEm)
                VALUES
                    (@Id, @CompanyId, @ClienteId, 2, @Valor, @SaldoAnterior, @SaldoAtual, NULL, @FormaPagamento, @Observacao, @OperadorNome, @CriadoEm);
                """;

            await using (var insertCmd = new SqlCommand(insertSql, db, transaction))
            {
                insertCmd.Parameters.AddWithValue("@Id", movId);
                insertCmd.Parameters.AddWithValue("@CompanyId", companyId);
                insertCmd.Parameters.AddWithValue("@ClienteId", clienteId);
                insertCmd.Parameters.AddWithValue("@Valor", valor);
                insertCmd.Parameters.AddWithValue("@SaldoAnterior", saldoAnterior);
                insertCmd.Parameters.AddWithValue("@SaldoAtual", saldoAtual);
                insertCmd.Parameters.AddWithValue("@FormaPagamento", formaPagamento);
                insertCmd.Parameters.AddWithValue("@Observacao", string.IsNullOrWhiteSpace(observacao) ? "Recebimento de fiado" : observacao.Trim());
                insertCmd.Parameters.AddWithValue("@OperadorNome", string.IsNullOrWhiteSpace(operadorNome) ? "Operador" : operadorNome);
                insertCmd.Parameters.AddWithValue("@CriadoEm", now);
                await insertCmd.ExecuteNonQueryAsync();
            }

            await transaction.CommitAsync();

            return new FiadoMovimentoAD
            {
                Id = movId,
                CompanyId = companyId,
                ClienteId = clienteId,
                ClienteNome = customerName,
                ClienteDocument = customerDoc,
                Tipo = 2,
                Valor = valor,
                SaldoAnterior = saldoAnterior,
                SaldoAtual = saldoAtual,
                VendaId = null,
                FormaPagamento = formaPagamento,
                Observacao = observacao,
                OperadorNome = operadorNome,
                CriadoEm = now
            };
        }
        catch
        {
            await transaction.RollbackAsync();
            throw;
        }
    }

    public async Task<List<FiadoDevedorAD>> ListarDevedoresAsync(string companyId, string? busca = null)
    {
        const string sql = """
            SELECT c.Id AS ClienteId, c.CustomerName, c.Document, c.Telephone, c.Cellphone,
                   c.LimiteCredito, c.SaldoDevedor,
                   (SELECT MAX(m.CriadoEm) FROM FiadoMovimentos m WHERE m.CompanyId = c.CompanyId AND m.ClienteId = c.Id AND m.Tipo = 1) AS UltimaCompra
            FROM Clientes c
            WHERE c.CompanyId = @CompanyId
              AND c.SaldoDevedor > 0
              AND (@Busca IS NULL OR c.CustomerName LIKE '%' + @Busca + '%' OR c.Document LIKE '%' + @Busca + '%')
            ORDER BY c.SaldoDevedor DESC;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@Busca", string.IsNullOrWhiteSpace(busca) ? DBNull.Value : busca.Trim());

        await using var reader = await command.ExecuteReaderAsync();
        var rows = new List<FiadoDevedorAD>();
        var now = HorusDateTime.Now;

        while (await reader.ReadAsync())
        {
            var ultimaCompraOrdinal = reader.GetOrdinal("UltimaCompra");
            DateTimeOffset? ultimaCompra = reader.IsDBNull(ultimaCompraOrdinal)
                ? null
                : reader.GetDateTimeOffset(ultimaCompraOrdinal);

            int? diasSemPagamento = ultimaCompra.HasValue
                ? Math.Max(0, (int)(now - ultimaCompra.Value).TotalDays)
                : null;

            rows.Add(new FiadoDevedorAD
            {
                ClienteId = reader.GetString(reader.GetOrdinal("ClienteId")),
                ClienteNome = reader.GetString(reader.GetOrdinal("CustomerName")),
                Document = reader.GetString(reader.GetOrdinal("Document")),
                Telephone = reader.GetString(reader.GetOrdinal("Telephone")),
                Cellphone = reader.GetString(reader.GetOrdinal("Cellphone")),
                LimiteCredito = reader.GetDecimal(reader.GetOrdinal("LimiteCredito")),
                SaldoDevedor = reader.GetDecimal(reader.GetOrdinal("SaldoDevedor")),
                UltimaCompra = ultimaCompra,
                DiasSemPagamento = diasSemPagamento
            });
        }

        return rows;
    }

    public async Task<List<FiadoMovimentoAD>> ObterExtratoAsync(
        string companyId,
        string clienteId,
        DateTimeOffset? dataInicio = null,
        DateTimeOffset? dataFim = null)
    {
        const string sql = """
            SELECT m.Id, m.CompanyId, m.ClienteId, c.CustomerName AS ClienteNome, c.Document AS ClienteDocument,
                   m.Tipo, m.Valor, m.SaldoAnterior, m.SaldoAtual, m.VendaId, m.FormaPagamento, m.Observacao,
                   m.OperadorNome, m.CriadoEm
            FROM FiadoMovimentos m
            INNER JOIN Clientes c ON c.Id = m.ClienteId
            WHERE m.CompanyId = @CompanyId
              AND m.ClienteId = @ClienteId
              AND (@DataInicio IS NULL OR m.CriadoEm >= @DataInicio)
              AND (@DataFim IS NULL OR m.CriadoEm <= @DataFim)
            ORDER BY m.CriadoEm DESC;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@ClienteId", clienteId);
        command.Parameters.AddWithValue("@DataInicio", dataInicio.HasValue ? dataInicio.Value : DBNull.Value);
        command.Parameters.AddWithValue("@DataFim", dataFim.HasValue ? dataFim.Value : DBNull.Value);

        await using var reader = await command.ExecuteReaderAsync();
        var rows = new List<FiadoMovimentoAD>();

        while (await reader.ReadAsync())
        {
            var vendaIdOrdinal = reader.GetOrdinal("VendaId");
            var pagOrdinal = reader.GetOrdinal("FormaPagamento");
            var obsOrdinal = reader.GetOrdinal("Observacao");

            rows.Add(new FiadoMovimentoAD
            {
                Id = reader.GetString(reader.GetOrdinal("Id")),
                CompanyId = reader.GetString(reader.GetOrdinal("CompanyId")),
                ClienteId = reader.GetString(reader.GetOrdinal("ClienteId")),
                ClienteNome = reader.GetString(reader.GetOrdinal("ClienteNome")),
                ClienteDocument = reader.GetString(reader.GetOrdinal("ClienteDocument")),
                Tipo = (byte)reader.GetByte(reader.GetOrdinal("Tipo")),
                Valor = reader.GetDecimal(reader.GetOrdinal("Valor")),
                SaldoAnterior = reader.GetDecimal(reader.GetOrdinal("SaldoAnterior")),
                SaldoAtual = reader.GetDecimal(reader.GetOrdinal("SaldoAtual")),
                VendaId = reader.IsDBNull(vendaIdOrdinal) ? null : reader.GetString(vendaIdOrdinal),
                FormaPagamento = reader.IsDBNull(pagOrdinal) ? null : reader.GetString(pagOrdinal),
                Observacao = reader.IsDBNull(obsOrdinal) ? null : reader.GetString(obsOrdinal),
                OperadorNome = reader.GetString(reader.GetOrdinal("OperadorNome")),
                CriadoEm = reader.GetDateTimeOffset(reader.GetOrdinal("CriadoEm"))
            });
        }

        return rows;
    }

    public async Task<FiadoResumoAD> ObterResumoAsync(string companyId)
    {
        const string sql = """
            SELECT ISNULL(SUM(c.SaldoDevedor), 0) AS TotalAReceber,
                   COUNT(CASE WHEN c.SaldoDevedor > 0 THEN 1 END) AS QuantidadeDevedores,
                   ISNULL(MAX(c.SaldoDevedor), 0) AS MaiorDebito
            FROM Clientes c
            WHERE c.CompanyId = @CompanyId;

            SELECT c.SaldoDevedor,
                   (SELECT MAX(m.CriadoEm) FROM FiadoMovimentos m WHERE m.CompanyId = c.CompanyId AND m.ClienteId = c.Id AND m.Tipo = 1) AS UltimaCompra
            FROM Clientes c
            WHERE c.CompanyId = @CompanyId AND c.SaldoDevedor > 0;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);

        await using var reader = await command.ExecuteReaderAsync();

        var resumo = new FiadoResumoAD();
        if (await reader.ReadAsync())
        {
            resumo.TotalAReceber = reader.GetDecimal(reader.GetOrdinal("TotalAReceber"));
            resumo.QuantidadeDevedores = reader.GetInt32(reader.GetOrdinal("QuantidadeDevedores"));
            resumo.MaiorDebito = reader.GetDecimal(reader.GetOrdinal("MaiorDebito"));
        }

        if (await reader.NextResultAsync())
        {
            var now = HorusDateTime.Now;
            decimal in30 = 0m;
            decimal in60 = 0m;

            while (await reader.ReadAsync())
            {
                var saldo = reader.GetDecimal(reader.GetOrdinal("SaldoDevedor"));
                var ucOrdinal = reader.GetOrdinal("UltimaCompra");
                if (!reader.IsDBNull(ucOrdinal))
                {
                    var dias = (int)(now - reader.GetDateTimeOffset(ucOrdinal)).TotalDays;
                    if (dias >= 60)
                    {
                        in60 += saldo;
                        in30 += saldo;
                    }
                    else if (dias >= 30)
                    {
                        in30 += saldo;
                    }
                }
            }

            resumo.Inadimplencia30Dias = in30;
            resumo.Inadimplencia60Dias = in60;
        }

        return resumo;
    }
}
