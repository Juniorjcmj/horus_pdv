/*
 * Arquivo: tests/HorusGateway.Tests/OrdersFlowTests.cs
 * Objetivo: cobrir o CHANGE GATEWAY 05 — ciclo de vida do pedido via eventos (Terminal → Gateway → Caixa),
 *           projeção consultável pelo caixa, transições inválidas rejeitadas e broadcast em tempo real.
 */
using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.AspNetCore.SignalR.Client;
using Xunit;

namespace HorusGateway.Tests;

public sealed class OrdersFlowTests : IDisposable
{
    private readonly string _dbPath;

    public OrdersFlowTests()
    {
        _dbPath = Path.Combine(Path.GetTempPath(), $"horus-gw-orders-{Guid.NewGuid():N}.db");
    }

    public void Dispose()
    {
        foreach (var suffix in new[] { "", "-wal", "-shm" })
        {
            var f = _dbPath + suffix;
            if (File.Exists(f)) File.Delete(f);
        }
    }

    private static object OrderEvent(string eventId, string type, string orderNumber, object? extra = null)
    {
        var payload = new Dictionary<string, object?> { ["orderId"] = orderNumber };
        if (extra is not null)
        {
            foreach (var p in extra.GetType().GetProperties())
                payload[p.Name] = p.GetValue(extra);
        }
        return new
        {
            eventId,
            companyId = "empresa-1",
            storeId = "store-001",
            terminalId = "PDV-01",
            eventType = type,
            payload
        };
    }

    private static async Task<HttpResponseMessage> Post(HttpClient client, object body)
        => await client.PostAsJsonAsync("/api/gateway/events", body);

    // Fluxo completo: criação no PDV → caixa consulta → transições até DELIVERED.
    [Fact]
    public async Task Full_order_lifecycle_via_events_is_projected_for_cashier()
    {
        using var factory = new GatewayAppFactory(_dbPath); // requireTerminalAuth = false (foco no fluxo de pedido)
        using var client = factory.CreateClient();

        Assert.Equal(HttpStatusCode.OK, (await Post(client, OrderEvent("e1", "ORDER_CREATED", "PED-1", new { totalAmount = "85,00" }))).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await Post(client, OrderEvent("e2", "ORDER_CONFIRMED", "PED-1"))).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await Post(client, OrderEvent("e3", "ORDER_PREPARING", "PED-1"))).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await Post(client, OrderEvent("e4", "ORDER_READY", "PED-1"))).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await Post(client, OrderEvent("e5", "ORDER_DELIVERED", "PED-1"))).StatusCode);

        // O caixa consulta a projeção.
        var order = await client.GetFromJsonAsync<JsonElement>("/api/gateway/orders/PED-1");
        Assert.Equal("DELIVERED", order.GetProperty("status").GetString());
        Assert.Equal("85,00", order.GetProperty("totalAmount").GetString());
        Assert.Equal(5, order.GetProperty("version").GetInt32());

        var list = await client.GetFromJsonAsync<JsonElement>("/api/gateway/orders");
        Assert.Equal(1, list.GetProperty("count").GetInt32());
    }

    // Transição inválida é rejeitada com 409 e não altera o pedido.
    [Fact]
    public async Task Invalid_transition_is_rejected_and_does_not_change_order()
    {
        using var factory = new GatewayAppFactory(_dbPath);
        using var client = factory.CreateClient();

        Assert.Equal(HttpStatusCode.OK, (await Post(client, OrderEvent("e1", "ORDER_CREATED", "PED-2"))).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await Post(client, OrderEvent("e2", "ORDER_DELIVERED", "PED-2"))).StatusCode); // pula direto (permitido: avanço)

        // Depois de DELIVERED (terminal), qualquer transição é inválida.
        var invalid = await Post(client, OrderEvent("e3", "ORDER_CANCELLED", "PED-2"));
        Assert.Equal(HttpStatusCode.Conflict, invalid.StatusCode);

        var order = await client.GetFromJsonAsync<JsonElement>("/api/gateway/orders/PED-2");
        Assert.Equal("DELIVERED", order.GetProperty("status").GetString());
    }

    // Evento para pedido inexistente (que não seja CREATED) é inválido.
    [Fact]
    public async Task Transition_on_unknown_order_is_rejected()
    {
        using var factory = new GatewayAppFactory(_dbPath);
        using var client = factory.CreateClient();

        var resp = await Post(client, OrderEvent("e1", "ORDER_CONFIRMED", "PED-INEXISTENTE"));
        Assert.Equal(HttpStatusCode.Conflict, resp.StatusCode);
    }

    // Evento ORDER_* sem orderNumber no payload é rejeitado.
    [Fact]
    public async Task Order_event_without_order_number_is_rejected()
    {
        using var factory = new GatewayAppFactory(_dbPath);
        using var client = factory.CreateClient();

        var body = new
        {
            eventId = "e1",
            companyId = "empresa-1",
            storeId = "store-001",
            terminalId = "PDV-01",
            eventType = "ORDER_CREATED",
            payload = new { semNumero = true }
        };
        var resp = await client.PostAsJsonAsync("/api/gateway/events", body);
        Assert.Equal(HttpStatusCode.BadRequest, resp.StatusCode);
    }

    // Cancelamento a partir de estado não terminal é permitido.
    [Fact]
    public async Task Cancel_from_non_terminal_state_is_allowed()
    {
        using var factory = new GatewayAppFactory(_dbPath);
        using var client = factory.CreateClient();

        Assert.Equal(HttpStatusCode.OK, (await Post(client, OrderEvent("e1", "ORDER_CREATED", "PED-3"))).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await Post(client, OrderEvent("e2", "ORDER_CONFIRMED", "PED-3"))).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await Post(client, OrderEvent("e3", "ORDER_CANCELLED", "PED-3"))).StatusCode);

        var order = await client.GetFromJsonAsync<JsonElement>("/api/gateway/orders/PED-3");
        Assert.Equal("CANCELLED", order.GetProperty("status").GetString());
    }

    // O caixa recebe o pedido em tempo real (PDV → Gateway → SignalR → Caixa) via "orderUpdated".
    [Fact]
    public async Task Cashier_receives_order_in_realtime()
    {
        using var factory = new GatewayAppFactory(_dbPath);

        var received = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        var connection = new HubConnectionBuilder()
            .WithUrl("http://localhost/hubs/events", o => o.HttpMessageHandlerFactory = _ => factory.Server.CreateHandler())
            .Build();
        connection.On<JsonElement>("orderUpdated", o => received.TrySetResult(o.GetProperty("status").GetString() ?? ""));

        await connection.StartAsync();
        await connection.InvokeAsync("Subscribe", "empresa-1");

        using var client = factory.CreateClient();
        await Post(client, OrderEvent("e1", "ORDER_CREATED", "PED-RT"));

        var completed = await Task.WhenAny(received.Task, Task.Delay(TimeSpan.FromSeconds(10)));
        Assert.True(completed == received.Task, "Caixa não recebeu o pedido em tempo real.");
        Assert.Equal("CREATED", await received.Task);

        await connection.DisposeAsync();
    }
}
