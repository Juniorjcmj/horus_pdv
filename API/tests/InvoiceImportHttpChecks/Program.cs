using System.Net;
using System.Net.Http.Json;
using System.Runtime.Loader;
using System.Text;
using System.Text.Json;
using HORUSPDV_API.Controllers.Produtos;
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Repositories;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Fiscal;
using HORUSPDV_API.Services.Produtos;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.Data.SqlClient;

if (args.Length != 3 || !args[2].StartsWith("127.0.0.1,")) throw new ArgumentException("Informe API compilada, senha em arquivo e porta SQL isolada no localhost.");
var apiOutput = Path.GetFullPath(args[0]);
var password = File.ReadAllText(args[1]).Trim();
Directory.SetCurrentDirectory(apiOutput);
AssemblyLoadContext.Default.Resolving += (context, name) => {
    var path = Path.Combine(apiOutput, name.Name + ".dll");
    return File.Exists(path) ? context.LoadFromAssemblyPath(path) : null;
};
var connectionString = new SqlConnectionStringBuilder { DataSource = args[2], InitialCatalog = "master", UserID = "sa", Password = password, TrustServerCertificate = true, ConnectTimeout = 3, MultipleActiveResultSets = true };
for (var attempt = 0; ; attempt++) {
    try {
        await using var master = new SqlConnection(connectionString.ConnectionString);
        await master.OpenAsync();
        await using var guard = new SqlCommand("IF DB_ID(N'HorusPdv') IS NOT NULL AND OBJECT_ID(N'HorusPdv.dbo.__InvoiceImportRegressionOnly', N'U') IS NULL THROW 51000, 'Banco sem marcador de teste; use um container novo.', 1;", master);
        await guard.ExecuteNonQueryAsync();
        break;
    } catch (SqlException error) when (error.Number != 51000 && attempt < 15) { await Task.Delay(1000); }
}
connectionString.InitialCatalog = "HorusPdv";
var builder = WebApplication.CreateBuilder(new WebApplicationOptions { Args = [] });
builder.Logging.ClearProviders();
builder.Configuration["ConnectionStrings:HorusPdv"] = connectionString.ConnectionString;
builder.Services.AddSingleton<Connection>();
builder.Services.AddSingleton<HorusSecurityOptions>();
builder.Services.AddSingleton<HorusSecretProtector>();
builder.Services.AddScoped<ProdutoAB>();
builder.Services.AddScoped<FornecedorAB>();
builder.Services.AddScoped<LoteAB>();
builder.Services.AddScoped<MapeamentoProdutoFornecedorAB>();
builder.Services.AddScoped<EmpresaAB>();
builder.Services.AddScoped<SefazDFeDownloadService>();
builder.Services.AddScoped<NfeImportService>();
builder.Services.AddScoped<NotaEntradaArquivoService>();
builder.Services.AddControllers().AddApplicationPart(typeof(NfeImportController).Assembly);
await using var app = builder.Build();
await HorusDatabaseInitializer.InitializeAsync(app.Services);
await using (var db = await app.Services.GetRequiredService<Connection>().OpenConnectionAsync()) {
    await using var marker = new SqlCommand("IF OBJECT_ID(N'dbo.__InvoiceImportRegressionOnly', N'U') IS NULL CREATE TABLE dbo.__InvoiceImportRegressionOnly (Id INT NOT NULL)", db);
    await marker.ExecuteNonQueryAsync();
    await using var reset = new SqlCommand("""
        DROP TRIGGER IF EXISTS dbo.__InvoiceArchiveFail;
        DELETE FROM NotasEntradaArquivo WHERE CompanyId=N'invoice-regression';
        DELETE FROM ProdutoLotes WHERE CompanyId=N'invoice-regression';
        DELETE FROM MapeamentoProdutoFornecedor WHERE CompanyId=N'invoice-regression';
        DELETE FROM Produtos WHERE CompanyId=N'invoice-regression';
        DELETE FROM Fornecedores WHERE CompanyId=N'invoice-regression';
        """, db);
    await reset.ExecuteNonQueryAsync();
}
const string companyId = "invoice-regression";
app.Use((context, next) => {
    if (context.Request.Headers["X-Test-No-User"] != "1")
        context.Items["CurrentUser"] = new AuthenticatedUser { Id = "invoice-user", CompanyId = context.Request.Headers["X-Test-Other-Company"] == "1" ? "another-company" : companyId, Name = "Operador de teste", Role = "gerente" };
    return next(context);
});
app.MapControllers();
app.Urls.Add("http://127.0.0.1:0");
await app.StartAsync();
var address = app.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>()!.Addresses.Single();
using var client = new HttpClient { BaseAddress = new Uri(address) };
var passed = 0;
void Check(bool condition, string name) { if (!condition) throw new Exception(name); Console.WriteLine("OK " + name); passed++; }
async Task<JsonElement> Send(string path, object body, HttpStatusCode status) {
    var response = await client.PostAsJsonAsync("/api/NfeImport/" + path, body);
    var text = await response.Content.ReadAsStringAsync();
    if (response.StatusCode != status) throw new Exception($"{path}: {response.StatusCode}: {text}");
    return JsonDocument.Parse(text).RootElement.Clone();
}
async Task<int> Count(string table) {
    if (table is not ("Produtos" or "Fornecedores")) throw new Exception("Tabela inválida.");
    await using var db = await app.Services.GetRequiredService<Connection>().OpenConnectionAsync();
    await using var command = new SqlCommand($"SELECT COUNT(*) FROM {table} WHERE CompanyId=@CompanyId", db);
    command.Parameters.AddWithValue("@CompanyId", companyId);
    return Convert.ToInt32(await command.ExecuteScalarAsync());
}
var keyError = await Send("buscar-sefaz", new { chaveAcesso = "33261036716865000104651160000104891000214277" }, HttpStatusCode.BadRequest);
Check(keyError.GetProperty("message").GetString()!.Contains("Digitar itens do cupom"), "NFC-e recebe alternativa antes de buscar certificado ou acessar SEFAZ");
foreach (var model in new[] { 55, 65 }) {
    var xml = $"""
    <nfeProc xmlns="http://www.portalfiscal.inf.br/nfe"><NFe><infNFe><ide><mod>{model}</mod><nNF>10489</nNF><serie>116</serie></ide>
    <emit><CNPJ>36716865000104</CNPJ><xNome>FORNECEDOR REGRESSÃO</xNome></emit>
    <det nItem="1"><prod><cProd>SKU-NFCE</cProd><cEAN>SEM GTIN</cEAN><xProd>Sal grosso</xProd><NCM>25010020</NCM><uCom>UN</uCom><qCom>4</qCom><vUnCom>7.45</vUnCom></prod></det>
    </infNFe></NFe><protNFe /></nfeProc>
    """;
    var preview = await Send("preview", new { xmlBase64 = Convert.ToBase64String(Encoding.UTF8.GetBytes(xml)) }, HttpStatusCode.OK);
    Check(preview.GetProperty("data").GetProperty("modelo").GetInt32() == model, $"HTTP preview XML modelo {model}");
}
Check(await Count("Produtos") == 0 && await Count("Fornecedores") == 0, "prévia não grava fornecedor nem estoque");
NfeImportItemInput Item(int number) => new() { NumeroItem = number, ProductCode = "SKU-NFCE" + number, ProductName = "Sal grosso", Quantidade = "4", PrecoCusto = "7,45", PrecoVenda = "10,00", Gtin = "SEM GTIN", Ncm = "25010020", UnidadeComercial = "UN" };
NfeImportConfirmRequest Request() => new() { Fornecedor = new() { Cnpj = "36716865000104", CompanyName = "FORNECEDOR REGRESSÃO" }, Itens = [Item(1)] };
var invalid = Request();
invalid.Itens.Add(Item(2));
invalid.Itens[1].PrecoVenda = "";
await Send("confirmar", invalid, HttpStatusCode.BadRequest);
Check(await Count("Produtos") == 0 && await Count("Fornecedores") == 0, "segundo item inválido não deixa primeiro item nem fornecedor gravados");
var manual = Request();
manual.Documento = new() { Modelo = 65, ChaveAcesso = "33261036716865000104651160000104891000214277" };
var created = await Send("confirmar", manual, HttpStatusCode.OK);
Check(created.GetProperty("data").GetProperty("produtosCriados").GetInt32() == 1, "entrada digitada cria produto");
using var scope = app.Services.CreateScope();
var repository = scope.ServiceProvider.GetRequiredService<ProdutoAB>();
var first = (await repository.ListarAsync(companyId)).Single();
Check(first.ProductQnt == 4m && first.ProductUnitPrice == 7.45m && first.ProductSalePrice == 10m, "estoque, custo e venda persistidos corretamente");
var incoming = Request();
incoming.Itens[0].ProdutoExistenteId = first.Id;
incoming.Itens[0].Quantidade = "2";
incoming.Itens[0].PrecoCusto = "8,00";
var updated = await Send("confirmar", incoming, HttpStatusCode.OK);
var next = (await repository.ListarAsync(companyId)).Single();
Check(updated.GetProperty("data").GetProperty("produtosAtualizados").GetInt32() == 1 && next.ProductQnt == 6m && next.ProductUnitPrice == 8m && next.ProductSalePrice == 10m, "produto vinculado soma quantidade e mantém venda sem margem cadastrada");
Check(await Count("Fornecedores") == 1, "fornecedor reaproveitado sem duplicação");

