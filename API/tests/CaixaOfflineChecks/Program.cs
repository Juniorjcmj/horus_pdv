// Verificações de integração do caixa offline (SQL Server em 127.0.0.1,14339 — mesmo container das demais checks).
// Cobre: fechamento antigo fecha só a sessão indicada, sessão inexistente, replay idempotente, abertura no passado
// com sessão aberta existente e cliente antigo sem os campos novos.
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Repositories;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Caixa;
using HORUSPDV_API.Services.Security;
using HORUSPDV_API.Services.Shared;
using Microsoft.Data.SqlClient;

const string company = "caixa-offline-test";
const string database = "HorusCaixaOfflineChecks";
const string master = "Server=127.0.0.1,14339;Database=master;User ID=sa;Password=HorusIntegrityTestOnly_2026!;TrustServerCertificate=True;Connect Timeout=10";
var sql = master.Replace("Database=master", $"Database={database}");
await using (var connection = new SqlConnection(master))
{
    await connection.OpenAsync();
    await using var command = new SqlCommand($"IF DB_ID('{database}') IS NOT NULL BEGIN ALTER DATABASE {database} SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE {database}; END; CREATE DATABASE {database};", connection);
    await command.ExecuteNonQueryAsync();
}
async Task Exec(string text)
{
    await using var c = new SqlConnection(sql); await c.OpenAsync();
    await using var cmd = new SqlCommand(text, c); await cmd.ExecuteNonQueryAsync();
}
async Task<object?> Scalar(string text)
{
    await using var c = new SqlConnection(sql); await c.OpenAsync();
    await using var cmd = new SqlCommand(text, c); return await cmd.ExecuteScalarAsync();
}
await Exec(await File.ReadAllTextAsync(Path.Combine(AppContext.BaseDirectory, "schema.sql")));
var configuration = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?> { ["ConnectionStrings:HorusPdv"] = sql }).Build();
var factory = new Connection(configuration);
var caixaAB = new CaixaAB(factory);
var service = new HorusCaixaService(caixaAB, new AuditLogAB(factory));
var user = new AuthenticatedUser { Id = "u1", CompanyId = company, Name = "Operadora", Role = "atendente" };
var passed = 0;
void Check(bool ok, string name) { if (!ok) throw new Exception(name); passed++; Console.WriteLine($"PASS {name}"); }
async Task Rejects(Func<Task> action, string contains, string name)
{
    try { await action(); }
    catch (InvalidOperationException ex) when (ex.Message.Contains(contains, StringComparison.Ordinal)) { Check(true, name); return; }
    throw new Exception($"Expected rejection: {name}");
}
async Task<bool> IsOpen(string id) => Convert.ToInt32(await Scalar($"SELECT CASE WHEN ClosedAt IS NULL THEN 1 ELSE 0 END FROM CaixaSessoes WHERE Id='{id}'")) == 1;
async Task<int> CountSessions() => Convert.ToInt32(await Scalar("SELECT COUNT(*) FROM CaixaSessoes"));

var now = HorusDateTime.Now;
var oldOpen = now.AddDays(-1).AddHours(-8);
var oldClose = now.AddDays(-1);

// --- Abertura no passado, com outra sessão já aberta hoje ---
await service.AbrirAsync(new AbrirCaixaRequest { OpeningAmount = "100,00" }, user);
var todayId = (string)(await Scalar("SELECT TOP 1 Id FROM CaixaSessoes WHERE ClosedAt IS NULL"))!;
await service.AbrirAsync(new AbrirCaixaRequest { ClientSessionId = "cx-offline-1", OpenedAt = oldOpen.ToString("o"), OpeningAmount = "50,00", EventId = "ev-open-1" }, user);
Check(await IsOpen("cx-offline-1") && await IsOpen(todayId), "abertura offline coexiste com sessão aberta hoje");
Check(await CountSessions() == 2, "duas sessões abertas");
var replay = await service.AbrirAsync(new AbrirCaixaRequest { ClientSessionId = "cx-offline-1", OpenedAt = oldOpen.ToString("o"), OpeningAmount = "50,00", EventId = "ev-open-2" }, user);
Check(replay.IsReplay && await CountSessions() == 2, "reabertura da mesma sessão é idempotente");

// --- Fechamento antigo fecha só a sessão indicada ---
await Exec($"INSERT INTO Vendas (Id,CompanyId,SaleNumber,PaymentType,TotalAmount,SaleDate,CaixaSessaoId,Status) VALUES ('v1','{company}','1','dinheiro',30,'{oldOpen.AddHours(1):o}','cx-offline-1','finalizada'), ('v2','{company}','2','dinheiro',999,'{now:o}','{todayId}','finalizada');");
await Exec($"INSERT INTO VendaPagamentos (Id,CompanyId,VendaId,PaymentType,Amount) VALUES ('p1','{company}','v1','dinheiro',30), ('p2','{company}','v2','dinheiro',999);");
await service.FecharAsync(new FecharCaixaRequest { ClientSessionId = "cx-offline-1", ClosedAt = oldClose.ToString("o"), ClosingAmount = "80,00", EventId = "ev-close-1" }, user);
Check(!await IsOpen("cx-offline-1") && await IsOpen(todayId), "fechamento antigo fecha somente a sessão indicada");
Check(Convert.ToDecimal(await Scalar("SELECT ExpectedCashAmount FROM CaixaSessoes WHERE Id='cx-offline-1'")) == 80m, "esperado considera só vendas da sessão indicada");
var closeReplay = await service.FecharAsync(new FecharCaixaRequest { ClientSessionId = "cx-offline-1", ClosedAt = oldClose.ToString("o"), ClosingAmount = "80,00", EventId = "ev-close-1" }, user);
Check(closeReplay.IsReplay, "reenvio do mesmo fechamento é replay");
await Rejects(() => service.FecharAsync(new FecharCaixaRequest { ClientSessionId = "cx-offline-1", ClosingAmount = "80,00", EventId = "ev-close-other" }, user), "já fechada", "sessão já fechada com outro evento é recusada");

// --- Sessão inexistente ---
await Rejects(() => service.FecharAsync(new FecharCaixaRequest { ClientSessionId = "cx-inexistente", ClosingAmount = "0,00", EventId = "ev-close-x" }, user), "não encontrada na nuvem", "sessão inexistente é recusada");
Check(await IsOpen(todayId), "sessão inexistente não fecha nenhuma outra");

// --- Cliente antigo sem os campos novos ---
await service.FecharAsync(new FecharCaixaRequest { ClosingAmount = "1099,00", DifferenceReason = "teste" }, user);
Check(!await IsOpen(todayId), "cliente sem ClientSessionId continua fechando a sessão aberta do operador");

Console.WriteLine($"{passed} checks passed");
