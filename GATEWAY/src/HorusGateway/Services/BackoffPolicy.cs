/*
 * Arquivo: Services/BackoffPolicy.cs
 * Objetivo: política de retry com backoff exponencial + jitter, alinhada às regras já usadas pelo
 *           SyncEngine do frontend (base 5s, fator 2, teto ~5min, jitter 30%, máx. 10 tentativas).
 *           Usada pelo Event Bus local para reagendar o envio de eventos à cloud (CHANGE GATEWAY 06).
 */
namespace HorusGateway.Services;

public static class BackoffPolicy
{
    public const int MaxRetries = 10;
    private static readonly TimeSpan BaseDelay = TimeSpan.FromSeconds(5);
    private const int ExponentCap = 6; // teto do expoente → ~5min de delay base

    /// <summary>Delay base determinístico (sem jitter) para a tentativa nº <paramref name="retryCount"/>.</summary>
    public static TimeSpan BaseFor(int retryCount)
    {
        var exponent = Math.Min(Math.Max(retryCount, 0), ExponentCap);
        return BaseDelay * Math.Pow(2, exponent);
    }

    /// <summary>Delay com jitter (30%) para espalhar reenvios simultâneos de vários eventos.</summary>
    public static TimeSpan NextDelay(int retryCount, Random? random = null)
    {
        var baseDelay = BaseFor(retryCount);
        var rng = random ?? Random.Shared;
        var jitter = baseDelay.TotalMilliseconds * 0.3 * rng.NextDouble();
        return baseDelay + TimeSpan.FromMilliseconds(jitter);
    }

    public static bool Exhausted(int retryCount) => retryCount >= MaxRetries;
}
