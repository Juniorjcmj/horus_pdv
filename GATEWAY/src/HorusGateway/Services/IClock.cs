/*
 * Arquivo: Services/IClock.cs
 * Objetivo: abstração de tempo para tornar o agendamento de retry testável de forma determinística.
 */
namespace HorusGateway.Services;

public interface IClock
{
    DateTimeOffset UtcNow { get; }
}

public sealed class SystemClock : IClock
{
    public DateTimeOffset UtcNow => DateTimeOffset.UtcNow;
}
