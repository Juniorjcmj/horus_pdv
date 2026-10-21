using System.Text.Json;
using Horus.Credit;
using HorusGateway.Configuration;
using HorusGateway.Data;
using HorusGateway.Models;
using HorusGateway.Services;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using Xunit;

namespace HorusGateway.Tests;

public sealed class GatewayCreditLedgerTests : IDisposable
{
    private sealed class Clock : IClock { public DateTimeOffset UtcNow {get;set;}=DateTimeOffset.UtcNow; }
    private readonly string path=Path.Combine(Path.GetTempPath(),Guid.NewGuid()+".db");
    private readonly Clock clock=new();
    private readonly GatewayDatabase database;
    private readonly GatewayCreditLedger ledger;
    private readonly CreditGrant grant;
    private readonly IOptions<GatewayOptions> options;
    public GatewayCreditLedgerTests()
    {
        options=Options.Create(new GatewayOptions {CompanyId="c",StoreId="s",GatewayId="g",CreditEpoch="e",CreditEnabled=true,OpenRegistration=false,DatabasePath=path});
        database=new(options,NullLogger<GatewayDatabase>.Instance);database.Initialize();
        ledger=Build();grant=new(1,"grant","c","customer","123","s","g","e",10000,clock.UtcNow.AddHours(1),"v1");ledger.Import(grant);
        ledger.ImportState("ACTIVE");
        ledger.ImportPermits([new("operator","t",Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes("permit"))).ToLowerInvariant(),clock.UtcNow.AddHours(1))]);
    }
    private GatewayCreditLedger Build()=>new(database,new GatewayIdentity(options),options,clock,new SqliteEventStore(database,clock,NullLogger<SqliteEventStore>.Instance));
    private CreditReservation Reservation(string id,long cents=7000)=>new(1,id,"event-"+id,grant.GrantId,"customer",cents,"operator","permit");
    private IngestEventRequest Sale(string id,decimal amount=70)=>new(){ CompanyId="c",StoreId="s",TerminalId="t",EventId="event-"+id,EventType="SALE_CREATED",Payload=JsonSerializer.SerializeToElement(new {clientSaleId=id,eventId="event-"+id,customerCpf="123",operatorId="operator",totalAmount="100,00",items=new[]{new{productCode="p",quantity=1,unitPrice=100}},payments=new[]{new{paymentType="fiado",amount},new{paymentType="pix",amount=100-amount}}})};
    [Fact]
    public async Task ConcurrentReservationsCannotOverspendRealSqlite()
    {
        var tasks=Enumerable.Range(0,2).Select(i=>Task.Run(()=>{try {return Build().Reserve("t",Reservation(i.ToString())).State;}catch(InvalidOperationException e){return e.Message;}}));
        var results=await Task.WhenAll(tasks);Assert.Single(results,x=>x=="RESERVED");Assert.Single(results,x=>x=="BUDGET_INSUFFICIENT");
    }
    [Fact]
    public async Task CommitReplayAcrossRestartAndConcurrentRetryHasOneEventAndDoesNotRefill()
    {
        ledger.Reserve("t",Reservation("a"));
        var results=await Task.WhenAll(Task.Run(()=>Build().Commit("t","a",Sale("a"))),Task.Run(()=>Build().Commit("t","a",Sale("a"))));
        Assert.All(results,r=>Assert.Equal("COMMITTED",r.State));
        Assert.Equal("COMMITTED",Build().Query("t","a")!.State);
        using var db=database.OpenConnection();using var cmd=db.CreateCommand();cmd.CommandText="SELECT COUNT(*) FROM GatewayEvents";Assert.Equal(1L,cmd.ExecuteScalar());
        Assert.Equal("COMMITTED",ledger.Cancel("t","a").State);
        Assert.Throws<InvalidOperationException>(()=>ledger.Reserve("t",Reservation("b",3100)));
        await Assert.ThrowsAsync<InvalidOperationException>(()=>ledger.Commit("t","a",Sale("a",69)));
    }
    [Fact]
    public async Task PartialCommitRollsBackAuthorizationAndEvent()
    {
        ledger.Reserve("t",Reservation("a"));
        using(var db=database.OpenConnection())using(var cmd=db.CreateCommand()){cmd.CommandText="CREATE TRIGGER fail_event BEFORE INSERT ON GatewayEvents BEGIN SELECT RAISE(ABORT,'injected'); END;";cmd.ExecuteNonQuery();}
        await Assert.ThrowsAnyAsync<Exception>(()=>ledger.Commit("t","a",Sale("a")));
        Assert.Equal("RESERVED",ledger.Query("t","a")!.State);
        using var verify=database.OpenConnection();using var count=verify.CreateCommand();count.CommandText="SELECT COUNT(*) FROM GatewayEvents";Assert.Equal(0L,count.ExecuteScalar());
    }
    [Fact]
    public async Task CancelCommitRaceHasOneTerminalState()
    {
        ledger.Reserve("t",Reservation("a"));
        var commit=Task.Run(async()=>{try{return (await Build().Commit("t","a",Sale("a"))).State;}catch(InvalidOperationException){return "REJECTED";}});
        var cancel=Task.Run(()=>Build().Cancel("t","a"));await Task.WhenAll(commit,cancel);
        var state=ledger.Query("t","a")!.State;Assert.Contains(state,new[]{"COMMITTED","CANCELLED"});
        using var db=database.OpenConnection();using var cmd=db.CreateCommand();cmd.CommandText="SELECT COUNT(*) FROM GatewayEvents";Assert.Equal(state=="COMMITTED"?1L:0L,cmd.ExecuteScalar());
    }
    [Fact]
    public async Task ExpiryClockScopeAndReservationIdentityFailClosed()
    {
        var r=Reservation("a");ledger.Reserve("t",r);
        Assert.Equal("RESERVED",Build().Reserve("t",r).State);
        Assert.Throws<InvalidOperationException>(()=>ledger.Reserve("t",r with{AmountCents=100}));
        Assert.Null(ledger.Query("other-terminal","a"));
        clock.UtcNow=clock.UtcNow.AddMinutes(3);await Assert.ThrowsAsync<InvalidOperationException>(()=>ledger.Commit("t","a",Sale("a")));
        ledger.Reserve("t",Reservation("b"));clock.UtcNow=clock.UtcNow.AddSeconds(-1);
        Assert.Throws<InvalidOperationException>(()=>ledger.Reserve("t",Reservation("c",100)));
        Assert.Throws<InvalidOperationException>(()=>ledger.Import(grant with{CompanyId="forged"}));
    }
    [Fact]
    public async Task NewPolicyBlocksPendingReservationButHonorsCommittedFacts()
    {
        ledger.Reserve("t",Reservation("a",3000));await ledger.Commit("t","a",Sale("a",30));
        ledger.Reserve("t",Reservation("b",3000));ledger.BlockCustomers(["customer"]);
        Assert.Throws<InvalidOperationException>(()=>ledger.Reserve("t",Reservation("c",100)));
        await Assert.ThrowsAsync<InvalidOperationException>(()=>ledger.Commit("t","b",Sale("b",30)));
        Assert.Equal("COMMITTED",(await ledger.Commit("t","a",Sale("a",30))).State);
        ledger.ImportState("DRAINING");Assert.Equal("CANCELLED",ledger.Query("t","b")!.State);
    }
    public void Dispose(){Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();foreach(var suffix in new[]{"","-wal","-shm"})File.Delete(path+suffix);}
}
