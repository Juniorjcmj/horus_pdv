/*
 * Arquivo: Services/ITerminalStore.cs
 * Objetivo: contrato do registro de terminais — cadastro/rotação de credencial, autenticação,
 *           heartbeat (LastSeenAt) e listagem com estado online/offline para o dashboard.
 */
using HorusGateway.Models;

namespace HorusGateway.Services;

public interface ITerminalStore
{
    /// <summary>Registra (ou re-registra, rotacionando a credencial) um terminal e retorna a apiKey uma vez.</summary>
    Task<TerminalRegistrationResult> RegisterAsync(RegisterTerminalRequest request, string gatewayId, CancellationToken cancellationToken = default);

    /// <summary>Pré-autoriza um terminal (admin): grava IP e/ou token de provisionamento; retorna a apiKey uma vez.</summary>
    Task<TerminalRegistrationResult> ProvisionAsync(ProvisionTerminalRequest request, string gatewayId, CancellationToken cancellationToken = default);

    /// <summary>
    /// Auto-identifica um terminal pré-autorizado pelo IP de origem e/ou token, rotaciona a credencial e a
    /// retorna. Null quando nenhum terminal pré-autorizado corresponde (conexão não reconhecida).
    /// </summary>
    Task<TerminalRegistrationResult?> IdentifyAsync(string? remoteIp, string? provisionToken, string gatewayId, CancellationToken cancellationToken = default);

    /// <summary>Autentica um terminal pela apiKey e valida o vínculo com a empresa deste Gateway.</summary>
    Task<TerminalAuthResult> AuthenticateAsync(string? terminalId, string? apiKey, CancellationToken cancellationToken = default);

    /// <summary>Atualiza o LastSeenAt do terminal (heartbeat).</summary>
    Task TouchAsync(string companyId, string terminalId, CancellationToken cancellationToken = default);

    /// <summary>Lista terminais da empresa com marcação online/offline pela janela de heartbeat.</summary>
    Task<IReadOnlyList<TerminalInfo>> ListAsync(string companyId, CancellationToken cancellationToken = default);
}
