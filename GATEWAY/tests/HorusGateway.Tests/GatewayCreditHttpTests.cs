using System.Net;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Horus.Credit;
using HorusGateway.Services;
using Microsoft.Extensions.DependencyInjection;
using Xunit;

namespace HorusGateway.Tests;
public sealed class GatewayCreditHttpTests
{
    [Fact]
    public async Task HttpCreditRequiresTerminalOperatorAndAtomicCommitAndSupportsDashboard()
    {
        if(!OperatingSystem.IsWindows())return;
        var folder=Path.Combine(Path.GetTempPath(),Guid.NewGuid().ToString());Directory.CreateDirectory(folder);
        var path=Path.Combine(folder,"gateway.db");var witness=Path.Combine(folder,"witness");
        File.WriteAllBytes(witness,ProtectedData.Protect(Encoding.UTF8.GetBytes("empresa-1|store-001|gw_test|test-epoch|0"),null,DataProtectionScope.LocalMachine));
        try
        {
            using var factory=new GatewayAppFactory(path,requireTerminalAuth:true,creditEnabled:true,witness:witness);
            using var client=factory.CreateClient();
            Assert.Equal(HttpStatusCode.Unauthorized,(await client.GetAsync("/api/gateway/credit/dashboard")).StatusCode);
            var registration=await client.PostAsJsonAsync("/api/gateway/register",new{companyId="empresa-1",storeId="store-001",terminalId="t",terminalType="CASH",registrationToken="test-registration-token"});registration.EnsureSuccessStatusCode();
            var terminal=await registration.Content.ReadFromJsonAsync<JsonElement>();client.DefaultRequestHeaders.Add("X-Terminal-Id","t");client.DefaultRequestHeaders.Add("X-Terminal-Key",terminal.GetProperty("apiKey").GetString());
            var ledger=factory.Services.GetRequiredService<GatewayCreditLedger>();ledger.Import(new(1,"grant","empresa-1","customer","123","store-001","gw_test","test-epoch",10000,DateTimeOffset.UtcNow.AddHours(1),"v1"));ledger.ImportState("ACTIVE");
            ledger.ImportPermits([new("operator","t",Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes("permit"))).ToLowerInvariant(),DateTimeOffset.UtcNow.AddHours(1))]);
            var reservation=new CreditReservation(1,"a","sale-event","grant","customer",3000,"operator","forged");
            Assert.Equal(HttpStatusCode.Conflict,(await client.PostAsJsonAsync("/api/gateway/credit/reserve",reservation)).StatusCode);
            reservation=reservation with{OperatorPermit="permit"};(await client.PostAsJsonAsync("/api/gateway/credit/reserve",reservation)).EnsureSuccessStatusCode();
            var sale=new{companyId="empresa-1",storeId="forged-store",terminalId="t",eventId="sale-event",eventType="SALE_CREATED",payload=new{eventId="sale-event",clientSaleId="sale",customerCpf="123",operatorId="operator",totalAmount="100,00",items=new[]{new{productCode="p",quantity=1,unitPrice=100}},payments=new[]{new{paymentType="fiado",amount=30},new{paymentType="pix",amount=70}}}};
            Assert.Equal(HttpStatusCode.Conflict,(await client.PostAsJsonAsync("/api/gateway/credit/a/commit",sale)).StatusCode);
            var correct=new{sale.companyId,storeId="store-001",sale.terminalId,sale.eventId,sale.eventType,sale.payload};
            Assert.Equal(HttpStatusCode.Conflict,(await client.PostAsJsonAsync("/api/gateway/events",correct)).StatusCode);
            (await client.PostAsJsonAsync("/api/gateway/credit/a/commit",correct)).EnsureSuccessStatusCode();
            (await client.PostAsJsonAsync("/api/gateway/credit/a/commit",correct)).EnsureSuccessStatusCode();
            var state=await client.GetFromJsonAsync<JsonElement>("/api/gateway/credit/sales/sale-event");Assert.Equal("COMMITTED",state.GetProperty("state").GetString());
            var cancel=await client.PostAsJsonAsync("/api/gateway/credit/a/cancel",new{});Assert.Equal("COMMITTED",(await cancel.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("state").GetString());
            var dashboard=await client.GetFromJsonAsync<JsonElement>("/api/gateway/credit/dashboard");Assert.Equal(7000,dashboard.GetProperty("grants")[0].GetProperty("freeCents").GetInt64());Assert.Equal(3000,dashboard.GetProperty("grants")[0].GetProperty("consumedCents").GetInt64());
            Assert.Contains("Fiado compartilhado",await client.GetStringAsync("/"));
        }
        finally{Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();foreach(var file in Directory.GetFiles(folder))File.Delete(file);Directory.Delete(folder);}
    }
}
