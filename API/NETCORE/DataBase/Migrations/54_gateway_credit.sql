-- Escrow is never released by expiry. All writers lock Clientes before this ledger.
IF OBJECT_ID('GatewayCreditGrants','U') IS NULL
BEGIN
 CREATE TABLE GatewayCreditGrants (
  CompanyId nvarchar(100) NOT NULL, GrantId nvarchar(100) NOT NULL,
  CustomerId nvarchar(100) NOT NULL, StoreId nvarchar(100) NOT NULL,
  GatewayId nvarchar(100) NOT NULL, Epoch nvarchar(100) NOT NULL,
  TokenId nvarchar(100) NOT NULL, OperationId nvarchar(100) NOT NULL,
  RequestHash varchar(64) NOT NULL, AmountCents bigint NOT NULL CHECK(AmountCents>0),
  ConsumedCents bigint NOT NULL DEFAULT 0, Closed bit NOT NULL DEFAULT 0,
  ExpiresAt datetimeoffset NOT NULL, GrantJson nvarchar(max) NOT NULL,
  CONSTRAINT PK_GatewayCreditGrants PRIMARY KEY(CompanyId,GrantId),
  CONSTRAINT UX_GatewayCreditOperation UNIQUE(CompanyId,OperationId),
  CONSTRAINT CK_GatewayCreditAmount CHECK(ConsumedCents>=0 AND ConsumedCents<=AmountCents));
 CREATE INDEX IX_GatewayCreditCustomer ON GatewayCreditGrants(CompanyId,CustomerId,Closed);
 CREATE TABLE GatewayCreditConsumptions (
  CompanyId nvarchar(100) NOT NULL, AuthorizationId nvarchar(100) NOT NULL,
  EventId nvarchar(100) NOT NULL, GrantId nvarchar(100) NOT NULL,
  AmountCents bigint NOT NULL, SaleHash varchar(64) NOT NULL,
  CONSTRAINT PK_GatewayCreditConsumptions PRIMARY KEY(CompanyId,AuthorizationId),
  CONSTRAINT UX_GatewayCreditEvent UNIQUE(CompanyId,EventId),
  CONSTRAINT FK_GatewayCreditGrant FOREIGN KEY(CompanyId,GrantId) REFERENCES GatewayCreditGrants(CompanyId,GrantId));
END;
IF OBJECT_ID('GatewayCreditStores','U') IS NULL
 CREATE TABLE GatewayCreditStores (
  CompanyId nvarchar(100) NOT NULL, StoreId nvarchar(100) NOT NULL,
  GatewayId nvarchar(100) NOT NULL, Epoch nvarchar(100) NOT NULL,
  TokenId nvarchar(100) NOT NULL, State varchar(20) NOT NULL DEFAULT 'DISABLED',
  InventoryJson nvarchar(max) NOT NULL DEFAULT '[]',
  PRIMARY KEY(CompanyId,StoreId));
IF COL_LENGTH('GatewayCreditStores','ActivationHash') IS NULL
 ALTER TABLE GatewayCreditStores ADD ActivationHash varchar(64) NULL;
IF COL_LENGTH('GatewayCreditStores','GatewayUrl') IS NULL
 ALTER TABLE GatewayCreditStores ADD GatewayUrl nvarchar(1000) NULL;
IF OBJECT_ID('UserCreditStores','U') IS NULL
 CREATE TABLE UserCreditStores(CompanyId nvarchar(100) NOT NULL, UserId nvarchar(100) NOT NULL,
  StoreId nvarchar(100) NOT NULL, PRIMARY KEY(CompanyId,UserId));
IF OBJECT_ID('GatewayCreditBudgets','U') IS NULL
 CREATE TABLE GatewayCreditBudgets(CompanyId nvarchar(100) NOT NULL, StoreId nvarchar(100) NOT NULL,
  CustomerId nvarchar(100) NOT NULL, MaximumCents bigint NOT NULL CHECK(MaximumCents>0),
  PRIMARY KEY(CompanyId,StoreId,CustomerId));
IF OBJECT_ID('GatewayCreditOperatorPermits','U') IS NULL
 CREATE TABLE GatewayCreditOperatorPermits(CompanyId nvarchar(100) NOT NULL, StoreId nvarchar(100) NOT NULL,
  OperatorId nvarchar(100) NOT NULL, TerminalId nvarchar(100) NOT NULL, PermitHash varchar(64) NOT NULL,
  ExpiresAt datetimeoffset NOT NULL, PRIMARY KEY(CompanyId,PermitHash));
IF OBJECT_ID('GatewayCreditTerminals','U') IS NULL
 CREATE TABLE GatewayCreditTerminals(CompanyId nvarchar(100) NOT NULL,StoreId nvarchar(100) NOT NULL,
  TerminalId nvarchar(100) NOT NULL, ReadyAt datetimeoffset NULL,ProtocolVersion int NULL,
  PRIMARY KEY(CompanyId,StoreId,TerminalId));
IF OBJECT_ID('CustomerCreditPolicies','U') IS NULL
 CREATE TABLE CustomerCreditPolicies(CompanyId nvarchar(100) NOT NULL,CustomerId nvarchar(100) NOT NULL,
  Blocked bit NOT NULL DEFAULT 0,PRIMARY KEY(CompanyId,CustomerId));
