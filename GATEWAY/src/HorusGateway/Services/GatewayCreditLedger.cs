using System.Text.Json;
using Horus.Credit;
using HorusGateway.Configuration;
using HorusGateway.Data;
using HorusGateway.Models;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.Options;

namespace HorusGateway.Services;

public sealed class GatewayCreditLedger(GatewayDatabase database, GatewayIdentity identity,
    IOptions<GatewayOptions> options, IClock clock, SqliteEventStore events, CreditInstallationGuard? installationGuard = null)
{
    private readonly GatewayOptions config=options.Value;
    internal void BlockCustomers(IEnumerable<string> customers)
    {using var db=database.OpenConnection();using var tx=db.BeginTransaction();Run(db,tx,"DELETE FROM CreditBlockedCustomers WHERE CompanyId=$c");foreach(var customer in customers)Run(db,tx,"INSERT INTO CreditBlockedCustomers VALUES($c,$customer)",("$customer",customer));tx.Commit();}
    private void CheckCustomer(SqliteConnection db,SqliteTransaction tx,string customer)
    {if(Convert.ToInt64(Scalar(db,tx,"SELECT COUNT(*) FROM CreditBlockedCustomers WHERE CompanyId=$c AND CustomerId=$customer",("$customer",customer)))>0)throw new InvalidOperationException("CUSTOMER_CREDIT_BLOCKED");}
    internal void ImportState(string state)
    {
        using var db=database.OpenConnection();using var tx=db.BeginTransaction();
        Run(db,tx,"UPDATE CreditPolicy SET State=$state WHERE Id=1",("$state",state));
        if(state is "DRAINING" or "DISABLED")
        {
            if(Convert.ToInt64(Scalar(db,tx,"SELECT COUNT(*) FROM CreditGrants WHERE CompanyId=$c AND Closed=0"))>0)installationGuard?.Advance(db,tx);
            Run(db,tx,"UPDATE CreditGrants SET Closed=1 WHERE CompanyId=$c AND Closed=0");
            Run(db,tx,"UPDATE CreditAuthorizations SET State='CANCELLED' WHERE CompanyId=$c AND State='RESERVED'");
        }
        tx.Commit();
    }
    internal IReadOnlyList<(string GrantId,long Consumed)> ClosingGrants()
    {
        using var db=database.OpenConnection();using var tx=db.BeginTransaction();
        using var cmd=Command(db,tx,"""
            SELECT g.GrantId,COALESCE(SUM(a.AmountCents),0) FROM CreditGrants g
            LEFT JOIN CreditAuthorizations a ON a.CompanyId=g.CompanyId AND a.GrantId=g.GrantId AND a.State='COMMITTED'
            WHERE g.CompanyId=$c AND g.Closed=1 AND NOT EXISTS(
              SELECT 1 FROM CreditAuthorizations x JOIN GatewayEvents e ON e.CompanyId=x.CompanyId AND e.EventId=x.SaleEventId
              WHERE x.CompanyId=g.CompanyId AND x.GrantId=g.GrantId AND x.State='COMMITTED' AND e.Status<>'SYNCED_CLOUD')
            GROUP BY g.GrantId
            """);
        using var reader=cmd.ExecuteReader();var result=new List<(string,long)>();while(reader.Read())result.Add((reader.GetString(0),reader.GetInt64(1)));return result;
    }
    internal void AcknowledgeClose(string grant)
    {using var db=database.OpenConnection();using var tx=db.BeginTransaction();Run(db,tx,"UPDATE CreditGrants SET Closed=2 WHERE CompanyId=$c AND GrantId=$g AND Closed=1",("$g",grant));tx.Commit();}
    public void Ready(string terminal,int version,int pending)
    {
        using var db=database.OpenConnection();using var tx=db.BeginTransaction();
        Run(db,tx,"INSERT INTO CreditTerminalReadiness VALUES($c,$terminal,$version,$pending,$now) ON CONFLICT(CompanyId,TerminalId) DO UPDATE SET ProtocolVersion=excluded.ProtocolVersion,PendingLegacy=excluded.PendingLegacy,ReadyAt=excluded.ReadyAt",("$terminal",terminal),("$version",version),("$pending",pending),("$now",clock.UtcNow.ToString("o")));tx.Commit();
    }
    internal IReadOnlyList<(string Terminal,int Version,int Pending)> Readiness()
    {
        using var db=database.OpenConnection();using var tx=db.BeginTransaction();
        using var cmd=Command(db,tx,"SELECT TerminalId,ProtocolVersion,PendingLegacy FROM CreditTerminalReadiness WHERE CompanyId=$c AND ReadyAt>$cutoff",("$cutoff",clock.UtcNow.AddMinutes(-1).ToString("o")));
        var localPending=Convert.ToInt32(Scalar(db,tx,"SELECT COUNT(*) FROM GatewayEvents WHERE CompanyId=$c AND EventType='SALE_CREATED' AND Status<>'SYNCED_CLOUD'"));
        using var reader=cmd.ExecuteReader();var rows=new List<(string,int,int)>();while(reader.Read())rows.Add((reader.GetString(0),reader.GetInt32(1),checked(reader.GetInt32(2)+localPending)));return rows;
    }
    internal void ImportPermits(IEnumerable<CreditOperatorPermit> permits)
    {
        using var db=database.OpenConnection();using var tx=db.BeginTransaction();
        Run(db,tx,"DELETE FROM CreditOperatorPermits WHERE CompanyId=$c");
        foreach(var permit in permits)Run(db,tx,"INSERT INTO CreditOperatorPermits VALUES($c,$op,$terminal,$hash,$expires)",("$op",permit.OperatorId),("$terminal",permit.TerminalId),("$hash",permit.PermitHash),("$expires",permit.ExpiresAt.ToString("o")));
        tx.Commit();
    }
    // Only the cloud client may import a grant obtained over its authenticated HTTPS channel.
    internal void Import(CreditGrant grant)
    {
        if (grant.Version!=1 || grant.CompanyId!=identity.CompanyId || grant.StoreId!=identity.StoreId || grant.GatewayId!=identity.GatewayId || grant.Epoch!=config.CreditEpoch || grant.AmountCents<=0)
            throw new InvalidOperationException("INVALID_GRANT_SCOPE");
        using var db=database.OpenConnection(); using var tx=db.BeginTransaction();
        var json=JsonSerializer.Serialize(grant);
        var existing=Scalar(db,tx,"SELECT GrantJson FROM CreditGrants WHERE CompanyId=$c AND GrantId=$g",("$g",grant.GrantId)) as string;
        if(existing is not null) { if(existing!=json) throw new InvalidOperationException("GRANT_CONFLICT"); return; }
        Run(db,tx,"INSERT INTO CreditGrants(CompanyId,GrantId,CustomerId,Document,StoreId,GatewayId,Epoch,AmountCents,ExpiresAt,GrantJson) VALUES($c,$g,$customer,$doc,$store,$gateway,$epoch,$amount,$expires,$json)",
            ("$g",grant.GrantId),("$customer",grant.CustomerId),("$doc",new string(grant.CustomerDocument.Where(char.IsDigit).ToArray())),("$store",grant.StoreId),("$gateway",grant.GatewayId),("$epoch",grant.Epoch),("$amount",grant.AmountCents),("$expires",grant.ExpiresAt.ToString("o")),("$json",json));
        tx.Commit();
    }

    public CreditDecision Reserve(string terminal, CreditReservation request)
    {
        if(request.Version!=1 || request.AmountCents<=0 || string.IsNullOrWhiteSpace(request.AuthorizationId) || string.IsNullOrWhiteSpace(request.SaleEventId) || string.IsNullOrWhiteSpace(request.OperatorId)) throw new InvalidOperationException("INVALID_RESERVATION");
        using var db=database.OpenConnection(); using var tx=db.BeginTransaction();
        var hash=CreditProtocol.Hash(JsonSerializer.SerializeToElement(new { terminal, request }));
        var previous=Read(db,tx,request.AuthorizationId,terminal);
        if(previous is not null)
        {
            if((string?)Scalar(db,tx,"SELECT RequestHash FROM CreditAuthorizations WHERE CompanyId=$c AND AuthorizationId=$id",("$id",request.AuthorizationId))!=hash) throw new InvalidOperationException("AUTHORIZATION_CONFLICT");
            return previous;
        }
        CheckAvailable(db,tx);
        CheckCustomer(db,tx,request.CustomerId);
        var permitHash=Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(request.OperatorPermit))).ToLowerInvariant();
        var permission=Scalar(db,tx,"SELECT ExpiresAt FROM CreditOperatorPermits WHERE CompanyId=$c AND OperatorId=$op AND TerminalId=$terminal AND PermitHash=$hash",("$op",request.OperatorId),("$terminal",terminal),("$hash",permitHash)) as string;
        if(permission is null || DateTimeOffset.Parse(permission)<=clock.UtcNow)throw new InvalidOperationException("OPERATOR_NOT_AUTHORIZED");
        Run(db,tx,"UPDATE CreditAuthorizations SET State='EXPIRED' WHERE CompanyId=$c AND State='RESERVED' AND ExpiresAt<=$now",("$now",clock.UtcNow.ToString("o")));
        using var grant=Command(db,tx,"SELECT AmountCents,ExpiresAt FROM CreditGrants WHERE CompanyId=$c AND GrantId=$g AND CustomerId=$customer AND Closed=0 AND Epoch=$epoch",("$g",request.GrantId),("$customer",request.CustomerId),("$epoch",config.CreditEpoch));
        long amount; DateTimeOffset grantExpiry;
        using(var reader=grant.ExecuteReader())
        { if(!reader.Read()) throw new InvalidOperationException("BUDGET_UNAVAILABLE"); amount=reader.GetInt64(0); grantExpiry=DateTimeOffset.Parse(reader.GetString(1)); }
        if(grantExpiry<=clock.UtcNow) throw new InvalidOperationException("GRANT_EXPIRED");
        var used=Convert.ToInt64(Scalar(db,tx,"SELECT COALESCE(SUM(AmountCents),0) FROM CreditAuthorizations WHERE CompanyId=$c AND GrantId=$g AND State IN ('RESERVED','COMMITTED')",("$g",request.GrantId)));
        if(request.AmountCents>amount-used) throw new InvalidOperationException("BUDGET_INSUFFICIENT");
        var expires=clock.UtcNow.AddSeconds(Math.Clamp(config.CreditReservationSeconds,15,300));
        if(expires>grantExpiry) expires=grantExpiry;
        Run(db,tx,"INSERT INTO CreditAuthorizations(CompanyId,AuthorizationId,SaleEventId,GrantId,CustomerId,TerminalId,OperatorId,AmountCents,RequestHash,State,ExpiresAt) VALUES($c,$id,$event,$g,$customer,$terminal,$operator,$amount,$hash,'RESERVED',$expires)",
            ("$id",request.AuthorizationId),("$event",request.SaleEventId),("$g",request.GrantId),("$customer",request.CustomerId),("$terminal",terminal),("$operator",request.OperatorId),("$amount",request.AmountCents),("$hash",hash),("$expires",expires.ToString("o")));
        installationGuard?.Advance(db,tx);
        Audit(db,tx,request.AuthorizationId,terminal,"RESERVED",request.AmountCents);
        tx.Commit(); return new("RESERVED",request.AuthorizationId,request.SaleEventId,request.AmountCents,expires);
    }

    public CreditDecision? Query(string terminal,string authorization)
    {
        using var db=database.OpenConnection(); using var tx=db.BeginTransaction();
        Run(db,tx,"UPDATE CreditAuthorizations SET State='EXPIRED' WHERE CompanyId=$c AND AuthorizationId=$id AND TerminalId=$terminal AND State='RESERVED' AND ExpiresAt<=$now",("$id",authorization),("$terminal",terminal),("$now",clock.UtcNow.ToString("o")));
        var result=Read(db,tx,authorization,terminal);tx.Commit();return result;
    }
    public CreditDecision? QuerySale(string terminal,string saleEventId)
    {
        using var db=database.OpenConnection();using var tx=db.BeginTransaction();
        var id=Scalar(db,tx,"SELECT AuthorizationId FROM CreditAuthorizations WHERE CompanyId=$c AND TerminalId=$terminal AND SaleEventId=$event",("$terminal",terminal),("$event",saleEventId)) as string;
        tx.Commit();return id is null?null:Query(terminal,id);
    }
    public object Budget(string document,long requested)
    {
        using var db=database.OpenConnection();using var tx=db.BeginTransaction();CheckAvailable(db,tx);
        using var cmd=Command(db,tx,"""
            SELECT g.GrantId,g.CustomerId,g.AmountCents,
             COALESCE(SUM(CASE WHEN a.State='COMMITTED' THEN a.AmountCents ELSE 0 END),0),
             COALESCE(SUM(CASE WHEN a.State='RESERVED' AND a.ExpiresAt>$now THEN a.AmountCents ELSE 0 END),0),g.ExpiresAt
            FROM CreditGrants g LEFT JOIN CreditAuthorizations a ON a.CompanyId=g.CompanyId AND a.GrantId=g.GrantId
            WHERE g.CompanyId=$c AND g.Document=$doc AND g.Closed=0 AND g.Epoch=$epoch AND g.ExpiresAt>$now
            GROUP BY g.GrantId,g.CustomerId,g.AmountCents,g.ExpiresAt
            """,("$doc",document),("$epoch",config.CreditEpoch),("$now",clock.UtcNow.ToString("o")));
        using var reader=cmd.ExecuteReader();
        while(reader.Read())
        {
            var free=reader.GetInt64(2)-reader.GetInt64(3)-reader.GetInt64(4);
            if(free>=requested)return new{grantId=reader.GetString(0),customerId=reader.GetString(1),freeCents=free,consumedCents=reader.GetInt64(3),reservedCents=reader.GetInt64(4),expiresAt=reader.GetString(5)};
        }
        throw new InvalidOperationException("BUDGET_UNAVAILABLE");
    }
    public object CloudResult(string terminal,string authorization)
    {
        var reservation=Query(terminal,authorization)??throw new InvalidOperationException("AUTHORIZATION_UNKNOWN");
        using var db=database.OpenConnection();using var tx=db.BeginTransaction();
        using var cmd=Command(db,tx,"SELECT Status,CloudSaleNumber,LastError FROM GatewayEvents WHERE CompanyId=$c AND EventId=$event",("$event",reservation.SaleEventId));
        using var reader=cmd.ExecuteReader();if(!reader.Read())return new{cloudSynced=false};
        return new{cloudSynced=reader.GetString(0)==GatewayEventStatus.SyncedCloud,saleNumber=reader.IsDBNull(1)?null:reader.GetString(1),error=reader.IsDBNull(2)?null:reader.GetString(2)};
    }
    public object Summary()
    {
        using var db=database.OpenConnection();using var tx=db.BeginTransaction();
        var state=Scalar(db,tx,"SELECT State FROM CreditPolicy WHERE Id=1") as string;
        using var cmd=Command(db,tx,"""
            SELECT g.GrantId,g.CustomerId,g.AmountCents,g.ExpiresAt,g.Closed,
             COALESCE(SUM(CASE WHEN a.State='COMMITTED' THEN a.AmountCents ELSE 0 END),0),
             COALESCE(SUM(CASE WHEN a.State='RESERVED' AND a.ExpiresAt>$now THEN a.AmountCents ELSE 0 END),0),
             SUM(CASE WHEN a.State='COMMITTED' AND e.Status<>'SYNCED_CLOUD' THEN 1 ELSE 0 END)
            FROM CreditGrants g LEFT JOIN CreditAuthorizations a ON a.CompanyId=g.CompanyId AND a.GrantId=g.GrantId
            LEFT JOIN GatewayEvents e ON e.CompanyId=a.CompanyId AND e.EventId=a.SaleEventId
            WHERE g.CompanyId=$c GROUP BY g.GrantId,g.CustomerId,g.AmountCents,g.ExpiresAt,g.Closed
            """,("$now",clock.UtcNow.ToString("o")));
        using var r=cmd.ExecuteReader();var rows=new List<object>();while(r.Read())rows.Add(new{grantId=r.GetString(0),customerId=r.GetString(1),freeCents=r.GetInt64(2)-r.GetInt64(5)-r.GetInt64(6),expiresAt=r.GetString(3),closed=r.GetInt64(4),consumedCents=r.GetInt64(5),reservedCents=r.GetInt64(6),pendingReconciliation=r.IsDBNull(7)?0:r.GetInt64(7)});
        return new{enabled=config.CreditEnabled,state,grants=rows,lowBudgetAlertCents=config.CreditLowBudgetAlertCents,serverTime=clock.UtcNow};
    }

    public CreditDecision Cancel(string terminal,string authorization)
    {
        using var db=database.OpenConnection(); using var tx=db.BeginTransaction();
        var result=Read(db,tx,authorization,terminal) ?? throw new InvalidOperationException("AUTHORIZATION_UNKNOWN");
        if(result.State=="RESERVED") { Run(db,tx,"UPDATE CreditAuthorizations SET State='CANCELLED' WHERE CompanyId=$c AND AuthorizationId=$id AND State='RESERVED'",("$id",authorization)); result=result with { State="CANCELLED" }; }
        Audit(db,tx,authorization,terminal,result.State,result.AmountCents);
        tx.Commit(); return result;
    }

    public async Task<CreditDecision> Commit(string terminal,string authorization,IngestEventRequest sale)
    {
        if(sale.CompanyId!=identity.CompanyId || sale.StoreId!=identity.StoreId || sale.TerminalId!=terminal || sale.EventType!="SALE_CREATED") throw new InvalidOperationException("INVALID_SALE_SCOPE");
        var saleHash=CreditProtocol.SaleHash(sale.Payload);
        var previous=Query(terminal,authorization) ?? throw new InvalidOperationException("AUTHORIZATION_UNKNOWN");
        if(previous.State=="COMMITTED") { if(previous.SaleHash!=saleHash || previous.SaleEventId!=sale.EventId) throw new InvalidOperationException("SALE_CONFLICT"); return previous; }
        CreditDecision? committed=null;
        var result=await events.AppendWithTransactionAsync(sale,(db,tx)=> {
            var reservation=Read(db,tx,authorization,terminal) ?? throw new InvalidOperationException("AUTHORIZATION_UNKNOWN");
            if(reservation.SaleEventId!=sale.EventId || reservation.State=="COMMITTED" && reservation.SaleHash!=saleHash) throw new InvalidOperationException("SALE_CONFLICT");
            if(reservation.State!="COMMITTED")
            {
                CheckAvailable(db,tx);
                var reservedCustomer=Scalar(db,tx,"SELECT CustomerId FROM CreditAuthorizations WHERE CompanyId=$c AND AuthorizationId=$id",("$id",authorization)) as string;
                CheckCustomer(db,tx,reservedCustomer!);
                if(reservation.State!="RESERVED" || reservation.ExpiresAt<=clock.UtcNow) throw new InvalidOperationException("AUTHORIZATION_NOT_RESERVED");
            }
            using var cmd=Command(db,tx,"SELECT a.GrantId,a.CustomerId,a.OperatorId,g.Document,g.ExpiresAt,g.Closed FROM CreditAuthorizations a JOIN CreditGrants g ON g.CompanyId=a.CompanyId AND g.GrantId=a.GrantId WHERE a.CompanyId=$c AND a.AuthorizationId=$id",("$id",authorization));
            string grantId,customer,oper,document; DateTimeOffset expiry; long closed;
            using(var reader=cmd.ExecuteReader()) { reader.Read(); grantId=reader.GetString(0); customer=reader.GetString(1); oper=reader.GetString(2); document=reader.GetString(3); expiry=DateTimeOffset.Parse(reader.GetString(4)); closed=reader.GetInt64(5); }
            var payload=sale.Payload;
            var amount=payload.GetProperty("payments").EnumerateArray().Where(p=>p.GetProperty("paymentType").GetString()?.Trim().Equals("fiado",StringComparison.OrdinalIgnoreCase)==true).Sum(p=>CreditProtocol.Cents(p.GetProperty("amount").GetDecimal()));
            if(reservation.State!="COMMITTED" && (closed!=0 || expiry<=clock.UtcNow) || amount!=reservation.AmountCents || new string(payload.GetProperty("customerCpf").GetString()!.Where(char.IsDigit).ToArray())!=document || CloudEndpointSyncClient.ReadOperatorId(payload.GetRawText())!=oper)
                throw new InvalidOperationException("SALE_RESERVATION_MISMATCH");
            // The proof is persisted in the exact event transaction, never supplied by the terminal.
            var body=System.Text.Json.Nodes.JsonNode.Parse(payload.GetRawText())!.AsObject();
            body["creditAuthorization"]=JsonSerializer.SerializeToNode(new CreditProof(1,authorization,grantId,identity.GatewayId,config.CreditEpoch,identity.StoreId,customer,amount,saleHash),new JsonSerializerOptions { PropertyNamingPolicy=JsonNamingPolicy.CamelCase });
            sale.Payload=JsonSerializer.SerializeToElement(body);
            Run(db,tx,"UPDATE CreditAuthorizations SET State='COMMITTED',SaleHash=$hash WHERE CompanyId=$c AND AuthorizationId=$id AND State='RESERVED'",("$hash",saleHash),("$id",authorization));
            committed=reservation with { State="COMMITTED",SaleHash=saleHash };
            if(reservation.State!="COMMITTED")Audit(db,tx,authorization,terminal,"COMMITTED",reservation.AmountCents);
        },beforeCommit:(db,tx)=>installationGuard?.Advance(db,tx));
        if(result.Outcome is not (IngestOutcome.Accepted or IngestOutcome.Replay) || committed is null) throw new InvalidOperationException("SALE_EVENT_CONFLICT");
        return committed;
    }

    private void CheckAvailable(SqliteConnection db,SqliteTransaction tx)
    {
        if(!config.CreditEnabled || !identity.IsBound || string.IsNullOrWhiteSpace(config.CreditEpoch) || config.OpenRegistration || !config.RequireTerminalAuth) throw new InvalidOperationException("CREDIT_DISABLED");
        if((string?)Scalar(db,tx,"SELECT State FROM CreditPolicy WHERE Id=1")!="ACTIVE")throw new InvalidOperationException("CREDIT_DISABLED");
        var last=Scalar(db,tx,"SELECT LastUtc FROM CreditClock WHERE Id=1") as string;
        if(last is not null && clock.UtcNow<DateTimeOffset.Parse(last)) throw new InvalidOperationException("CLOCK_REGRESSED");
        Run(db,tx,"INSERT INTO CreditClock(Id,LastUtc) VALUES(1,$now) ON CONFLICT(Id) DO UPDATE SET LastUtc=excluded.LastUtc",("$now",clock.UtcNow.ToString("o")));
    }
    public IReadOnlyList<CreditAuditEntry> AuditLog(int limit=50)
    {
        using var db=database.OpenConnection();using var tx=db.BeginTransaction();
        using var cmd=Command(db,tx,"SELECT Seq,AuthorizationId,TerminalId,State,AmountCents,OccurredAt FROM CreditAudit WHERE CompanyId=$c ORDER BY Seq DESC LIMIT $limit",("$limit",limit));
        using var r=cmd.ExecuteReader();var rows=new List<CreditAuditEntry>();while(r.Read())rows.Add(new(r.GetInt64(0),r.GetString(1),r.GetString(2),r.GetString(3),r.GetInt64(4),r.GetString(5)));return rows;
    }
    private void Audit(SqliteConnection db,SqliteTransaction tx,string authorization,string terminal,string state,long cents)
    {Run(db,tx,"INSERT INTO CreditAudit(CompanyId,AuthorizationId,TerminalId,State,AmountCents,OccurredAt) VALUES($c,$id,$terminal,$state,$cents,$now)",("$id",authorization),("$terminal",terminal),("$state",state),("$cents",cents),("$now",clock.UtcNow.ToString("o")));}
    private CreditDecision? Read(SqliteConnection db,SqliteTransaction tx,string id,string terminal)
    {
        using var cmd=Command(db,tx,"SELECT State,SaleEventId,AmountCents,ExpiresAt,SaleHash FROM CreditAuthorizations WHERE CompanyId=$c AND AuthorizationId=$id AND TerminalId=$terminal",("$id",id),("$terminal",terminal));
        using var r=cmd.ExecuteReader(); return !r.Read()?null:new(r.GetString(0),id,r.GetString(1),r.GetInt64(2),DateTimeOffset.Parse(r.GetString(3)),r.IsDBNull(4)?null:r.GetString(4));
    }
    private SqliteCommand Command(SqliteConnection db,SqliteTransaction tx,string sql,params (string,object)[] args)
    { var cmd=db.CreateCommand();cmd.Transaction=tx;cmd.CommandText=sql;cmd.Parameters.AddWithValue("$c",identity.CompanyId);foreach(var (key,value) in args)cmd.Parameters.AddWithValue(key,value);return cmd; }
    private object? Scalar(SqliteConnection db,SqliteTransaction tx,string sql,params (string,object)[] args) { using var cmd=Command(db,tx,sql,args);return cmd.ExecuteScalar(); }
    private void Run(SqliteConnection db,SqliteTransaction tx,string sql,params (string,object)[] args) { using var cmd=Command(db,tx,sql,args);cmd.ExecuteNonQuery(); }
}
