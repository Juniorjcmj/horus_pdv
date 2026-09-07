/**
 * Arquivo: API/NETCORE/Services/Security/HorusRoles.cs
 * Objetivo: centraliza os nomes de papéis (roles) e a checagem de hierarquia usada fora do
 *           HorusAuthorizeRolesAttribute (regras que dependem de "dono do recurso OU gerente").
 * Entradas esperadas: recebe o valor de Usuarios.Role/AuthenticatedUser.Role já carregado.
 */
namespace HORUSPDV_API.Services.Security;

public static class HorusRoles
{
    public const string Administrador = "administrador";
    public const string Gerente = "gerente";
    public const string Atendente = "atendente";

    /// <summary>
    /// Perfil restrito à frente de caixa (vender + finalizar pedido) e à abertura/fechamento de
    /// caixa — sem acesso a cadastros, relatórios, fiscal ou pedidos. Não aparece em nenhum
    /// [HorusAuthorizeRoles]: fica de fora por não estar nas listas de administrador/gerente/
    /// atendente dos controllers restritos (ver CaixaController, que fica aberto a todos).
    /// </summary>
    public const string Caixa = "caixa";

    public static bool IsGerenteOuAdmin(string? role) =>
        string.Equals(role, Administrador, StringComparison.OrdinalIgnoreCase) ||
        string.Equals(role, Gerente, StringComparison.OrdinalIgnoreCase);
}
