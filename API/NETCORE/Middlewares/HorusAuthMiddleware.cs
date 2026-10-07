/**
 * Arquivo: API/NETCORE/Middlewares/HorusAuthMiddleware.cs
 * Objetivo: intercepta requisições HTTP para aplicar regra transversal de autenticação, cadastro, recuperação de senha e sessões.
 * Entradas esperadas: recebe HttpContext do pipeline ASP.NET Core e decide se a requisição segue para o próximo middleware.
 */
using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Security;

namespace HORUSPDV_API.Middlewares;

public class HorusAuthMiddleware(RequestDelegate next)
{
    /// <summary>Header com o id do operador que fez a operação no caixa (obrigatório nas rotas de negócio do Gateway).</summary>
    public const string GatewayOperatorHeader = "X-Operator-Id";

    /// <summary>
    /// Rotas que um token de Gateway pode chamar (método + caminho exato). Tudo fora daqui continua exigindo
    /// login de usuário. RequiresOperator=false só para o teste de conexão.
    /// </summary>
    private static readonly (string Method, string Path, bool RequiresOperator)[] GatewayAllowedRoutes =
    [
        ("GET", "/api/GatewayToken/ping", false),
        ("POST", "/api/HistoricoVendas", true),
        ("GET", "/api/Caixa/status", true),
        ("POST", "/api/Caixa/abrir", true),
        ("POST", "/api/Caixa/fechar", true),
        ("POST", "/api/Caixa/movimento", true),
        ("GET", "/api/Produto", true),
        ("GET", "/api/Cliente", true),
    ];

    public async Task InvokeAsync(
        HttpContext context,
        HorusJwtService jwtService,
        HorusSecurityStore securityStore,
        GatewayTokenAB gatewayTokenAB)
    {
        if (ShouldSkipAuth(context))
        {
            await next(context);
            return;
        }

        var token = ResolveToken(context);

        AuthenticatedUser? authenticatedUser;
        string companyStatus;

        if (token.StartsWith(GatewayTokenAB.TokenPrefixMarker, StringComparison.Ordinal))
        {
            // Local Gateway da loja (token por loja): age em nome do operador informado no header.
            var gatewayAuth = await AuthenticateGatewayAsync(context, token, gatewayTokenAB);
            if (gatewayAuth is null) return; // resposta de erro já escrita
            (authenticatedUser, companyStatus) = gatewayAuth.Value;
        }
        else
        {
            authenticatedUser = string.IsNullOrWhiteSpace(token) ? null : jwtService.ValidateToken(token);
            // Sessão + usuário + empresa numa única consulta assíncrona (antes: 3 conexões síncronas por requisição).
            var validation = authenticatedUser is null
                ? null
                : await securityStore.ValidateRequestAsync(
                    authenticatedUser.SessionId,
                    authenticatedUser.Id,
                    authenticatedUser.CompanyId,
                    context.RequestAborted);
            if (authenticatedUser is null || validation is null || !validation.SessionActive || !validation.UserActive)
            {
                context.Response.StatusCode = StatusCodes.Status401Unauthorized;
                await context.Response.WriteAsJsonAsync(new ApiResponse<object>
                {
                    Success = false,
                    Message = "Sessao expirada ou token invalido."
                });
                return;
            }
            companyStatus = validation.CompanyStatus;
            authenticatedUser.PerfisAdicionais = HorusRoles.ParsePerfisAdicionais(validation.PerfisAdicionais);
        }

        context.Items["CurrentUser"] = authenticatedUser;

        if (!string.Equals(authenticatedUser.CompanyId, "empresa-principal", StringComparison.OrdinalIgnoreCase))
        {
            if (!string.Equals(companyStatus, "aprovada", StringComparison.OrdinalIgnoreCase))
            {
                context.Response.StatusCode = StatusCodes.Status403Forbidden;
                await context.Response.WriteAsJsonAsync(new ApiResponse<object>
                {
                    Success = false,
                    Message = companyStatus switch
                    {
                        "pendente" => "Sua empresa ainda aguarda aprovação da administração.",
                        "rejeitada" => "O cadastro da sua empresa foi recusado.",
                        "bloqueada" => "O acesso da sua empresa está bloqueado/suspenso.",
                        _ => "Acesso restrito para esta empresa."
                    }
                });
                return;
            }
        }

        var rolePolicy = context.GetEndpoint()?.Metadata.GetMetadata<HorusAuthorizeRolesAttribute>();
        // Vale o perfil principal OU qualquer perfil adicional (ex.: gerente + financeiro passa em "financeiro").
        if (rolePolicy is not null && rolePolicy.Roles.Count > 0
            && !rolePolicy.Roles.Contains(authenticatedUser.Role)
            && !authenticatedUser.PerfisAdicionais.Any(rolePolicy.Roles.Contains))
        {
            context.Response.StatusCode = StatusCodes.Status403Forbidden;
            await context.Response.WriteAsJsonAsync(new ApiResponse<object>
            {
                Success = false,
                Message = "Usuário sem permissão para esta ação."
            });
            return;
        }

        await next(context);
    }

