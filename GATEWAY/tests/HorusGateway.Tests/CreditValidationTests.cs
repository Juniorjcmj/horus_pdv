using System.Net;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Horus.Credit;
using HorusGateway.Configuration;
using HorusGateway.Data;
using HorusGateway.Models;
using HorusGateway.Services;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using Xunit;

namespace HorusGateway.Tests;

// ──────────────────────────────────────────────────────────────────────────────
// 7.1 — Concurrency: real SQLite serialized transactions
// ──────────────────────────────────────────────────────────────────────────────
public sealed class CreditConcurrencyTests : IDisposable
{
    private sealed class Clock : IClock { public DateTimeOffset UtcNow { get; set; } = DateTimeOffset.UtcNow; }
    private readonly string path = Path.Combine(Path.GetTempPath(), Guid.NewGuid() + ".db");
    private readonly Clock clock = new();
    private readonly GatewayDatabase database;
    private readonly IOptions<GatewayOptions> options;
    public CreditConcurrencyTests()
    {
        options = Options.Create(new GatewayOptions { CompanyId = "c", StoreId = "s", GatewayId = "g", CreditEpoch = "e", CreditEnabled = true, OpenRegistration = false, DatabasePath = path });
        database = new(options, NullLogger<GatewayDatabase>.Instance); database.Initialize();
    }
    private GatewayCreditLedger Build() => new(database, new GatewayIdentity(options), options, clock, new SqliteEventStore(database, clock, NullLogger<SqliteEventStore>.Instance));
    private static string PermitHash(string permit) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(permit))).ToLowerInvariant();
    private void Seed(string grantId, long cents, string customer = "customer", string doc = "123")
    {
        var ledger = Build();
        ledger.Import(new(1, grantId, "c", customer, doc, "s", "g", "e", cents, clock.UtcNow.AddHours(1), "v1"));
        ledger.ImportState("ACTIVE");
        ledger.ImportPermits([new("operator", "t1", PermitHash("permit-t1"), clock.UtcNow.AddHours(1)), new("operator", "t2", PermitHash("permit-t2"), clock.UtcNow.AddHours(1))]);
    }
    private CreditReservation Reservation(string id, string grantId, long cents, string terminal = "t1") =>
        new(1, id, "event-" + id, grantId, "customer", cents, "operator", "permit-" + terminal);
    private IngestEventRequest Sale(string id, decimal fiado, string terminal = "t1") => new()
    {
        CompanyId = "c", StoreId = "s", TerminalId = terminal, EventId = "event-" + id, EventType = "SALE_CREATED",
        Payload = JsonSerializer.SerializeToElement(new
        {
            clientSaleId = id, eventId = "event-" + id, customerCpf = "123", operatorId = "operator",
            totalAmount = "100,00",
            items = new[] { new { productCode = "p", quantity = 1, unitPrice = 100 } },
            payments = new[] { new { paymentType = "fiado", amount = fiado }, new { paymentType = "pix", amount = 100 - fiado } }
        })
    };

    [Fact]
    public async Task TwoReserves70In100OnlyOneSucceeds()
    {
        Seed("grant", 10000);
        var tasks = Enumerable.Range(0, 2).Select(i => Task.Run(() =>
        {
            try { return Build().Reserve(i == 0 ? "t1" : "t2", Reservation("r" + i, "grant", 7000, i == 0 ? "t1" : "t2")).State; }
            catch (InvalidOperationException e) { return e.Message; }
        }));
        var results = await Task.WhenAll(tasks);
        Assert.Single(results, x => x == "RESERVED");
        Assert.Single(results, x => x == "BUDGET_INSUFFICIENT");
    }

    [Fact]
    public async Task TwoTerminalsConcurrentReserveAndCommitOneGrant()
    {
        Seed("grant", 10000);
        var ledger1 = Build(); var ledger2 = Build();
        // Both reserve at once: 6000 + 5000 = 11000 > 10000
        var r1 = Task.Run(() => { try { return ledger1.Reserve("t1", Reservation("a", "grant", 6000, "t1")); } catch { return null; } });
        var r2 = Task.Run(() => { try { return ledger2.Reserve("t2", Reservation("b", "grant", 5000, "t2")); } catch { return null; } });
        var reservations = await Task.WhenAll(r1, r2);
        var succeeded = reservations.Count(x => x is not null);
        // At most one can succeed if sum > budget; or both succeed if races serialize such that first finishes before second reads
        // Either way, total reserved must not exceed 10000
        using var db = database.OpenConnection();
        using var cmd = db.CreateCommand();
        cmd.CommandText = "SELECT COALESCE(SUM(AmountCents),0) FROM CreditAuthorizations WHERE CompanyId='c' AND State='RESERVED'";
        var totalReserved = Convert.ToInt64(cmd.ExecuteScalar());
        Assert.True(totalReserved <= 10000, $"Over-reserved: {totalReserved}");
    }

    [Fact]
    public async Task ConcurrentCommitAndReserveFromDifferentTerminals()
    {
        Seed("grant", 10000);
        var ledger = Build();
        ledger.Reserve("t1", Reservation("a", "grant", 6000, "t1"));
        // t1 commits 6000 while t2 tries to reserve 5000 — only commit or insufficient, never both succeed with overspend
        var commit = Task.Run(async () => { try { return (await Build().Commit("t1", "a", Sale("a", 60, "t1"))).State; } catch { return "FAILED"; } });
        var reserve = Task.Run(() => { try { return Build().Reserve("t2", Reservation("b", "grant", 5000, "t2")).State; } catch (InvalidOperationException e) { return e.Message; } });
        await Task.WhenAll(commit, reserve);
        using var db = database.OpenConnection();
        using var cmd = db.CreateCommand();
        cmd.CommandText = "SELECT COALESCE(SUM(AmountCents),0) FROM CreditAuthorizations WHERE CompanyId='c' AND State IN ('RESERVED','COMMITTED')";
        Assert.True(Convert.ToInt64(cmd.ExecuteScalar()) <= 10000);
    }

    [Fact]
    public async Task FourConcurrentReservesOnSmallBudgetNeverOverspend()
    {
        Seed("grant", 5000);
        var tasks = Enumerable.Range(0, 4).Select(i => Task.Run(() =>
        {
            var terminal = i % 2 == 0 ? "t1" : "t2";
            try { return (long?)Build().Reserve(terminal, Reservation("r" + i, "grant", 2000, terminal)).AmountCents; }
            catch { return null; }
        }));
        var results = await Task.WhenAll(tasks);
        var totalReserved = results.Where(x => x.HasValue).Sum(x => x!.Value);
        Assert.True(totalReserved <= 5000, $"Over-reserved {totalReserved} on 5000 budget");
        Assert.True(results.Count(x => x.HasValue) <= 2, "At most 2 x 2000 fit in 5000");
    }

    public void Dispose() { Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools(); foreach (var suffix in new[] { "", "-wal", "-shm" }) File.Delete(path + suffix); }
}

