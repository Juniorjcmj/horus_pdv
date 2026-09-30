-- Migration 25: Endereço LAN do Local Gateway (Quack Gateway) por empresa/loja.
-- A Cloud é a autoridade do endereço; os terminais aprendem online e usam no fallback offline.
-- Aditiva e idempotente: não altera nenhuma tabela existente.

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = N'LojaGatewayConfig')
BEGIN
    CREATE TABLE LojaGatewayConfig (
        CompanyId  NVARCHAR(100) NOT NULL,
        StoreId    NVARCHAR(100) NULL,
        GatewayUrl NVARCHAR(500) NOT NULL,
        Enabled    BIT NOT NULL CONSTRAINT DF_LojaGatewayConfig_Enabled DEFAULT (1),
        UpdatedAt  DATETIME2 NOT NULL CONSTRAINT DF_LojaGatewayConfig_UpdatedAt DEFAULT (SYSUTCDATETIME()),
        UpdatedBy  NVARCHAR(100) NULL,
        CONSTRAINT PK_LojaGatewayConfig PRIMARY KEY (CompanyId)
    );
END
