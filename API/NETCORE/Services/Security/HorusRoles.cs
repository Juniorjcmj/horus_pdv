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
    /// Perfil da frente de caixa (vender, sincronizar catálogo/clientes, registrar cliente rápido,
    /// fiado e abertura/fechamento de caixa). Não acessa relatórios gerenciais ou cadastros administrativos.
    /// </summary>
    public const string Caixa = "caixa";

    /// <summary>
    /// Perfil ADICIONAL (somado ao principal, ver Usuarios.PerfisAdicionais): acesso a tudo de notas fiscais —
    /// consulta (inclusive canceladas), XML, cancelamento, inutilização, contingência e configuração fiscal.
    /// O administrador já tem esse acesso; gerente/atendente só com este perfil marcado.
    /// Use em [HorusAuthorizeRoles(Administrador, Financeiro)].
    /// </summary>
    public const string Financeiro = "financeiro";

    /// <summary>Perfis que podem ser dados como adicionais (lista fechada).</summary>
    public static readonly IReadOnlySet<string> PerfisAdicionaisValidos =
        new HashSet<string>(StringComparer.OrdinalIgnoreCase) { Financeiro };

    public static bool IsGerenteOuAdmin(string? role) =>
        string.Equals(role, Administrador, StringComparison.OrdinalIgnoreCase) ||
        string.Equals(role, Gerente, StringComparison.OrdinalIgnoreCase);

    /// <summary>"financeiro, x" (coluna) → só os perfis válidos, sem repetição, em minúsculas.</summary>
    public static List<string> ParsePerfisAdicionais(string? raw) =>
        NormalizePerfisAdicionais((raw ?? string.Empty).Split(',', StringSplitOptions.RemoveEmptyEntries));

    public static List<string> NormalizePerfisAdicionais(IEnumerable<string>? perfis) =>
        (perfis ?? [])
            .Select(perfil => perfil.Trim().ToLowerInvariant())
            .Where(perfil => PerfisAdicionaisValidos.Contains(perfil))
            .Distinct()
            .OrderBy(perfil => perfil, StringComparer.Ordinal)
            .ToList();

    /// <summary>Acesso a notas fiscais: administrador sempre; os demais só com o perfil Financeiro.</summary>
    public static bool TemAcessoFinanceiro(string? role, IEnumerable<string>? perfisAdicionais) =>
        string.Equals(role, Administrador, StringComparison.OrdinalIgnoreCase) ||
        string.Equals(role, Financeiro, StringComparison.OrdinalIgnoreCase) || // perfil principal antigo
        (perfisAdicionais ?? []).Contains(Financeiro, StringComparer.OrdinalIgnoreCase);
}
