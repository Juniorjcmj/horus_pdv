using System.Text.Json;
using Horus.Credit;
using HORUSPDV_API.Repositories;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Controllers.Gateway;

[ApiController]
[Route("api/gateway-credit")]
public sealed class GatewayCreditController(Connection connection) : ControllerBase
{
    [HttpPut("customer-policy")]
    [HorusAuthorizeRoles("administrador","gerente")]
    public async Task<IActionResult> CustomerPolicy(CustomerPolicyRequest request)
    {
        if(HttpContext.Items["CurrentUser"] is not AuthenticatedUser user)return Unauthorized();
        await using var db=await connection.OpenConnectionAsync();await using var tx=(SqlTransaction)await db.BeginTransactionAsync();
        await using var cmd=new SqlCommand("""
            IF NOT EXISTS(SELECT 1 FROM Clientes WITH(UPDLOCK,HOLDLOCK) WHERE CompanyId=@Company AND Id=@Customer) THROW 51000,'CUSTOMER_UNAVAILABLE',1;
            MERGE CustomerCreditPolicies WITH(HOLDLOCK) t USING(SELECT @Company CompanyId,@Customer CustomerId) s ON s.CompanyId=t.CompanyId AND s.CustomerId=t.CustomerId
            WHEN MATCHED THEN UPDATE SET Blocked=@Blocked WHEN NOT MATCHED THEN INSERT(CompanyId,CustomerId,Blocked) VALUES(@Company,@Customer,@Blocked);
            """,db,tx);
        cmd.Parameters.AddWithValue("@Company",user.CompanyId);cmd.Parameters.AddWithValue("@Customer",request.CustomerId);cmd.Parameters.AddWithValue("@Blocked",request.Blocked);
        await cmd.ExecuteNonQueryAsync();await tx.CommitAsync();return Ok(new{request.Blocked});
    }
    [HttpGet("blocked-customers")]
    public async Task<IActionResult> BlockedCustomers()
    {
        if(HttpContext.Items["CurrentGateway"] is not GatewayTokenValidation auth)return Forbid();
        await using var db=await connection.OpenConnectionAsync();await using var cmd=new SqlCommand("SELECT CustomerId FROM CustomerCreditPolicies WHERE CompanyId=@Company AND Blocked=1",db);cmd.Parameters.AddWithValue("@Company",auth.CompanyId);
        var result=new List<string>();await using var r=await cmd.ExecuteReaderAsync();while(await r.ReadAsync())result.Add(r.GetString(0));return Ok(result);
    }
    [HttpPut("store")]
    [HorusAuthorizeRoles("administrador","gerente")]
    public async Task<IActionResult> Store(StoreRequest request)
    {
        if(HttpContext.Items["CurrentUser"] is not AuthenticatedUser user || HttpContext.Items.ContainsKey("CurrentGateway"))return Unauthorized();
        if(request.State is not ("PREPARING" or "ACTIVE" or "DRAINING" or "DISABLED") || string.IsNullOrWhiteSpace(request.StoreId)||string.IsNullOrWhiteSpace(request.Epoch)||string.IsNullOrWhiteSpace(request.GatewayId))return BadRequest();
        if(request.State=="PREPARING" && (!Uri.TryCreate(request.GatewayUrl,UriKind.Absolute,out var gatewayUrl) || gatewayUrl.Scheme!="https"))return BadRequest(new{error="GATEWAY_HTTPS_URL_REQUIRED"});
        await using var db=await connection.OpenConnectionAsync();await using var tx=(SqlTransaction)await db.BeginTransactionAsync();
        var activationHash=CreditProtocol.Hash(JsonSerializer.SerializeToElement(request));
        if(request.State=="ACTIVE")
        {
            await using var prior=new SqlCommand("SELECT ActivationHash FROM GatewayCreditStores WITH(UPDLOCK,HOLDLOCK) WHERE CompanyId=@Company AND StoreId=@Store AND State='ACTIVE'",db,tx);
            prior.Parameters.AddWithValue("@Company",user.CompanyId);prior.Parameters.AddWithValue("@Store",request.StoreId);
            var previous=await prior.ExecuteScalarAsync() as string;
            if(previous is not null)return previous==activationHash?Ok(new{request.State,isReplay=true}):Conflict(new{error="ACTIVATION_CONFLICT"});
        }
        await using var cmd=new SqlCommand("""
            IF NOT EXISTS(SELECT 1 FROM GatewayTokens WHERE CompanyId=@Company AND StoreId=@Store AND Id=@Token AND RevokedAt IS NULL) THROW 51000,'TOKEN_SCOPE_INVALID',1;
            IF EXISTS(SELECT 1 FROM GatewayCreditGrants WHERE CompanyId=@Company AND StoreId=@Store AND Closed=0 AND (GatewayId<>@Gateway OR Epoch<>@Epoch OR TokenId<>@Token)) THROW 51000,'PREVIOUS_LEDGER_NOT_CLOSED',1;
            IF @State='PREPARING'
            BEGIN
              IF EXISTS(SELECT 1 FROM GatewayCreditStores WHERE CompanyId=@Company AND StoreId=@Store AND State<>'DISABLED') THROW 51000,'DRAIN_BEFORE_REPROVISIONING',1;
              MERGE GatewayCreditStores WITH(HOLDLOCK) t USING(SELECT @Company CompanyId,@Store StoreId) s ON s.CompanyId=t.CompanyId AND s.StoreId=t.StoreId
               WHEN MATCHED THEN UPDATE SET State='PREPARING',GatewayId=@Gateway,Epoch=@Epoch,TokenId=@Token,InventoryJson=@Inventory,GatewayUrl=@Url
               WHEN NOT MATCHED THEN INSERT(CompanyId,StoreId,GatewayId,Epoch,TokenId,State,InventoryJson,GatewayUrl) VALUES(@Company,@Store,@Gateway,@Epoch,@Token,'PREPARING',@Inventory,@Url);
              DELETE FROM GatewayCreditTerminals WHERE CompanyId=@Company AND StoreId=@Store;
              DELETE FROM GatewayCreditBudgets WHERE CompanyId=@Company AND StoreId=@Store;
              INSERT INTO GatewayCreditTerminals(CompanyId,StoreId,TerminalId) SELECT @Company,@Store,value FROM OPENJSON(@Inventory);
            END
            ELSE IF @State='ACTIVE'
            BEGIN
              IF NOT EXISTS(SELECT 1 FROM GatewayCreditStores WITH(UPDLOCK,HOLDLOCK) WHERE CompanyId=@Company AND StoreId=@Store AND State='PREPARING' AND TokenId=@Token AND GatewayId=@Gateway AND Epoch=@Epoch) THROW 51000,'STORE_NOT_PREPARED',1;
              IF NOT EXISTS(SELECT 1 FROM GatewayCreditTerminals WHERE CompanyId=@Company AND StoreId=@Store) OR EXISTS(SELECT 1 FROM GatewayCreditTerminals WHERE CompanyId=@Company AND StoreId=@Store AND (ReadyAt IS NULL OR ReadyAt<DATEADD(minute,-2,SYSDATETIMEOFFSET()) OR ProtocolVersion<>1)) THROW 51000,'TERMINAL_NOT_READY',1;
              IF EXISTS(SELECT 1 FROM Usuarios u WHERE u.CompanyId=@Company AND u.Status='ativo' AND u.Role IN ('caixa','atendente','gerente','administrador') AND NOT EXISTS(SELECT 1 FROM UserCreditStores b WHERE b.CompanyId=u.CompanyId AND b.UserId=u.Id)) THROW 51000,'OPERATOR_NOT_ENROLLED',1;
              UPDATE GatewayCreditStores SET State='ACTIVE',ActivationHash=@ActivationHash WHERE CompanyId=@Company AND StoreId=@Store;
            END
            ELSE IF @State='DRAINING'
              UPDATE GatewayCreditStores SET State='DRAINING' WHERE CompanyId=@Company AND StoreId=@Store;
            ELSE
            BEGIN
              IF EXISTS(SELECT 1 FROM GatewayCreditGrants WHERE CompanyId=@Company AND StoreId=@Store AND Closed=0) THROW 51000,'GRANTS_NOT_CLOSED',1;
              UPDATE GatewayCreditStores SET State='DISABLED' WHERE CompanyId=@Company AND StoreId=@Store;
            END
            """,db,tx);
        if(request.State=="PREPARING" && (request.TerminalIds.Length==0 || request.TerminalIds.Any(string.IsNullOrWhiteSpace) || request.TerminalIds.Distinct().Count()!=request.TerminalIds.Length))return BadRequest();
        foreach(var (key,value) in new (string,object)[]{("Company",user.CompanyId),("Store",request.StoreId),("Token",request.TokenId),("Gateway",request.GatewayId),("Epoch",request.Epoch),("State",request.State),("Inventory",JsonSerializer.Serialize(request.TerminalIds))})cmd.Parameters.AddWithValue("@"+key,value);
        cmd.Parameters.AddWithValue("@ActivationHash",activationHash);
        cmd.Parameters.AddWithValue("@Url",(object?)request.GatewayUrl??DBNull.Value);
        try
        {
            await cmd.ExecuteNonQueryAsync();
            if(request.State=="ACTIVE")
            {
                if(request.InitialBudgets.Length==0)return Conflict(new{error="INITIAL_BUDGET_REQUIRED"});
                foreach(var budget in request.InitialBudgets.OrderBy(x=>x.CustomerId,StringComparer.Ordinal))
                {
                    if(budget.AmountCents<=0)return BadRequest();
                    await using var b=new SqlCommand("INSERT INTO GatewayCreditBudgets(CompanyId,StoreId,CustomerId,MaximumCents) VALUES(@Company,@Store,@Customer,@Amount)",db,tx);
                    b.Parameters.AddWithValue("@Company",user.CompanyId);b.Parameters.AddWithValue("@Store",request.StoreId);b.Parameters.AddWithValue("@Customer",budget.CustomerId);b.Parameters.AddWithValue("@Amount",budget.AmountCents);await b.ExecuteNonQueryAsync();
                    await GatewayCreditAB.GrantAsync(db,tx,user.CompanyId,request.TokenId,request.StoreId,request.GatewayId,request.Epoch,budget.CustomerId,budget.OperationId,budget.AmountCents,budget.ExpiresAt);
                }
            }
            await tx.CommitAsync();return Ok(new{request.State});
        }
        catch(SqlException e)when(e.Number==51000){return Conflict(new{error=e.Message});}
        catch(InvalidOperationException e){return Conflict(new{error=e.Message});}
    }
    [HttpGet("state")]
    public async Task<IActionResult> State()
    {
        if(HttpContext.Items["CurrentGateway"] is not GatewayTokenValidation auth)return Forbid();
        await using var db=await connection.OpenConnectionAsync();await using var cmd=new SqlCommand("SELECT State FROM GatewayCreditStores WHERE CompanyId=@Company AND StoreId=@Store AND TokenId=@Token",db);
        cmd.Parameters.AddWithValue("@Company",auth.CompanyId);cmd.Parameters.AddWithValue("@Store",(object?)auth.StoreId??DBNull.Value);cmd.Parameters.AddWithValue("@Token",auth.TokenId);
        return Ok(new{state=await cmd.ExecuteScalarAsync() as string??"DISABLED"});
    }
    [HttpPost("readiness")]
    public async Task<IActionResult> Readiness(ReadinessRequest request)
    {
        if(HttpContext.Items["CurrentGateway"] is not GatewayTokenValidation auth)return Forbid();
        await using var db=await connection.OpenConnectionAsync();await using var cmd=new SqlCommand("UPDATE GatewayCreditTerminals SET ReadyAt=CASE WHEN @Pending=0 AND @Version=1 THEN SYSDATETIMEOFFSET() ELSE NULL END,ProtocolVersion=@Version WHERE CompanyId=@Company AND StoreId=@Store AND TerminalId=@Terminal",db);
        foreach(var (key,value) in new (string,object)[]{("Company",auth.CompanyId),("Store",auth.StoreId??""),("Terminal",request.TerminalId),("Pending",request.PendingLegacy),("Version",request.Version)})cmd.Parameters.AddWithValue("@"+key,value);
        return await cmd.ExecuteNonQueryAsync()==1?Ok():NotFound();
    }
    [HttpGet("policy")]
    public async Task<IActionResult> Policy()
    {
        if(HttpContext.Items["CurrentUser"] is not AuthenticatedUser user)return Unauthorized();
        await using var db=await connection.OpenConnectionAsync();
        await using var cmd=new SqlCommand("SELECT State FROM GatewayCreditStores WHERE CompanyId=@Company AND (StoreId=@Store OR @Store IS NULL) AND State<>'DISABLED'",db);
        cmd.Parameters.AddWithValue("@Company",user.CompanyId);cmd.Parameters.AddWithValue("@Store",(object?)user.CreditStoreId??DBNull.Value);
        var state=await cmd.ExecuteScalarAsync() as string;
        return Ok(new {success=true,data=new{companyId=user.CompanyId,storeId=user.CreditStoreId,required=state is not null,state=state??"DISABLED",version=1}});
    }
    [HttpPost("operator-permit")]
    [HorusAuthorizeRoles("administrador","gerente","caixa","atendente")]
    public async Task<IActionResult> Permit(PermitRequest request)
    {
        if(HttpContext.Items["CurrentUser"] is not AuthenticatedUser user || string.IsNullOrWhiteSpace(user.CreditStoreId))return Unauthorized();
        await using var db=await connection.OpenConnectionAsync();
        var permit=Convert.ToHexString(System.Security.Cryptography.RandomNumberGenerator.GetBytes(32));
        var hash=Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(permit))).ToLowerInvariant();
        var expiry=DateTimeOffset.UtcNow.AddHours(8);
        await using var cmd=new SqlCommand("INSERT INTO GatewayCreditOperatorPermits SELECT @Company,@Store,@Operator,@Terminal,@Hash,@Expires WHERE EXISTS(SELECT 1 FROM GatewayCreditTerminals WHERE CompanyId=@Company AND StoreId=@Store AND TerminalId=@Terminal)",db);
        foreach(var (key,value) in new (string,object)[]{("Company",user.CompanyId),("Store",user.CreditStoreId),("Operator",user.Id),("Terminal",request.TerminalId),("Hash",hash),("Expires",expiry)})cmd.Parameters.AddWithValue("@"+key,value);
        if(await cmd.ExecuteNonQueryAsync()!=1)return Conflict(new{error="TERMINAL_NOT_ENROLLED"});
        return Ok(new{success=true,data=new{permit,expiresAt=expiry}});
    }
    [HttpGet("operators")]
    public async Task<IActionResult> Operators()
    {
        if(HttpContext.Items["CurrentGateway"] is not GatewayTokenValidation auth)return Forbid();
        await using var db=await connection.OpenConnectionAsync();
        await using var cmd=new SqlCommand("SELECT p.OperatorId,p.TerminalId,p.PermitHash,p.ExpiresAt FROM GatewayCreditOperatorPermits p JOIN UserCreditStores b ON b.CompanyId=p.CompanyId AND b.UserId=p.OperatorId AND b.StoreId=p.StoreId JOIN Usuarios u ON u.Id=p.OperatorId AND u.CompanyId=p.CompanyId WHERE p.CompanyId=@Company AND p.StoreId=@Store AND p.ExpiresAt>SYSDATETIMEOFFSET() AND u.Status='ativo' AND u.Role IN ('administrador','gerente','caixa','atendente')",db);
        cmd.Parameters.AddWithValue("@Company",auth.CompanyId);cmd.Parameters.AddWithValue("@Store",(object?)auth.StoreId??DBNull.Value);
        var rows=new List<CreditOperatorPermit>();await using var r=await cmd.ExecuteReaderAsync();while(await r.ReadAsync())rows.Add(new(r.GetString(0),r.GetString(1),r.GetString(2),r.GetDateTimeOffset(3)));return Ok(rows);
    }
    [HttpPost("grants")]
    public async Task<IActionResult> Grant(GrantRequest request)
    {
        if(request.Version!=1)return BadRequest(new{error="UNSUPPORTED_VERSION"});
        if(HttpContext.Items["CurrentGateway"] is not GatewayTokenValidation auth || string.IsNullOrWhiteSpace(auth.StoreId)) return Forbid();
        await using var db=await connection.OpenConnectionAsync();await using var tx=(SqlTransaction)await db.BeginTransactionAsync();
        try
        {
            await using(var replay=new SqlCommand("SELECT COUNT(*) FROM GatewayCreditGrants WHERE CompanyId=@Company AND OperationId=@Operation AND TokenId=@Token",db,tx))
            {
                replay.Parameters.AddWithValue("@Company",auth.CompanyId);replay.Parameters.AddWithValue("@Operation",request.OperationId);replay.Parameters.AddWithValue("@Token",auth.TokenId);
                if(Convert.ToInt32(await replay.ExecuteScalarAsync())>0)
                {
                    var original=await GatewayCreditAB.GrantAsync(db,tx,auth.CompanyId,auth.TokenId,auth.StoreId,request.GatewayId,request.Epoch,request.CustomerId,request.OperationId,request.AmountCents,request.ExpiresAt);
                    await tx.CommitAsync();return Ok(original);
                }
            }
            // A token cannot allocate arbitrary money: manager must configure a per-customer ceiling
            // and provision the exact installation/epoch before any grant exists.
            await using var policy=new SqlCommand("SELECT MaximumCents FROM GatewayCreditBudgets b JOIN GatewayCreditStores s ON s.CompanyId=b.CompanyId AND s.StoreId=b.StoreId WHERE b.CompanyId=@Company AND b.StoreId=@Store AND b.CustomerId=@Customer AND s.TokenId=@Token AND s.GatewayId=@Gateway AND s.Epoch=@Epoch AND s.State='ACTIVE'",db,tx);
            foreach(var (key,value) in new (string,object)[] {("Company",auth.CompanyId),("Store",auth.StoreId),("Customer",request.CustomerId),("Token",auth.TokenId),("Gateway",request.GatewayId),("Epoch",request.Epoch)})policy.Parameters.AddWithValue("@"+key,value);
            var maximum=await policy.ExecuteScalarAsync();
            if(maximum is null || request.AmountCents>Convert.ToInt64(maximum))return Conflict(new{error="BUDGET_NOT_PROVISIONED"});
            var grant=await GatewayCreditAB.GrantAsync(db,tx,auth.CompanyId,auth.TokenId,auth.StoreId,request.GatewayId,request.Epoch,request.CustomerId,request.OperationId,request.AmountCents,request.ExpiresAt,Convert.ToInt64(maximum));
            await tx.CommitAsync();return Ok(grant);
        }
        catch(InvalidOperationException e){return Conflict(new{error=e.Message});}
    }
    [HttpGet("grants")]
    public async Task<IActionResult> Grants()
    {
        if(HttpContext.Items["CurrentGateway"] is not GatewayTokenValidation auth)return Forbid();
        await using var db=await connection.OpenConnectionAsync();
        await using var cmd=new SqlCommand("SELECT GrantJson FROM GatewayCreditGrants WHERE CompanyId=@Company AND TokenId=@Token AND Closed=0",db);
        cmd.Parameters.AddWithValue("@Company",auth.CompanyId);cmd.Parameters.AddWithValue("@Token",auth.TokenId);
        var rows=new List<CreditGrant>();await using var r=await cmd.ExecuteReaderAsync();while(await r.ReadAsync())rows.Add(JsonSerializer.Deserialize<CreditGrant>(r.GetString(0))!);
        return Ok(rows);
    }
    [HttpPost("checkpoint")]
    public async Task<IActionResult> Close(CloseRequest request)
    {
        if(request.Version!=1)return BadRequest();
        if(HttpContext.Items["CurrentGateway"] is not GatewayTokenValidation auth)return Forbid();
        await using var db=await connection.OpenConnectionAsync();await using var tx=(SqlTransaction)await db.BeginTransactionAsync();
        // Lock customer first, exactly as grants, ordinary debits and reconciliation do.
        await using(var locked=new SqlCommand("SELECT c.Id FROM Clientes c WITH(UPDLOCK,HOLDLOCK) JOIN GatewayCreditGrants g ON c.Id=g.CustomerId AND c.CompanyId=g.CompanyId WHERE g.CompanyId=@Company AND g.GrantId=@Grant AND g.TokenId=@Token",db,tx))
        {locked.Parameters.AddWithValue("@Company",auth.CompanyId);locked.Parameters.AddWithValue("@Grant",request.GrantId);locked.Parameters.AddWithValue("@Token",auth.TokenId);if(await locked.ExecuteScalarAsync() is null)return NotFound();}
        await using var cmd=new SqlCommand("UPDATE GatewayCreditGrants SET Closed=1 WHERE CompanyId=@Company AND GrantId=@Grant AND TokenId=@Token AND GatewayId=@Gateway AND Epoch=@Epoch AND ConsumedCents=@Consumed;",db,tx);
        foreach(var (key,value) in new (string,object)[]{("Company",auth.CompanyId),("Grant",request.GrantId),("Token",auth.TokenId),("Gateway",request.GatewayId),("Epoch",request.Epoch),("Consumed",request.ConsumedCents)})cmd.Parameters.AddWithValue("@"+key,value);
        if(await cmd.ExecuteNonQueryAsync()!=1)return Conflict(new{error="CHECKPOINT_NOT_RECONCILED"});
        await tx.CommitAsync();return Ok(new{state="CLOSED",request.GrantId,request.ConsumedCents});
    }
    [HttpPut("operator-store")]
    [HorusAuthorizeRoles("administrador","gerente")]
    public async Task<IActionResult> Bind(BindRequest request)
    {
        if(HttpContext.Items["CurrentUser"] is not AuthenticatedUser user || HttpContext.Items.ContainsKey("CurrentGateway"))return Unauthorized();
        if(string.IsNullOrWhiteSpace(request.StoreId)||string.IsNullOrWhiteSpace(request.UserId))return BadRequest();
        await using var db=await connection.OpenConnectionAsync();
        await using var cmd=new SqlCommand("""
            IF NOT EXISTS(SELECT 1 FROM Usuarios WHERE CompanyId=@Company AND Id=@User) THROW 51000,'USER_UNAVAILABLE',1;
            IF EXISTS(SELECT 1 FROM UserCreditStores b JOIN GatewayCreditGrants g ON b.CompanyId=g.CompanyId AND b.StoreId=g.StoreId WHERE b.CompanyId=@Company AND b.UserId=@User AND b.StoreId<>@Store AND g.Closed=0) THROW 51000,'CLOSE_GRANTS_BEFORE_REBINDING',1;
            MERGE UserCreditStores WITH(HOLDLOCK) t USING(SELECT @Company CompanyId,@User UserId) s ON s.CompanyId=t.CompanyId AND s.UserId=t.UserId WHEN MATCHED THEN UPDATE SET StoreId=@Store WHEN NOT MATCHED THEN INSERT(CompanyId,UserId,StoreId) VALUES(@Company,@User,@Store);
            """,db);
        cmd.Parameters.AddWithValue("@Company",user.CompanyId);cmd.Parameters.AddWithValue("@User",request.UserId);cmd.Parameters.AddWithValue("@Store",request.StoreId);await cmd.ExecuteNonQueryAsync();return Ok();
    }
}
public sealed record GrantRequest(string CustomerId,string GatewayId,string Epoch,string OperationId,long AmountCents,DateTimeOffset ExpiresAt,int Version=1);
public sealed record CloseRequest(string GrantId,string GatewayId,string Epoch,long ConsumedCents,int Version=1);
public sealed record BindRequest(string UserId,string StoreId);
public sealed record PermitRequest(string TerminalId);
public sealed record InitialBudget(string CustomerId,long AmountCents,string OperationId,DateTimeOffset ExpiresAt);
public sealed record StoreRequest(string StoreId,string GatewayId,string Epoch,string TokenId,string State,string[] TerminalIds,InitialBudget[] InitialBudgets,string? GatewayUrl=null);
public sealed record ReadinessRequest(string TerminalId,int Version,int PendingLegacy);
public sealed record CustomerPolicyRequest(string CustomerId,bool Blocked);
