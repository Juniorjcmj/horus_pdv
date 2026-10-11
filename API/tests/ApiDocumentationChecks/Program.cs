using System.Net;
using System.Runtime.Loader;
using System.Text.Json;
using HORUSPDV_API.Controllers.Admin;
using HORUSPDV_API.Services.Admin;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.AspNetCore.Mvc.ApiExplorer;

if (args.Length != 1) throw new ArgumentException("Informe a pasta da API compilada. Este teste não usa banco nem serviços externos.");
var apiOutput = Path.GetFullPath(args[0]);
AssemblyLoadContext.Default.Resolving += (context, name) => {
    var path = Path.Combine(apiOutput, name.Name + ".dll");
    return File.Exists(path) ? context.LoadFromAssemblyPath(path) : null;
};
var builder = WebApplication.CreateBuilder(new WebApplicationOptions { Args = [], EnvironmentName = "Production" });
builder.Logging.ClearProviders();
builder.Services.AddControllers().AddApplicationPart(typeof(ApiDocumentationController).Assembly);
builder.Services.AddHorusApiDocumentation();
await using var app = builder.Build();
app.Use(async (context, next) => {
    if (context.Request.Headers.TryGetValue("X-Test-Role", out var role))
        context.Items["CurrentUser"] = new AuthenticatedUser { Id = "documentation-test", Name = "Teste", Role = role!,
            CompanyId = context.Request.Headers["X-Test-Company"].FirstOrDefault() ?? "empresa-principal" };
    try { await next(); }
    catch (Exception error) { Console.Error.WriteLine(error); throw; }
});
app.MapControllers();
app.Urls.Add("http://127.0.0.1:0");
await app.StartAsync();
using var client = new HttpClient { BaseAddress = new Uri(app.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>()!.Addresses.Single()) };
const string endpoint = "/api/Admin/Empresas/documentacao-api";
var passed = 0;
void Check(bool condition, string label) { if (!condition) throw new Exception(label); passed++; Console.WriteLine("OK: " + label); }
foreach (var (role, company, expected) in new[] {
    ((string?)null, "empresa-principal", HttpStatusCode.Unauthorized),
    ("gerente", "empresa-principal", HttpStatusCode.Forbidden),
    ("caixa", "empresa-principal", HttpStatusCode.Forbidden),
    ("administrador", "outra-loja", HttpStatusCode.Forbidden),
}) {
    using var request = new HttpRequestMessage(HttpMethod.Get, endpoint);
    if (role is not null) request.Headers.Add("X-Test-Role", role);
    request.Headers.Add("X-Test-Company", company);
    using var refused = await client.SendAsync(request);
    Check(refused.StatusCode == expected, $"acesso {role ?? "sem sessão"} / {company} retorna {(int)expected}");
    Check(!refused.Content.Headers.ContentDisposition?.DispositionType?.Equals("attachment") ?? true, "recusa não oferece arquivo");
}
using var authorized = new HttpRequestMessage(HttpMethod.Get, endpoint);
authorized.Headers.Add("X-Test-Role", "administrador");
using var response = await client.SendAsync(authorized);
var body = await response.Content.ReadAsStringAsync();
Check(response.StatusCode == HttpStatusCode.OK, "administrador geral recebe JSON OpenAPI por HTTP: " + (int)response.StatusCode);
Check(response.Content.Headers.ContentType?.MediaType == "application/json", "arquivo tem tipo JSON");
Check(response.Content.Headers.ContentDisposition?.DispositionType == "attachment" &&
    response.Content.Headers.ContentDisposition.FileName?.Trim('"') == ApiDocumentation.FileName, "download tem nome e disposição de anexo");
Check(response.Headers.CacheControl?.NoStore == true, "documentação não fica em cache");
using var json = JsonDocument.Parse(body);
var document = json.RootElement;
Check(document.GetProperty("openapi").GetString()!.StartsWith("3."), "JSON tem versão OpenAPI");
Check(document.GetProperty("info").GetProperty("title").GetString()!.Contains("Quack"), "identificação da API no arquivo");
Check(document.GetProperty("servers")[0].GetProperty("url").GetString() == client.BaseAddress!.ToString().TrimEnd('/'), "arquivo informa a origem da API que gerou o download");
var paths = document.GetProperty("paths");
var endpoints = app.Services.GetRequiredService<IApiDescriptionGroupCollectionProvider>().ApiDescriptionGroups.Items.SelectMany(g => g.Items).ToArray();
foreach (var api in endpoints) {
    var path = "/" + api.RelativePath!.Split('?')[0];
    if (!paths.TryGetProperty(path, out var item) || !item.TryGetProperty(api.HttpMethod!.ToLowerInvariant(), out _))
        throw new Exception($"Endpoint ausente: {api.HttpMethod} {path}");
}
Check(endpoints.Length > 100, $"todos os {endpoints.Length} endpoints publicados pelo API Explorer aparecem no documento");
Check(paths.GetProperty("/api/Produto").GetProperty("post").GetProperty("requestBody").ValueKind == JsonValueKind.Object, "cadastro de produto documenta corpo da requisição");
var schemas = document.GetProperty("components").GetProperty("schemas");
void ValidateReferences(JsonElement node) {
    if (node.ValueKind == JsonValueKind.Object) {
        foreach (var property in node.EnumerateObject()) {
            if (property.Name == "$ref" && property.Value.GetString() is { } reference && reference.StartsWith("#/components/schemas/")) {
                var key = reference[21..].Replace("~1", "/").Replace("~0", "~");
                if (!schemas.TryGetProperty(key, out _)) throw new Exception("Schema referenciado ausente: " + reference);
            } else ValidateReferences(property.Value);
        }
    } else if (node.ValueKind == JsonValueKind.Array) foreach (var item in node.EnumerateArray()) ValidateReferences(item);
}
ValidateReferences(document);
Check(true, $"referências dos {schemas.EnumerateObject().Count()} schemas são válidas");
Check(document.GetProperty("components").GetProperty("securitySchemes").GetProperty("SessionCookie").GetProperty("name").GetString() == HorusJwtService.AuthCookieName, "cookie de autenticação documentado corresponde à API");
Check((await client.GetAsync("/swagger/v1/swagger.json")).StatusCode == HttpStatusCode.NotFound, "exportação em produção não publica Swagger sem autenticação");
var policy = typeof(ApiDocumentationController).GetCustomAttributes(typeof(HorusAuthorizeRolesAttribute), true).Cast<HorusAuthorizeRolesAttribute>().Single();
Check(policy.Roles.SetEquals(["administrador"]), "rota declara perfil administrador para o middleware de autenticação");
await File.WriteAllTextAsync(Path.Combine(apiOutput, ApiDocumentation.FileName), body);
Console.WriteLine($"PASS: {passed} verificações, sem banco ou chamadas externas.");
await app.StopAsync();
