using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Admin;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Mvc;

namespace HORUSPDV_API.Controllers.Admin;

[ApiController]
[Route("api/Admin/Empresas/backup")]
[HorusAuthorizeRoles("administrador")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class DatabaseBackupController(DatabaseBackupService backups, AuditLogAB audit) : ControllerBase
{
    private AuthenticatedUser? SuperAdmin(out IActionResult? error)
    {
        error = null;
        if (HttpContext.Items["CurrentUser"] is not AuthenticatedUser user)
        { error = Unauthorized(new ApiResponse<object> { Message = "Sessão não encontrada." }); return null; }
        if (!string.Equals(user.CompanyId, "empresa-principal", StringComparison.OrdinalIgnoreCase) ||
            !string.Equals(user.Role, "administrador", StringComparison.OrdinalIgnoreCase))
        { error = StatusCode(403, new ApiResponse<object> { Message = "Backup completo restrito ao Administrador Geral da Plataforma." }); return null; }
        return user;
    }

    [HttpPost]
    public IActionResult Start()
    {
        var user = SuperAdmin(out var error);
        if (user is null) return error!;
        // Exige o tipo enviado pelo aplicativo; POST de formulário de outra origem não inicia backup.
        if (!Request.HasJsonContentType())
            return StatusCode(415, new ApiResponse<object> { Message = "Use o botão de backup no painel administrativo." });
        try
        {
            var job = backups.Start(user.Id, user.Name, HttpContext.Connection.RemoteIpAddress?.ToString());
            return Accepted(new ApiResponse<DatabaseBackupStatus> { Success = true, Message = "Backup iniciado.", Data = job });
        }
        catch (InvalidOperationException exception)
        { return StatusCode(503, new ApiResponse<object> { Message = exception.Message }); }
    }

    [HttpGet("{id:guid}")]
    public IActionResult Status(Guid id)
    {
        var user = SuperAdmin(out var error);
        if (user is null) return error!;
        var state = backups.Get(id, user.Id);
        return state is null ? NotFound(new ApiResponse<object> { Message = "Backup não encontrado ou expirado. Gere uma nova cópia." })
            : Ok(new ApiResponse<DatabaseBackupStatus> { Success = true, Data = state });
    }

    [HttpGet("{id:guid}/arquivo")]
    public async Task<IActionResult> Download(Guid id)
    {
        var user = SuperAdmin(out var error);
        if (user is null) return error!;
        var download = backups.OpenDownload(id, user.Id);
        if (download is null) return NotFound(new ApiResponse<object> { Message = "Backup indisponível. Aguarde a conclusão ou gere uma nova cópia." });
        try
        {
            await audit.RegistrarAsync("empresa-principal", user.Id, user.Name, "BackupBancoDownload",
                "Download do backup completo solicitado.", "DatabaseBackup", id.ToString(), HttpContext.Connection.RemoteIpAddress?.ToString());
            return File(download.Value.Stream, "application/octet-stream", download.Value.FileName, enableRangeProcessing: true);
        }
        catch { await download.Value.Stream.DisposeAsync(); throw; }
    }
}
