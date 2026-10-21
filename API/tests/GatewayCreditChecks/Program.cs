using System.Text.Json;
using Horus.Credit;
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Repositories;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Shared;
using Microsoft.Data.SqlClient;
using Microsoft.Extensions.Logging.Abstractions;

// Deliberately fixed local test endpoint; no application initialization or production config.
const string master="Server=127.0.0.1,14339;Database=master;User ID=sa;Password=HorusIntegrityTestOnly_2026!;TrustServerCertificate=True;Connect Timeout=10";
const string database="HorusGatewayCreditChecks";
await using(var db=new SqlConnection(master)){await db.OpenAsync();await using var cmd=new SqlCommand($"IF DB_ID('{database}') IS NOT NULL BEGIN ALTER DATABASE {database} SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE {database}; END; CREATE DATABASE {database};",db);await cmd.ExecuteNonQueryAsync();}
var sql=master.Replace("Database=master","Database="+database);
async Task Execute(string text){await using var db=new SqlConnection(sql);await db.OpenAsync();await using var cmd=new SqlCommand(text,db);await cmd.ExecuteNonQueryAsync();}
async Task<decimal> Scalar(string text){await using var db=new SqlConnection(sql);await db.OpenAsync();await using var cmd=new SqlCommand(text,db);return Convert.ToDecimal(await cmd.ExecuteScalarAsync());}
await Execute(await File.ReadAllTextAsync(Path.Combine(AppContext.BaseDirectory,"schema.sql")));
await Execute(await File.ReadAllTextAsync(Path.Combine(AppContext.BaseDirectory,"credit.sql")));
await Execute("INSERT INTO Produtos VALUES ('p1','c','SKU','Produto teste',100,1,100,100); INSERT INTO Clientes(Id,CompanyId,CustomerName,Document,LimiteCredito,SaldoDevedor) VALUES('customer','c','Cliente','123',100,0);");
var config=new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string,string?>{["ConnectionStrings:HorusPdv"]=sql}).Build();var connection=new Connection(config);
var sales=new HistoricoVendasAB(connection,new FiadoAB(connection),new AuditLogAB(connection),new LoteAB(connection,NullLogger<LoteAB>.Instance));
var passed=0;void Check(bool test,string name){if(!test)throw new Exception(name);passed++;Console.WriteLine("PASS "+name);}
async Task Reject(Func<Task> action,string name){try{await action();}catch(InvalidOperationException){Check(true,name);return;}throw new Exception("Expected domain rejection "+name);}
var expiry=DateTimeOffset.UtcNow.AddHours(1);
async Task<CreditGrant> Allocate(string operation,string store,long cents=7000){await using var db=new SqlConnection(sql);await db.OpenAsync();await using var tx=(SqlTransaction)await db.BeginTransactionAsync();var result=await GatewayCreditAB.GrantAsync(db,tx,"c","token",store,"gateway-"+store,"epoch","customer",operation,cents,expiry);await tx.CommitAsync();return result;}
var attempts=await Task.WhenAll(Enumerable.Range(0,2).Select(async i=>{try{return await Allocate("op"+i,"store"+i);}catch(InvalidOperationException){return null;}}));
var grant=attempts.Single(x=>x is not null)!;Check(await Scalar("SELECT SUM(AmountCents) FROM GatewayCreditGrants")==7000,"two stores race for one customer credit");
var op=attempts[0] is not null?"op0":"op1";Check((await Allocate(op,grant.StoreId)).GrantId==grant.GrantId,"lost grant response retries same allocation");
await Reject(()=>Allocate(op,grant.StoreId,6900),"operation hash conflict");
VendaRequest Sale(decimal amount)=>new(){ClientSaleId=Guid.NewGuid().ToString(),EventId=Guid.NewGuid().ToString(),CustomerCpf="123",CustomerName="Cliente",TotalAmount=amount.ToString(System.Globalization.CultureInfo.InvariantCulture),PaymentType="fiado",OperatorName="Caixa",ReenvioOffline=true,Items=[new(){ProductCode="SKU",Quantity=1,UnitPrice=amount}],Payments=[new(){PaymentType="fiado",Amount=amount}]};
await Reject(()=>sales.RegistrarAsync("c",Sale(60)),"cloud cannot spend delegated money");
var sale=Sale(70);var hash=CreditProtocol.SaleHash(JsonSerializer.SerializeToElement(sale,new JsonSerializerOptions{PropertyNamingPolicy=JsonNamingPolicy.CamelCase}));
sale.CreditAuthorization=new(1,"authorization",grant.GrantId,grant.GatewayId,"epoch",grant.StoreId,"customer",7000,hash);
await Reject(()=>sales.RegistrarAsync("c",sale),"terminal fabricated proof rejected");
sale.AuthenticatedGatewayTokenId="token";sale.AuthenticatedGatewayStoreId=grant.StoreId;
var recorded=await sales.RegistrarAsync("c",sale);var replay=await sales.RegistrarAsync("c",sale);
Check(recorded.VendaId==replay.VendaId && replay.IsReplay && await Scalar("SELECT COUNT(*) FROM Vendas")==1 && await Scalar("SELECT COUNT(*) FROM FiadoMovimentos")==1 && await Scalar("SELECT COUNT(*) FROM GatewayCreditConsumptions")==1,"one atomic sale debit consumption across retries");
Check(await Scalar("SELECT SaldoDevedor FROM Clientes")==70 && await Scalar("SELECT AmountCents-ConsumedCents FROM GatewayCreditGrants")==0,"consumption exchanges delegation for debt");
await sales.RegistrarAsync("c",Sale(30));await Reject(()=>sales.RegistrarAsync("c",Sale(.01m)),"central invariant preserved after full credit spend");
for(var retry=0;retry<2;retry++)
{await using var db=new SqlConnection(sql);await db.OpenAsync();await using var tx=(SqlTransaction)await db.BeginTransactionAsync();await FiadoAB.ReverseDebitAsync(db,tx,"c","customer",recorded.VendaId,70,"Caixa");await tx.CommitAsync();}
Check(await Scalar("SELECT SaldoDevedor FROM Clientes")==30 && await Scalar("SELECT COUNT(*) FROM FiadoMovimentos WHERE Tipo=2")==1,"cancellation credit depends on original debit and is idempotent");
Check(await Scalar("SELECT ConsumedCents FROM GatewayCreditGrants")==7000,"cancellation does not recycle local consumption");
await using(var beforeSaleDb=new SqlConnection(sql))
{
    await beforeSaleDb.OpenAsync();await using var tx=(SqlTransaction)await beforeSaleDb.BeginTransactionAsync();
    try{await FiadoAB.ReverseDebitAsync(beforeSaleDb,tx,"c","customer","not-yet-reconciled",10,"Caixa");throw new Exception("Cancel credited before debit");}
    catch(SqlException e)when(e.Number==51000 && e.Message.Contains("ORIGINAL_DEBIT_REQUIRED")){Check(true,"cancel before cloud consumption waits for original debit without crediting customer");}
    await tx.RollbackAsync();
}
expiry=DateTimeOffset.UtcNow.AddSeconds(2);var shortGrant=await Allocate("short-lived","short-store",100);
await Task.Delay(2200);Check((await Allocate("short-lived","short-store",100)).GrantId==shortGrant.GrantId,"lost allocation response retries after grant expiry");
var large=Sale(1234.56m);large.TotalAmount="1.234,56";large.Items=[new(){ProductCode="SKU",Quantity=1230,UnitPrice=1.005m,Desconto=1.59m}];large.Payments=[new(){PaymentType="fiado",Amount=234.56m},new(){PaymentType="pix",Amount=1000}];
var largeHash=CreditProtocol.SaleHash(JsonSerializer.SerializeToElement(large,new JsonSerializerOptions{PropertyNamingPolicy=JsonNamingPolicy.CamelCase}));large.TotalAmount="1234.56";
Check(largeHash==CreditProtocol.SaleHash(JsonSerializer.SerializeToElement(large,new JsonSerializerOptions{PropertyNamingPolicy=JsonNamingPolicy.CamelCase})),"canonical protocol parses grouped pt-BR total and preserves fractional price and mixed discount");
large.TotalAmount="invalid";try{CreditProtocol.SaleHash(JsonSerializer.SerializeToElement(large,new JsonSerializerOptions{PropertyNamingPolicy=JsonNamingPolicy.CamelCase}));throw new Exception("Invalid money hashed");}catch(InvalidOperationException){Check(true,"canonical protocol rejects invalid required money");}
await Execute("UPDATE Clientes SET LimiteCredito=0 WHERE CompanyId='c'; INSERT INTO CustomerCreditPolicies VALUES('c','customer',1);");
var guaranteed=Sale(1);guaranteed.CreditAuthorization=new(1,"expired-grant-auth",shortGrant.GrantId,shortGrant.GatewayId,shortGrant.Epoch,shortGrant.StoreId,"customer",100,CreditProtocol.SaleHash(JsonSerializer.SerializeToElement(guaranteed,new JsonSerializerOptions{PropertyNamingPolicy=JsonNamingPolicy.CamelCase})));
guaranteed.AuthenticatedGatewayStoreId=shortGrant.StoreId;guaranteed.AuthenticatedGatewayTokenId="token";await sales.RegistrarAsync("c",guaranteed);
Check(await Scalar("SELECT ConsumedCents FROM GatewayCreditGrants WHERE OperationId='short-lived'")==100,"previously committed grant reconciles after expiry and later central block/zero limit");
await Execute("DELETE FROM CustomerCreditPolicies;UPDATE Clientes SET LimiteCredito=100,SaldoDevedor=0 WHERE CompanyId='c';");expiry=DateTimeOffset.UtcNow.AddHours(1);
var grantRace=Task.Run(async()=>{try{await Allocate("race60","mixed",6000);return true;}catch(InvalidOperationException){return false;}});
var cloudRace=Task.Run(async()=>{try{await sales.RegistrarAsync("c",Sale(60));return true;}catch(InvalidOperationException){return false;}});
var mixedRace=await Task.WhenAll(grantRace,cloudRace);
Check(mixedRace.Count(x=>x)==1 && await Scalar("SELECT SaldoDevedor+(SELECT COALESCE(SUM((AmountCents-ConsumedCents)/100.0),0) FROM GatewayCreditGrants WHERE CompanyId='c' AND Closed=0) FROM Clientes WHERE CompanyId='c'")<=100,"real concurrent grant60 versus cloud60 cannot spend over limit100");
await Execute("UPDATE GatewayCreditGrants SET Closed=1 WHERE OperationId='race60';");
await Execute("ALTER TABLE Pedidos ADD OrderNumber nvarchar(40),CustomerCpf nvarchar(40);");
await Execute("CREATE TABLE PedidoItens(Id nvarchar(80),PedidoId nvarchar(80),ProductCode nvarchar(80),ProductName nvarchar(80),Quantity decimal(18,4),UnitPrice decimal(18,4),ItemTotal decimal(18,2)); INSERT INTO Pedidos(Id,CompanyId,Status,OrderNumber,CustomerCpf) VALUES('frozen','c',0,'42','123'); INSERT INTO PedidoItens VALUES('line','frozen','SKU','Produto teste',1,10,10);");
var orderGrant=await Allocate("order-grant","order-store",1000);var orderSale=Sale(10);orderSale.OrderNumber="42";
orderSale.CreditAuthorization=new(1,"order-auth",orderGrant.GrantId,orderGrant.GatewayId,orderGrant.Epoch,orderGrant.StoreId,"customer",1000,CreditProtocol.SaleHash(JsonSerializer.SerializeToElement(orderSale,new JsonSerializerOptions{PropertyNamingPolicy=JsonNamingPolicy.CamelCase})));
orderSale.AuthenticatedGatewayStoreId=orderGrant.StoreId;orderSale.AuthenticatedGatewayTokenId="token";
var orderResult=await sales.RegistrarAsync("c",orderSale);var orderReplay=await sales.RegistrarAsync("c",orderSale);
Check(orderReplay.IsReplay && orderResult.VendaId==orderReplay.VendaId && await Scalar("SELECT Status FROM Pedidos WHERE Id='frozen'")==1 && await Scalar("SELECT ConsumedCents FROM GatewayCreditGrants WHERE OperationId='order-grant'")==1000,"Gateway-authorized frozen order finalizes order sale debt and grant atomically once");
await CreditHttpChecks.Run(sql,Execute,Check);
Console.WriteLine($"PASS {passed} credit integrity checks (isolated SQL Server)");
