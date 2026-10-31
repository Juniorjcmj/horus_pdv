namespace HORUSPDV_API.Models.Requests;

public class CorrecaoVendaRequest
{
    /// <summary>Códigos dos produtos a remover da venda.</summary>
    public List<string> ProdutosParaRemover { get; set; } = [];
    /// <summary>Justificativa obrigatória (mín. 5 caracteres).</summary>
    public string Justificativa { get; set; } = "";
    /// <summary>
    /// Opcional: gerente/administrador que autoriza a correção com a senha dele (frente de caixa). Quando
    /// informado, o operador logado não precisa ter perfil financeiro e o gerente fica como responsável.
    /// </summary>
    public string? SupervisorId { get; set; }
    /// <summary>Senha do gerente informado em <see cref="SupervisorId"/>.</summary>
    public string? SupervisorPassword { get; set; }
}
