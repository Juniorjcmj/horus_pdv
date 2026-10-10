using System.Net;
using System.Runtime.Loader;
using System.Text.Json;
using HORUSPDV_API.Controllers.Admin;
using HORUSPDV_API.Repositories;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Admin;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.Data.SqlClient;

if (args.Length != 4 || !args[2].StartsWith("127.0.0.1,"))
    throw new ArgumentException("Informe API compilada, senha em arquivo, SQL isolado no localhost e diretório compartilhado de teste.");
var apiOutput = Path.GetFullPath(args[0]);
var storage = Path.GetFullPath(args[3]);
Directory.SetCurrentDirectory(apiOutput);
AssemblyLoadContext.Default.Resolving += (context, name) => {
    var file = Path.Combine(apiOutput, name.Name + ".dll");
    return File.Exists(file) ? context.LoadFromAssemblyPath(file) : null;
};
var sql = new SqlConnectionStringBuilder { DataSource = args[2], InitialCatalog = "master", UserID = "sa",
    Password = File.ReadAllText(args[1]).Trim(), TrustServerCertificate = true, ConnectTimeout = 3 };
await using var master = new SqlConnection(sql.ConnectionString);
for (var attempt = 0; ; attempt++) {
    try { await master.OpenAsync(); break; }
    catch (SqlException) when (attempt < 25) { await Task.Delay(1000); }
}
async Task Execute(SqlConnection db, string command) {
    await using var query = new SqlCommand(command, db) { CommandTimeout = 60 };
    await query.ExecuteNonQueryAsync();
}
await Execute(master, "IF DB_ID('HorusPdvBackupChecks') IS NOT NULL OR DB_ID('HorusPdvBackupRestoredChecks') IS NOT NULL THROW 51000, 'Use um container novo e isolado para estes testes.', 1; CREATE DATABASE HorusPdvBackupChecks;");
sql.InitialCatalog = "HorusPdvBackupChecks";
await using var fixture = new SqlConnection(sql.ConnectionString);
await fixture.OpenAsync();
await Execute(fixture, """
CREATE TABLE __BackupRegressionOnly (Id INT PRIMARY KEY);
CREATE TABLE AuditLog (Id INT IDENTITY PRIMARY KEY, OccurredAt DATETIME2 DEFAULT SYSUTCDATETIME(), CompanyId NVARCHAR(100), UserId NVARCHAR(100), UserName NVARCHAR(100), EventType NVARCHAR(100), EntityType NVARCHAR(100), EntityId NVARCHAR(100), Description NVARCHAR(MAX), Ip NVARCHAR(100));
CREATE TABLE Empresas (Id NVARCHAR(100) PRIMARY KEY, Nome NVARCHAR(100));
CREATE TABLE Produtos (Id INT IDENTITY PRIMARY KEY, CompanyId NVARCHAR(100) REFERENCES Empresas(Id), Descricao NVARCHAR(100), Preco DECIMAL(12,2), Foto VARBINARY(MAX));
CREATE INDEX IX_Produtos_CompanyId ON Produtos(CompanyId);
CREATE TABLE Vendas (Id INT PRIMARY KEY, CompanyId NVARCHAR(100), Total DECIMAL(12,2));
CREATE TABLE DocumentosFiscais (Id INT PRIMARY KEY, CompanyId NVARCHAR(100), Xml NVARCHAR(MAX), Certificado VARBINARY(MAX), SegredoCifrado NVARCHAR(MAX));
INSERT INTO Empresas VALUES ('loja-a', N'Empresa A'), ('loja-b', N'Empresa B');
INSERT INTO Produtos(CompanyId, Descricao, Preco, Foto) VALUES ('loja-a', N'Café', 7.45, 0x010203), ('loja-b', N'Leite', 10.00, 0x040506);
INSERT INTO Vendas VALUES (1, 'loja-a', 7.45), (2, 'loja-b', 10.00);
INSERT INTO DocumentosFiscais VALUES (1, 'loja-a', '<nfe>teste</nfe>', 0x07102026, N'segredo-cifrado-de-teste');
""");
var builder = WebApplication.CreateBuilder(new WebApplicationOptions { Args = [] });
builder.Logging.ClearProviders();
builder.Configuration["ConnectionStrings:HorusPdv"] = sql.ConnectionString;
builder.Configuration["DatabaseBackup:StorageDirectory"] = storage;
builder.Configuration["DatabaseBackup:SqlDirectory"] = "/var/opt/mssql/backups";
builder.Services.AddSingleton<Connection>();
builder.Services.AddScoped<AuditLogAB>();
builder.Services.AddSingleton<DatabaseBackupService>();
builder.Services.AddHostedService(provider => provider.GetRequiredService<DatabaseBackupService>());
builder.Services.AddControllers().AddApplicationPart(typeof(DatabaseBackupController).Assembly);
await using var app = builder.Build();
app.Use((context, next) => {
    if (context.Request.Headers.TryGetValue("X-Test-Role", out var role))
        context.Items["CurrentUser"] = new AuthenticatedUser { Id = context.Request.Headers["X-Test-User"].FirstOrDefault() ?? "super-admin",
            Name = "Administrador teste", Role = role.ToString(), CompanyId = context.Request.Headers["X-Test-Company"].FirstOrDefault() ?? "empresa-principal" };
    return next(context);
});
app.MapControllers();
app.Urls.Add("http://127.0.0.1:0");
await app.StartAsync();
using var client = new HttpClient { BaseAddress = new Uri(app.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>()!.Addresses.Single()) };
var passed = 0;
void Check(bool condition, string label) { if (!condition) throw new Exception(label); Console.WriteLine("OK " + label); passed++; }
const string route = "/api/Admin/Empresas/backup";
async Task<HttpResponseMessage> Send(HttpMethod method, string path, string? role = "administrador", string company = "empresa-principal", string user = "super-admin") {
    var request = new HttpRequestMessage(method, path);
    if (method == HttpMethod.Post) request.Content = new StringContent("", System.Text.Encoding.UTF8, "application/json");
    if (role is not null) request.Headers.Add("X-Test-Role", role);
    request.Headers.Add("X-Test-Company", company);
    request.Headers.Add("X-Test-User", user);
    return await client.SendAsync(request);
}
foreach (var (role, company, expected) in new (string?, string, HttpStatusCode)[] {
    (null, "empresa-principal", HttpStatusCode.Unauthorized), ("administrador", "loja-a", HttpStatusCode.Forbidden),
    ("gerente", "empresa-principal", HttpStatusCode.Forbidden), ("atendente", "loja-a", HttpStatusCode.Forbidden) }) {
    foreach (var suffix in new[] { "", "/00000000-0000-0000-0000-000000000001", "/00000000-0000-0000-0000-000000000001/arquivo" })
        Check((await Send(suffix == "" ? HttpMethod.Post : HttpMethod.Get, route + suffix, role, company)).StatusCode == expected, $"permissão {role ?? "sem sessão"} / {company} {suffix}");
}
using (var form = new HttpRequestMessage(HttpMethod.Post, route) { Content = new FormUrlEncodedContent([]) }) {
    form.Headers.Add("X-Test-Role", "administrador");
    Check((await client.SendAsync(form)).StatusCode == HttpStatusCode.UnsupportedMediaType, "POST de formulário não inicia backup");
}
Check(!Directory.EnumerateFiles(storage, "*.bak").Any(), "acessos recusados não geram arquivos");
var start = await Send(HttpMethod.Post, route);
Check(start.StatusCode == HttpStatusCode.Accepted, "início rápido por HTTP, independente do tempo do backup");
var state = JsonDocument.Parse(await start.Content.ReadAsStringAsync()).RootElement.GetProperty("data");
var id = state.GetProperty("id").GetString();
var repeated = JsonDocument.Parse(await (await Send(HttpMethod.Post, route)).Content.ReadAsStringAsync()).RootElement.GetProperty("data");
Check(repeated.GetProperty("id").GetString() == id, "clique repetido reutiliza geração em andamento");
Check((await Send(HttpMethod.Get, $"{route}/{id}", user: "outro-admin")).StatusCode == HttpStatusCode.NotFound, "outro usuário não consulta a cópia");
Check((await Send(HttpMethod.Get, $"{route}/{id}/arquivo", user: "outro-admin")).StatusCode == HttpStatusCode.NotFound, "outro usuário não baixa a cópia");
for (var attempt = 0; ; attempt++) {
    var response = await Send(HttpMethod.Get, $"{route}/{id}");
    state = JsonDocument.Parse(await response.Content.ReadAsStringAsync()).RootElement.GetProperty("data");
    var status = state.GetProperty("status").GetString();
    if (status == "concluido") break;
    if (status == "falhou" || attempt >= 120) throw new Exception(state.ToString());
    await Task.Delay(500);
}
Check(state.GetProperty("sizeBytes").GetInt64() > 0, "backup nativo concluído e verificado");
var download = await Send(HttpMethod.Get, $"{route}/{id}/arquivo");
var bytes = await download.Content.ReadAsByteArrayAsync();
var filename = state.GetProperty("fileName").GetString()!;
Check(download.StatusCode == HttpStatusCode.OK && bytes.SequenceEqual(await File.ReadAllBytesAsync(Path.Combine(storage, filename))), "download contém o BAK íntegro");
Check(download.Headers.CacheControl?.NoStore == true && download.Content.Headers.ContentDisposition?.DispositionType == "attachment", "download autenticado sem cache e como arquivo");
await using var restore = new SqlCommand("RESTORE DATABASE HorusPdvBackupRestoredChecks FROM DISK = @path WITH MOVE 'HorusPdvBackupChecks' TO '/var/opt/mssql/data/BackupRestoredChecks.mdf', MOVE 'HorusPdvBackupChecks_log' TO '/var/opt/mssql/data/BackupRestoredChecks_log.ldf', CHECKSUM;", master) { CommandTimeout = 60 };
restore.Parameters.AddWithValue("@path", "/var/opt/mssql/backups/" + filename);
await restore.ExecuteNonQueryAsync();
await using var compare = new SqlCommand("""
SELECT CASE WHEN (SELECT COUNT(*) FROM HorusPdvBackupRestoredChecks.dbo.Empresas) = 2
AND (SELECT SUM(Total) FROM HorusPdvBackupRestoredChecks.dbo.Vendas) = 17.45
AND EXISTS (SELECT 1 FROM HorusPdvBackupRestoredChecks.dbo.Produtos WHERE Descricao = N'Café' AND Foto = 0x010203)
AND EXISTS (SELECT 1 FROM HorusPdvBackupRestoredChecks.dbo.DocumentosFiscais WHERE Xml = '<nfe>teste</nfe>' AND Certificado = 0x07102026 AND SegredoCifrado = N'segredo-cifrado-de-teste')
AND EXISTS (SELECT 1 FROM HorusPdvBackupRestoredChecks.sys.indexes WHERE name = 'IX_Produtos_CompanyId')
AND EXISTS (SELECT 1 FROM HorusPdvBackupRestoredChecks.dbo.AuditLog WHERE EventType = 'BackupBancoSolicitado')
THEN 1 ELSE 0 END;
""", master);
Check(Convert.ToInt32(await compare.ExecuteScalarAsync()) == 1, "restauração recupera todas as empresas, valores, binários, documentos, auditoria e índices");
await using var audit = new SqlCommand("SELECT COUNT(*) FROM AuditLog WHERE EventType IN ('BackupBancoSolicitado','BackupBancoConcluido','BackupBancoDownload')", fixture);
Check(Convert.ToInt32(await audit.ExecuteScalarAsync()) == 3, "solicitação, conclusão e download auditados");
var missing = builder.Configuration["DatabaseBackup:SqlDirectory"];
builder.Configuration["DatabaseBackup:SqlDirectory"] = "/pasta-inexistente-do-teste";
var failedStart = JsonDocument.Parse(await (await Send(HttpMethod.Post, route)).Content.ReadAsStringAsync()).RootElement.GetProperty("data").GetProperty("id").GetString();
for (var attempt = 0; ; attempt++) {
    var failed = JsonDocument.Parse(await (await Send(HttpMethod.Get, $"{route}/{failedStart}")).Content.ReadAsStringAsync()).RootElement.GetProperty("data");
    if (failed.GetProperty("status").GetString() == "falhou") break;
    if (attempt >= 40) throw new Exception("O erro de pasta não foi informado.");
    await Task.Delay(250);
}
Check((await Send(HttpMethod.Get, $"{route}/{failedStart}/arquivo")).StatusCode == HttpStatusCode.NotFound, "falha de geração não permite download incompleto");
builder.Configuration["DatabaseBackup:SqlDirectory"] = "";
Check((await Send(HttpMethod.Post, route)).StatusCode == HttpStatusCode.ServiceUnavailable, "servidor sem configuração explica indisponibilidade");
builder.Configuration["DatabaseBackup:SqlDirectory"] = missing;
Console.WriteLine($"{passed} verificações de backup passaram.");
await app.StopAsync();
