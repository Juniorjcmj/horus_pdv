/*
 * Arquivo: tests/HorusGateway.Tests/BackoffPolicyTests.cs
 * Objetivo: verificar as regras de backoff exponencial (alinhadas ao SyncEngine).
 */
using HorusGateway.Services;
using Xunit;

namespace HorusGateway.Tests;

public sealed class BackoffPolicyTests
{
    [Theory]
    [InlineData(0, 5)]     // base
    [InlineData(1, 10)]
    [InlineData(2, 20)]
    [InlineData(3, 40)]
    [InlineData(6, 320)]   // teto do expoente
    [InlineData(9, 320)]   // além do teto continua no máximo
    public void BaseFor_grows_exponentially_and_caps(int retry, double expectedSeconds)
    {
        Assert.Equal(expectedSeconds, BackoffPolicy.BaseFor(retry).TotalSeconds, 3);
    }

    [Fact]
    public void NextDelay_stays_within_jitter_bounds()
    {
        var rng = new Random(42);
        for (var retry = 0; retry <= 8; retry++)
        {
            var baseMs = BackoffPolicy.BaseFor(retry).TotalMilliseconds;
            var delay = BackoffPolicy.NextDelay(retry, rng).TotalMilliseconds;
            Assert.InRange(delay, baseMs, baseMs * 1.3);
        }
    }

    [Fact]
    public void Exhausted_after_max_retries()
    {
        Assert.False(BackoffPolicy.Exhausted(BackoffPolicy.MaxRetries - 1));
        Assert.True(BackoffPolicy.Exhausted(BackoffPolicy.MaxRetries));
    }
}
