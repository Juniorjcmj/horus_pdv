/*
 * Arquivo: Services/OrderStateMachine.cs
 * Objetivo: regras puras de transição de estado do pedido. Cada evento ORDER_* mapeia para um
 *           estado alvo, e só transições válidas são permitidas — nunca alterar estado sem registrar
 *           a transição correspondente.
 */
using HorusGateway.Models;

namespace HorusGateway.Services;

public static class OrderStateMachine
{
    /// <summary>Estados finais não aceitam novas transições.</summary>
    public static bool IsTerminal(string status)
        => status is OrderState.Delivered or OrderState.Cancelled;

    /// <summary>
    /// Calcula o próximo estado a partir do estado atual (null = pedido inexistente) e do tipo de evento.
    /// Retorna false quando a transição é inválida.
    /// </summary>
    public static bool TryNext(string? current, string eventType, out string next)
    {
        next = current ?? string.Empty;

        // Pedido ainda não existe: só pode ser criado.
        if (current is null)
        {
            if (eventType == OrderEventType.Created)
            {
                next = OrderState.Created;
                return true;
            }
            return false;
        }

        if (IsTerminal(current)) return false;

        // ORDER_UPDATED mantém o estado (atualiza itens/total) enquanto não for terminal.
        if (eventType == OrderEventType.Updated)
        {
            next = current;
            return true;
        }

        // Cancelamento é permitido de qualquer estado não terminal.
        if (eventType == OrderEventType.Cancelled)
        {
            next = OrderState.Cancelled;
            return true;
        }

        // Recriar um pedido existente é inválido.
        if (eventType == OrderEventType.Created) return false;

        var target = TargetOf(eventType);
        if (target is null) return false; // tipo de evento desconhecido

        if (IsForward(current, target))
        {
            next = target;
            return true;
        }

        return false;
    }

    private static string? TargetOf(string eventType) => eventType switch
    {
        OrderEventType.Received => OrderState.Received,
        OrderEventType.Confirmed => OrderState.Confirmed,
        OrderEventType.Preparing => OrderState.Preparing,
        OrderEventType.Ready => OrderState.Ready,
        OrderEventType.Delivered => OrderState.Delivered,
        _ => null
    };

    private static readonly string[] Forward =
    {
        OrderState.Created,
        OrderState.Received,
        OrderState.Confirmed,
        OrderState.Preparing,
        OrderState.Ready,
        OrderState.Delivered
    };

    /// <summary>Avanço só é permitido para um estado à frente no fluxo (permite pular etapas, nunca retroceder).</summary>
    private static bool IsForward(string current, string target)
    {
        var ci = Array.IndexOf(Forward, current);
        var ti = Array.IndexOf(Forward, target);
        return ci >= 0 && ti > ci;
    }
}
