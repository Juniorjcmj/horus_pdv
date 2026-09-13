/**
 * Arquivo: API/NETCORE/Models/Categorias/CategoriaModel.cs
 * Objetivo: representa dados de categoria e departamento com hierarquia
 *           para serialização nas respostas HTTP.
 */
namespace HORUSPDV_API.Models.Categorias;

public class CategoriaModel
{
    public string Id { get; set; } = string.Empty;
    public string Nome { get; set; } = string.Empty;
    public string? CategoriaPaiId { get; set; }
    public string? CategoriaPaiNome { get; set; }
    public int Ordem { get; set; }
    public bool Ativa { get; set; } = true;
    public int QuantidadeProdutos { get; set; }
    public List<CategoriaModel> Subcategorias { get; set; } = [];
}
