using System.Net;
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
    context.Items["CurrentUser"] = new AuthenticatedUser { Id = "regression-user", CompanyId = companyId, Role = "gerente" };
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
await app.StopAsync();
Console.WriteLine($"{passed} verificações HTTP com banco real passaram.");
