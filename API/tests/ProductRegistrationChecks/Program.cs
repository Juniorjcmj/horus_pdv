using System.Net;
using System.IO.Compression;
using System.Text;
using System.Xml.Linq;
using HORUSPDV_API.Controllers.Fiscal;
using HORUSPDV_API.Services.Fiscal;
using Microsoft.AspNetCore.Mvc;
using System.Net.Http.Json;
using System.Runtime.Loader;
using System.Text.Json;
using HORUSPDV_API.Controllers.Produtos;
using HORUSPDV_API.Repositories;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Produtos;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.Data.SqlClient;

if (args.Length != 3) throw new ArgumentException("Informe pasta da API compilada, arquivo de senha e endereço SQL isolado (127.0.0.1,porta).");
var apiOutput = Path.GetFullPath(args[0]);
var password = File.ReadAllText(args[1]).Trim();
if (!args[2].StartsWith("127.0.0.1,")) throw new ArgumentException("Somente SQL Server isolado no localhost é permitido.");
Directory.SetCurrentDirectory(apiOutput);
AssemblyLoadContext.Default.Resolving += (context, name) => {
    var path = Path.Combine(apiOutput, name.Name + ".dll");
    return File.Exists(path) ? context.LoadFromAssemblyPath(path) : null;
};
var connectionString = new SqlConnectionStringBuilder {
    DataSource = args[2], InitialCatalog = "master", UserID = "sa", Password = password,
    TrustServerCertificate = true, ConnectTimeout = 30, MultipleActiveResultSets = true
};
await using (var master = new SqlConnection(connectionString.ConnectionString)) {
    await master.OpenAsync();
    await using var guard = new SqlCommand("""
        IF DB_ID(N'HorusPdv') IS NOT NULL
           AND OBJECT_ID(N'HorusPdv.dbo.__ProductRegistrationRegressionOnly', N'U') IS NULL
            THROW 51000, 'Banco existente sem marcador de teste. Use um container SQL novo e descartável.', 1;
        """, master);
    await guard.ExecuteNonQueryAsync();
}
connectionString.InitialCatalog = "HorusPdv";
var builder = WebApplication.CreateBuilder(new WebApplicationOptions { Args = [] });
builder.Logging.ClearProviders();
builder.Configuration["ConnectionStrings:HorusPdv"] = connectionString.ConnectionString;
builder.Services.AddSingleton<Connection>();
builder.Services.AddSingleton<HorusSecurityOptions>();
builder.Services.AddSingleton<HorusSecretProtector>();
builder.Services.AddSingleton<FiscalReferenceTables>();
builder.Services.AddScoped<EmpresaAB>();
builder.Services.AddScoped<ProdutoAB>();
builder.Services.AddScoped<FornecedorAB>();
builder.Services.AddScoped<LoteAB>();
builder.Services.AddScoped<IProdutoService, ProdutoService>();
builder.Services.AddControllers().AddApplicationPart(typeof(ProdutoController).Assembly);
await using var app = builder.Build();
await HorusDatabaseInitializer.InitializeAsync(app.Services);
await using (var db = await app.Services.GetRequiredService<Connection>().OpenConnectionAsync()) {
    await using var marker = new SqlCommand("IF OBJECT_ID(N'dbo.__ProductRegistrationRegressionOnly', N'U') IS NULL CREATE TABLE dbo.__ProductRegistrationRegressionOnly (Id INT NOT NULL)", db);
    await marker.ExecuteNonQueryAsync();
}
const string companyId = "regression-no-supplier";
app.Use((context, next) => {
    // Usuário fictício apenas neste host de teste; autenticação e autorização de produção são preservadas.
    if (context.Request.Headers["X-Test-Anonymous"] != "1")
        context.Items["CurrentUser"] = new AuthenticatedUser { Id = "regression-user", CompanyId = context.Request.Headers["X-Test-Company"].FirstOrDefault() ?? companyId, Role = "gerente" };
    return next();
});
app.MapControllers();
app.Urls.Add("http://127.0.0.1:0");
await app.StartAsync();
var address = app.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>()!.Addresses.Single();
using var client = new HttpClient { BaseAddress = new Uri(address) };
var passed = 0;
Dictionary<string, object?> Minimum() => new() { ["productName"] = "Arroz teste", ["productUnitPrice"] = "10,00", ["productSalePrice"] = "15,00" };
async Task<JsonElement> Send(string label, Dictionary<string, object?> payload, HttpStatusCode expected, string? id = null) {
    using var response = id is null ? await client.PostAsJsonAsync("/api/Produto", payload) : await client.PutAsJsonAsync("/api/Produto/" + id, payload);
    var body = await response.Content.ReadAsStringAsync();
    if (response.StatusCode != expected) throw new Exception($"{label}: HTTP {(int)response.StatusCode}, esperado {(int)expected}. {body}");
    var json = JsonDocument.Parse(body).RootElement.Clone();
    if (expected != HttpStatusCode.BadRequest) {
        var savedId = json.GetProperty("data").GetProperty("id").GetString();
        var supplier = payload.GetValueOrDefault("productSupplier")?.ToString()?.Trim() ?? "";
        await using var db = await app.Services.GetRequiredService<Connection>().OpenConnectionAsync();
        await using var check = new SqlCommand("SELECT ProductSupplier, SupplierId, ProductCode FROM Produtos WHERE Id = @Id AND CompanyId = @CompanyId", db);
        check.Parameters.AddWithValue("@Id", savedId);
        check.Parameters.AddWithValue("@CompanyId", companyId);
        await using var reader = await check.ExecuteReaderAsync();
        if (!await reader.ReadAsync() || reader.GetString(0) != supplier || reader.IsDBNull(1) != (supplier.Length == 0) || string.IsNullOrWhiteSpace(reader.GetString(2)))
            throw new Exception(label + ": gravação de fornecedor/código incorreta no banco.");
    }
    Console.WriteLine("OK: " + label);
    passed++;
    return json;
}
foreach (var scenario in new[] { "ausente", "vazio", "nulo", "espaços" }) {
    var payload = Minimum();
    if (scenario != "ausente") payload["productSupplier"] = scenario == "nulo" ? null : scenario == "espaços" ? "   " : "";
    await Send("criação com fornecedor " + scenario, payload, HttpStatusCode.Created);
}
var descriptionOnly = Minimum();
descriptionOnly.Remove("productName");
descriptionOnly["productDescription"] = "Arroz descrição alternativa";
await Send("descrição, custo e venda sem fornecedor", descriptionOnly, HttpStatusCode.Created);
await using (var db = await app.Services.GetRequiredService<Connection>().OpenConnectionAsync()) {
    await using var seed = new SqlCommand("IF NOT EXISTS (SELECT 1 FROM Fornecedores WHERE Id = N'regression-supplier') INSERT INTO Fornecedores (Id, CompanyId, CompanyName, FantasyName) VALUES (N'regression-supplier', @CompanyId, N'Fornecedor Teste', N'Fornecedor Teste')", db);
    seed.Parameters.AddWithValue("@CompanyId", companyId);
    await seed.ExecuteNonQueryAsync();
}
var linked = Minimum();
linked["productSupplier"] = "Fornecedor Teste";
var created = await Send("fornecedor cadastrado mantém o vínculo", linked, HttpStatusCode.Created);
var productId = created.GetProperty("data").GetProperty("id").GetString()!;
var originalCode = created.GetProperty("data").GetProperty("productCode").GetString();
var unlinked = Minimum();
unlinked["productSupplier"] = null;
var edited = await Send("edição remove fornecedor usando valor nulo", unlinked, HttpStatusCode.OK, productId);
if (edited.GetProperty("data").GetProperty("productCode").GetString() != originalCode) throw new Exception("Código alterado na edição.");
await Send("edição sem o campo fornecedor", Minimum(), HttpStatusCode.OK, productId);
var invalid = Minimum();
invalid["productSupplier"] = "Fornecedor inexistente";
await Send("fornecedor informado continua precisando existir", invalid, HttpStatusCode.BadRequest);
foreach (var field in new[] { "productName", "productUnitPrice", "productSalePrice" }) {
    var payload = Minimum();
    payload.Remove(field);
    await Send("campo obrigatório " + field, payload, HttpStatusCode.BadRequest);
}
void Verify(bool valid, string name) { if (!valid) throw new Exception(name); passed++; Console.WriteLine("OK: " + name); }
using var scope = app.Services.CreateScope();
var connection = scope.ServiceProvider.GetRequiredService<Connection>();
await using (var db = await connection.OpenConnectionAsync()) {
    await using var seed = new SqlCommand("IF NOT EXISTS (SELECT 1 FROM Empresas WHERE Id=@CompanyId) INSERT INTO Empresas (Id, Uf, Crt, AmbienteFiscal) VALUES (@CompanyId, N'RJ', 1, 1); UPDATE Produtos SET Ncm=N'10063021', CsosnIcms=N'102', CstPis=N'07', CstCofins=N'07', Gtin=N'SEM GTIN' WHERE Id=@ProductId AND CompanyId=@CompanyId;", db);
    seed.Parameters.AddWithValue("@CompanyId", companyId); seed.Parameters.AddWithValue("@ProductId", productId); await seed.ExecuteNonQueryAsync();
}
using (var fiscal = await client.GetAsync($"/api/Produto/{productId}/verificacao-fiscal")) {
    var body = await fiscal.Content.ReadAsStringAsync();
    Verify(fiscal.StatusCode == HttpStatusCode.OK, "Conferência fiscal por HTTP retorna dados da empresa e produto reais do teste");
    var data = JsonDocument.Parse(body).RootElement.GetProperty("data");
    Verify(data.GetProperty("crt").GetInt32() == 1 && data.GetProperty("uf").GetString() == "RJ" && data.GetProperty("descricaoNcm").ValueKind == JsonValueKind.String, "Conferência associa regime e descrição oficial do NCM");
    Verify(!body.Contains("certificado", StringComparison.OrdinalIgnoreCase) && !body.Contains("csc", StringComparison.OrdinalIgnoreCase), "Resposta não contém certificado ou CSC");
}
client.DefaultRequestHeaders.Add("X-Test-Company", "outra-empresa-do-teste");
using (var inaccessible = await client.GetAsync($"/api/Produto/{productId}/verificacao-fiscal")) Verify(inaccessible.StatusCode == HttpStatusCode.NotFound, "Empresa diferente não pode consultar o produto");
client.DefaultRequestHeaders.Remove("X-Test-Company");
client.DefaultRequestHeaders.Add("X-Test-Anonymous", "1");
using (var anonymous = await client.GetAsync($"/api/Produto/{productId}/verificacao-fiscal")) Verify(anonymous.StatusCode == HttpStatusCode.Unauthorized, "Consulta sem usuário é recusada");
client.DefaultRequestHeaders.Remove("X-Test-Anonymous");
Verify(await scope.ServiceProvider.GetRequiredService<EmpresaAB>().ObterExataAsync("empresa-inexistente-do-teste") is null, "Empresa sem cadastro não herda regime da empresa principal");
var policy = typeof(ProdutoController).GetMethod("VerificarFiscal")!.GetCustomAttributes(typeof(HorusAuthorizeRolesAttribute), true).Cast<HorusAuthorizeRolesAttribute>().Single();
Verify(policy.Roles.Contains("administrador") && policy.Roles.Contains("gerente") && !policy.Roles.Contains("caixa"), "Endpoint declara autorização para administração e exclui operador de caixa");

