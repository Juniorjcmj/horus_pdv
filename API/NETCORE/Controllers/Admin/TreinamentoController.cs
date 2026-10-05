/**
 * Arquivo: API/NETCORE/Controllers/Admin/TreinamentoController.cs
 * Objetivo: endpoints da página de aprendizado. Qualquer usuário autenticado (inclusive caixa) assiste;
 *           somente o administrador geral da plataforma (empresa-principal) cria/edita/exclui seções e vídeos.
 */
using System.Text.RegularExpressions;
using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Mvc;

namespace HORUSPDV_API.Controllers.Admin;

[ApiController]
[Route("api/treinamento")]
[HorusAuthorizeRoles("administrador", "gerente", "atendente", "caixa")]
public partial class TreinamentoController(TreinamentoAB treinamentoAB) : ControllerBase
{
    [HttpGet]
    public async Task<IActionResult> Listar(CancellationToken cancellationToken)
    {
        if (HttpContext.Items["CurrentUser"] is not AuthenticatedUser user)
        {
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        }

        var secoes = await treinamentoAB.ListarAsync(cancellationToken);
        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = "Treinamentos obtidos com sucesso.",
            Data = new { podeGerenciar = PodeGerenciar(user), secoes }
        });
    }

    [HttpPost("secoes")]
    public Task<IActionResult> SalvarSecao([FromBody] SalvarSecaoRequest request, CancellationToken cancellationToken)
        => SalvarSecaoInternoAsync(null, request, cancellationToken);

    [HttpPut("secoes/{id}")]
    public Task<IActionResult> AtualizarSecao([FromRoute] string id, [FromBody] SalvarSecaoRequest request, CancellationToken cancellationToken)
        => SalvarSecaoInternoAsync(id, request, cancellationToken);

    [HttpDelete("secoes/{id}")]
    public async Task<IActionResult> ExcluirSecao([FromRoute] string id, CancellationToken cancellationToken)
    {
        if (Negar() is { } negado) return negado;
        var ok = await treinamentoAB.ExcluirSecaoAsync(id, cancellationToken);
        return ok
            ? Ok(new ApiResponse<object> { Success = true, Message = "Seção excluída com sucesso." })
            : NotFound(new ApiResponse<object> { Success = false, Message = "Seção não encontrada." });
    }

    [HttpPost("videos")]
    public Task<IActionResult> SalvarVideo([FromBody] SalvarVideoRequest request, CancellationToken cancellationToken)
        => SalvarVideoInternoAsync(null, request, cancellationToken);

    [HttpPut("videos/{id}")]
    public Task<IActionResult> AtualizarVideo([FromRoute] string id, [FromBody] SalvarVideoRequest request, CancellationToken cancellationToken)
        => SalvarVideoInternoAsync(id, request, cancellationToken);

    [HttpDelete("videos/{id}")]
    public async Task<IActionResult> ExcluirVideo([FromRoute] string id, CancellationToken cancellationToken)
    {
        if (Negar() is { } negado) return negado;
        var ok = await treinamentoAB.ExcluirVideoAsync(id, cancellationToken);
        return ok
            ? Ok(new ApiResponse<object> { Success = true, Message = "Vídeo excluído com sucesso." })
            : NotFound(new ApiResponse<object> { Success = false, Message = "Vídeo não encontrado." });
    }

    private async Task<IActionResult> SalvarSecaoInternoAsync(string? id, SalvarSecaoRequest? request, CancellationToken cancellationToken)
    {
        if (Negar() is { } negado) return negado;
        if (request is null || string.IsNullOrWhiteSpace(request.Nome))
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Informe o nome da seção." });
        }

        var salvoId = await treinamentoAB.SalvarSecaoAsync(
            id, request.Nome.Trim(), request.Descricao, request.Ordem, cancellationToken);
        return string.IsNullOrEmpty(salvoId)
            ? NotFound(new ApiResponse<object> { Success = false, Message = "Seção não encontrada." })
            : Ok(new ApiResponse<object> { Success = true, Message = "Seção salva com sucesso.", Data = new { id = salvoId } });
    }

    private async Task<IActionResult> SalvarVideoInternoAsync(string? id, SalvarVideoRequest? request, CancellationToken cancellationToken)
    {
        if (Negar() is { } negado) return negado;
        if (request is null || string.IsNullOrWhiteSpace(request.Titulo) || string.IsNullOrWhiteSpace(request.SecaoId))
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Informe a seção e o título do vídeo." });
        }

        var youtubeId = ExtrairYoutubeId(request.Url);
        if (youtubeId is null)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Link do YouTube inválido." });
        }

        var salvoId = await treinamentoAB.SalvarVideoAsync(
            id, request.SecaoId, request.Titulo.Trim(), request.Descricao, request.Instrucoes, youtubeId, request.Ordem, cancellationToken);
        return string.IsNullOrEmpty(salvoId)
            ? NotFound(new ApiResponse<object> { Success = false, Message = "Vídeo não encontrado." })
            : Ok(new ApiResponse<object> { Success = true, Message = "Vídeo salvo com sucesso.", Data = new { id = salvoId } });
    }

    private static bool PodeGerenciar(AuthenticatedUser user)
        => string.Equals(user.CompanyId, "empresa-principal", StringComparison.OrdinalIgnoreCase)
           && string.Equals(user.Role, "administrador", StringComparison.OrdinalIgnoreCase);

    /// <summary>Devolve 403 quando o usuário não é o administrador geral; null quando pode editar.</summary>
    private IActionResult? Negar()
    {
        if (HttpContext.Items["CurrentUser"] is not AuthenticatedUser user)
        {
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        }

        return PodeGerenciar(user)
            ? null
            : StatusCode(StatusCodes.Status403Forbidden, new ApiResponse<object>
            {
                Success = false,
                Message = "Somente o administrador geral da plataforma pode editar os treinamentos."
            });
    }

    /// <summary>Aceita o link (youtu.be, watch?v=, embed, shorts) ou o próprio ID de 11 caracteres.</summary>
    private static string? ExtrairYoutubeId(string? valor)
    {
        if (string.IsNullOrWhiteSpace(valor)) return null;
        var texto = valor.Trim();
        if (IdYoutube().IsMatch(texto)) return texto;
        var match = LinkYoutube().Match(texto);
        return match.Success ? match.Groups[1].Value : null;
    }

    [GeneratedRegex("^[A-Za-z0-9_-]{11}$")]
    private static partial Regex IdYoutube();

    [GeneratedRegex(@"(?:youtu\.be/|youtube\.com/(?:watch\?(?:.*&)?v=|embed/|shorts/|live/))([A-Za-z0-9_-]{11})")]
    private static partial Regex LinkYoutube();
}

public class SalvarSecaoRequest
{
    public string Nome { get; set; } = string.Empty;
    public string? Descricao { get; set; }
    public int Ordem { get; set; }
}

public class SalvarVideoRequest
{
    public string SecaoId { get; set; } = string.Empty;
    public string Titulo { get; set; } = string.Empty;
    public string? Descricao { get; set; }
    public string? Instrucoes { get; set; }
    /// <summary>Link do YouTube (ou o ID do vídeo).</summary>
    public string Url { get; set; } = string.Empty;
    public int Ordem { get; set; }
}
