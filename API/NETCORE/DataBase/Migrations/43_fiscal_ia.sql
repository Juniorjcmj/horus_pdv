IF OBJECT_ID(N'dbo.FiscalIaConfig', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.FiscalIaConfig (
        CompanyId NVARCHAR(100) NOT NULL PRIMARY KEY,
        ChaveProtegida NVARCHAR(2000) NOT NULL,
        UsarJev BIT NOT NULL DEFAULT 1,
        AtualizadoEm DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
END;
IF OBJECT_ID(N'dbo.FiscalIaAnalises', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.FiscalIaAnalises (
        Id UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
        CompanyId NVARCHAR(100) NOT NULL,
        ProdutoId NVARCHAR(100) NOT NULL,
        RelatorioJson NVARCHAR(MAX) NOT NULL,
        CriadaEm DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        AplicadaEm DATETIME2 NULL,
        AplicadaPor NVARCHAR(100) NULL,
        CamposAplicados NVARCHAR(MAX) NULL
    );
    CREATE INDEX IX_FiscalIaAnalises_EmpresaProduto ON dbo.FiscalIaAnalises(CompanyId, ProdutoId, CriadaEm);
END;
