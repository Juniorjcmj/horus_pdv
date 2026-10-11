using System.Net;
using System.Text.Json;
using System.Runtime.Loader;
using HORUSPDV_API.Repositories;
using HORUSPDV_API.Repositories.DataAccess;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Fiscal;
using HORUSPDV_API.Services.Security;
using Microsoft.Data.SqlClient;

if (args.Length != 3 || args[2] != "127.0.0.1,51460") throw new ArgumentException("Use a API compilada, arquivo de senha e SQL descartável 127.0.0.1,51460.");
var output = Path.GetFullPath(args[0]);
Directory.SetCurrentDirectory(output);
AssemblyLoadContext.Default.Resolving += (context, name) => { var p = Path.Combine(output, name.Name + ".dll"); return File.Exists(p) ? context.LoadFromAssemblyPath(p) : null; };
var cs = new SqlConnectionStringBuilder { DataSource = args[2], InitialCatalog = "master", UserID = "sa", Password = File.ReadAllText(args[1]).Trim(), TrustServerCertificate = true };
await using (var db = new SqlConnection(cs.ConnectionString)) {
    await db.OpenAsync();
    await using var guard = new SqlCommand("IF DB_ID('HorusPdv') IS NOT NULL AND OBJECT_ID('HorusPdv.dbo.__FiscalAiOnly') IS NULL THROW 51000,'Banco não descartável: recusado.',1;", db);
    await guard.ExecuteNonQueryAsync();
}
cs.InitialCatalog = "HorusPdv";
var builder = WebApplication.CreateBuilder(); builder.Logging.ClearProviders();
builder.Configuration["ConnectionStrings:HorusPdv"] = cs.ConnectionString;
builder.Configuration["Security:EncryptionKey"] = "fiscal-ai-local-test-encryption-key-123456789";
builder.Services.AddSingleton<Connection>(); builder.Services.AddSingleton<HorusSecurityOptions>(); builder.Services.AddSingleton<HorusSecretProtector>();
await using var app = builder.Build();
await HorusDatabaseInitializer.InitializeAsync(app.Services);
var connection = app.Services.GetRequiredService<Connection>();
async Task Sql(string query) { await using var db = await connection.OpenConnectionAsync(); await using var cmd = new SqlCommand(query, db); await cmd.ExecuteNonQueryAsync(); }
await Sql("IF OBJECT_ID('__FiscalAiOnly') IS NULL CREATE TABLE __FiscalAiOnly(Id INT); IF NOT EXISTS(SELECT 1 FROM Empresas WHERE Id='fiscal-ai-test') INSERT Empresas(Id,Uf,Crt) VALUES('fiscal-ai-test','RJ',1);");
var store = new FiscalAiAB(connection, app.Services.GetRequiredService<HorusSecretProtector>());
var products = new ProdutoAB(connection);
var product = new ProdutoAD { Id = "ai-product", ProductName = "Cerveja Brahma lata 473ml", ProductCode = "1234567890123", Ncm = "22030000", Cest = "0302100", Cfop = "5405", CsosnIcms = "500", CstPis = "06", CstCofins = "06", Gtin = "SEM GTIN", ProductQnt = 45, ProductUnitPrice = 5, ProductSalePrice = 6 };
await products.SalvarAsync("fiscal-ai-test", product);
var company = new EmpresaAD { Uf = "RJ", Crt = 1, AmbienteFiscal = 1 };
var offline = new Fake { Offline = true };
var tables = await new FiscalReferenceTables(app.Environment, Microsoft.Extensions.Logging.Abstractions.NullLogger<FiscalReferenceTables>.Instance, new HttpClient(offline)).GetAsync();
var fake = new Fake();
var service = new FiscalAiService(app.Environment, new HttpClient(fake));
const string key = "sk-or-v1-fake-not-a-real-secret-123456";
var passed = 0;
void Check(bool ok, string name) { if (!ok) throw new Exception(name); passed++; Console.WriteLine("OK: " + name); }
async Task Reject(Func<Task> action, string name) { try { await action(); } catch (Exception e) when (e is InvalidOperationException or FiscalAiProviderError or SqlException) { Check(true, name); return; } throw new Exception("Não recusou: " + name); }
await store.SaveConfigAsync("fiscal-ai-test", key, true, false, default);
await using (var db = await connection.OpenConnectionAsync()) { await using var cmd = new SqlCommand("SELECT ChaveProtegida FROM FiscalIaConfig WHERE CompanyId='fiscal-ai-test'", db); var secret = (string)(await cmd.ExecuteScalarAsync())!; Check(secret.StartsWith("enc:v1:") && !secret.Contains(key), "Chave protegida em repouso"); }
Check((await store.GetConfigAsync("another-company", default)).Key == "", "Configuração isolada por empresa");
await store.SaveConfigAsync("fiscal-ai-test", "", false, false, default);
Check((await store.GetConfigAsync("fiscal-ai-test", default)).Key == key, "Chave vazia preserva segredo salvo");
var report = await service.AnalyzeAsync(product, company, tables, key, true, default);
Check(report.Sugestoes.Single().PodeAplicar && report.Sugestoes.Single().ProbabilidadeJev == .97, "Gemini e Jev conferem sugestão com fonte oficial");
Check(fake.LastChat.Contains("json_schema") && fake.LastChat.Contains("require_parameters") && !fake.LastChat.Contains(key), "Resposta estruturada, fornecedor compatível e segredo fora do prompt");
Check(!fake.LastChat.Contains("Cnpj") && !fake.LastChat.Contains("ProductSalePrice"), "Prompt não envia CNPJ, certificado ou preços");
await store.SaveReportAsync("fiscal-ai-test", report, default);
await Reject(() => store.ApplyAsync("another-company", product.Id, report.Id, ["cest"], "u", "Teste", tables, service.Cests, default), "Análise de outra empresa não pode ser aplicada");
await Reject(() => store.ApplyAsync("fiscal-ai-test", product.Id, report.Id, ["productSalePrice"], "u", "Teste", tables, service.Cests, default), "Correção de preço não autorizada pelo fluxo fiscal");
await Sql("CREATE OR ALTER TRIGGER trgFiscalAiRollback ON AuditLog AFTER INSERT AS THROW 51000,'Falha simulada de auditoria',1;");
await Reject(() => store.ApplyAsync("fiscal-ai-test", product.Id, report.Id, ["cest"], "u", "Teste", tables, service.Cests, default), "Auditoria e atualização fazem parte da mesma transação");
Check((await products.ObterAsync("fiscal-ai-test", product.Id))!.Cest == "0302100", "Falha de auditoria reverte alteração fiscal");
await Sql("DROP TRIGGER trgFiscalAiRollback;");
await store.ApplyAsync("fiscal-ai-test", product.Id, report.Id, ["cest"], "u", "Teste", tables, service.Cests, default);
var saved = (await products.ObterAsync("fiscal-ai-test", product.Id))!;
Check(saved.Cest == "0302103" && saved.ProductSalePrice == 6 && saved.ProductQnt == 45 && saved.Ncm == product.Ncm, "Aplica apenas CEST escolhido e preserva preço, estoque e demais campos");
await Reject(() => store.ApplyAsync("fiscal-ai-test", product.Id, report.Id, ["cest"], "u", "Teste", tables, service.Cests, default), "Análise aplicada não pode ser repetida");
await store.SaveReportAsync("fiscal-ai-test", report with { Id = Guid.NewGuid() }, default);
var stale = report with { Id = Guid.NewGuid() }; await store.SaveReportAsync("fiscal-ai-test", stale, default);
await Reject(() => store.ApplyAsync("fiscal-ai-test", product.Id, stale.Id, ["cest"], "u", "Teste", tables, service.Cests, default), "Cadastro alterado depois da análise impede sobrescrita");
fake.Probability = .2;
Check(!(await service.AnalyzeAsync(product, company, tables, key, true, default)).Sugestoes.Single().PodeAplicar, "Jev sem sustentação bloqueia aplicação");
fake.JevUnavailable = true;
Check(!(await service.AnalyzeAsync(product, company, tables, key, true, default)).Sugestoes.Single().PodeAplicar, "Jev indisponível permite leitura e bloqueia aplicação");
fake.JevUnavailable = false; fake.Probability = .97; fake.Field = "ncm"; fake.Value = "99999999"; fake.Source = "ncm:99999999";
Check(!(await service.AnalyzeAsync(product, company, tables, key, false, default)).Sugestoes.Single().PodeAplicar, "NCM e referência inventados não são aplicáveis");
fake.Field = "cstIbsCbs"; fake.Value = "000"; fake.Source = "simples2026";
Check(!(await service.AnalyzeAsync(product, company, tables, key, false, default)).Sugestoes.Single().PodeAplicar, "Simples 2026 não recebe preenchimento automático de IBS/CBS");
fake.Field = "cfop"; fake.Value = "5102"; fake.Source = "cest:0302103";
Check(!(await service.AnalyzeAsync(product, company, tables, key, false, default)).Sugestoes.Single().PodeAplicar, "Código existente não comprova tratamento de ICMS da operação");
fake.RawDraft = "{\"resumo\":\"Teste\",\"pendencias\":[],\"sugestoes\":[null]}";
await Reject(() => service.AnalyzeAsync(product, company, tables, key, false, default), "Sugestão nula é recusada sem falha não tratada");
fake.RawDraft = null;
fake.ChatStatus = HttpStatusCode.PaymentRequired;
await Reject(() => service.AnalyzeAsync(product, company, tables, key, true, default), "Saldo insuficiente não grava alterações");
await store.SaveConfigAsync("fiscal-ai-test", "", true, true, default);
Check((await store.GetConfigAsync("fiscal-ai-test", default)).Key == "", "Remoção explícita da chave");
Console.WriteLine($"{passed} verificações passaram. Sem emissão, sem OpenRouter real, sem banco da loja.");

