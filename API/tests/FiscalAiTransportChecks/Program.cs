using System.Net;
using System.Runtime.Loader;
using System.Text.Json;
using HORUSPDV_API.Repositories.DataAccess;
using HORUSPDV_API.Services.Fiscal;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;

if (args.Length != 1) throw new ArgumentException("Informe a pasta da API compilada.");
var output = Path.GetFullPath(args[0]);
AssemblyLoadContext.Default.Resolving += (context, name) =>
{
    var file = Path.Combine(output, name.Name + ".dll");
    return File.Exists(file) ? context.LoadFromAssemblyPath(file) : null;
};
var builder = WebApplication.CreateBuilder(new WebApplicationOptions { ContentRootPath = output });
builder.Logging.ClearProviders();
builder.WebHost.UseUrls("http://127.0.0.1:0");
await using var app = builder.Build();
var calls = new List<(string Path, string Title, string Authorization, JsonElement Body)>();
app.MapPost("/{**path}", async (HttpContext context) =>
{
    using var body = await JsonDocument.ParseAsync(context.Request.Body);
    calls.Add((context.Request.Path.Value!, context.Request.Headers["X-Title"].ToString(),
        context.Request.Headers.Authorization.ToString(), body.RootElement.Clone()));
    if (context.Request.Path == "/api/alpha/decisions")
        return Results.Json(new { answers = new { s0 = new { type = "noul", noul = .97 } }, usage = new { cost = .0001m } });
    var draft = JsonSerializer.Serialize(new
    {
        resumo = "Conferência da embalagem", pendencias = Array.Empty<string>(),
        sugestoes = new[] { new { campo = "cest", sugerido = "0302103", justificativa = "A descrição informa lata.", fontes = new[] { "cest:0302103" } } }
    });
    return Results.Json(new { choices = new[] { new { finish_reason = "stop", message = new { content = draft } } }, usage = new { cost = .0002m } });
});
await app.StartAsync();
try
{
    var address = app.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>()!.Addresses.Single();
    using var http = new HttpClient(new LoopbackTransport(new Uri(address))) { Timeout = TimeSpan.FromSeconds(5) };
    var service = new FiscalAiService(app.Environment, http);
    var product = new ProdutoAD
    {
        Id = "transport-product", ProductName = "Cerveja revisão lata 473ml", ProductDescription = "Descrição com acentuação",
        Ncm = "22030000", Cest = "0302100", Cfop = "5405", CsosnIcms = "500", CstPis = "06", CstCofins = "06", Gtin = "SEM GTIN"
    };
    var company = new EmpresaAD { Crt = 1, Uf = "RJ", AmbienteFiscal = 1 };
    var tables = new FiscalTableData(new Dictionary<string, FiscalNcm>
    {
        [product.Ncm] = new(product.Ncm, "Cervejas de malte", new(2022, 1, 1), new(9999, 12, 31))
    }, new Dictionary<string, FiscalClass>(), false, DateTimeOffset.UtcNow, "Referência local de teste");
    const string key = "sk-or-v1-fake-not-a-real-secret-transport-test";
    var passed = 0;
    void Check(bool ok, string name) { if (!ok) throw new Exception(name); passed++; Console.WriteLine("OK: " + name); }
    var report = await service.AnalyzeAsync(product, company, tables, key, false, default);
    Check(calls.Count == 1 && calls[0].Path == "/api/v1/chat/completions" && report.Sugestoes.Single().PodeAplicar,
        "Análise atravessa o transporte HTTP real e gera relatório");
    report = await service.AnalyzeAsync(product, company, tables, key, true, default);
    Check(calls.Count == 3 && calls[2].Path == "/api/alpha/decisions" && report.Sugestoes.Single().ProbabilidadeJev == .97,
        "Conferência Jev também atravessa o transporte HTTP real");
    Check(calls.All(c => !string.IsNullOrEmpty(c.Title) && c.Title.All(char.IsAscii)), "Identificação enviada ao provedor aceita codificação HTTP padrão");
    Check(calls.All(c => c.Authorization == "Bearer " + key), "Credencial continua no cabeçalho de autenticação");
    var prompt = calls[0].Body.GetProperty("messages")[1].GetProperty("content").GetString()!;
    using var state = JsonDocument.Parse(prompt);
    Check(state.RootElement.GetProperty("produto").GetProperty("productName").GetString() == product.ProductName
        && state.RootElement.GetProperty("produto").GetProperty("descricao").GetString() == product.ProductDescription,
        "Nome e descrição preservam acentos no conteúdo JSON");
    Check(!prompt.Contains(key) && report.Resumo == "Conferência da embalagem", "Segredo não entra no prompt e relatório mantém português");
    Console.WriteLine($"{passed} verificações passaram. Somente HTTP local: sem OpenRouter real, banco ou emissão fiscal.");
}
finally { await app.StopAsync(); }

// Usa o serializador HTTP de produção; só redireciona o destino para o servidor local.
sealed class LoopbackTransport(Uri origin) : HttpMessageHandler
{
    private readonly HttpMessageInvoker transport = new(new HttpClientHandler { AllowAutoRedirect = false, UseProxy = false });
    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
    {
        request.RequestUri = new Uri(origin, request.RequestUri!.PathAndQuery);
        return transport.SendAsync(request, ct);
    }
    protected override void Dispose(bool disposing) { if (disposing) transport.Dispose(); base.Dispose(disposing); }
}