    /// <summary>
    /// Valida token de Gateway: rota liberada, token ativo e (quando a rota exige) operador ativo da mesma
    /// empresa. Escreve a resposta de erro e devolve null quando recusa.
    /// </summary>
    private static async Task<(AuthenticatedUser User, string CompanyStatus)?> AuthenticateGatewayAsync(
        HttpContext context,
        string token,
        GatewayTokenAB gatewayTokenAB)
    {
        var path = (context.Request.Path.Value ?? "").TrimEnd('/');
        var route = GatewayAllowedRoutes.FirstOrDefault(r =>
            string.Equals(r.Method, context.Request.Method, StringComparison.OrdinalIgnoreCase) &&
            string.Equals(r.Path, path, StringComparison.OrdinalIgnoreCase));
        if (route.Path is null)
        {
            await WriteErrorAsync(context, StatusCodes.Status403Forbidden, "Rota não liberada para o token do Gateway.");
            return null;
        }

        var operatorId = context.Request.Headers[GatewayOperatorHeader].FirstOrDefault()?.Trim();
        var validation = await gatewayTokenAB.ValidateAsync(token, operatorId, context.RequestAborted);
        if (validation is null)
        {
            await WriteErrorAsync(context, StatusCodes.Status401Unauthorized, "Token do Gateway inválido ou revogado.");
            return null;
        }

        context.Items["CurrentGateway"] = validation;

        if (route.RequiresOperator && validation.OperatorId is null)
        {
            await WriteErrorAsync(
                context,
                StatusCodes.Status401Unauthorized,
                $"Operador inválido: informe no header {GatewayOperatorHeader} um usuário ativo desta empresa.");
            return null;
        }

        var user = new AuthenticatedUser
        {
            // Teste de conexão (sem operador): identidade técnica do Gateway, sem perfil de usuário.
            Id = validation.OperatorId ?? $"gateway:{validation.TokenId}",
            CompanyId = validation.CompanyId,
            SessionId = $"gateway:{validation.TokenId}",
            Name = validation.OperatorName ?? "Local Gateway",
            Email = validation.OperatorEmail ?? string.Empty,
            Role = validation.OperatorRole ?? string.Empty
        };
        return (user, validation.CompanyStatus);
    }

    private static Task WriteErrorAsync(HttpContext context, int statusCode, string message)
    {
        context.Response.StatusCode = statusCode;
        return context.Response.WriteAsJsonAsync(new ApiResponse<object> { Success = false, Message = message });
    }

    private static bool ShouldSkipAuth(HttpContext context)
    {
        if (HttpMethods.IsOptions(context.Request.Method)) return true;

        var path = context.Request.Path.Value ?? "";
        return path.StartsWith("/api/Auth/login", StringComparison.OrdinalIgnoreCase) ||
               path.StartsWith("/api/Auth/forgot-password", StringComparison.OrdinalIgnoreCase) ||
               path.StartsWith("/api/Auth/reset-password", StringComparison.OrdinalIgnoreCase) ||
               path.StartsWith("/api/Auth/register", StringComparison.OrdinalIgnoreCase) ||
               (context.RequestServices.GetRequiredService<IWebHostEnvironment>().IsDevelopment() &&
                path.StartsWith("/swagger", StringComparison.OrdinalIgnoreCase));
    }

    private static string ResolveToken(HttpContext context)
    {
        var cookieToken = context.Request.Cookies[HorusJwtService.AuthCookieName];
        if (!string.IsNullOrWhiteSpace(cookieToken))
        {
            return cookieToken;
        }

        var authHeader = context.Request.Headers.Authorization.ToString();
        var parts = authHeader.Split(' ', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        return parts.Length == 2 && parts[0].Equals("Bearer", StringComparison.Ordinal)
            ? parts[1]
            : "";
    }
}
