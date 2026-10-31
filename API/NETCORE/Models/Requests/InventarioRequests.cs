/**
 * Arquivo: API/NETCORE/Models/Requests/InventarioRequests.cs
 * Objetivo: corpos das rotas de /api/Inventario (abrir, contar, anular, recontar, aplicar, cancelar).
 */
namespace HORUSPDV_API.Models.Requests;

public class AbrirInventarioRequest
{
    public string? Descricao { get; set; }
    /// <summary>loja | categorias | produtos</summary>
    public string Escopo { get; set; } = "loja";
    public List<string>? CategoriaIds { get; set; }
    public List<string>? ProdutoIds { get; set; }
    public string? Terminal { get; set; }
}

public class ContagemInventarioRequest
{
    public string? ProdutoId { get; set; }
    /// <summary>Código interno ou GTIN (alternativa ao ProdutoId).</summary>
    public string? Codigo { get; set; }
    public decimal Quantidade { get; set; }
    public string? Local { get; set; }
    public string? Terminal { get; set; }
}

public class AnularContagemRequest
{
    public string Motivo { get; set; } = string.Empty;
}

public class RecontarInventarioRequest
{
    public List<string> ProdutoIds { get; set; } = [];
}

public class AplicarInventarioRequest
{
    public string SupervisorId { get; set; } = string.Empty;
    public string SupervisorPassword { get; set; } = string.Empty;
    public string Justificativa { get; set; } = string.Empty;
    public bool ZerarNaoContados { get; set; }
    public string? Terminal { get; set; }
}

public class CancelarInventarioRequest
{
    public string Motivo { get; set; } = string.Empty;
    public string? Terminal { get; set; }
}
