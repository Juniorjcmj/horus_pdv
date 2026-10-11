using System.Runtime.Loader;
using System.Text.Json;
using HORUSPDV_API.Repositories;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Repositories.DataAccess;
using HORUSPDV_API.Models.Requests;
using Microsoft.Data.SqlClient;

if (args.Length != 3 || args[2] != "127.0.0.1,51461")
    throw new ArgumentException("Informe saída compilada da API, arquivo da senha e SQL isolado 127.0.0.1,51461.");
var apiOutput = Path.GetFullPath(args[0]);
Directory.SetCurrentDirectory(apiOutput);
AssemblyLoadContext.Default.Resolving += (context, name) => {
    var path = Path.Combine(apiOutput, name.Name + ".dll");
    return File.Exists(path) ? context.LoadFromAssemblyPath(path) : null;
};
var cs = new SqlConnectionStringBuilder { DataSource = args[2], InitialCatalog = "master", UserID = "sa",
    Password = File.ReadAllText(args[1]).Trim(), TrustServerCertificate = true, MultipleActiveResultSets = true };
await using (var master = new SqlConnection(cs.ConnectionString)) {
    await master.OpenAsync();
    await using var guard = new SqlCommand("IF DB_ID(N'HorusPdv') IS NOT NULL THROW 51000, 'Use um container novo e descartável.', 1;", master);
    await guard.ExecuteNonQueryAsync();
}
cs.InitialCatalog = "HorusPdv";
var builder = WebApplication.CreateBuilder(new WebApplicationOptions { Args = [] });
builder.Logging.ClearProviders();
builder.Configuration["ConnectionStrings:HorusPdv"] = cs.ConnectionString;
builder.Services.AddSingleton<Connection>();
builder.Services.AddScoped<AuditLogAB>(); builder.Services.AddScoped<FiadoAB>();
builder.Services.AddScoped<LoteAB>(); builder.Services.AddScoped<HistoricoVendasAB>(); builder.Services.AddScoped<RelatorioAB>();
await using var app = builder.Build();
await HorusDatabaseInitializer.InitializeAsync(app.Services);
var connection = app.Services.GetRequiredService<Connection>();
using var scope = app.Services.CreateScope();
var report = scope.ServiceProvider.GetRequiredService<RelatorioAB>();
var sales = scope.ServiceProvider.GetRequiredService<HistoricoVendasAB>();
var options = new JsonSerializerOptions(JsonSerializerDefaults.Web);
var passed = 0;
void Check(bool condition, string label) { if (!condition) throw new Exception(label); passed++; Console.WriteLine("OK: " + label); }
async Task Sql(string sql) {
    await using var db = await connection.OpenConnectionAsync(); await using var command = new SqlCommand(sql, db); await command.ExecuteNonQueryAsync();
}
Dictionary<string, JsonElement> Filters(string start = "2026-10-10", string end = "2026-10-10", string search = "", string category = "all") =>
    new() { ["startDate"] = JsonSerializer.SerializeToElement(start), ["endDate"] = JsonSerializer.SerializeToElement(end),
        ["productSearch"] = JsonSerializer.SerializeToElement(search), ["categoriaId"] = JsonSerializer.SerializeToElement(category) };
async Task<JsonElement> Generate(Dictionary<string, JsonElement>? filters = null, string company = "report-test") =>
    JsonSerializer.SerializeToElement(await report.GerarAsync(company, "lucro-markup-produtos", filters ?? Filters()), options);
decimal Currency(JsonElement element, string key) => decimal.Parse(element.GetProperty(key).GetString()!.Replace("R$", "").Trim(), new System.Globalization.CultureInfo("pt-BR"));
await Sql("""
    INSERT INTO Categorias(Id, CompanyId, Nome) VALUES ('report-cat','report-test','Bebidas teste');
    INSERT INTO Produtos(Id,CompanyId,ProductCode,ProductName,ProductQnt,ProductUnitPrice,ProductSalePrice,CategoriaId)
    VALUES ('report-p1','report-test','A','Produto A',100,10,15,'report-cat'),
           ('report-p2','report-test','B','Produto B',100,20,35,NULL),
           ('report-pz','report-test','Z','Produto custo zero',100,0,5,NULL),
           ('report-other-p','other-company','A','Outro produto',100,999,999,NULL);
    INSERT INTO Vendas(Id,CompanyId,SaleNumber,TotalAmount,SaleDate,Status)
    VALUES ('report-s1','report-test','T1',60,'2026-10-10T00:00:00-03:00','finalizada'),
           ('report-s2','report-test','T2',35,'2026-10-10T23:59:59-03:00','finalizada'),
           ('report-s3','report-test','T3',999,'2026-10-11T00:00:00-03:00','finalizada'),
           ('report-s4','report-test','T4',999,'2026-10-09T23:59:59-03:00','finalizada'),
           ('report-sc','report-test','TC',999,'2026-10-10T12:00:00-03:00','cancelada'),
           ('report-so','other-company','TO',999,'2026-10-10T12:00:00-03:00','finalizada');
    INSERT INTO VendaItens(Id,VendaId,ProductCode,ProductName,Quantity,UnitPrice,ItemTotal,Desconto,CostPrice)
    VALUES ('report-i1','report-s1','A','Produto A',2,15,28,2,10),
           ('report-i2','report-s1','A','Produto A',1,15,15,0,12),
           ('report-i3','report-s1','B','Produto B',0.5,35,17,0.5,20),
           ('report-i4','report-s2','B','Produto B',1,35,35,0,20),
           ('report-i5','report-s3','A','Produto A',1,999,999,0,10),
           ('report-i6','report-s4','A','Produto A',1,999,999,0,10),
           ('report-ic','report-sc','A','Produto A',1,999,999,0,10),
           ('report-io','report-so','A','Outro produto',1,999,999,0,999);
    """);
