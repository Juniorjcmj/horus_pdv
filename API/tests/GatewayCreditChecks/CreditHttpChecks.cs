using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using HORUSPDV_API.Controllers.Gateway;
using HORUSPDV_API.Middlewares;
using HORUSPDV_API.Repositories;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Security;

internal static class CreditHttpChecks
{
    internal static async Task Run(string sql,Func<string,Task> execute,Action<bool,string> check)
    {
        await execute("""
            CREATE TABLE Usuarios(Id nvarchar(100) NOT NULL,CompanyId nvarchar(100) NOT NULL,Name nvarchar(100),Email nvarchar(100),Role nvarchar(40),Status nvarchar(20),PerfisAdicionais nvarchar(200));
            CREATE TABLE Sessoes(Id nvarchar(100) NOT NULL);CREATE TABLE Empresas(Id nvarchar(100),Status nvarchar(20));
            INSERT INTO Usuarios VALUES('manager','c','Gerente','manager@test','gerente','ativo',''),('cashier','c','Caixa','cashier@test','caixa','ativo',''),('other','c','Outra loja','other@test','caixa','ativo','');
            INSERT INTO Sessoes VALUES('manager-session'),('cashier-session'),('other-session');
            CREATE TABLE GatewayTokens(Id nvarchar(100) NOT NULL,CompanyId nvarchar(100),StoreId nvarchar(100),Nome nvarchar(120),TokenHash varchar(64),TokenPrefix nvarchar(16),CreatedAt datetimeoffset,CreatedBy nvarchar(100),LastUsedAt datetimeoffset,RevokedAt datetimeoffset,RevokedBy nvarchar(100));
            UPDATE Clientes SET LimiteCredito=100,SaldoDevedor=0 WHERE CompanyId='c';
            """);
        var builder=WebApplication.CreateBuilder(new WebApplicationOptions{EnvironmentName="Production"});
        builder.Configuration.AddInMemoryCollection(new Dictionary<string,string?>{["ConnectionStrings:HorusPdv"]=sql,["Auth:JwtSecret"]="isolated-credit-http-test-secret-only-2026"});
        builder.Logging.ClearProviders();builder.WebHost.UseUrls("http://127.0.0.1:0");
        builder.Services.AddSingleton<Connection>();builder.Services.AddSingleton<HorusSecurityOptions>();builder.Services.AddSingleton<HorusJwtService>();
        builder.Services.AddScoped<HorusSecurityStore>();builder.Services.AddScoped<GatewayTokenAB>();builder.Services.AddScoped<IntegracaoTokenAB>();
        builder.Services.AddScoped<LojaGatewayConfigAB>();
        builder.Services.AddControllers().AddApplicationPart(typeof(GatewayCreditController).Assembly);
        await using var app=builder.Build();app.UseRouting();app.UseMiddleware<HorusAuthMiddleware>();app.MapControllers();await app.StartAsync();
        try
        {
            using var scope=app.Services.CreateScope();var tokens=scope.ServiceProvider.GetRequiredService<GatewayTokenAB>();var jwt=scope.ServiceProvider.GetRequiredService<HorusJwtService>();
            var (token,raw)=await tokens.CreateAsync("c","Test only","pilot","manager");
            using var gateway=new HttpClient{BaseAddress=new Uri(app.Urls.Single())};gateway.DefaultRequestHeaders.Authorization=new AuthenticationHeaderValue("Bearer",raw);
            using var manager=new HttpClient{BaseAddress=gateway.BaseAddress};manager.DefaultRequestHeaders.Authorization=new AuthenticationHeaderValue("Bearer",jwt.CreateToken(new SecurityUserDto{Id="manager",CompanyId="c",Name="Gerente",Role="gerente"},new SecuritySession{Id="manager-session"},"127.0.0.1"));
            using var cashier=new HttpClient{BaseAddress=gateway.BaseAddress};cashier.DefaultRequestHeaders.Authorization=new AuthenticationHeaderValue("Bearer",jwt.CreateToken(new SecurityUserDto{Id="cashier",CompanyId="c",Name="Caixa",Role="caixa"},new SecuritySession{Id="cashier-session"},"127.0.0.1"));
            using var anonymous=new HttpClient{BaseAddress=gateway.BaseAddress};
            check((await anonymous.GetAsync("api/gateway-credit/grants")).StatusCode==HttpStatusCode.Unauthorized,"HTTP anonymous cannot inspect grants");
            check((await cashier.PutAsJsonAsync("api/gateway-credit/operator-store",new{userId="cashier",storeId="pilot"})).StatusCode==HttpStatusCode.Forbidden,"HTTP cashier cannot enroll own store");
            check((await gateway.GetAsync("api/gateway-credit/state")).IsSuccessStatusCode,"HTTP Gateway technical token allowlisted without operator");
            foreach(var id in new[]{"manager","cashier"})check((await manager.PutAsJsonAsync("api/gateway-credit/operator-store",new{userId=id,storeId="pilot"})).IsSuccessStatusCode,"HTTP manager enrolls operator "+id);
            (await manager.PutAsJsonAsync("api/gateway-credit/operator-store",new{userId="other",storeId="branch-b"})).EnsureSuccessStatusCode();
            var (branchToken,_)=await tokens.CreateAsync("c","Branch test","branch-b","manager");
            (await manager.PutAsJsonAsync("api/gateway-credit/store",new{storeId="branch-b",gatewayId="branch-gw",gatewayUrl="https://branch-b.invalid:5443",epoch="branch-epoch",tokenId=branchToken.Id,state="PREPARING",terminalIds=new[]{"b"},initialBudgets=Array.Empty<object>()})).EnsureSuccessStatusCode();
            using var other=new HttpClient{BaseAddress=gateway.BaseAddress};other.DefaultRequestHeaders.Authorization=new AuthenticationHeaderValue("Bearer",jwt.CreateToken(new SecurityUserDto{Id="other",CompanyId="c",Name="Outra loja",Role="caixa"},new SecuritySession{Id="other-session"},"127.0.0.1"));
            var otherConfig=await other.GetFromJsonAsync<JsonElement>("api/gateway-config");check(otherConfig.GetProperty("data").GetProperty("gatewayUrl").GetString()=="https://branch-b.invalid:5443","second operator learns distinct Gateway URL from trusted store binding");
            var expiry=DateTimeOffset.UtcNow.AddHours(1);
            var prepare=new{storeId="pilot",gatewayId="pilot-gw",gatewayUrl="https://pilot-gateway.invalid:5443",epoch="pilot-epoch",tokenId=token.Id,state="PREPARING",terminalIds=new[]{"a","missing"},initialBudgets=Array.Empty<object>()};
            check((await manager.PutAsJsonAsync("api/gateway-credit/store",prepare)).IsSuccessStatusCode,"HTTP prepare opts in without issuing budget");
            var learned=await cashier.GetFromJsonAsync<JsonElement>("api/gateway-config");check(learned.GetProperty("data").GetProperty("gatewayUrl").GetString()==prepare.gatewayUrl,"bound operator learns own store Gateway URL");
            var active=new{prepare.storeId,prepare.gatewayId,prepare.epoch,prepare.tokenId,state="ACTIVE",prepare.terminalIds,initialBudgets=new[]{new{customerId="customer",amountCents=6000,operationId="initial-pilot",expiresAt=expiry}}};
            await gateway.PostAsJsonAsync("api/gateway-credit/readiness",new{terminalId="a",version=1,pendingLegacy=0});
            check((await manager.PutAsJsonAsync("api/gateway-credit/store",active)).StatusCode==HttpStatusCode.Conflict,"HTTP absent terminal prevents activation");
            await gateway.PostAsJsonAsync("api/gateway-credit/readiness",new{terminalId="missing",version=1,pendingLegacy=1});
            check((await manager.PutAsJsonAsync("api/gateway-credit/store",active)).StatusCode==HttpStatusCode.Conflict,"HTTP undrained legacy prevents activation");
            await gateway.PostAsJsonAsync("api/gateway-credit/readiness",new{terminalId="missing",version=0,pendingLegacy=0});
            check((await manager.PutAsJsonAsync("api/gateway-credit/store",active)).StatusCode==HttpStatusCode.Conflict,"HTTP old protocol prevents activation");
            await gateway.PostAsJsonAsync("api/gateway-credit/readiness",new{terminalId="missing",version=1,pendingLegacy=0});
            var activated=await manager.PutAsJsonAsync("api/gateway-credit/store",active);
            check(activated.IsSuccessStatusCode,"HTTP activation provisions initial grant atomically: "+await activated.Content.ReadAsStringAsync());
            check((await manager.PutAsJsonAsync("api/gateway-credit/store",active)).IsSuccessStatusCode,"HTTP lost activation response replays original");
            var sales=new HistoricoVendasAB(new Connection(builder.Configuration),new FiadoAB(new Connection(builder.Configuration)),new AuditLogAB(new Connection(builder.Configuration)),new LoteAB(new Connection(builder.Configuration),Microsoft.Extensions.Logging.Abstractions.NullLogger<LoteAB>.Instance));
            var legacy=new HORUSPDV_API.Models.Requests.VendaRequest{EventId=Guid.NewGuid().ToString(),ClientSaleId=Guid.NewGuid().ToString(),CustomerCpf="123",TotalAmount="20",PaymentType="fiado",ReenvioOffline=true,Items=[new(){ProductCode="SKU",Quantity=1,UnitPrice=20}],Payments=[new(){PaymentType="fiado",Amount=20}]};
            try{await sales.RegistrarAsync("c",legacy);throw new Exception("Unbound bypass accepted");}catch(InvalidOperationException e){check(e.Message=="GATEWAY_AUTHORIZATION_REQUIRED","unbound and old sessions cannot bypass migrated store");}
            legacy.AuthenticatedCreditStoreId="other-store";await sales.RegistrarAsync("c",legacy);check(true,"another trusted store can use non-delegated cloud credit");
            var customer=new HORUSPDV_API.Repositories.DataAccess.ClienteAD{Id="customer",CustomerName="Cliente",Document="123",LimiteCredito=50};
            try{await new ClienteAB(new Connection(builder.Configuration)).SalvarAsync("c",customer);throw new Exception("Unsafe limit reduction accepted");}catch(InvalidOperationException){check(true,"limit reduction respects debt plus delegated credit");}
            check((await manager.PutAsJsonAsync("api/gateway-credit/customer-policy",new{customerId="customer",blocked=true})).IsSuccessStatusCode,"manager blocks new customer credit without releasing escrow");
            var blocks=await gateway.GetFromJsonAsync<string[]>("api/gateway-credit/blocked-customers");check(blocks!.Contains("customer"),"block policy reaches authenticated Gateway snapshot");
            await manager.PutAsJsonAsync("api/gateway-credit/customer-policy",new{customerId="customer",blocked=false});
            var excess=await gateway.PostAsJsonAsync("api/gateway-credit/grants",new{customerId="customer",gatewayId="pilot-gw",epoch="pilot-epoch",operationId="excess",amountCents=4000,expiresAt=expiry});
            check(excess.StatusCode==HttpStatusCode.Conflict && (await excess.Content.ReadAsStringAsync()).Contains("MANAGER_BUDGET_EXCEEDED"),"HTTP new operation cannot multiply manager budget ceiling");
            var policy=await cashier.GetFromJsonAsync<JsonElement>("api/gateway-credit/policy");check(policy.GetProperty("data").GetProperty("required").GetBoolean(),"HTTP policy uses trusted operator binding");
            var permits=await cashier.PostAsJsonAsync("api/gateway-credit/operator-permit",new{terminalId="a",storeId="forged"});check(permits.IsSuccessStatusCode,"HTTP operator permit derives store from binding");
            var snapshot=await gateway.GetFromJsonAsync<JsonElement>("api/gateway-credit/operators");check(snapshot.GetArrayLength()==1 && snapshot[0].GetProperty("operatorId").GetString()=="cashier","HTTP server permission snapshot includes authenticated operator only");
            var grants=await gateway.GetFromJsonAsync<JsonElement>("api/gateway-credit/grants");var grant=grants[0].GetProperty("grantId").GetString();
            var draining=new{prepare.storeId,prepare.gatewayId,prepare.epoch,prepare.tokenId,state="DRAINING",prepare.terminalIds,initialBudgets=Array.Empty<object>()};
            check((await manager.PutAsJsonAsync("api/gateway-credit/store",draining)).IsSuccessStatusCode,"HTTP drain stops new allocations");
            var disabled=new{prepare.storeId,prepare.gatewayId,prepare.epoch,prepare.tokenId,state="DISABLED",prepare.terminalIds,initialBudgets=Array.Empty<object>()};
            check((await manager.PutAsJsonAsync("api/gateway-credit/store",disabled)).StatusCode==HttpStatusCode.Conflict,"HTTP cannot rollback with open grant");
            check((await gateway.PostAsJsonAsync("api/gateway-credit/checkpoint",new{grantId=grant,gatewayId="pilot-gw",epoch="pilot-epoch",consumedCents=1})).StatusCode==HttpStatusCode.Conflict,"HTTP unmatched checkpoint retains escrow");
            for(var i=0;i<2;i++)check((await gateway.PostAsJsonAsync("api/gateway-credit/checkpoint",new{grantId=grant,gatewayId="pilot-gw",epoch="pilot-epoch",consumedCents=0})).IsSuccessStatusCode,"HTTP checkpoint replay "+i);
            check((await manager.PutAsJsonAsync("api/gateway-credit/store",disabled)).IsSuccessStatusCode,"HTTP rollback after grant closure");
        }
        finally{await app.StopAsync();}
    }
}
