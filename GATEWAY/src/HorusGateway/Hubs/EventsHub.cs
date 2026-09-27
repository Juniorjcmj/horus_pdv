/*
 * Arquivo: Hubs/EventsHub.cs
 * Objetivo: canal de tempo real (SignalR) para distribuir eventos aos terminais da mesma empresa.
 *           Estrutura preparada para o fluxo PDV → Gateway → SignalR → Caixa (CHANGE GATEWAY 05).
 *           O isolamento multi-tenant é feito por grupos nomeados por CompanyId.
 */
using Microsoft.AspNetCore.SignalR;

namespace HorusGateway.Hubs;

public sealed class EventsHub : Hub
{
    /// <summary>Prefixo dos grupos por empresa — garante que um terminal só receba eventos da própria empresa.</summary>
    public static string GroupFor(string companyId) => $"company:{companyId}";

    /// <summary>Terminal chama após conectar para assinar os eventos da sua empresa.</summary>
    public Task Subscribe(string companyId)
        => Groups.AddToGroupAsync(Context.ConnectionId, GroupFor(companyId));

    public Task Unsubscribe(string companyId)
        => Groups.RemoveFromGroupAsync(Context.ConnectionId, GroupFor(companyId));
}
