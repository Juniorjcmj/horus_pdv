/*
 * Arquivo: tests/HorusGateway.Tests/RealtimeDeliveryTests.cs
 * Objetivo: provar o primeiro objetivo funcional em tempo real — Terminal A cria um evento e o
 *           Terminal B (assinante SignalR da mesma empresa) recebe quase imediatamente.
 *           Complementa a recuperação REST com a distribuição push (PDV → Gateway → SignalR → Caixa).
 */
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.AspNetCore.SignalR.Client;
using Xunit;

namespace HorusGateway.Tests;

public sealed class RealtimeDeliveryTests : IDisposable
{
    private readonly string _dbPath;

    public RealtimeDeliveryTests()
    {
        _dbPath = Path.Combine(Path.GetTempPath(), $"horus-gw-rt-{Guid.NewGuid():N}.db");
    }

    public void Dispose()
    {
        Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
        foreach (var suffix in new[] { "", "-wal", "-shm" })
        {
            var f = _dbPath + suffix;
            try { if (File.Exists(f)) File.Delete(f); } catch { }
        }
    }

    [Fact]
    public async Task Event_created_by_terminal_A_is_pushed_to_terminal_B_via_signalr()
    {
        using var factory = new GatewayAppFactory(_dbPath);

        // Terminal B: conecta ao Hub via handler do servidor de teste e assina a empresa.
        var received = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);

        var connection = new HubConnectionBuilder()
            .WithUrl("http://localhost/hubs/events", options =>
            {
                options.HttpMessageHandlerFactory = _ => factory.Server.CreateHandler();
            })
            .Build();

        connection.On<JsonElement>("eventReceived", evt =>
        {
            received.TrySetResult(evt.GetProperty("eventId").GetString() ?? "");
        });

        await connection.StartAsync();
        await connection.InvokeAsync("Subscribe", "empresa-1");

        // Terminal A: cria o evento via REST.
        using var client = factory.CreateClient();
        var evt = new
        {
            eventId = "evt-realtime",
            companyId = "empresa-1",
            storeId = "store-001",
            terminalId = "PDV-01",
            eventType = "ORDER_CREATED",
            occurredAt = DateTimeOffset.UtcNow.ToString("o"),
            payload = new { orderId = "PED-RT-1" }
        };
        var post = await client.PostAsJsonAsync("/api/gateway/events", evt);
        post.EnsureSuccessStatusCode();

        // Terminal B deve receber o push em tempo hábil.
        var completed = await Task.WhenAny(received.Task, Task.Delay(TimeSpan.FromSeconds(10)));
        Assert.True(completed == received.Task, "Terminal B não recebeu o evento em tempo real.");
        Assert.Equal("evt-realtime", await received.Task);

        await connection.DisposeAsync();
    }
}
