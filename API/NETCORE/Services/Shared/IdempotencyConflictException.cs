/**
 * Arquivo: API/NETCORE/Services/Shared/IdempotencyConflictException.cs
 * Objetivo: exceção lançada quando uma requisição com o mesmo EventId chega com payload divergente.
 */
namespace HORUSPDV_API.Services.Shared;

public class IdempotencyConflictException : InvalidOperationException
{
    public string EventId { get; }

    public IdempotencyConflictException(string eventId, string message) : base(message)
    {
        EventId = eventId;
    }
}
