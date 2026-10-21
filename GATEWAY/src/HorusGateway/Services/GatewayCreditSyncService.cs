using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using Horus.Credit;
using HorusGateway.Configuration;
using Microsoft.Extensions.Options;

namespace HorusGateway.Services;

// Grants, permissions and policy travel only on this server-authenticated channel.
public sealed class GatewayCreditSyncService(IHttpClientFactory clients,IOptions<GatewayOptions> options,
    GatewayCreditLedger ledger,ILogger<GatewayCreditSyncService> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var config=options.Value;
        var failures=0;
        while(!stoppingToken.IsCancellationRequested)
        {
            try
            {
                if(config.UsesCloudEndpoints && new Uri(config.CloudApiBaseUrl).Scheme=="https")
                {
                    using var client=clients.CreateClient("GatewayCredit");client.BaseAddress=new Uri(config.CloudApiBaseUrl.TrimEnd('/')+"/");
                    client.DefaultRequestHeaders.Authorization=new AuthenticationHeaderValue("Bearer",config.CloudSyncToken);
                    var state=await client.GetFromJsonAsync<JsonElement>("api/gateway-credit/state",stoppingToken);
                    var status=state.GetProperty("state").GetString()!;
                    // Apply a block before receiving grants. Already committed events still reconcile.
                    if(status!="ACTIVE")ledger.ImportState(status);
                    ledger.BlockCustomers(await client.GetFromJsonAsync<string[]>("api/gateway-credit/blocked-customers",stoppingToken)??[]);
                    var grants=await client.GetFromJsonAsync<CreditGrant[]>("api/gateway-credit/grants",stoppingToken)??[];
                    foreach(var grant in grants)ledger.Import(grant);
                    ledger.ImportPermits(await client.GetFromJsonAsync<CreditOperatorPermit[]>("api/gateway-credit/operators",stoppingToken)??[]);
                    ledger.ImportState(status);
                    foreach(var item in ledger.Readiness())
                    { using var response=await client.PostAsJsonAsync("api/gateway-credit/readiness",new{terminalId=item.Terminal,version=item.Version,pendingLegacy=item.Pending},stoppingToken);response.EnsureSuccessStatusCode(); }
                    foreach(var item in ledger.ClosingGrants())
                    { using var response=await client.PostAsJsonAsync("api/gateway-credit/checkpoint",new{grantId=item.GrantId,gatewayId=config.GatewayId,epoch=config.CreditEpoch,consumedCents=item.Consumed},stoppingToken);response.EnsureSuccessStatusCode();ledger.AcknowledgeClose(item.GrantId); }
                    failures=0;
                }
            }
            catch(Exception e)when(!stoppingToken.IsCancellationRequested){failures=Math.Min(5,failures+1);logger.LogWarning(e,"Crédito Gateway: sincronização pendente; orçamento comprometido é preservado.");}
            try{await Task.Delay(TimeSpan.FromSeconds(Math.Min(300,15*Math.Pow(2,failures))),stoppingToken);}catch(OperationCanceledException){break;}
        }
    }
}