// ──────────────────────────────────────────────────────────────────────────────
// 7.2 — Lost response, restart, idempotency, hash conflict
// ──────────────────────────────────────────────────────────────────────────────
public sealed class CreditIdempotencyTests : IDisposable
{
    private sealed class Clock : IClock { public DateTimeOffset UtcNow { get; set; } = DateTimeOffset.UtcNow; }
    private readonly string path = Path.Combine(Path.GetTempPath(), Guid.NewGuid() + ".db");
    private readonly Clock clock = new();
    private readonly GatewayDatabase database;
    private readonly IOptions<GatewayOptions> options;
    public CreditIdempotencyTests()
    {
        options = Options.Create(new GatewayOptions { CompanyId = "c", StoreId = "s", GatewayId = "g", CreditEpoch = "e", CreditEnabled = true, OpenRegistration = false, DatabasePath = path });
        database = new(options, NullLogger<GatewayDatabase>.Instance); database.Initialize();
    }
    private GatewayCreditLedger Build() => new(database, new GatewayIdentity(options), options, clock, new SqliteEventStore(database, clock, NullLogger<SqliteEventStore>.Instance));
    private void Seed()
    {
        var ledger = Build();
        ledger.Import(new(1, "grant", "c", "customer", "123", "s", "g", "e", 10000, clock.UtcNow.AddHours(1), "v1"));
        ledger.ImportState("ACTIVE");
        ledger.ImportPermits([new("operator", "t", Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes("permit"))).ToLowerInvariant(), clock.UtcNow.AddHours(1))]);
    }
    private CreditReservation Reservation(string id, long cents = 7000) => new(1, id, "event-" + id, "grant", "customer", cents, "operator", "permit");
    private IngestEventRequest Sale(string id, decimal fiado = 70) => new()
    {
        CompanyId = "c", StoreId = "s", TerminalId = "t", EventId = "event-" + id, EventType = "SALE_CREATED",
        Payload = JsonSerializer.SerializeToElement(new
        {
            clientSaleId = id, eventId = "event-" + id, customerCpf = "123", operatorId = "operator",
            totalAmount = "100,00", items = new[] { new { productCode = "p", quantity = 1, unitPrice = 100 } },
            payments = new[] { new { paymentType = "fiado", amount = fiado }, new { paymentType = "pix", amount = 100 - fiado } }
        })
    };

    [Fact]
    public void ReserveReplayReturnsSameResultAfterLostResponse()
    {
        Seed();
        var r1 = Build().Reserve("t", Reservation("a"));
        // Simulate lost response: client retries exact same request
        var r2 = Build().Reserve("t", Reservation("a"));
        Assert.Equal(r1.AuthorizationId, r2.AuthorizationId);
        Assert.Equal(r1.State, r2.State);
        Assert.Equal(r1.AmountCents, r2.AmountCents);
    }

    [Fact]
    public void ReserveReplayWithDifferentAmountThrowsConflict()
    {
        Seed();
        Build().Reserve("t", Reservation("a", 5000));
        Assert.Throws<InvalidOperationException>(() => Build().Reserve("t", Reservation("a", 6000)));
    }

    [Fact]
    public async Task CommitReplayAfterRestartReturnsSameDecision()
    {
        Seed();
        var ledger = Build();
        ledger.Reserve("t", Reservation("a"));
        var c1 = await ledger.Commit("t", "a", Sale("a"));
        // "Restart": new ledger instance
        var c2 = await Build().Commit("t", "a", Sale("a"));
        Assert.Equal("COMMITTED", c1.State);
        Assert.Equal("COMMITTED", c2.State);
        Assert.Equal(c1.SaleHash, c2.SaleHash);
        // Exactly one event in the database
        using var db = database.OpenConnection();
        using var cmd = db.CreateCommand();
        cmd.CommandText = "SELECT COUNT(*) FROM GatewayEvents";
        Assert.Equal(1L, cmd.ExecuteScalar());
    }

    [Fact]
    public async Task ConcurrentCommitRetryProducesExactlyOneEventAndOneDecision()
    {
        Seed();
        Build().Reserve("t", Reservation("a"));
        var results = await Task.WhenAll(
            Task.Run(() => Build().Commit("t", "a", Sale("a"))),
            Task.Run(() => Build().Commit("t", "a", Sale("a")))
        );
        Assert.All(results, r => Assert.Equal("COMMITTED", r.State));
        using var db = database.OpenConnection();
        using var cmd = db.CreateCommand();
        cmd.CommandText = "SELECT COUNT(*) FROM GatewayEvents";
        Assert.Equal(1L, cmd.ExecuteScalar());
    }

    [Fact]
    public async Task CommitWithDifferentSaleHashThrowsConflict()
    {
        Seed();
        Build().Reserve("t", Reservation("a"));
        await Build().Commit("t", "a", Sale("a", 70));
        // Retry with different amount: hash mismatch
        await Assert.ThrowsAsync<InvalidOperationException>(() => Build().Commit("t", "a", Sale("a", 69)));
    }

    [Fact]
    public async Task CommittedAuthorizationCannotBeReReservedOrDoubleSpent()
    {
        Seed();
        Build().Reserve("t", Reservation("a"));
        await Build().Commit("t", "a", Sale("a"));
        // Cannot reuse the consumed credit for a new reservation
        Assert.Throws<InvalidOperationException>(() => Build().Reserve("t", Reservation("b", 3100)));
    }

    [Fact]
    public async Task CancelAfterCommitPreservesCommittedState()
    {
        Seed();
        Build().Reserve("t", Reservation("a"));
        await Build().Commit("t", "a", Sale("a"));
        var cancel = Build().Cancel("t", "a");
        Assert.Equal("COMMITTED", cancel.State);
    }

    [Fact]
    public async Task PartialCommitFailureRollsBackEverything()
    {
        Seed();
        Build().Reserve("t", Reservation("a"));
        // Inject trigger to fail the event insert
        using (var db = database.OpenConnection())
        using (var cmd = db.CreateCommand())
        {
            cmd.CommandText = "CREATE TRIGGER fail_event_validation BEFORE INSERT ON GatewayEvents BEGIN SELECT RAISE(ABORT,'injected_failure'); END;";
            cmd.ExecuteNonQuery();
        }
        await Assert.ThrowsAnyAsync<Exception>(() => Build().Commit("t", "a", Sale("a")));
        // Reservation still intact, no event written
        Assert.Equal("RESERVED", Build().Query("t", "a")!.State);
        using var verify = database.OpenConnection();
        using var count = verify.CreateCommand();
        count.CommandText = "SELECT COUNT(*) FROM GatewayEvents";
        Assert.Equal(0L, count.ExecuteScalar());
        // Remove trigger and commit successfully
        using (var db = database.OpenConnection())
        using (var cmd = db.CreateCommand()) { cmd.CommandText = "DROP TRIGGER fail_event_validation;"; cmd.ExecuteNonQuery(); }
        var committed = await Build().Commit("t", "a", Sale("a"));
        Assert.Equal("COMMITTED", committed.State);
    }

    [Fact]
    public void QueryBySaleEventIdReturnsCorrectAuthorization()
    {
        Seed();
        Build().Reserve("t", Reservation("a"));
        var result = Build().QuerySale("t", "event-a");
        Assert.NotNull(result);
        Assert.Equal("a", result.AuthorizationId);
        Assert.Equal("RESERVED", result.State);
    }

    public void Dispose() { Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools(); foreach (var suffix in new[] { "", "-wal", "-shm" }) File.Delete(path + suffix); }
}

