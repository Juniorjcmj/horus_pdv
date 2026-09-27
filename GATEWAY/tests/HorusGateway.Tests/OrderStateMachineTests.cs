/*
 * Arquivo: tests/HorusGateway.Tests/OrderStateMachineTests.cs
 * Objetivo: verificar as regras puras de transição de estado do pedido.
 */
using HorusGateway.Models;
using HorusGateway.Services;
using Xunit;

namespace HorusGateway.Tests;

public sealed class OrderStateMachineTests
{
    [Fact]
    public void New_order_can_only_be_created()
    {
        Assert.True(OrderStateMachine.TryNext(null, OrderEventType.Created, out var s) && s == OrderState.Created);
        Assert.False(OrderStateMachine.TryNext(null, OrderEventType.Confirmed, out _));
        Assert.False(OrderStateMachine.TryNext(null, OrderEventType.Cancelled, out _));
    }

    [Fact]
    public void Forward_transitions_are_allowed_including_skips()
    {
        Assert.True(OrderStateMachine.TryNext(OrderState.Created, OrderEventType.Received, out var a) && a == OrderState.Received);
        Assert.True(OrderStateMachine.TryNext(OrderState.Created, OrderEventType.Confirmed, out var b) && b == OrderState.Confirmed); // pula RECEIVED
        Assert.True(OrderStateMachine.TryNext(OrderState.Preparing, OrderEventType.Ready, out var c) && c == OrderState.Ready);
        Assert.True(OrderStateMachine.TryNext(OrderState.Ready, OrderEventType.Delivered, out var d) && d == OrderState.Delivered);
    }

    [Fact]
    public void Backward_transitions_are_rejected()
    {
        Assert.False(OrderStateMachine.TryNext(OrderState.Confirmed, OrderEventType.Received, out _));
        Assert.False(OrderStateMachine.TryNext(OrderState.Ready, OrderEventType.Preparing, out _));
    }

    [Fact]
    public void Cancel_allowed_from_any_non_terminal_state()
    {
        Assert.True(OrderStateMachine.TryNext(OrderState.Created, OrderEventType.Cancelled, out var a) && a == OrderState.Cancelled);
        Assert.True(OrderStateMachine.TryNext(OrderState.Preparing, OrderEventType.Cancelled, out var b) && b == OrderState.Cancelled);
    }

    [Fact]
    public void Terminal_states_reject_further_transitions()
    {
        Assert.False(OrderStateMachine.TryNext(OrderState.Delivered, OrderEventType.Cancelled, out _));
        Assert.False(OrderStateMachine.TryNext(OrderState.Cancelled, OrderEventType.Confirmed, out _));
        Assert.False(OrderStateMachine.TryNext(OrderState.Delivered, OrderEventType.Updated, out _));
    }

    [Fact]
    public void Update_keeps_current_state_while_non_terminal()
    {
        Assert.True(OrderStateMachine.TryNext(OrderState.Confirmed, OrderEventType.Updated, out var s) && s == OrderState.Confirmed);
    }

    [Fact]
    public void Recreating_existing_order_is_invalid()
    {
        Assert.False(OrderStateMachine.TryNext(OrderState.Created, OrderEventType.Created, out _));
    }
}