const string key = "33261055719385000133650030000000211132918790";
const string ns = "http://www.portalfiscal.inf.br/nfe";
var signed = $"<NFe xmlns='{ns}'><infNFe Id='NFe{key}' versao='4.00'><det nItem='1'><prod><NCM>10063021</NCM></prod></det></infNFe></NFe>";
var receipt = $"<retEnviNFe xmlns='{ns}' versao='4.00'><cStat>104</cStat><protNFe versao='4.00'><infProt><chNFe>{key}</chNFe><cStat>100</cStat></infProt></protNFe></retEnviNFe>";
await using (var db = await connection.OpenConnectionAsync()) {
    await using var seed = new SqlCommand("DELETE FROM DocumentosFiscais WHERE Id=N'fiscal-regression' AND CompanyId=@Company; INSERT INTO DocumentosFiscais (Id, CompanyId, Serie, NumeroNf, Ambiente, Status, ChaveAcesso, XmlAssinado, XmlProtocolado, DhAutorizacao) VALUES (N'fiscal-regression', @Company, 3, 21, 1, 3, @Key, @Signed, @Receipt, '2026-10-02T15:29:20-03:00');", db);
    seed.Parameters.AddWithValue("@Company", companyId); seed.Parameters.AddWithValue("@Key", key); seed.Parameters.AddWithValue("@Signed", signed); seed.Parameters.AddWithValue("@Receipt", receipt); await seed.ExecuteNonQueryAsync();
}
// Only read/export methods are exercised: transmission dependencies are intentionally absent.
var documents = new DocumentoFiscalAB(connection, null!, scope.ServiceProvider.GetRequiredService<ProdutoAB>(), null!, scope.ServiceProvider.GetRequiredService<LoteAB>());
var stored = await documents.ObterXmlAsync(companyId, "fiscal-regression");
Verify(stored!.Value.Xml!.Contains("nfeProc") && stored.Value.Xml.Contains("<NCM>10063021"), "Download individual recupera XML completo de registro legado");
Verify(await documents.ObterXmlAsync("outra-empresa-do-teste", "fiscal-regression") is null, "XML individual não vaza para outra empresa");
var controller = new NfceController(documents, null!, null!, null!);
var context = new DefaultHttpContext(); context.Items["CurrentUser"] = new AuthenticatedUser { Id="regression-user", CompanyId=companyId, Role="administrador" };
controller.ControllerContext = new ControllerContext { HttpContext = context };
var result = await controller.ExportarXmlsMes(2026, 10);
Verify(result is FileContentResult, "Exportação mensal aceita notas antigas com resposta retEnviNFe");
using (var zip = new ZipArchive(new MemoryStream(((FileContentResult)result).FileContents))) {
    using var reader = new StreamReader(zip.Entries.Single().Open()); var xml = await reader.ReadToEndAsync();
    Verify(XDocument.Parse(xml).Root!.Name == XName.Get("nfeProc", ns) && xml.Contains("<NCM>10063021"), "ZIP mensal contém produtos e protocolo, em vez de apenas resposta SEFAZ");
}
await using (var db = await connection.OpenConnectionAsync()) {
    await using var check = new SqlCommand("SELECT XmlProtocolado FROM DocumentosFiscais WHERE Id=N'fiscal-regression'; SELECT COUNT(*) FROM Produtos WHERE Id=@ProductId AND CompanyId=@CompanyId AND Ncm=N'10063021';", db);
    check.Parameters.AddWithValue("@ProductId", productId); check.Parameters.AddWithValue("@CompanyId", companyId);
    await using var reader = await check.ExecuteReaderAsync(); await reader.ReadAsync(); var saved = reader.GetString(0); await reader.NextResultAsync(); await reader.ReadAsync();
    Verify(saved == receipt && reader.GetInt32(0) == 1, "Exportação e conferência são somente leitura e preservam os registros originais");
}
await using (var db = await connection.OpenConnectionAsync()) {
    await using var missing = new SqlCommand("UPDATE DocumentosFiscais SET Status=6, XmlAssinado=NULL, XmlCancelamento=N'<evento>cancelamento-original</evento>' WHERE Id=N'fiscal-regression' AND CompanyId=@Company;", db);
    missing.Parameters.AddWithValue("@Company", companyId); await missing.ExecuteNonQueryAsync();
}
var incomplete = await controller.ExportarXmlsMes(2026, 10);
Verify(incomplete is ConflictObjectResult, "Exportação incompleta informa pendência em vez de baixar ZIP parcial ou protocolo isolado");
var cancelDownload = await controller.BaixarXml("fiscal-regression", "cancelamento");
Verify(cancelDownload is FileContentResult cancelFile && Encoding.UTF8.GetString(cancelFile.FileContents).Contains("cancelamento-original"), "Evento de cancelamento continua disponível mesmo sem o XML original da nota");
await app.StopAsync();
Console.WriteLine($"{passed} verificações HTTP com banco real passaram.");