sealed class Fake : HttpMessageHandler
{
    public bool Offline, JevUnavailable; public double Probability = .97; public HttpStatusCode ChatStatus = HttpStatusCode.OK;
    public string Field = "cest", Value = "0302103", Source = "cest:0302103", LastChat = "";
    public string? RawDraft;
    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
    {
        if (Offline) throw new HttpRequestException("Offline fixture");
        var decision = request.RequestUri!.AbsolutePath.EndsWith("decisions");
        if (!decision) LastChat = await request.Content!.ReadAsStringAsync(ct);
        if (decision && JevUnavailable) return new(HttpStatusCode.ServiceUnavailable);
        if (!decision && ChatStatus != HttpStatusCode.OK) return new(ChatStatus);
        object response = decision ? new { answers = new { s0 = new { type = "noul", noul = Probability } }, usage = new { cost = .0001m } }
            : new { choices = new[] { new { finish_reason = "stop", message = new { content = RawDraft ?? JsonSerializer.Serialize(new { resumo = "Conferir embalagem da cerveja", pendencias = Array.Empty<string>(), sugestoes = new[] { new { campo = Field, sugerido = Value, justificativa = "A descrição informa lata e a referência corresponde à embalagem.", fontes = new[] { Source } } } }) } } }, usage = new { cost = .0002m } };
        return new(HttpStatusCode.OK) { Content = new StringContent(JsonSerializer.Serialize(response)) };
    }
}
