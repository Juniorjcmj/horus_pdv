using System.Text.Json;
using HORUSPDV_API.Services.Fiscal;
using HORUSPDV_API.Services.Security;
using HORUSPDV_API.Repositories.DataAccess;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public sealed class FiscalAiAB(Connection connection, HorusSecretProtector protector)
{
    public async Task<Guid> EnqueueAsync(string companyId, string productId, string userId, Guid id, CancellationToken ct)
    {
        if (id == Guid.Empty) throw new InvalidOperationException("Identificação da solicitação inválida.");
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var tx = (SqlTransaction)await db.BeginTransactionAsync(System.Data.IsolationLevel.Serializable, ct);
        await using var cmd = new SqlCommand("SELECT COUNT(*) FROM FiscalIaSolicitacoes WITH(UPDLOCK,HOLDLOCK) WHERE ConcluidaEm IS NULL", db, tx);
        var activeCount = (int)(await cmd.ExecuteScalarAsync(ct))!;
        cmd.Parameters.AddWithValue("@Id", id); cmd.Parameters.AddWithValue("@CompanyId", companyId);
        cmd.Parameters.AddWithValue("@ProductId", productId); cmd.Parameters.AddWithValue("@UserId", userId);
        cmd.CommandText = "SELECT CompanyId,ProdutoId FROM FiscalIaSolicitacoes WHERE Id=@Id";
        await using (var reader = await cmd.ExecuteReaderAsync(ct))
        {
            if (await reader.ReadAsync(ct))
            {
                if (reader.GetString(0) != companyId || reader.GetString(1) != productId) throw new FiscalAiConflict("Solicitação inválida para este produto.");
                await reader.DisposeAsync();
                await tx.CommitAsync(ct);
                return id;
            }
        }
        cmd.CommandText = "SELECT Id FROM FiscalIaSolicitacoes WHERE CompanyId=@CompanyId AND ProdutoId=@ProductId AND ConcluidaEm IS NULL";
        if (await cmd.ExecuteScalarAsync(ct) is Guid previous)
        {
            await tx.CommitAsync(ct);
            return previous; // Repetir o clique ou perder a resposta não cria outra cobrança.
        }
        if (activeCount >= 100) throw new InvalidOperationException("A fila de análises está cheia. Aguarde e tente novamente.");
        cmd.CommandText = "SELECT COUNT(*) FROM FiscalIaSolicitacoes WHERE CompanyId=@CompanyId AND ConcluidaEm IS NULL";
        if ((int)(await cmd.ExecuteScalarAsync(ct))! >= 10) throw new InvalidOperationException("Sua empresa já tem análises aguardando. Aguarde a conclusão.");
        cmd.CommandText = "INSERT FiscalIaSolicitacoes(Id,CompanyId,ProdutoId,UsuarioId) VALUES(@Id,@CompanyId,@ProductId,@UserId)";
        await cmd.ExecuteNonQueryAsync(ct); await tx.CommitAsync(ct);
        return id;
    }

    public async Task<FiscalAiJobStatus?> GetJobAsync(string companyId, Guid id, CancellationToken ct)
    {
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var cmd = new SqlCommand("""
            SELECT s.Id,s.ProdutoId,s.Status,s.Mensagem,a.RelatorioJson
            FROM FiscalIaSolicitacoes s LEFT JOIN FiscalIaAnalises a ON a.Id=s.AnaliseId AND a.CompanyId=s.CompanyId
            WHERE s.Id=@Id AND s.CompanyId=@CompanyId
            """, db);
        cmd.Parameters.AddWithValue("@Id", id); cmd.Parameters.AddWithValue("@CompanyId", companyId);
        await using var reader = await cmd.ExecuteReaderAsync(ct);
        if (!await reader.ReadAsync(ct)) return null;
        return new(reader.GetGuid(0), reader.GetString(1), reader.GetString(2), reader.IsDBNull(3) ? null : reader.GetString(3),
            reader.IsDBNull(4) ? null : JsonSerializer.Deserialize<FiscalAiReport>(reader.GetString(4), FiscalAiRules.Json));
    }

    public async Task<FiscalAiJob?> ClaimJobAsync(CancellationToken ct)
    {
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var tx = (SqlTransaction)await db.BeginTransactionAsync(System.Data.IsolationLevel.ReadCommitted, ct);
        await using var cmd = new SqlCommand("""
            UPDATE FiscalIaSolicitacoes SET Status='falha',ConcluidaEm=SYSUTCDATETIME(),
                Mensagem=N'Análise interrompida no servidor. Ela não foi repetida automaticamente. Nenhum cadastro foi alterado.'
            WHERE Status='analisando' AND IniciadaEm<DATEADD(minute,-10,SYSUTCDATETIME());
            ;WITH nextJob AS (
                SELECT TOP(1) * FROM FiscalIaSolicitacoes WITH(UPDLOCK,READPAST,READCOMMITTEDLOCK)
                WHERE Status='aguardando' ORDER BY CriadaEm
            ) UPDATE nextJob SET Status='analisando',IniciadaEm=SYSUTCDATETIME()
                OUTPUT inserted.Id,inserted.CompanyId,inserted.ProdutoId;
            """, db, tx);
        await using var reader = await cmd.ExecuteReaderAsync(ct);
        FiscalAiJob? job = await reader.ReadAsync(ct) ? new(reader.GetGuid(0), reader.GetString(1), reader.GetString(2)) : null;
        await reader.DisposeAsync();
        await tx.CommitAsync(ct);
        return job;
    }

    public async Task CompleteJobAsync(FiscalAiJob job, FiscalAiReport report, CancellationToken ct)
    {
        if (report.ProdutoId != job.ProductId) throw new InvalidOperationException("O resultado pertence a outro produto.");
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var tx = (SqlTransaction)await db.BeginTransactionAsync(ct);
        await using var cmd = new SqlCommand("""
            INSERT FiscalIaAnalises(Id,CompanyId,ProdutoId,RelatorioJson) VALUES(@AnalysisId,@CompanyId,@ProductId,@Json);
            UPDATE FiscalIaSolicitacoes SET Status='concluida',ConcluidaEm=SYSUTCDATETIME(),AnaliseId=@AnalysisId
            WHERE Id=@Id AND CompanyId=@CompanyId AND ProdutoId=@ProductId AND Status='analisando';
            IF @@ROWCOUNT<>1 THROW 51000,'Solicitação não está em execução.',1;
            """, db, tx);
        cmd.Parameters.AddWithValue("@Id", job.Id); cmd.Parameters.AddWithValue("@CompanyId", job.CompanyId);
        cmd.Parameters.AddWithValue("@ProductId", job.ProductId); cmd.Parameters.AddWithValue("@AnalysisId", report.Id);
        cmd.Parameters.AddWithValue("@Json", JsonSerializer.Serialize(report, FiscalAiRules.Json));
        await cmd.ExecuteNonQueryAsync(ct); await tx.CommitAsync(ct);
    }

    public async Task FailJobAsync(FiscalAiJob job, string message, CancellationToken ct)
    {
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var cmd = new SqlCommand("""
            UPDATE FiscalIaSolicitacoes SET Status='falha',ConcluidaEm=SYSUTCDATETIME(),Mensagem=@Message
            WHERE Id=@Id AND CompanyId=@CompanyId AND Status='analisando'
            """, db);
        cmd.Parameters.AddWithValue("@Id", job.Id); cmd.Parameters.AddWithValue("@CompanyId", job.CompanyId);
        cmd.Parameters.AddWithValue("@Message", message[..Math.Min(message.Length, 1000)]);
        await cmd.ExecuteNonQueryAsync(ct);
    }

    public async Task<(string Key, bool Jev)> GetConfigAsync(string companyId, CancellationToken ct)
    {
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var cmd = new SqlCommand("SELECT ChaveProtegida, UsarJev FROM FiscalIaConfig WHERE CompanyId=@CompanyId", db);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        await using var reader = await cmd.ExecuteReaderAsync(ct);
        return await reader.ReadAsync(ct) ? (protector.Unprotect(reader.GetString(0)), reader.GetBoolean(1)) : ("", true);
    }

    public async Task SaveConfigAsync(string companyId, string? key, bool jev, bool remove, CancellationToken ct)
    {
        key = key?.Trim();
        if (!string.IsNullOrEmpty(key) && (!key.StartsWith("sk-or-", StringComparison.Ordinal) || key.Length is < 20 or > 256 || key.Any(char.IsWhiteSpace)))
            throw new InvalidOperationException("Informe uma chave válida do OpenRouter.");
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var tx = (SqlTransaction)await db.BeginTransactionAsync(ct);
        await using var cmd = new SqlCommand("SELECT ChaveProtegida FROM FiscalIaConfig WITH(UPDLOCK,HOLDLOCK) WHERE CompanyId=@CompanyId", db, tx);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        var previous = await cmd.ExecuteScalarAsync(ct) as string;
        var secret = remove ? "" : !string.IsNullOrEmpty(key) ? protector.Protect(key) : previous ?? "";
        cmd.CommandText = """
            IF EXISTS(SELECT 1 FROM FiscalIaConfig WHERE CompanyId=@CompanyId)
              UPDATE FiscalIaConfig SET ChaveProtegida=@Key,UsarJev=@Jev,AtualizadoEm=SYSUTCDATETIME() WHERE CompanyId=@CompanyId;
            ELSE INSERT FiscalIaConfig(CompanyId,ChaveProtegida,UsarJev) VALUES(@CompanyId,@Key,@Jev);
            """;
        cmd.Parameters.AddWithValue("@Key", secret);
        cmd.Parameters.AddWithValue("@Jev", jev);
        await cmd.ExecuteNonQueryAsync(ct);
        await tx.CommitAsync(ct);
    }

    public async Task SaveReportAsync(string companyId, FiscalAiReport report, CancellationToken ct)
    {
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var cmd = new SqlCommand("INSERT FiscalIaAnalises(Id,CompanyId,ProdutoId,RelatorioJson) VALUES(@Id,@CompanyId,@ProdutoId,@Json)", db);
        cmd.Parameters.AddWithValue("@Id", report.Id); cmd.Parameters.AddWithValue("@CompanyId", companyId);
        cmd.Parameters.AddWithValue("@ProdutoId", report.ProdutoId); cmd.Parameters.AddWithValue("@Json", JsonSerializer.Serialize(report, FiscalAiRules.Json));
        await cmd.ExecuteNonQueryAsync(ct);
    }

    public async Task ApplyAsync(string companyId, string productId, Guid analysisId, string[] fields, string userId,
        string userName, FiscalTableData tables, IReadOnlyList<FiscalCest> cests, CancellationToken ct)
    {
        if (fields.Length is < 1 or > 11 || fields.Distinct().Count() != fields.Length || fields.Any(f => !FiscalAiRules.Columns.ContainsKey(f)))
            throw new InvalidOperationException("Selecione campos fiscais válidos, sem repetição.");
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var tx = (SqlTransaction)await db.BeginTransactionAsync(ct);
        await using var cmd = new SqlCommand("""
            SELECT RelatorioJson FROM FiscalIaAnalises WITH(UPDLOCK,HOLDLOCK)
            WHERE Id=@AnalysisId AND CompanyId=@CompanyId AND ProdutoId=@ProductId AND AplicadaEm IS NULL
              AND CriadaEm>DATEADD(hour,-24,SYSUTCDATETIME());
            """, db, tx);
        cmd.Parameters.AddWithValue("@AnalysisId", analysisId); cmd.Parameters.AddWithValue("@CompanyId", companyId);
        cmd.Parameters.AddWithValue("@ProductId", productId);
        var json = await cmd.ExecuteScalarAsync(ct) as string ?? throw new FiscalAiConflict("Análise inexistente, já aplicada ou expirada. Analise o produto novamente.");
        var report = JsonSerializer.Deserialize<FiscalAiReport>(json, FiscalAiRules.Json)!;
        cmd.CommandText = "SELECT p.* FROM Produtos p WITH(UPDLOCK,HOLDLOCK) WHERE Id=@ProductId AND CompanyId=@CompanyId FOR JSON PATH,WITHOUT_ARRAY_WRAPPER";
        var productJson = await cmd.ExecuteScalarAsync(ct) as string;
        if (string.IsNullOrEmpty(productJson)) throw new FiscalAiConflict("Produto não encontrado nesta empresa.");
        var product = JsonSerializer.Deserialize<ProdutoAD>(productJson, FiscalAiRules.Json)!;
        var snapshot = FiscalAiRules.Snapshot(product);
        if (report.CadastroOriginal.Any(pair => !snapshot.TryGetValue(pair.Key, out var current) || current != pair.Value))
            throw new FiscalAiConflict("O cadastro mudou depois da análise. Analise novamente antes de corrigir.");
        cmd.CommandText = "SELECT Crt,Uf FROM Empresas WITH(HOLDLOCK) WHERE Id=@CompanyId";
        byte crt; string uf;
        await using (var reader = await cmd.ExecuteReaderAsync(ct))
        {
            if (!await reader.ReadAsync(ct)) throw new FiscalAiConflict("Empresa não encontrada.");
            crt = Convert.ToByte(reader[0]); uf = reader.GetString(1);
        }
        var now = HORUSPDV_API.Services.Shared.HorusDateTime.Now;
        if (crt != report.Crt || uf != report.Uf || report.AnalisadoEm.Year != now.Year)
            throw new FiscalAiConflict("Regime, UF ou período fiscal mudou. Analise novamente.");
        var company = new EmpresaAD { Crt = crt, Uf = uf, AmbienteFiscal = 1 };
        var before = ProductFiscalReview.Check(product, company, tables, now, cests).Apontamentos.Where(a => a.Nivel == "erro").Select(a => a.Campo + a.Mensagem).ToHashSet();
        var selected = fields.Select(f => report.Sugestoes.SingleOrDefault(s => s.Campo == f && s.PodeAplicar)
            ?? throw new InvalidOperationException("A sugestão selecionada não pode ser aplicada.")).ToArray();
        foreach (var suggestion in selected) FiscalAiRules.Set(product, suggestion.Campo, suggestion.Sugerido);
        foreach (var suggestion in selected)
            if (FiscalAiRules.Validate(suggestion.Campo, suggestion.Sugerido, product, company, tables, cests, DateOnly.FromDateTime(now.DateTime)) is { } problem)
                throw new InvalidOperationException(problem);
        if (ProductFiscalReview.Check(product, company, tables, now, cests).Apontamentos.Any(a => a.Nivel == "erro" && !before.Contains(a.Campo + a.Mensagem)))
            throw new InvalidOperationException("As alterações criam incompatibilidade fiscal. Confira os campos relacionados e analise novamente.");
        cmd.CommandText = "UPDATE Produtos SET " + string.Join(",", selected.Select((s, i) => FiscalAiRules.Columns[s.Campo] + "=@Value" + i)) + " WHERE Id=@ProductId AND CompanyId=@CompanyId";
        for (var i = 0; i < selected.Length; i++)
        {
            var property = typeof(ProdutoAD).GetProperty(FiscalAiRules.Columns[selected[i].Campo])!;
            cmd.Parameters.AddWithValue("@Value" + i, property.GetValue(product) ?? DBNull.Value);
        }
        await cmd.ExecuteNonQueryAsync(ct);
        var applied = JsonSerializer.Serialize(selected, FiscalAiRules.Json);
        cmd.Parameters.AddWithValue("@User", userId); cmd.Parameters.AddWithValue("@Name", userName); cmd.Parameters.AddWithValue("@Applied", applied);
        cmd.CommandText = """
            UPDATE FiscalIaAnalises SET AplicadaEm=SYSUTCDATETIME(),AplicadaPor=@User,CamposAplicados=@Applied WHERE Id=@AnalysisId AND CompanyId=@CompanyId;
            INSERT AuditLog(CompanyId,UserId,UserName,EventType,EntityType,EntityId,Description)
            VALUES(@CompanyId,@User,@Name,'ProdutoFiscalIa','Produto',@ProductId,@Applied);
            """;
        await cmd.ExecuteNonQueryAsync(ct);
        await tx.CommitAsync(ct);
    }
}

public sealed class FiscalAiConflict(string message) : InvalidOperationException(message);