var manualId = created.GetProperty("data").GetProperty("notaEntradaId").GetString()!;
var manualDetail = JsonDocument.Parse(await client.GetStringAsync("/api/NfeImport/notas-entrada/" + manualId)).RootElement;
Check(manualDetail.GetProperty("data").GetProperty("nota").GetProperty("origem").GetString() == "digitada" && !manualDetail.GetProperty("data").GetProperty("nota").GetProperty("temXml").GetBoolean(), "cupom digitado conserva chave e itens sem inventar XML");
await Send("confirmar", manual, HttpStatusCode.BadRequest);
Check((await repository.ListarAsync(companyId)).Single().ProductQnt == 6m, "repetição da chave do cupom não duplica estoque");
Check((await client.GetAsync("/api/NfeImport/notas-entrada/" + manualId + "/xml")).StatusCode == HttpStatusCode.NotFound, "cupom sem XML não oferece arquivo fabricado");
string Key55() {
    var digits = "3326103671686500010455116000010490100021427";
    var sum = 0; for (int i = 42, weight = 2; i >= 0; i--, weight = weight == 9 ? 2 : weight + 1) sum += (digits[i] - '0') * weight;
    var remainder = sum % 11; return digits + (remainder < 2 ? 0 : 11 - remainder);
}
var key55 = Key55();
var originalXml = Encoding.UTF8.GetPreamble().Concat(Encoding.UTF8.GetBytes($"""
<?xml version="1.0" encoding="UTF-8"?>
<nfeProc xmlns="http://www.portalfiscal.inf.br/nfe"><NFe><infNFe Id="NFe{key55}"><ide><mod>55</mod><nNF>10490</nNF><serie>116</serie><dhEmi>2026-10-10T09:37:00-03:00</dhEmi></ide>
<emit><CNPJ>36716865000104</CNPJ><xNome>FORNECEDOR REGRESSÃO</xNome></emit>
<det nItem="1"><prod><cProd>SKU-NFCE1</cProd><xProd>Sal grosso — original</xProd><qCom>5</qCom><vUnCom>7.45</vUnCom></prod></det>
<total><ICMSTot><vNF>37.25</vNF></ICMSTot></total></infNFe></NFe><protNFe><infProt><chNFe>{key55}</chNFe></infProt></protNFe></nfeProc>
""")).ToArray();
var xmlRequest = Request(); xmlRequest.Itens[0].ProdutoExistenteId = first.Id; xmlRequest.Itens[0].Quantidade = "2"; xmlRequest.Itens[0].PrecoCusto = "8,00";
xmlRequest.Itens[0].DataValidade = "2027-01-01"; xmlRequest.Itens[0].NumeroLote = "LOTE-XML";
xmlRequest.Documento = new() { XmlBase64 = Convert.ToBase64String(originalXml) };
var pre = await Send("preview", new { xmlBase64 = xmlRequest.Documento.XmlBase64 }, HttpStatusCode.OK);
Check(pre.GetProperty("data").GetProperty("documento").GetProperty("xmlBase64").GetString() == xmlRequest.Documento.XmlBase64, "prévia mantém bytes originais para upload e SEFAZ");
var simultaneous = await Task.WhenAll(Enumerable.Range(0, 2).Select(_ => client.PostAsJsonAsync("/api/NfeImport/confirmar", xmlRequest)));
Check(simultaneous.Count(r => r.StatusCode == HttpStatusCode.OK) == 1 && simultaneous.Count(r => r.StatusCode == HttpStatusCode.BadRequest) == 1, "duas confirmações simultâneas importam a nota uma única vez");
var saved = JsonDocument.Parse(await simultaneous.Single(r => r.StatusCode == HttpStatusCode.OK).Content.ReadAsStringAsync()).RootElement;
var xmlId = saved.GetProperty("data").GetProperty("notaEntradaId").GetString()!;
Check((await repository.ListarAsync(companyId)).Single().ProductQnt == 8m, "importação concorrente soma apenas a quantidade revisada");
var detail = JsonDocument.Parse(await client.GetStringAsync("/api/NfeImport/notas-entrada/" + xmlId)).RootElement.GetProperty("data");
Check(detail.GetProperty("nota").GetProperty("valorNota").GetDecimal() == 37.25m && detail.GetProperty("nota").GetProperty("valorEntrada").GetDecimal() == 16m && detail.GetProperty("entrada").GetProperty("itens")[0].GetProperty("produtoExistenteId").GetString() == first.Id, "histórico separa valor fiscal do custo recebido e conserva vínculo ao produto");
var file = await client.GetAsync("/api/NfeImport/notas-entrada/" + xmlId + "/xml");
Check((await file.Content.ReadAsByteArrayAsync()).SequenceEqual(originalXml) && file.Content.Headers.ContentDisposition?.DispositionType == "attachment" && file.Headers.CacheControl?.NoStore == true, "download preserva XML original byte a byte e não permite cache");
var search = JsonDocument.Parse(await client.GetStringAsync("/api/NfeImport/notas-entrada?busca=" + key55 + "&pagina=1&tamanhoPagina=1")).RootElement.GetProperty("data");
Check(search.GetProperty("total").GetInt32() == 1 && search.GetProperty("notas").GetArrayLength() == 1, "histórico permite busca por chave e paginação");
client.DefaultRequestHeaders.Add("X-Test-Other-Company", "1");
var otherList = JsonDocument.Parse(await client.GetStringAsync("/api/NfeImport/notas-entrada")).RootElement.GetProperty("data");
Check(otherList.GetProperty("total").GetInt32() == 0 && (await client.GetAsync("/api/NfeImport/notas-entrada/" + xmlId)).StatusCode == HttpStatusCode.NotFound && (await client.GetAsync("/api/NfeImport/notas-entrada/" + xmlId + "/xml")).StatusCode == HttpStatusCode.NotFound, "outra empresa não acessa lista, detalhe ou XML");
client.DefaultRequestHeaders.Remove("X-Test-Other-Company"); client.DefaultRequestHeaders.Add("X-Test-No-User", "1");
Check((await client.GetAsync("/api/NfeImport/notas-entrada")).StatusCode == HttpStatusCode.Unauthorized && (await client.GetAsync("/api/NfeImport/notas-entrada/" + xmlId + "/xml")).StatusCode == HttpStatusCode.Unauthorized, "histórico e XML exigem sessão autenticada");
client.DefaultRequestHeaders.Remove("X-Test-No-User");
var badSupplier = Request(); badSupplier.Fornecedor.Cnpj = "11111111000111"; badSupplier.Documento = new() { XmlBase64 = Convert.ToBase64String(originalXml) };
await Send("confirmar", badSupplier, HttpStatusCode.BadRequest);
Check((await repository.ListarAsync(companyId)).Single().ProductQnt == 8m, "XML de fornecedor divergente não altera estoque");
await using (var db = await app.Services.GetRequiredService<Connection>().OpenConnectionAsync()) {
    await using var trigger = new SqlCommand("CREATE TRIGGER dbo.__InvoiceArchiveFail ON dbo.NotasEntradaArquivo AFTER INSERT AS BEGIN IF EXISTS (SELECT 1 FROM inserted WHERE FornecedorNome=N'FALHA ARQUIVO') THROW 51001, 'Falha simulada de armazenamento', 1; END;", db);
    await trigger.ExecuteNonQueryAsync();
}
var failing = Request(); failing.Fornecedor.CompanyName = "FALHA ARQUIVO"; failing.Itens[0].ProductCode = "FALHA-NOVO"; failing.Itens[0].DataValidade = "2027-02-02";
var failed = await client.PostAsJsonAsync("/api/NfeImport/confirmar", failing);
Check(failed.StatusCode == HttpStatusCode.InternalServerError && await Count("Produtos") == 1 && (await repository.ListarAsync(companyId)).Single().ProductQnt == 8m, "falha ao arquivar reverte cadastro e estoque na mesma transação");
await using (var db = await app.Services.GetRequiredService<Connection>().OpenConnectionAsync()) {
    await using var command = new SqlCommand("SELECT (SELECT COUNT(*) FROM NotasEntradaArquivo WHERE CompanyId=@CompanyId),(SELECT COUNT(*) FROM ProdutoLotes WHERE CompanyId=@CompanyId),(SELECT CompanyName FROM Fornecedores WHERE CompanyId=@CompanyId)", db);
    command.Parameters.AddWithValue("@CompanyId", companyId); await using var reader = await command.ExecuteReaderAsync(); await reader.ReadAsync();
    Check(reader.GetInt32(0) == 3 && reader.GetInt32(1) == 1 && reader.GetString(2) != "FALHA ARQUIVO", "rollback não deixa nota, lote ou fornecedor parcialmente modificados");
}
await app.StopAsync();
Console.WriteLine($"{passed} verificações HTTP/SQL passaram no container isolado.");
