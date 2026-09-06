/**
 * Arquivo: API/NETCORE/Repositories/DataAccess/AuditLogAD.cs
 * Objetivo: representa uma linha da trilha de auditoria (quem fez o quê e quando) retornada
 *           pelo acesso ao banco.
 * Entradas esperadas: recebe valores lidos do SQL Server e alimenta serviços/relatórios superiores.
 */
namespace HORUSPDV_API.Repositories.DataAccess;

public class AuditLogAD
{
    public long Id { get; set; }
    public DateTimeOffset OccurredAt { get; set; }
    public string UserId { get; set; } = string.Empty;
    public string UserName { get; set; } = string.Empty;
    public string EventType { get; set; } = string.Empty;
    public string? EntityType { get; set; }
    public string? EntityId { get; set; }
    public string Description { get; set; } = string.Empty;
    public string? Ip { get; set; }
}
