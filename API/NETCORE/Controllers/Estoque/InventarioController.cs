/**
 * Arquivo: API/NETCORE/Controllers/Estoque/InventarioController.cs
 * Objetivo: rotas do inventário de estoque (abrir, contar, anular, recontar, divergências, aplicar, cancelar, trilha).
 *           Abrir/contar/consultar: administrador, gerente, estoquista. Aplicar/cancelar/recontar/trilha: gerência.
 *           Aplicar exige também a senha de um gerente/administrador (como na correção de venda).
 * Entradas esperadas: corpo JSON (Models/Requests/InventarioRequests.cs); usuário da sessão em HttpContext.Items.
 */
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Mvc;

namespace HORUSPDV_API.Controllers.Estoque;

[ApiController]
[Route("api/Inventario")]
public class InventarioController(
    InventarioAB inventarioAB,
    HorusSecurityStore securityStore,
    HorusSecurityOptions securityOptions,
    HORUSPDV_API.Services.Estoque.EstoqueAnaliseCache analiseCache) : ControllerBase
{
    [HttpPost]
    [HorusAuthorizeRoles("administrador", "gerente", "estoquista")]
    public async Task<IActionResult> Abrir([FromBody] AbrirInventarioRequest request)
    {
        var user = GetCurrentUser();
        if (user is null) return Sessao();
        var r = await inventarioAB.AbrirAsync(user.CompanyId, Ator(user, request.Terminal), request.Descricao ?? "",
            request.Escopo, request.CategoriaIds, request.ProdutoIds);
        return Responder(r);
    }

    [HttpGet]
    [HorusAuthorizeRoles("administrador", "gerente", "estoquista")]
    public async Task<IActionResult> Listar([FromQuery] string? status = null)
    {
        var user = GetCurrentUser();
        if (user is null) return Sessao();
        return Ok(new ApiResponse<object> { Success = true, Message = "Inventários.", Data = await inventarioAB.ListarAsync(user.CompanyId, status) });
    }

    [HttpGet("{id}")]
    [HorusAuthorizeRoles("administrador", "gerente", "estoquista")]
    public async Task<IActionResult> Obter(string id)
    {
        var user = GetCurrentUser();
        if (user is null) return Sessao();
        var d = await inventarioAB.ObterAsync(user.CompanyId, id, ComValores(user));
        return d is null ? NaoEncontrado() : Ok(new ApiResponse<object> { Success = true, Message = "Inventário.", Data = d });
    }

    [HttpGet("{id}/itens")]
    [HorusAuthorizeRoles("administrador", "gerente", "estoquista")]
    public async Task<IActionResult> Itens(string id, [FromQuery] string? filtro = null)
    {
        var user = GetCurrentUser();
        if (user is null) return Sessao();
        var itens = await inventarioAB.ItensAsync(user.CompanyId, id, filtro, ComValores(user));
        return itens is null ? NaoEncontrado() : Ok(new ApiResponse<object> { Success = true, Message = "Itens do inventário.", Data = itens });
    }

    /// <summary>Contagens do inventário. Por padrão só as do usuário logado (últimos lançamentos).</summary>
    [HttpGet("{id}/contagens")]
    [HorusAuthorizeRoles("administrador", "gerente", "estoquista")]
    public async Task<IActionResult> Contagens(string id, [FromQuery] bool todas = false, [FromQuery] string? produtoId = null, [FromQuery] int limite = 50)
    {
        var user = GetCurrentUser();
        if (user is null) return Sessao();
        var soDoUsuario = !(todas && HorusRoles.IsGerenteOuAdmin(user.Role));
        var list = await inventarioAB.ContagensAsync(user.CompanyId, id, soDoUsuario ? user.Id : null, produtoId, limite);
        return Ok(new ApiResponse<object> { Success = true, Message = "Contagens.", Data = list });
    }

    [HttpPost("{id}/contagens")]
    [HorusAuthorizeRoles("administrador", "gerente", "estoquista")]
    public async Task<IActionResult> Contar(string id, [FromBody] ContagemInventarioRequest request)
    {
        var user = GetCurrentUser();
        if (user is null) return Sessao();
        var r = await inventarioAB.ContarAsync(user.CompanyId, id, Ator(user, request.Terminal),
            request.ProdutoId, request.Codigo, request.Quantidade, request.Local);
        return Responder(r);
    }

    [HttpPost("{id}/contagens/{contagemId}/anular")]
    [HorusAuthorizeRoles("administrador", "gerente", "estoquista")]
    public async Task<IActionResult> Anular(string id, string contagemId, [FromBody] AnularContagemRequest request)
    {
        var user = GetCurrentUser();
        if (user is null) return Sessao();
        var r = await inventarioAB.AnularContagemAsync(user.CompanyId, id, contagemId, Ator(user, null),
            request.Motivo, apenasPropria: !HorusRoles.IsGerenteOuAdmin(user.Role));
        return Responder(r);
    }

    [HttpPost("{id}/recontar")]
    [HorusAuthorizeRoles("administrador", "gerente")]
    public async Task<IActionResult> Recontar(string id, [FromBody] RecontarInventarioRequest request)
    {
        var user = GetCurrentUser();
        if (user is null) return Sessao();
        return Responder(await inventarioAB.RecontarAsync(user.CompanyId, id, Ator(user, null), request.ProdutoIds));
    }

    [HttpPost("{id}/aplicar")]
    [HorusAuthorizeRoles("administrador", "gerente")]
    public async Task<IActionResult> Aplicar(string id, [FromBody] AplicarInventarioRequest request)
    {
        var user = GetCurrentUser();
        if (user is null) return Sessao();
        var ator = Ator(user, request.Terminal);
        var justificativa = request.Justificativa?.Trim() ?? "";

        if (justificativa.Length < 5)
        {
            const string msg = "Informe uma justificativa de no mínimo 5 caracteres.";
            await inventarioAB.RegistrarNegadaAsync(user.CompanyId, id, ator, request.SupervisorId, msg);
            return BadRequest(new ApiResponse<object> { Success = false, Message = msg, Data = new { codigo = "JUSTIFICATIVA_CURTA" } });
        }

        var (valido, msgSupervisor, supervisor) = securityStore.ValidateSupervisorCredentials(
            request.SupervisorId, request.SupervisorPassword, user.CompanyId);
        if (!valido || supervisor is null)
        {
            await inventarioAB.RegistrarNegadaAsync(user.CompanyId, id, ator, request.SupervisorId, msgSupervisor);
            return StatusCode(StatusCodes.Status403Forbidden, new ApiResponse<object>
            {
                Success = false, Message = msgSupervisor, Data = new { codigo = "SENHA_INVALIDA" },
            });
        }

        var r = await inventarioAB.AplicarAsync(user.CompanyId, id, ator, supervisor.Id, supervisor.Name,
            justificativa, request.ZerarNaoContados);
        if (!r.Sucesso && r.Codigo != "NAO_ENCONTRADO")
            await inventarioAB.RegistrarNegadaAsync(user.CompanyId, id, ator, supervisor.Id, r.Mensagem);
        // a acuracidade e as divergências da análise passam a incluir este inventário já na próxima consulta
        if (r.Sucesso) analiseCache.Invalidar(user.CompanyId);
        return Responder(r);
    }

    [HttpPost("{id}/cancelar")]
    [HorusAuthorizeRoles("administrador", "gerente")]
    public async Task<IActionResult> Cancelar(string id, [FromBody] CancelarInventarioRequest request)
    {
        var user = GetCurrentUser();
        if (user is null) return Sessao();
        return Responder(await inventarioAB.CancelarAsync(user.CompanyId, id, Ator(user, request.Terminal), request.Motivo));
    }

    [HttpGet("{id}/trilha")]
    [HorusAuthorizeRoles("administrador", "gerente")]
    public async Task<IActionResult> Trilha(string id)
    {
        var user = GetCurrentUser();
        if (user is null) return Sessao();
        var t = await inventarioAB.TrilhaAsync(user.CompanyId, id);
        return t is null ? NaoEncontrado() : Ok(new ApiResponse<object> { Success = true, Message = "Trilha do inventário.", Data = t });
    }

    // ------------------------------------------------------------------

    private IActionResult Responder(InventarioResultado r)
    {
        if (r.Sucesso)
            return Ok(new ApiResponse<object> { Success = true, Message = r.Mensagem, Data = r.Dados });

        var body = new ApiResponse<object>
        {
            Success = false,
            Message = r.Mensagem,
            Data = r.Dados is null ? new { codigo = r.Codigo } : new { codigo = r.Codigo, detalhe = r.Dados },
        };
        return r.Codigo switch
        {
            "NAO_ENCONTRADO" or "CODIGO_DESCONHECIDO" => NotFound(body),
            "ESCOPO_SOBREPOSTO" or "INVENTARIO_ENCERRADO" or "JA_APLICADO" or "JA_ANULADA" or "OCUPADO" => Conflict(body),
            "SEM_PERMISSAO" => StatusCode(StatusCodes.Status403Forbidden, body),
            "FORA_DO_ESCOPO" => UnprocessableEntity(body),
            _ => BadRequest(body),
        };
    }

    private IActionResult Sessao() => Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

    private IActionResult NaoEncontrado() =>
        NotFound(new ApiResponse<object> { Success = false, Message = "Inventário não encontrado.", Data = new { codigo = "NAO_ENCONTRADO" } });

    private static bool ComValores(AuthenticatedUser user) => HorusRoles.IsGerenteOuAdmin(user.Role);

    private InventarioAtor Ator(AuthenticatedUser user, string? terminal) => new(
        user.Id, user.Name, HorusClientIpResolver.Resolve(HttpContext, securityOptions),
        string.IsNullOrWhiteSpace(terminal) ? null : terminal.Trim());

    private AuthenticatedUser? GetCurrentUser() => HttpContext.Items["CurrentUser"] as AuthenticatedUser;
}
