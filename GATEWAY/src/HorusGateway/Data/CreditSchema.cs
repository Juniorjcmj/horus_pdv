using Microsoft.Data.Sqlite;

namespace HorusGateway.Data;

internal static class CreditSchema
{
    internal static void Initialize(SqliteConnection connection)
    {
        using var cmd=connection.CreateCommand();
        cmd.CommandText="""
            CREATE TABLE IF NOT EXISTS CreditGrants(
              CompanyId TEXT NOT NULL, GrantId TEXT NOT NULL, CustomerId TEXT NOT NULL,
              Document TEXT NOT NULL, StoreId TEXT NOT NULL, GatewayId TEXT NOT NULL, Epoch TEXT NOT NULL,
              AmountCents INTEGER NOT NULL CHECK(AmountCents>0), ExpiresAt TEXT NOT NULL,
              GrantJson TEXT NOT NULL, Closed INTEGER NOT NULL DEFAULT 0,
              PRIMARY KEY(CompanyId,GrantId));
            CREATE TABLE IF NOT EXISTS CreditAuthorizations(
              CompanyId TEXT NOT NULL, AuthorizationId TEXT NOT NULL, SaleEventId TEXT NOT NULL,
              GrantId TEXT NOT NULL, CustomerId TEXT NOT NULL, TerminalId TEXT NOT NULL, OperatorId TEXT NOT NULL,
              AmountCents INTEGER NOT NULL CHECK(AmountCents>0), RequestHash TEXT NOT NULL,
              State TEXT NOT NULL CHECK(State IN ('RESERVED','COMMITTED','CANCELLED','EXPIRED')),
              ExpiresAt TEXT NOT NULL, SaleHash TEXT NULL,
              PRIMARY KEY(CompanyId,AuthorizationId), UNIQUE(CompanyId,SaleEventId),
              FOREIGN KEY(CompanyId,GrantId) REFERENCES CreditGrants(CompanyId,GrantId));
            CREATE TABLE IF NOT EXISTS CreditClock(Id INTEGER PRIMARY KEY CHECK(Id=1), LastUtc TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS CreditAudit(Seq INTEGER PRIMARY KEY AUTOINCREMENT,CompanyId TEXT NOT NULL,
              AuthorizationId TEXT NOT NULL,TerminalId TEXT NOT NULL,State TEXT NOT NULL,AmountCents INTEGER NOT NULL,OccurredAt TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS CreditPolicy(Id INTEGER PRIMARY KEY CHECK(Id=1),State TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS CreditBlockedCustomers(CompanyId TEXT NOT NULL,CustomerId TEXT NOT NULL,PRIMARY KEY(CompanyId,CustomerId));
            INSERT OR IGNORE INTO CreditPolicy VALUES(1,'DISABLED');
            CREATE TABLE IF NOT EXISTS CreditRevision(Id INTEGER PRIMARY KEY CHECK(Id=1),Revision INTEGER NOT NULL);
            INSERT OR IGNORE INTO CreditRevision VALUES(1,0);
            CREATE TABLE IF NOT EXISTS CreditTerminalReadiness(CompanyId TEXT NOT NULL,TerminalId TEXT NOT NULL,
              ProtocolVersion INTEGER NOT NULL,PendingLegacy INTEGER NOT NULL,ReadyAt TEXT NOT NULL,PRIMARY KEY(CompanyId,TerminalId));
            CREATE TABLE IF NOT EXISTS CreditOperatorPermits(CompanyId TEXT NOT NULL,OperatorId TEXT NOT NULL,
              TerminalId TEXT NOT NULL,PermitHash TEXT NOT NULL,ExpiresAt TEXT NOT NULL,PRIMARY KEY(CompanyId,PermitHash));
            """;
        cmd.ExecuteNonQuery();
    }
}
