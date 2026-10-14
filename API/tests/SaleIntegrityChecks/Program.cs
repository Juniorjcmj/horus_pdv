using System.Text.Json;
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Repositories;
using HORUSPDV_API.Repositories.DataAccess;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Shared;
using HORUSPDV_API.Services.Fiscal;
using Microsoft.Data.SqlClient;
using Microsoft.Extensions.Logging.Abstractions;

// Deliberately never loads appsettings, environment connection strings, or the application initializer.
const string company = "integrity-test";
const string database = "HorusIntegrityChecks";
const string master = "Server=127.0.0.1,14339;Database=master;User ID=sa;Password=HorusIntegrityTestOnly_2026!;TrustServerCertificate=True;Connect Timeout=10";
var sql = master.Replace("Database=master", $"Database={database}");
await using (var connection = new SqlConnection(master))
{
    await connection.OpenAsync();
    await using var command = new SqlCommand($"IF DB_ID('{database}') IS NOT NULL BEGIN ALTER DATABASE {database} SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE {database}; END; CREATE DATABASE {database};", connection);
    await command.ExecuteNonQueryAsync();
}
async Task Exec(string commandText)
{
    await using var connection = new SqlConnection(sql); await connection.OpenAsync();
    await using var command = new SqlCommand(commandText, connection); await command.ExecuteNonQueryAsync();
}
async Task<decimal> Scalar(string commandText)
{
    await using var connection = new SqlConnection(sql); await connection.OpenAsync();
    await using var command = new SqlCommand(commandText, connection); return Convert.ToDecimal(await command.ExecuteScalarAsync());
}
await Exec(await File.ReadAllTextAsync(Path.Combine(AppContext.BaseDirectory, "schema.sql")));
var configuration = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?> { ["ConnectionStrings:HorusPdv"] = sql }).Build();
var factory = new Connection(configuration);
var repository = new HistoricoVendasAB(factory, new FiadoAB(factory), new AuditLogAB(factory), new LoteAB(factory, NullLogger<LoteAB>.Instance));
var jsonOptions = new JsonSerializerOptions { PropertyNameCaseInsensitive = true };
var fixturesPath = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "../../../../../../tests/fixtures/sale-settlements.json"));
// bin/Debug/net8.0 -> project tests -> repo root
if (!File.Exists(fixturesPath)) fixturesPath = Path.GetFullPath("tests/fixtures/sale-settlements.json");
using var fixtures = JsonDocument.Parse(await File.ReadAllTextAsync(fixturesPath));
var passed = 0;
void Check(bool result, string name) { if (!result) throw new Exception(name); passed++; Console.WriteLine($"PASS {name}"); }
async Task Reject(Func<Task> action, string name)
{
    var expected = name switch
    {
        var n when n.StartsWith("available40") || n.StartsWith("zero blocks") || n.StartsWith("aggregate80") || n == "order zero limit blocks" => new[] { "Crédito insuficiente" },
        "fiado requires customer" => ["obrigatório vincular"],
        "invalid sale rejected" => ["Soma dos itens líquidos", "Soma dos pagamentos", "Quantidade, preço ou desconto inválido", "Desconto superior", "Pagamento, recebido ou troco inválido", "Troco permitido", "Líquido informado"],
        "wrong cash change one cent" => ["Recebido em dinheiro"],
        "omitted price never becomes implicit free item" => ["Preço do item ausente"],
        "identity conflicts on changed payload" => ["Conflito de idempotência"],
        "order rejects one cent" => ["Soma dos pagamentos"],
        "failure after debt rolls everything back" => ["Fixture injected failure"],
        _ => throw new Exception($"Missing explicit expected domain failure for {name}")
    };
    try { await action(); }
    catch (Exception ex) when ((ex is InvalidOperationException or ArgumentException || ex is SqlException sqlError && sqlError.Number == 51000 && name == "failure after debt rolls everything back") && expected.Any(message => ex.Message.Contains(message, StringComparison.Ordinal)))
    { Check(true, name); return; }
    throw new Exception($"Expected rejection: {name}");
}
void RejectRule(Action action, string name)
{
    try { action(); } catch (FiscalConfigurationException ex) when (ex.Message.Contains("não confere")) { Check(true, name); return; }
    throw new Exception($"Expected rejection: {name}");
}
async Task Reset(decimal limit = 500, decimal debt = 0)
{
    await Exec($"DELETE FROM ProcessedEvents; DELETE FROM AuditLog; DELETE FROM FiadoMovimentos; DELETE FROM VendaPagamentos; DELETE FROM VendaItens; DELETE FROM Pedidos; DELETE FROM Vendas; DELETE FROM Produtos; DELETE FROM Clientes; INSERT INTO Produtos VALUES ('p1','{company}','SKU','Produto teste',100,1,100,100), ('p2','{company}','FREE','Gratuito',100,1,5.14,100); INSERT INTO Clientes (Id,CompanyId,CustomerName,Document,LimiteCredito,SaldoDevedor) VALUES ('c1','{company}','Cliente teste','52998224725',{limit.ToString(System.Globalization.CultureInfo.InvariantCulture)},{debt.ToString(System.Globalization.CultureInfo.InvariantCulture)});");
}
VendaRequest Sale(decimal total, string type = "fiado") => new()
{
    ClientSaleId = Guid.NewGuid().ToString(), EventId = Guid.NewGuid().ToString(), ReenvioOffline = true,
    CustomerName = "Cliente teste", CustomerCpf = "52998224725", OperatorName = "Caixa teste", PaymentType = type,
    TotalAmount = HorusMoneyFormat.Format(total), Items = [new() { ProductCode = "SKU", ProductName = "Produto teste", Quantity = 1, UnitPrice = total }],
    Payments = [new() { PaymentType = type, Amount = total }]
};
Check(SaleMoney.Money(1.005m) == 1.01m && SaleMoney.Money(-1.005m) == -1.01m, "midpoint AwayFromZero");
Check(SaleMoney.ItemNet(.125m, 10, 0) == 1.25m && SaleMoney.ItemNet(1, 5.14m, 5.14m) == 0, "fractional and free item");
foreach (var fixture in fixtures.RootElement.EnumerateArray())
{
    await Reset();
    var request = JsonSerializer.Deserialize<VendaRequest>(fixture.GetProperty("sale"), jsonOptions)!;
    request.EventId = Guid.NewGuid().ToString(); request.ClientSaleId = Guid.NewGuid().ToString(); request.ReenvioOffline = true;
    var result = await repository.RegistrarAsync(company, request, "test-cash");
    var total = HorusMoneyFormat.ParseDecimalStrict(request.TotalAmount);
    Check(await Scalar("SELECT SUM(ItemTotal) FROM VendaItens") == total && await Scalar("SELECT SUM(Amount) FROM VendaPagamentos") == total, "persisted fixture " + fixture.GetProperty("name").GetString());
    var replay = await repository.RegistrarAsync(company, request, "test-cash");
    Check(replay.IsReplay && replay.VendaId == result.VendaId && await Scalar("SELECT COUNT(*) FROM Vendas") == 1 && await Scalar("SELECT ProductQnt FROM Produtos WHERE ProductCode='SKU'") == 100 - request.Items.Where(i => i.ProductCode == "SKU").Sum(i => i.Quantity), "retry preserves all effects " + fixture.GetProperty("name").GetString());
    if (fixture.GetProperty("name").GetString() == "midpoint")
        Check(HorusMoneyFormat.ParseDecimalStrict(result.Rows.Single().UnitPrice) == 1.005m && HorusMoneyFormat.ParseDecimalStrict((await repository.ObterPorIdAsync(company, result.VendaId)).Single().UnitPrice) == 1.005m, "response and fiscal reread preserve unit-price precision");
    if (fixture.GetProperty("name").GetString() == "legado")
        Check(result.Payments.Single().CashGiven is null && result.Payments.Single().ChangeAmount is null && await Scalar("SELECT COUNT(*) FROM VendaPagamentos WHERE CashGiven IS NULL AND ChangeAmount IS NULL") == 1, "legacy unknown values survive persistence");
}
await Reset();
var split = Sale(100); split.Payments = [new() { PaymentType = "fiado", Amount = 30 }, new() { PaymentType = "fiado", Amount = 50 }, new() { PaymentType = "pix", Amount = 20 }];
await repository.RegistrarAsync(company, split);
Check(await Scalar("SELECT SUM(Valor) FROM FiadoMovimentos") == 80 && await Scalar("SELECT SaldoDevedor FROM Clientes") == 80 && await Scalar("SELECT COUNT(*) FROM AuditLog WHERE EventType='FiadoDebito'") == 1, "one aggregate debt and atomic audit");
foreach (var amount in new[] { 100m, 40.01m })
{
    await Reset(100, 60); await Reject(() => repository.RegistrarAsync(company, Sale(amount)), $"available40 rejects {amount}");
    Check(await Scalar("SELECT COUNT(*) FROM Vendas") == 0 && await Scalar("SELECT COUNT(*) FROM FiadoMovimentos") == 0 && await Scalar("SELECT SaldoDevedor FROM Clientes") == 60 && await Scalar("SELECT ProductQnt FROM Produtos WHERE ProductCode='SKU'") == 100, "credit rejection has no effects");
}
await Reset(100, 60); await repository.RegistrarAsync(company, Sale(40)); Check(await Scalar("SELECT SaldoDevedor FROM Clientes") == 100, "available40 permits exactly40");
await Reset(0); await Reject(() => repository.RegistrarAsync(company, Sale(1)), "zero blocks fiado");
var mixedZero = Sale(10); mixedZero.Payments = [new() { PaymentType = "pix", Amount = 9 }, new() { PaymentType = "fiado", Amount = 1 }];
await Reject(() => repository.RegistrarAsync(company, mixedZero), "zero blocks mixed fiado");
await repository.RegistrarAsync(company, Sale(10, "pix")); Check(await Scalar("SELECT COUNT(*) FROM Vendas") == 1, "zero does not block non-fiado");
await Reset(500, 440); await Reject(() => repository.RegistrarAsync(company, split), "aggregate80 cannot fit available60");
await Reset(); var noCustomer = Sale(10); noCustomer.CustomerCpf = "-"; await Reject(() => repository.RegistrarAsync(company, noCustomer), "fiado requires customer");
foreach (var mutation in new Action<VendaRequest>[] {
    r => r.TotalAmount = "9,99", r => r.Payments[0].Amount = 9.99m, r => r.Items[0].Quantity = 0,
    r => r.Items[0].Quantity = -1, r => r.Items[0].Desconto = 10.01m, r => r.Items[0].UnitPrice = -1,
    r => r.Payments[0].Amount = -1, r => r.Payments[0].ChangeAmount = 1, r => r.Items[0].ItemTotal = 9.99m })
{
    await Reset(); var invalid = Sale(10, "pix"); mutation(invalid);
    await Reject(() => repository.RegistrarAsync(company, invalid), "invalid sale rejected");
    Check(await Scalar("SELECT COUNT(*) FROM Vendas") == 0 && await Scalar("SELECT COUNT(*) FROM ProcessedEvents") == 0 && await Scalar("SELECT ProductQnt FROM Produtos WHERE ProductCode='SKU'") == 100, "invalid sale atomic");
}
await Reset(); var cash = Sale(8.8m, "dinheiro"); cash.Payments[0].CashGiven = 10; cash.Payments[0].ChangeAmount = 1.19m;
await Reject(() => repository.RegistrarAsync(company, cash), "wrong cash change one cent");
var omittedPrice = JsonSerializer.Deserialize<VendaRequest>("{\"totalAmount\":\"10,00\",\"items\":[{\"productCode\":\"SKU\",\"quantity\":1}]}", jsonOptions)!;
await Reject(() => repository.RegistrarAsync(company, omittedPrice), "omitted price never becomes implicit free item");
await Reset(); var stable = Sale(10); await repository.RegistrarAsync(company, stable); stable.TotalAmount = "11,00";
await Reject(() => repository.RegistrarAsync(company, stable), "identity conflicts on changed payload");
await Reset(100, 60);
var competing = await Task.WhenAll(Enumerable.Range(0, 2).Select(async _ => { try { await repository.RegistrarAsync(company, Sale(30)); return true; } catch (InvalidOperationException) { return false; } }));
Check(competing.Count(ok => ok) == 1 && await Scalar("SELECT SaldoDevedor FROM Clientes") == 90 && await Scalar("SELECT COUNT(*) FROM Vendas") == 1, "concurrent sales cannot exceed remaining credit");
await Reset(); var sameIdentity = Sale(20); var concurrentReplay = await Task.WhenAll(repository.RegistrarAsync(company, sameIdentity), repository.RegistrarAsync(company, sameIdentity));
Check(concurrentReplay.Count(r => r.IsReplay) == 1 && await Scalar("SELECT COUNT(*) FROM Vendas") == 1 && await Scalar("SELECT SUM(Valor) FROM FiadoMovimentos") == 20, "concurrent same event applied once");
await Reset();
await Exec("CREATE TRIGGER RejectTestAudit ON AuditLog INSTEAD OF INSERT AS THROW 51000, 'Fixture injected failure after debt', 1;");
await Reject(() => repository.RegistrarAsync(company, Sale(20)), "failure after debt rolls everything back");
Check(await Scalar("SELECT COUNT(*) FROM Vendas") == 0 && await Scalar("SELECT COUNT(*) FROM ProcessedEvents") == 0 && await Scalar("SELECT COUNT(*) FROM FiadoMovimentos") == 0 && await Scalar("SELECT SaldoDevedor FROM Clientes") == 0 && await Scalar("SELECT ProductQnt FROM Produtos WHERE ProductCode='SKU'") == 100, "audit failure leaves no partial effects");
await Exec("DROP TRIGGER RejectTestAudit;");
await Reset(); await Exec($"INSERT INTO Pedidos (Id, CompanyId) VALUES ('order-test','{company}');");
var orderItems = new List<PedidoItemAD> { new() { ProductCode = "SKU", ProductName = "Frozen", Quantity = .125m, UnitPrice = 10, ItemTotal = 1.25m } };
await Reject(() => repository.RegistrarComPrecosFixosAsync(company, "Cliente", "52998224725", "pix", "Caixa", orderItems, [new() { PaymentType = "pix", Amount = 1.24m }], pedidoId: "order-test"), "order rejects one cent");
Check(await Scalar("SELECT Status FROM Pedidos") == 0 && await Scalar("SELECT COUNT(*) FROM Vendas") == 0, "order failure retains open status");
var orders = await Task.WhenAll(Enumerable.Range(0, 2).Select(_ => repository.RegistrarComPrecosFixosAsync(company, "Cliente", "52998224725", "fiado", "Caixa", orderItems, [new() { PaymentType = "fiado", Amount = .5m }, new() { PaymentType = "fiado", Amount = .75m }], pedidoId: "order-test")));
Check(orders.Count(o => o.IsReplay) == 1 && orders[0].VendaId == orders[1].VendaId && await Scalar("SELECT COUNT(*) FROM Vendas") == 1 && await Scalar("SELECT Status FROM Pedidos") == 1 && await Scalar("SELECT SUM(Valor) FROM FiadoMovimentos") == 1.25m, "order concurrently finalized once with aggregate debt");
await Reset(0); await Exec($"INSERT INTO Pedidos (Id, CompanyId) VALUES ('order-zero','{company}');");
await Reject(() => repository.RegistrarComPrecosFixosAsync(company, "Cliente", "52998224725", "fiado", "Caixa", orderItems, pedidoId: "order-zero"), "order zero limit blocks");
await Reset(100, 60); var acceptedSale = Sale(20); await repository.RegistrarAsync(company, acceptedSale);
var snapshot = await new ClienteAB(factory).CreditSnapshotAsync(company, [acceptedSale.ClientSaleId!, "not-accepted"]);
Check(snapshot.Customers.Single().SaldoDevedor == 80 && snapshot.AcceptedClientSaleIds.SequenceEqual(new[] { acceptedSale.ClientSaleId! }), "snapshot debt excludes already accepted pending identity");
var declared = FiscalPaymentRules.DeclaredAmount(new VendaPagamentoAD { PaymentType = "dinheiro", Amount = 8.8m, CashGiven = 10, ChangeAmount = JsonSerializer.Deserialize<decimal>("1.1999999999999993") });
Check(declared == 10, "cloud accepts cent-normalized JSON artifact");
FiscalPaymentRules.Validate([new() { Tipo = "17", Valor = 50 }, new() { Tipo = "01", Valor = 100 }], 100, 50);
Check(true, "cloud preserves declared gross mixed payment");
RejectRule(() => FiscalPaymentRules.Validate([new() { Tipo = "01", Valor = 10 }], 8.8m, 1.19m), "cloud rejects real one cent");
RejectRule(() => FiscalPaymentRules.DeclaredAmount(new VendaPagamentoAD { PaymentType = "dinheiro", Amount = 8.8m, CashGiven = 10, ChangeAmount = 1.19m }), "declared received rejects real one cent");
var migration = await File.ReadAllTextAsync(Path.Combine(Path.GetDirectoryName(Path.GetDirectoryName(Path.GetDirectoryName(fixturesPath)))!, "API/NETCORE/DataBase/Migrations/49_pagamento_dados_ausentes.sql"));
await Exec("UPDATE VendaPagamentos SET CashGiven = Amount, ChangeAmount = 0; ALTER TABLE VendaPagamentos ALTER COLUMN CashGiven DECIMAL(18,2) NOT NULL; ALTER TABLE VendaPagamentos ALTER COLUMN ChangeAmount DECIMAL(18,2) NOT NULL;");
var historicReceived = await Scalar("SELECT SUM(CashGiven) FROM VendaPagamentos");
await Exec(migration); await Exec(migration);
Check(await Scalar("SELECT SUM(CashGiven) FROM VendaPagamentos") == historicReceived && await Scalar("SELECT COUNT(*) FROM sys.columns WHERE object_id=OBJECT_ID('VendaPagamentos') AND name IN ('CashGiven','ChangeAmount') AND is_nullable=1") == 2, "nullable migration from NOT NULL preserves existing facts and is idempotent");
Console.WriteLine($"{passed} checks passed; isolated SQL database {database} at 127.0.0.1:14339. No production configuration loaded.");
