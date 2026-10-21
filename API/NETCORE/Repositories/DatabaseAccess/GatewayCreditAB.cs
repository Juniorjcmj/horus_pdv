using System.Text.Json;
using Horus.Credit;
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Services.Shared;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public static class GatewayCreditAB
{
    public static async Task RequireUnblockedAsync(SqlConnection db,SqlTransaction tx,string company,string customer)
    {
        await using var cmd=new SqlCommand("SELECT COUNT(*) FROM CustomerCreditPolicies WHERE CompanyId=@Company AND CustomerId=@Customer AND Blocked=1",db,tx);
        cmd.Parameters.AddWithValue("@Company",company);cmd.Parameters.AddWithValue("@Customer",customer);
        if(Convert.ToInt32(await cmd.ExecuteScalarAsync())>0)throw new InvalidOperationException("CUSTOMER_CREDIT_BLOCKED");
    }
    public static async Task RequireLegacyAsync(SqlConnection db, SqlTransaction tx, string company, string? store = null)
    {
        await using var cmd = new SqlCommand("SELECT COUNT(*) FROM GatewayCreditStores WHERE CompanyId=@Company AND State<>'DISABLED' AND (@Store IS NULL OR StoreId=@Store)",db,tx);
        cmd.Parameters.AddWithValue("@Company",company);
        cmd.Parameters.AddWithValue("@Store",(object?)store ?? DBNull.Value);
        if(Convert.ToInt32(await cmd.ExecuteScalarAsync())>0) throw new InvalidOperationException("GATEWAY_AUTHORIZATION_REQUIRED");
    }
    public static async Task<string?> StoreForUserAsync(SqlConnection db, string company, string user)
    {
        try
        {
            await using var cmd=new SqlCommand("SELECT StoreId FROM UserCreditStores WHERE CompanyId=@Company AND UserId=@User",db);
            cmd.Parameters.AddWithValue("@Company",company);cmd.Parameters.AddWithValue("@User",user);
            return await cmd.ExecuteScalarAsync() as string;
        }
        catch(SqlException ex) when(ex.Number==208) // Invalid object name — table not yet created
        {
            return null;
        }
    }
    // Caller must hold UPDLOCK on the company/customer row for the entire transaction.
    public static async Task<decimal> DelegatedAsync(SqlConnection db, SqlTransaction tx, string company, string customer)
    {
        await using var cmd = new SqlCommand("SELECT COALESCE(SUM(AmountCents-ConsumedCents),0) FROM GatewayCreditGrants WHERE CompanyId=@Company AND CustomerId=@Customer AND Closed=0", db, tx);
        cmd.Parameters.AddWithValue("@Company", company); cmd.Parameters.AddWithValue("@Customer", customer);
        return Convert.ToDecimal(await cmd.ExecuteScalarAsync()) / 100;
    }

    public static async Task<CreditGrant> GrantAsync(SqlConnection db, SqlTransaction tx, string company, string token, string store,
        string gateway, string epoch, string customer, string operation, long cents, DateTimeOffset expires, long? maximumCents = null)
    {
        if (cents <= 0 || string.IsNullOrWhiteSpace(store) || string.IsNullOrWhiteSpace(gateway) || string.IsNullOrWhiteSpace(epoch) || string.IsNullOrWhiteSpace(operation)
            ) throw new InvalidOperationException("INVALID_GRANT");
        await using var locked = new SqlCommand("SELECT LimiteCredito,SaldoDevedor,Document FROM Clientes WITH(UPDLOCK,HOLDLOCK) WHERE CompanyId=@Company AND Id=@Customer", db, tx);
        locked.Parameters.AddWithValue("@Company", company); locked.Parameters.AddWithValue("@Customer", customer);
        decimal limit, debt; string document;
        await using (var reader = await locked.ExecuteReaderAsync())
        { if (!await reader.ReadAsync()) throw new InvalidOperationException("CUSTOMER_UNAVAILABLE"); limit=reader.GetDecimal(0); debt=reader.GetDecimal(1); document=reader.GetString(2); }
        var hash = CreditProtocol.Hash(JsonSerializer.SerializeToElement(new { token, store, gateway, epoch, customer, operation, cents, expires }));
        await using var replay = new SqlCommand("SELECT RequestHash,GrantJson FROM GatewayCreditGrants WHERE CompanyId=@Company AND OperationId=@Operation",db,tx);
        replay.Parameters.AddWithValue("@Company",company); replay.Parameters.AddWithValue("@Operation",operation);
        await using(var reader=await replay.ExecuteReaderAsync())
        { if(await reader.ReadAsync()) { if(reader.GetString(0)!=hash) throw new InvalidOperationException("OPERATION_CONFLICT"); return JsonSerializer.Deserialize<CreditGrant>(reader.GetString(1))!; } }
        if(expires <= DateTimeOffset.UtcNow || expires > DateTimeOffset.UtcNow.AddHours(24)) throw new InvalidOperationException("INVALID_GRANT_EXPIRY");
        await using(var blocked=new SqlCommand("SELECT COUNT(*) FROM CustomerCreditPolicies WHERE CompanyId=@Company AND CustomerId=@Customer AND Blocked=1",db,tx))
        {blocked.Parameters.AddWithValue("@Company",company);blocked.Parameters.AddWithValue("@Customer",customer);if(Convert.ToInt32(await blocked.ExecuteScalarAsync())>0)throw new InvalidOperationException("CUSTOMER_CREDIT_BLOCKED");}
        if(maximumCents.HasValue)
        {
            await using var budget=new SqlCommand("SELECT COALESCE(SUM(AmountCents-ConsumedCents),0) FROM GatewayCreditGrants WHERE CompanyId=@Company AND CustomerId=@Customer AND StoreId=@Store AND Closed=0",db,tx);
            budget.Parameters.AddWithValue("@Company",company);budget.Parameters.AddWithValue("@Customer",customer);budget.Parameters.AddWithValue("@Store",store);
            if(Convert.ToInt64(await budget.ExecuteScalarAsync())+cents>maximumCents.Value)throw new InvalidOperationException("MANAGER_BUDGET_EXCEEDED");
        }
        SaleMoney.Credit(limit, debt + await DelegatedAsync(db,tx,company,customer), cents/100m);
        var grant = new CreditGrant(1,Guid.NewGuid().ToString(),company,customer,document,store,gateway,epoch,cents,expires,"v1");
        await using var insert=new SqlCommand("INSERT INTO GatewayCreditGrants(CompanyId,GrantId,CustomerId,StoreId,GatewayId,Epoch,TokenId,OperationId,RequestHash,AmountCents,ExpiresAt,GrantJson) VALUES(@Company,@Grant,@Customer,@Store,@Gateway,@Epoch,@Token,@Operation,@Hash,@Amount,@Expires,@Json)",db,tx);
        foreach(var pair in new Dictionary<string,object> { ["Company"]=company,["Grant"]=grant.GrantId,["Customer"]=customer,["Store"]=store,["Gateway"]=gateway,["Epoch"]=epoch,["Token"]=token,["Operation"]=operation,["Hash"]=hash,["Amount"]=cents,["Expires"]=expires,["Json"]=JsonSerializer.Serialize(grant) }) insert.Parameters.AddWithValue("@"+pair.Key,pair.Value);
        await insert.ExecuteNonQueryAsync(); return grant;
    }

    public static async Task ConsumeAsync(SqlConnection db, SqlTransaction tx, string company, string customer, decimal amount, VendaRequest sale)
    {
        var proof=sale.CreditAuthorization ?? throw new InvalidOperationException("GATEWAY_AUTHORIZATION_REQUIRED");
        if (sale.AuthenticatedGatewayTokenId is null || sale.AuthenticatedGatewayStoreId != proof.StoreId || proof.CustomerId!=customer || proof.AmountCents!=CreditProtocol.Cents(amount) || proof.Version!=1
            || string.IsNullOrWhiteSpace(sale.EventId) || proof.SaleHash != CreditProtocol.SaleHash(JsonSerializer.SerializeToElement(sale,new JsonSerializerOptions { PropertyNamingPolicy=JsonNamingPolicy.CamelCase }))) throw new InvalidOperationException("INVALID_CREDIT_PROOF");
        await using(var prior=new SqlCommand("SELECT COUNT(*) FROM GatewayCreditConsumptions WHERE CompanyId=@Company AND (AuthorizationId=@Authorization OR EventId=@Event)",db,tx))
        {
            prior.Parameters.AddWithValue("@Company",company);prior.Parameters.AddWithValue("@Authorization",proof.AuthorizationId);prior.Parameters.AddWithValue("@Event",sale.EventId);
            if(Convert.ToInt32(await prior.ExecuteScalarAsync())>0)throw new IdempotencyConflictException(sale.EventId,"Autorização ou evento de crédito já consumido por outra identidade.");
        }
        await using var cmd=new SqlCommand("""
            UPDATE GatewayCreditGrants SET ConsumedCents=ConsumedCents+@Amount
            WHERE CompanyId=@Company AND GrantId=@Grant AND CustomerId=@Customer AND StoreId=@Store
              AND GatewayId=@Gateway AND Epoch=@Epoch AND TokenId=@Token AND Closed=0
              AND ConsumedCents+@Amount<=AmountCents;
            IF @@ROWCOUNT<>1 THROW 51000,'INVALID_CREDIT_GRANT',1;
            INSERT INTO GatewayCreditConsumptions(CompanyId,AuthorizationId,EventId,GrantId,AmountCents,SaleHash)
            VALUES(@Company,@Authorization,@Event,@Grant,@Amount,@Hash);
            """,db,tx);
        foreach(var pair in new Dictionary<string,object> { ["Company"]=company,["Grant"]=proof.GrantId,["Customer"]=customer,["Store"]=proof.StoreId,["Gateway"]=proof.GatewayId,["Epoch"]=proof.Epoch,["Token"]=sale.AuthenticatedGatewayTokenId,["Amount"]=proof.AmountCents,["Authorization"]=proof.AuthorizationId,["Event"]=sale.EventId,["Hash"]=proof.SaleHash }) cmd.Parameters.AddWithValue("@"+pair.Key,pair.Value);
        try{await cmd.ExecuteNonQueryAsync();}catch(SqlException e)when(e.Number==51000){throw new InvalidOperationException("INVALID_CREDIT_GRANT",e);}
    }
}
