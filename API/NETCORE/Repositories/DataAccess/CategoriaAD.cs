/**
 * Arquivo: API/NETCORE/Repositories/DataAccess/CategoriaAD.cs
 * Objetivo: representa estrutura de dados de categoria/departamento hierárquico
 *           retornada pelo banco de dados SQL Server.
 */
namespace HORUSPDV_API.Repositories.DataAccess;

public class CategoriaAD
{
    public string Id { get; set; } = string.Empty;
    public string CompanyId { get; set; } = "empresa-principal";
    public string Nome { get; set; } = string.Empty;
    public string? CategoriaPaiId { get; set; }
    public string? CategoriaPaiNome { get; set; }
    public int Ordem { get; set; }
    public bool Ativa { get; set; } = true;
    public int QuantidadeProdutos { get; set; }
    public List<CategoriaAD> Subcategorias { get; set; } = [];
}