// ──────────────────────────────────────────────────────────────────────────────
// 7.3 — Expiration, cancel races, policy changes, grant TTL
// ──────────────────────────────────────────────────────────────────────────────
public sealed class CreditExpirationPolicyTests : IDisposable
{
    private sealed class Clock : IClock { public DateTimeOffset UtcNow { get; set; } = DateTimeOffset.UtcNow; }
    private readonly string path = Path.Combine(Path.GetTempPath(), Guid.NewGuid() + ".db");
    private readonly Clock clock = new();
    private readonly GatewayDatabase database;
    private readonly IOptions<GatewayOptions> options;
    public CreditExpirationPolicyTests()
    {
        options = Options.Create(new GatewayOptions { CompanyId = "c", StoreId = "s", GatewayId = "g", CreditEpoch = "e", CreditEnabled = true, OpenRegistration = false, DatabasePath = path });
        database = new(options, NullLogger<GatewayDatabase>.Instance); database.Initialize();
    }
    private GatewayCreditLedger Build() => new(database, new GatewayIdentity(options), options, clock, new SqliteEventStore(database, clock, NullLogger<SqliteEventStore>.Instance));
    private void Seed(DateTimeOffset? grantExpiry = null)
    {
        var ledger = Build();
        ledger.Import(new(1, "grant", "c", "customer", "123", "s", "g", "e", 10000, grantExpiry ?? clock.UtcNow.AddHours(1), "v1"));
        ledger.ImportState("ACTIVE");
        ledger.ImportPermits([new("operator", "t", Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes("permit"))).ToLowerInvariant(), clock.UtcNow.AddHours(2))]);
    }
    private CreditReservation Reservation(string id, long cents = 5000) => new(1, id, "event-" + id, "grant", "customer", cents, "operator", "permit");
    private IngestEventRequest Sale(string id, decimal fiado = 50) => new()
    {
        CompanyId = "c", StoreId = "s", TerminalId = "t", EventId = "event-" + id, EventType = "SALE_CREATED",
        Payload = JsonSerializer.SerializeToElement(new
        {
            clientSaleId = id, eventId = "event-" + id, customerCpf = "123", operatorId = "operator",
            totalAmount = "100,00", items = new[] { new { productCode = "p", quantity = 1, unitPrice = 100 } },
            payments = new[] { new { paymentType = "fiado", amount = fiado }, new { paymentType = "pix", amount = 100 - fiado } }
        })
    };

    [Fact]
    public async Task ExpiredReservationCannotBeCommitted()
    {
        Seed();
        Build().Reserve("t", Reservation("a"));
        clock.UtcNow = clock.UtcNow.AddMinutes(6); // well past the max 300s reservation TTL
        await Assert.ThrowsAsync<InvalidOperationException>(() => Build().Commit("t", "a", Sale("a")));
    }

    [Fact]
    public void ExpiredReservationFreesCredit()
    {
        Seed();
        Build().Reserve("t", Reservation("a", 8000));
        clock.UtcNow = clock.UtcNow.AddMinutes(6);
        // After expiry, the credit should be free for a new reservation
        var r = Build().Reserve("t", Reservation("b", 8000));
        Assert.Equal("RESERVED", r.State);
    }

    [Fact]
    public void ExpiredGrantRejectsNewReservation()
    {
        Seed(clock.UtcNow.AddSeconds(2));
        clock.UtcNow = clock.UtcNow.AddSeconds(3);
        Assert.Throws<InvalidOperationException>(() => Build().Reserve("t", Reservation("a")));
    }

    [Fact]
    public async Task CancelConcurrentWithCommitProducesConsistentState()
    {
        Seed();
        Build().Reserve("t", Reservation("a"));
        var commit = Task.Run(async () => { try { return (await Build().Commit("t", "a", Sale("a"))).State; } catch (InvalidOperationException) { return "REJECTED"; } });
        var cancel = Task.Run(() => Build().Cancel("t", "a").State);
        await Task.WhenAll(commit, cancel);
        var state = Build().Query("t", "a")!.State;
        Assert.Contains(state, new[] { "COMMITTED", "CANCELLED" });
        // Event count matches state
        using var db = database.OpenConnection();
        using var cmd = db.CreateCommand();
        cmd.CommandText = "SELECT COUNT(*) FROM GatewayEvents";
        Assert.Equal(state == "COMMITTED" ? 1L : 0L, cmd.ExecuteScalar());
    }

    [Fact]
    public void CancelBeforeCommitReleasesCredit()
    {
        Seed();
        Build().Reserve("t", Reservation("a", 8000));
        Build().Cancel("t", "a");
        // Credit freed
        var r = Build().Reserve("t", Reservation("b", 8000));
        Assert.Equal("RESERVED", r.State);
    }

    [Fact]
    public async Task BlockedCustomerRejectsNewReservationButHonorsExistingCommit()
    {
        Seed();
        Build().Reserve("t", Reservation("a", 3000));
        await Build().Commit("t", "a", Sale("a", 30));
        Build().Reserve("t", Reservation("b", 3000));
        Build().BlockCustomers(["customer"]);
        // New reservation rejected
        Assert.Throws<InvalidOperationException>(() => Build().Reserve("t", Reservation("c", 100)));
        // Existing uncommitted reservation: commit also rejected by policy
        await Assert.ThrowsAsync<InvalidOperationException>(() => Build().Commit("t", "b", Sale("b", 30)));
        // But replay of already committed sale still works
        Assert.Equal("COMMITTED", (await Build().Commit("t", "a", Sale("a", 30))).State);
    }

    [Fact]
    public void DrainingCancelsReservationsAndClosesGrants()
    {
        Seed();
        Build().Reserve("t", Reservation("a", 3000));
        Build().ImportState("DRAINING");
        Assert.Equal("CANCELLED", Build().Query("t", "a")!.State);
        Assert.Throws<InvalidOperationException>(() => Build().Reserve("t", Reservation("b")));
    }

    [Fact]
    public void ClockRegressionBlocksAllCreditOperations()
    {
        Seed();
        Build().Reserve("t", Reservation("a"));
        clock.UtcNow = clock.UtcNow.AddSeconds(-5);
        Assert.Throws<InvalidOperationException>(() => Build().Reserve("t", Reservation("b", 100)));
    }

    [Fact]
    public void GrantReservationExpiryClampedToGrantExpiry()
    {
        var grantExpiry = clock.UtcNow.AddSeconds(30);
        Seed(grantExpiry);
        var r = Build().Reserve("t", Reservation("a"));
        Assert.True(r.ExpiresAt <= grantExpiry);
    }

    public void Dispose() { Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools(); foreach (var suffix in new[] { "", "-wal", "-shm" }) File.Delete(path + suffix); }
}

