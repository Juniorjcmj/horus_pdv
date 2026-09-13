/**
 * Arquivo: API/NETCORE/Models/Requests/CategoriaRequest.cs
 * Objetivo: define contratos de entrada para criação, edição e alteração de status de categorias.
 */
namespace HORUSPDV_API.Models.Requests;

public class CategoriaRequest
{
    public string? Id { get; set; }
    public string Nome { get; set; } = string.Empty;
    public string? CategoriaPaiId { get; set; }
    public int Ordem { get; set; }
    public bool Ativa { get; set; } = true;
}

public class CategoriaStatusRequest
{
    public bool Ativa { get; set; }
}
