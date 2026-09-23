/**
 * Arquivo: API/NETCORE/Controllers/Compras/OrdemCompraController.cs
 * Objetivo: endpoints de criação, listagem, atualização, recebimento e cancelamento de ordens de compra,
 *           além de sugestões de reposição baseadas em estoque mínimo.
 */
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Repositories.DataAccess;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Mvc;

namespace HORUSPDV_API.Controllers.Compras;

[ApiController]
[Route("api/[controller]")]
[HorusAuthorizeRoles("administrador", "gerente")]
public class OrdemCompraController(
    OrdemCompraAB ordemCompraAb,
    FornecedorAB fornecedorAb,
    ProdutoAB produtoAb) : ControllerBase
{
    /// <summary>Cria uma nova ordem de compra.</summary>
    [HttpPost]
    public async Task<IActionResult> Criar([FromBody] CriarOrdemCompraRequest request)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null)
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        if (request.Items.Count == 0)
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Ordem de compra sem itens." });

        // Resolve fornecedor
        var supplierName = "Fornecedor não informado";
        var supplierCnpj = "";
        if (!string.IsNullOrWhiteSpace(request.SupplierId))
        {
            var fornecedor = await fornecedorAb.ObterAsync(currentUser.CompanyId, request.SupplierId);
            if (fornecedor is not null)
            {
                supplierName = fornecedor.FantasyName ?? fornecedor.CompanyName ?? supplierName;
                supplierCnpj = fornecedor.Cnpj ?? "";
            }
        }

        // Resolve nomes dos produtos
        var items = new List<(string ProductCode, string ProductName, decimal Quantity, decimal UnitCost)>();
        foreach (var item in request.Items)
        {
            if (item.Quantity <= 0)
                return BadRequest(new ApiResponse<object> { Success = false, Message = $"Quantidade do item {item.ProductCode} deve ser maior que zero." });

            var produto = await produtoAb.ObterPorCodigoAsync(currentUser.CompanyId, item.ProductCode);
            var productName = produto?.ProductName ?? item.ProductCode;
            items.Add((item.ProductCode, productName, item.Quantity, item.UnitCost));
        }

        try
        {
            var oc = await ordemCompraAb.CriarAsync(
                currentUser.CompanyId,
                request.SupplierId,
                supplierName,
                supplierCnpj,
                currentUser.Id,
                currentUser.Name,
                request.Note,
                request.PrevisaoEntrega,
                request.CondicaoPagamento,
                request.FormaPagamento,
                request.ValorFrete,
                request.ValorDesconto,
                items);

            return StatusCode(StatusCodes.Status201Created, new ApiResponse<object>
            {
                Success = true,
                Message = "Ordem de compra criada com sucesso.",
                Data = new { oc.OrderNumber }
            });
        }
        catch (Exception ex)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = ex.Message });
        }
    }

    /// <summary>Atualiza uma ordem de compra pendente.</summary>
    [HttpPut("{orderNumber}")]
    public async Task<IActionResult> Atualizar(string orderNumber, [FromBody] AtualizarOrdemCompraRequest request)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null)
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        if (request.Items.Count == 0)
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Ordem de compra sem itens." });

        // Resolve fornecedor
        var supplierName = "Fornecedor não informado";
        var supplierCnpj = "";
        if (!string.IsNullOrWhiteSpace(request.SupplierId))
        {
            var fornecedor = await fornecedorAb.ObterAsync(currentUser.CompanyId, request.SupplierId);
            if (fornecedor is not null)
            {
                supplierName = fornecedor.FantasyName ?? fornecedor.CompanyName ?? supplierName;
                supplierCnpj = fornecedor.Cnpj ?? "";
            }
        }

        // Resolve nomes dos produtos
        var items = new List<(string ProductCode, string ProductName, decimal Quantity, decimal UnitCost)>();
        foreach (var item in request.Items)
        {
            if (item.Quantity <= 0)
                return BadRequest(new ApiResponse<object> { Success = false, Message = $"Quantidade do item {item.ProductCode} deve ser maior que zero." });

            var produto = await produtoAb.ObterPorCodigoAsync(currentUser.CompanyId, item.ProductCode);
            var productName = produto?.ProductName ?? item.ProductCode;
            items.Add((item.ProductCode, productName, item.Quantity, item.UnitCost));
        }

        try
        {
            var oc = await ordemCompraAb.AtualizarAsync(
                currentUser.CompanyId,
                orderNumber,
                request.SupplierId,
                supplierName,
                supplierCnpj,
                request.Note,
                request.PrevisaoEntrega,
                request.CondicaoPagamento,
                request.FormaPagamento,
                request.ValorFrete,
                request.ValorDesconto,
                items);

            return Ok(new ApiResponse<OrdemCompraAD>
            {
                Success = true,
                Message = "Ordem de compra atualizada com sucesso.",
                Data = oc
            });
        }
        catch (Exception ex)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = ex.Message });
        }
    }

    /// <summary>Lista ordens de compra, opcionalmente filtradas por status.</summary>
    [HttpGet]
    public async Task<IActionResult> Listar([FromQuery] int? status)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null)
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        var rows = await ordemCompraAb.ListarAsync(currentUser.CompanyId, status);
        return Ok(new ApiResponse<List<OrdemCompraAD>>
        {
            Success = true,
            Message = "Ordens de compra listadas.",
            Data = rows
        });
    }

    /// <summary>Retorna detalhe de uma ordem de compra com seus itens.</summary>
    [HttpGet("{orderNumber}")]
    public async Task<IActionResult> ObterPorNumero(string orderNumber)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null)
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        var oc = await ordemCompraAb.ObterPorNumeroAsync(currentUser.CompanyId, orderNumber);
        if (oc is null)
            return NotFound(new ApiResponse<object> { Success = false, Message = "Ordem de compra não encontrada." });

        return Ok(new ApiResponse<OrdemCompraAD>
        {
            Success = true,
            Message = "Ordem de compra encontrada.",
            Data = oc
        });
    }

    /// <summary>Recebe uma ordem de compra — dá entrada de estoque e atualiza validade por item.</summary>
    [HttpPost("{orderNumber}/receber")]
    public async Task<IActionResult> Receber(string orderNumber, [FromBody] ReceberOrdemCompraRequest request)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null)
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        if (request.Itens.Count == 0)
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Nenhum item para receber." });

        try
        {
            var itens = request.Itens
                .Select(i => (i.ProductCode, i.QuantityReceived, i.DataValidade))
                .ToList();

            var novoStatus = await ordemCompraAb.ReceberAsync(
                currentUser.CompanyId,
                orderNumber,
                currentUser.Id,
                currentUser.Name,
                itens);

            var mensagem = novoStatus switch
            {
                OrdemCompraStatus.Recebido => "Ordem de compra recebida integralmente. Estoque atualizado.",
                OrdemCompraStatus.RecebidoParcial => "Recebimento parcial registrado. Estoque atualizado para os itens recebidos.",
                _ => "Recebimento processado."
            };

            return Ok(new ApiResponse<object>
            {
                Success = true,
                Message = mensagem,
                Data = new { status = (int)novoStatus }
            });
        }
        catch (Exception ex)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = ex.Message });
        }
    }

    /// <summary>Cancela uma ordem de compra pendente.</summary>
    [HttpPost("{orderNumber}/cancelar")]
    public async Task<IActionResult> Cancelar(string orderNumber, [FromBody] CancelarOrdemCompraRequest? request = null)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null)
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        var cancelado = await ordemCompraAb.CancelarAsync(currentUser.CompanyId, orderNumber, request?.MotivoCancelamento);
        if (!cancelado)
            return NotFound(new ApiResponse<object> { Success = false, Message = "Ordem de compra não encontrada ou não está pendente." });

        return Ok(new ApiResponse<object> { Success = true, Message = "Ordem de compra cancelada com sucesso." });
    }

    /// <summary>Retorna produtos abaixo do estoque mínimo para sugestão de reposição.</summary>
    [HttpGet("sugestoes-reposicao")]
    public async Task<IActionResult> SugestoesReposicao()
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null)
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        var sugestoes = await ordemCompraAb.SugerirReposicaoAsync(currentUser.CompanyId);
        return Ok(new ApiResponse<List<SugestaoReposicaoAD>>
        {
            Success = true,
            Message = "Sugestões de reposição.",
            Data = sugestoes
        });
    }

    private AuthenticatedUser? GetCurrentUser()
        => HttpContext.Items["CurrentUser"] as AuthenticatedUser;
}