var result = await Generate();
Check(result.GetProperty("rows").GetArrayLength() == 2, "Agrupa o mesmo código, exclui cancelamentos e outras empresas");
Check(Currency(result.GetProperty("totalRow"), "faturamento") == 95, "Período em Brasília inclui todo o dia e exclui ambos os limites externos");
Check(Currency(result.GetProperty("totalRow"), "custo") == 62, "Custo considera quantidades fracionadas e diferentes custos unitários");
Check(Currency(result.GetProperty("totalRow"), "lucro") == 33, "Lucro usa receita líquida dos descontos");
Check(result.GetProperty("totalRow").GetProperty("markup").GetString() == "53,23%", "Markup consolidado ponderado, sem soma ou média simples de percentuais");
var a = result.GetProperty("rows")[0];
Check(Currency(a, "compra") == 10.67m && Currency(a, "venda") == 14.33m, "Valores unitários são médias ponderadas");
Check(Currency((await Generate(Filters(search: "A"))).GetProperty("totalRow"), "faturamento") == 43, "Filtro por código recalcula os totais de todas as linhas");
Check((await Generate(Filters(search: "%"))).GetProperty("rows").GetArrayLength() == 0, "Busca trata metacaracteres SQL como texto literal");
Check(Currency((await Generate(Filters(category: "report-cat"))).GetProperty("totalRow"), "lucro") == 11, "Filtro por departamento recalcula o lucro");
await Sql("UPDATE Produtos SET ProductUnitPrice = 100 WHERE CompanyId='report-test' AND ProductCode='A';");
Check(Currency((await Generate()).GetProperty("totalRow"), "custo") == 62, "Alterar o custo atual não muda vendas com custo salvo");
await Sql("UPDATE VendaItens SET CostPrice=NULL WHERE Id='report-i1';");
result = await Generate();
Check(result.GetProperty("notes").EnumerateArray().Any(n => n.GetString()!.Contains("estimado")), "Venda antiga usa custo atual com aviso explícito de estimativa");
Check(Currency(result.GetProperty("totalRow"), "custo") == 242, "Fallback legado usa o cadastro da mesma empresa");
await Sql("UPDATE VendaItens SET CostPrice=NULL, ProductCode='REMOVIDO' WHERE Id='report-i1';");
Check((await Generate()).GetProperty("totalRow").GetProperty("lucro").GetString() == "Não calculado", "Custo ausente impede totais enganosos de lucro e markup");
var zero = SalesProfitReport.Build([new("Z", "Custo zero", 1, 10, 0, false)]);
Check((string)zero.Rows[0]["markup"] == "—" && (string)zero.TotalRow["markup"] == "—", "Custo zero não divide por zero nem simula 0% de markup");
var negative = SalesProfitReport.Build([new("N", "Prejuízo", 1, 8, 10, false)]);
Check((string)negative.TotalRow["markup"] == "-20,00%", "Prejuízo gera lucro e markup negativos");
var empty = await Generate(Filters(search: "INEXISTENTE"));
Check(Currency(empty.GetProperty("totalRow"), "lucro") == 0, "Período sem vendas tem totais zerados e markup indefinido");
try { await Generate(Filters("2026-10-11", "2026-10-10")); throw new Exception("Período invertido aceito"); }
catch (InvalidOperationException) { Check(true, "Recusa datas invertidas"); }
try { await Generate(Filters("errada")); throw new Exception("Data inválida aceita"); }
catch (InvalidOperationException) { Check(true, "Recusa datas inválidas"); }
// Exercita os dois caminhos reais de gravação, inclusive custo salvo após alterar cadastro.
var registered = await sales.RegistrarAsync("report-test", new VendaRequest {
    PaymentType = "Dinheiro", OperatorName = "Teste", TotalAmount = "33,00", Items = [new() { ProductCode = "B", ProductName = "Produto B", Quantity = 1, UnitPrice = 35, Desconto = 2 }]
});
await using (var db = await connection.OpenConnectionAsync()) {
    await using var check = new SqlCommand("SELECT CostPrice, ItemTotal FROM VendaItens WHERE VendaId=@Id", db);
    check.Parameters.AddWithValue("@Id", registered.VendaId); await using var r = await check.ExecuteReaderAsync();
    Check(await r.ReadAsync() && r.GetDecimal(0) == 20 && r.GetDecimal(1) == 33, "Venda normal salva custo do servidor e receita descontada");
}
var order = await sales.RegistrarComPrecosFixosAsync("report-test", "Consumidor", "-", "Dinheiro", "Teste",
    [new PedidoItemAD { ProductCode = "B", ProductName = "Produto B", Quantity = 1, UnitPrice = 35 }]);
await using (var db = await connection.OpenConnectionAsync()) {
    await using var check = new SqlCommand("SELECT CostPrice FROM VendaItens WHERE VendaId=@Id", db);
    check.Parameters.AddWithValue("@Id", order.VendaId);
    Check((decimal)(await check.ExecuteScalarAsync())! == 20, "Finalização de pedido também salva custo");
}
Console.WriteLine($"{passed} verificações aprovadas em SQL isolado.");
