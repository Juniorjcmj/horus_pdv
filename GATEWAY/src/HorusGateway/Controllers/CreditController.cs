using Horus.Credit;
using HorusGateway.Models;
using HorusGateway.Services;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.SignalR;
using HorusGateway.Hubs;

namespace HorusGateway.Controllers;

[ApiController]
[Route("api/gateway/credit")]
public sealed class CreditController(GatewayCreditLedger ledger,ITerminalStore terminals,GatewayIdentity identity,IHubContext<EventsHub> hub,ILogger<CreditController> logger) : ControllerBase
{
    [HttpGet("dashboard")]
    public async Task<IActionResult> Dashboard(CancellationToken ct)
    {if(await Terminal(ct) is null)return Unauthorized();return Ok(ledger.Summary());}
    [HttpGet("audit")]
    public async Task<IActionResult> Audit(CancellationToken ct)
    {if(await Terminal(ct) is null)return Unauthorized();return Ok(ledger.AuditLog());}
    [HttpPost("readiness")]
    public async Task<IActionResult> Ready(ReadinessRequest request,CancellationToken ct)
    {var terminal=await Terminal(ct);if(terminal is null)return Unauthorized();ledger.Ready(terminal,request.Version,request.PendingLegacy);return Ok(new{ready=true});}
    [HttpGet("budget")]
    public async Task<IActionResult> Budget(string document,long amountCents,CancellationToken ct)
    { if(await Terminal(ct) is null)return Unauthorized();try{return Ok(ledger.Budget(document,amountCents));}catch(InvalidOperationException e){return Conflict(new{error=e.Message});} }
    [HttpGet("{authorization}/result")]
    public async Task<IActionResult> Result(string authorization,CancellationToken ct)
    { var terminal=await Terminal(ct);if(terminal is null)return Unauthorized();try{return Ok(ledger.CloudResult(terminal,authorization));}catch(InvalidOperationException e){return Conflict(new{error=e.Message});} }
    private async Task<string?> Terminal(CancellationToken ct)
    {
        var result=await terminals.AuthenticateAsync(Request.Headers["X-Terminal-Id"].FirstOrDefault(),Request.Headers["X-Terminal-Key"].FirstOrDefault(),ct);
        return result.Authenticated && result.Terminal?.StoreId==identity.StoreId ? result.Terminal.TerminalId : null;
    }
    [HttpPost("reserve")]
    public async Task<IActionResult> Reserve(CreditReservation request,CancellationToken ct)
    {
        var terminal=await Terminal(ct);if(terminal is null)return Unauthorized();
        try { return Ok(ledger.Reserve(terminal,request)); } catch(InvalidOperationException e) { return Conflict(new { error=e.Message }); }
        catch(Microsoft.Data.Sqlite.SqliteException e)when(e.SqliteErrorCode==19){return Conflict(new{error="AUTHORIZATION_CONFLICT"});}
    }
    [HttpGet("{authorization}")]
    public async Task<IActionResult> Query(string authorization,CancellationToken ct)
    { var terminal=await Terminal(ct);if(terminal is null)return Unauthorized();var result=ledger.Query(terminal,authorization);return result is null?NotFound():Ok(result); }
    [HttpGet("sales/{saleEventId}")]
    public async Task<IActionResult> QuerySale(string saleEventId,CancellationToken ct)
    {var terminal=await Terminal(ct);if(terminal is null)return Unauthorized();var result=ledger.QuerySale(terminal,saleEventId);return result is null?NotFound():Ok(result);}
    [HttpPost("{authorization}/cancel")]
    public async Task<IActionResult> Cancel(string authorization,CancellationToken ct)
    { var terminal=await Terminal(ct);if(terminal is null)return Unauthorized();try{return Ok(ledger.Cancel(terminal,authorization));}catch(InvalidOperationException e){return Conflict(new{error=e.Message});} }
    [HttpPost("{authorization}/commit")]
    public async Task<IActionResult> Commit(string authorization,IngestEventRequest request,CancellationToken ct)
    {
        var terminal=await Terminal(ct);if(terminal is null)return Unauthorized();
        try
        {
            var result=await ledger.Commit(terminal,authorization,request);
            try{await hub.Clients.Group(EventsHub.GroupFor(identity.CompanyId)).SendAsync("creditUpdated",new{authorizationId=authorization,state=result.State},ct);}catch(Exception e){logger.LogDebug(e,"Broadcast de crédito perdido; decisão durável preservada.");}
            return Ok(result);
        }
        catch(InvalidOperationException e){logger.LogWarning("Crédito recusado: Company={Company} Terminal={Terminal} Authorization={Authorization} Reason={Reason}",identity.CompanyId,terminal,authorization,e.Message);return Conflict(new{error=e.Message});}
    }
}
public sealed record ReadinessRequest(int Version,int PendingLegacy);
