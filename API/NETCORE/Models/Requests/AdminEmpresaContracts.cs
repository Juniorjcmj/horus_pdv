/**
 * Arquivo: API/NETCORE/Models/Requests/AdminEmpresaContracts.cs
 * Objetivo: DTOs e contratos de requisição/resposta para o módulo SuperAdmin de Gerenciamento de Empresas.
 */
namespace HORUSPDV_API.Models.Requests;

public class EmpresaAdminItemDto
{
    public string Id { get; set; } = string.Empty;
    public string FantasyName { get; set; } = string.Empty;
    public string CorporateName { get; set; } = string.Empty;
    public string Cnpj { get; set; } = string.Empty;
    public string Email { get; set; } = string.Empty;
    public string Phone { get; set; } = string.Empty;
    public string Mobile { get; set; } = string.Empty;
    public string City { get; set; } = string.Empty;
    public string Uf { get; set; } = string.Empty;
    public string Status { get; set; } = "pendente";
    public DateTimeOffset CreatedAt { get; set; }
    public DateTimeOffset? ReviewedAt { get; set; }
    public string? ReviewedBy { get; set; }
    public string? RejectionReason { get; set; }

    // Dados do usuário administrador inicial
    public string? AdminUserName { get; set; }
    public string? AdminUserEmail { get; set; }
    public string? AdminUserPhone { get; set; }

    // Métricas operacionais
    public int TotalUsers { get; set; }
    public int TotalProducts { get; set; }
    public int TotalSales { get; set; }
}

public class EmpresasMetricasDto
{
    public int Total { get; set; }
    public int Pendentes { get; set; }
    public int Aprovadas { get; set; }
    public int Rejeitadas { get; set; }
    public int Bloqueadas { get; set; }
    public bool RequireApprovalForNewCompanies { get; set; } = true;
}

public class RejeitarEmpresaRequest
{
    public string Reason { get; set; } = string.Empty;
}

public class BloquearEmpresaRequest
{
    public string Reason { get; set; } = string.Empty;
}

public class AtualizarConfiguracaoPlataformaRequest
{
    public bool RequireApprovalForNewCompanies { get; set; } = true;
}

public class AlterarCredenciaisEmpresaRequest
{
    public string? NewEmail { get; set; }
    public string? NewPassword { get; set; }
}

public class EmpresasAdminListResult
{
    public List<EmpresaAdminItemDto> Items { get; set; } = [];
    public int TotalCount { get; set; }
    public int Page { get; set; } = 1;
    public int PageSize { get; set; } = 20;
}