// ──────────────────────────────────────────────────────────────────────────────
// 7.4 — Security: spoofing, bypass, fabricated proofs, version checks
// ──────────────────────────────────────────────────────────────────────────────
public sealed class CreditSecurityTests : IDisposable
{
    private sealed class Clock : IClock { public DateTimeOffset UtcNow { get; set; } = DateTimeOffset.UtcNow; }
    private readonly string path = Path.Combine(Path.GetTempPath(), Guid.NewGuid() + ".db");
    private readonly Clock clock = new();
    private readonly GatewayDatabase database;
    private readonly IOptions<GatewayOptions> options;
    public CreditSecurityTests()
    {
        options = Options.Create(new GatewayOptions { CompanyId = "c", StoreId = "s", GatewayId = "g", CreditEpoch = "e", CreditEnabled = true, OpenRegistration = false, DatabasePath = path });
        database = new(options, NullLogger<GatewayDatabase>.Instance); database.Initialize();
    }
    private GatewayCreditLedger Build() => new(database, new GatewayIdentity(options), options, clock, new SqliteEventStore(database, clock, NullLogger<SqliteEventStore>.Instance));
    private static string PermitHash(string permit) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(permit))).ToLowerInvariant();
    private void Seed()
    {
        var ledger = Build();
        ledger.Import(new(1, "grant", "c", "customer", "123", "s", "g", "e", 10000, clock.UtcNow.AddHours(1), "v1"));
        ledger.ImportState("ACTIVE");
        ledger.ImportPermits([new("operator", "t", PermitHash("permit"), clock.UtcNow.AddHours(1))]);
    }
    private CreditReservation Reservation(string id, long cents = 5000) => new(1, id, "event-" + id, "grant", "customer", cents, "operator", "permit");
    private IngestEventRequest Sale(string id, decimal fiado = 50) => new()
    {
        CompanyId = "c", StoreId = "s", TerminalId = "t", EventId = "event-" + id, EventType = "SALE_CREATED",
        Payload = JsonSerializer.SerializeToElement(new
        {
            clientSaleId = id, eventId = "event-" + id, customerCpf = "123", operatorId = "operator",
            totalAmount = "100,00", items = new[] { new { productCode = "p", quantity = 1, unitPrice = 100 } },
            payments = new[] { new { paymentType = "fiado", amount = fiado }, new { paymentType = "pix", amount = 100 - fiado } }
        })
    };

    // --- Spoofed identities ---

    [Fact]
    public void ImportRejectsGrantWithWrongCompany()
    {
        Seed();
        Assert.Throws<InvalidOperationException>(() => Build().Import(
            new(1, "forged", "wrong-company", "customer", "123", "s", "g", "e", 1000, clock.UtcNow.AddHours(1), "v1")));
    }

    [Fact]
    public void ImportRejectsGrantWithWrongStore()
    {
        Seed();
        Assert.Throws<InvalidOperationException>(() => Build().Import(
            new(1, "forged", "c", "customer", "123", "wrong-store", "g", "e", 1000, clock.UtcNow.AddHours(1), "v1")));
    }

    [Fact]
    public void ImportRejectsGrantWithWrongGateway()
    {
        Seed();
        Assert.Throws<InvalidOperationException>(() => Build().Import(
            new(1, "forged", "c", "customer", "123", "s", "wrong-gw", "e", 1000, clock.UtcNow.AddHours(1), "v1")));
    }

    [Fact]
    public void ImportRejectsGrantWithWrongEpoch()
    {
        Seed();
        Assert.Throws<InvalidOperationException>(() => Build().Import(
            new(1, "forged", "c", "customer", "123", "s", "g", "wrong-epoch", 1000, clock.UtcNow.AddHours(1), "v1")));
    }

    [Fact]
    public void ImportRejectsGrantWithZeroAmount()
    {
        Seed();
        Assert.Throws<InvalidOperationException>(() => Build().Import(
            new(1, "forged", "c", "customer", "123", "s", "g", "e", 0, clock.UtcNow.AddHours(1), "v1")));
    }

    [Fact]
    public void ImportRejectsGrantWithWrongVersion()
    {
        Seed();
        Assert.Throws<InvalidOperationException>(() => Build().Import(
            new(99, "forged", "c", "customer", "123", "s", "g", "e", 1000, clock.UtcNow.AddHours(1), "v1")));
    }

    [Fact]
    public void DuplicateGrantImportWithDifferentContentThrowsConflict()
    {
        Seed();
        Assert.Throws<InvalidOperationException>(() => Build().Import(
            new(1, "grant", "c", "customer", "123", "s", "g", "e", 9999, clock.UtcNow.AddHours(1), "v1")));
    }

    [Fact]
    public void DuplicateGrantImportWithSameContentIsIdempotent()
    {
        Seed();
        // Same grant imported again: no exception
        Build().Import(new(1, "grant", "c", "customer", "123", "s", "g", "e", 10000, clock.UtcNow.AddHours(1), "v1"));
    }

    // --- Fabricated operator permit ---

    [Fact]
    public void ReserveWithForgedPermitIsRejected()
    {
        Seed();
        var forged = new CreditReservation(1, "a", "event-a", "grant", "customer", 5000, "operator", "wrong-permit");
        Assert.Throws<InvalidOperationException>(() => Build().Reserve("t", forged));
    }

    [Fact]
    public void ReserveWithExpiredPermitIsRejected()
    {
        var ledger = Build();
        ledger.Import(new(1, "grant", "c", "customer", "123", "s", "g", "e", 10000, clock.UtcNow.AddHours(1), "v1"));
        ledger.ImportState("ACTIVE");
        ledger.ImportPermits([new("operator", "t", PermitHash("permit"), clock.UtcNow.AddSeconds(-1))]); // already expired
        Assert.Throws<InvalidOperationException>(() => ledger.Reserve("t", Reservation("a")));
    }

    // --- Terminal cross-scope ---

    [Fact]
    public void QueryFromWrongTerminalReturnsNull()
    {
        Seed();
        Build().Reserve("t", Reservation("a"));
        Assert.Null(Build().Query("other-terminal", "a"));
    }

    // --- Commit scope validation ---

    [Fact]
    public async Task CommitWithWrongCompanyInSaleRejected()
    {
        Seed();
        Build().Reserve("t", Reservation("a"));
        var sale = Sale("a");
        sale.CompanyId = "wrong-company";
        await Assert.ThrowsAsync<InvalidOperationException>(() => Build().Commit("t", "a", sale));
    }

    [Fact]
    public async Task CommitWithWrongStoreInSaleRejected()
    {
        Seed();
        Build().Reserve("t", Reservation("a"));
        var sale = Sale("a");
        sale.StoreId = "wrong-store";
        await Assert.ThrowsAsync<InvalidOperationException>(() => Build().Commit("t", "a", sale));
    }

    [Fact]
    public async Task CommitWithWrongTerminalInSaleRejected()
    {
        Seed();
        Build().Reserve("t", Reservation("a"));
        var sale = Sale("a");
        sale.TerminalId = "wrong-terminal";
        await Assert.ThrowsAsync<InvalidOperationException>(() => Build().Commit("t", "a", sale));
    }

    [Fact]
    public async Task CommitNonSaleEventTypeRejected()
    {
        Seed();
        Build().Reserve("t", Reservation("a"));
        var sale = Sale("a");
        sale.EventType = "CASH_OPEN";
        await Assert.ThrowsAsync<InvalidOperationException>(() => Build().Commit("t", "a", sale));
    }

    [Fact]
    public async Task CommitWithMismatchedCustomerCpfRejected()
    {
        Seed();
        Build().Reserve("t", Reservation("a"));
        var sale = new IngestEventRequest
        {
            CompanyId = "c", StoreId = "s", TerminalId = "t", EventId = "event-a", EventType = "SALE_CREATED",
            Payload = JsonSerializer.SerializeToElement(new
            {
                clientSaleId = "a", eventId = "event-a", customerCpf = "999", operatorId = "operator",
                totalAmount = "100,00", items = new[] { new { productCode = "p", quantity = 1, unitPrice = 100 } },
                payments = new[] { new { paymentType = "fiado", amount = 50 }, new { paymentType = "pix", amount = 50 } }
            })
        };
        await Assert.ThrowsAsync<InvalidOperationException>(() => Build().Commit("t", "a", sale));
    }

    [Fact]
    public async Task CommitWithMismatchedOperatorRejected()
    {
        Seed();
        Build().Reserve("t", Reservation("a"));
        var sale = new IngestEventRequest
        {
            CompanyId = "c", StoreId = "s", TerminalId = "t", EventId = "event-a", EventType = "SALE_CREATED",
            Payload = JsonSerializer.SerializeToElement(new
            {
                clientSaleId = "a", eventId = "event-a", customerCpf = "123", operatorId = "different-operator",
                totalAmount = "100,00", items = new[] { new { productCode = "p", quantity = 1, unitPrice = 100 } },
                payments = new[] { new { paymentType = "fiado", amount = 50 }, new { paymentType = "pix", amount = 50 } }
            })
        };
        await Assert.ThrowsAsync<InvalidOperationException>(() => Build().Commit("t", "a", sale));
    }

    [Fact]
    public async Task CommitWithMismatchedFiadoAmountRejected()
    {
        Seed();
        Build().Reserve("t", Reservation("a"));
        // Reserve was for 5000 cents (50), but sale claims 60 fiado
        var sale = new IngestEventRequest
        {
            CompanyId = "c", StoreId = "s", TerminalId = "t", EventId = "event-a", EventType = "SALE_CREATED",
            Payload = JsonSerializer.SerializeToElement(new
            {
                clientSaleId = "a", eventId = "event-a", customerCpf = "123", operatorId = "operator",
                totalAmount = "100,00", items = new[] { new { productCode = "p", quantity = 1, unitPrice = 100 } },
                payments = new[] { new { paymentType = "fiado", amount = 60 }, new { paymentType = "pix", amount = 40 } }
            })
        };
        await Assert.ThrowsAsync<InvalidOperationException>(() => Build().Commit("t", "a", sale));
    }

    // --- Bypass: generic event POST cannot smuggle a credit sale ---

    [Fact]
    public async Task GenericEventIngestCannotBypassCreditAuthorization()
    {
        if (!OperatingSystem.IsWindows()) return;
        var folder = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString()); Directory.CreateDirectory(folder);
        var dbPath = Path.Combine(folder, "gateway.db"); var witness = Path.Combine(folder, "witness");
        File.WriteAllBytes(witness, ProtectedData.Protect(Encoding.UTF8.GetBytes("empresa-1|store-001|gw_test|test-epoch|0"), null, DataProtectionScope.LocalMachine));
        try
        {
            using var factory = new GatewayAppFactory(dbPath, requireTerminalAuth: true, creditEnabled: true, witness: witness);
            using var client = factory.CreateClient();
            var reg = await client.PostAsJsonAsync("/api/gateway/register", new { companyId = "empresa-1", storeId = "store-001", terminalId = "t", terminalType = "CASH", registrationToken = "test-registration-token" });
            reg.EnsureSuccessStatusCode();
            var terminal = await reg.Content.ReadFromJsonAsync<JsonElement>();
            client.DefaultRequestHeaders.Add("X-Terminal-Id", "t");
            client.DefaultRequestHeaders.Add("X-Terminal-Key", terminal.GetProperty("apiKey").GetString());

            var ledger = factory.Services.GetRequiredService<GatewayCreditLedger>();
            ledger.Import(new(1, "grant", "empresa-1", "customer", "123", "store-001", "gw_test", "test-epoch", 10000, DateTimeOffset.UtcNow.AddHours(1), "v1"));
            ledger.ImportState("ACTIVE");
            ledger.ImportPermits([new("operator", "t", PermitHash("permit"), DateTimeOffset.UtcNow.AddHours(1))]);

            // Reserve through proper channel
            var reservation = new CreditReservation(1, "auth-bypass", "event-bypass", "grant", "customer", 5000, "operator", "permit");
            (await client.PostAsJsonAsync("/api/gateway/credit/reserve", reservation)).EnsureSuccessStatusCode();

            // Attempt to push the sale via generic /api/gateway/events instead of /credit/{id}/commit
            var smuggledSale = new
            {
                companyId = "empresa-1", storeId = "store-001", terminalId = "t", eventId = "event-bypass", eventType = "SALE_CREATED",
                payload = new
                {
                    clientSaleId = "bypass", eventId = "event-bypass", customerCpf = "123", operatorId = "operator",
                    totalAmount = "100,00", items = new[] { new { productCode = "p", quantity = 1, unitPrice = 100 } },
                    payments = new[] { new { paymentType = "fiado", amount = 50 }, new { paymentType = "pix", amount = 50 } }
                }
            };
            var response = await client.PostAsJsonAsync("/api/gateway/events", smuggledSale);
            // Generic endpoint must reject or the event must NOT contain credit proof
            Assert.Equal(HttpStatusCode.Conflict, response.StatusCode);
        }
        finally { Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools(); foreach (var file in Directory.GetFiles(folder)) File.Delete(file); Directory.Delete(folder); }
    }

    // --- HTTP level: unauthenticated access ---

    [Fact]
    public async Task UnauthenticatedCreditEndpointsReturnUnauthorized()
    {
        if (!OperatingSystem.IsWindows()) return;
        var folder = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString()); Directory.CreateDirectory(folder);
        var dbPath = Path.Combine(folder, "gateway.db"); var witness = Path.Combine(folder, "witness");
        File.WriteAllBytes(witness, ProtectedData.Protect(Encoding.UTF8.GetBytes("empresa-1|store-001|gw_test|test-epoch|0"), null, DataProtectionScope.LocalMachine));
        try
        {
            using var factory = new GatewayAppFactory(dbPath, requireTerminalAuth: true, creditEnabled: true, witness: witness);
            using var client = factory.CreateClient();
            // GET endpoints check auth before reading body
            Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync("/api/gateway/credit/dashboard")).StatusCode);
            Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync("/api/gateway/credit/audit")).StatusCode);
            Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync("/api/gateway/credit/budget?document=123&amountCents=1000")).StatusCode);
            Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync("/api/gateway/credit/x")).StatusCode);
            Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync("/api/gateway/credit/x/result")).StatusCode);
            Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync("/api/gateway/credit/sales/x")).StatusCode);
        }
        finally { Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools(); foreach (var file in Directory.GetFiles(folder)) File.Delete(file); Directory.Delete(folder); }
    }

    // --- Credit disabled: all operations rejected ---

    [Fact]
    public void CreditDisabledByConfigRejectsAllOperations()
    {
        var disabledOptions = Options.Create(new GatewayOptions { CompanyId = "c", StoreId = "s", GatewayId = "g", CreditEpoch = "e", CreditEnabled = false, OpenRegistration = false, DatabasePath = path });
        var db = new GatewayDatabase(disabledOptions, NullLogger<GatewayDatabase>.Instance); db.Initialize();
        var ledger = new GatewayCreditLedger(db, new GatewayIdentity(disabledOptions), disabledOptions, clock, new SqliteEventStore(db, clock, NullLogger<SqliteEventStore>.Instance));
        ledger.Import(new(1, "grant", "c", "customer", "123", "s", "g", "e", 10000, clock.UtcNow.AddHours(1), "v1"));
        // Even with data, disabled config blocks operations
        Assert.Throws<InvalidOperationException>(() => ledger.Reserve("t", Reservation("a")));
    }

    [Fact]
    public void OpenRegistrationModeBlocksCreditOperations()
    {
        var openOptions = Options.Create(new GatewayOptions { CompanyId = "c", StoreId = "s", GatewayId = "g", CreditEpoch = "e", CreditEnabled = true, OpenRegistration = true, DatabasePath = path });
        var db = new GatewayDatabase(openOptions, NullLogger<GatewayDatabase>.Instance); db.Initialize();
        var ledger = new GatewayCreditLedger(db, new GatewayIdentity(openOptions), openOptions, clock, new SqliteEventStore(db, clock, NullLogger<SqliteEventStore>.Instance));
        ledger.Import(new(1, "grant", "c", "customer", "123", "s", "g", "e", 10000, clock.UtcNow.AddHours(1), "v1"));
        ledger.ImportState("ACTIVE");
        Assert.Throws<InvalidOperationException>(() => ledger.Reserve("t", Reservation("a")));
    }

    // --- Audit durability: verify audit entries are transactional ---

    [Fact]
    public async Task AuditRecordsAllStateTransitions()
    {
        Seed();
        Build().Reserve("t", Reservation("a", 3000));
        await Build().Commit("t", "a", Sale("a", 30));
        Build().Reserve("t", Reservation("b", 3000));
        Build().Cancel("t", "b");
        var audit = Build().AuditLog();
        Assert.True(audit.Count >= 4, $"Expected at least 4 audit entries, got {audit.Count}");
        // Most recent first: cancel b, reserve b, commit a, reserve a
        Assert.Equal("CANCELLED", audit[0].State);
        Assert.Equal("RESERVED", audit[1].State);
        Assert.Equal("COMMITTED", audit[2].State);
        Assert.Equal("RESERVED", audit[3].State);
    }

    [Fact]
    public void ReserveInvalidVersionIsRejected()
    {
        Seed();
        var badVersion = new CreditReservation(99, "a", "event-a", "grant", "customer", 5000, "operator", "permit");
        Assert.Throws<InvalidOperationException>(() => Build().Reserve("t", badVersion));
    }

    [Fact]
    public void ReserveWithZeroAmountIsRejected()
    {
        Seed();
        var zero = new CreditReservation(1, "a", "event-a", "grant", "customer", 0, "operator", "permit");
        Assert.Throws<InvalidOperationException>(() => Build().Reserve("t", zero));
    }

    [Fact]
    public void ReserveWithNegativeAmountIsRejected()
    {
        Seed();
        var negative = new CreditReservation(1, "a", "event-a", "grant", "customer", -100, "operator", "permit");
        Assert.Throws<InvalidOperationException>(() => Build().Reserve("t", negative));
    }

    public void Dispose() { Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools(); foreach (var suffix in new[] { "", "-wal", "-shm" }) File.Delete(path + suffix); }
}
