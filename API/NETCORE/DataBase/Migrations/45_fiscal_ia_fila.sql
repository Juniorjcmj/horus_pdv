IF OBJECT_ID(N'dbo.FiscalIaSolicitacoes', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.FiscalIaSolicitacoes (
        Id UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
        CompanyId NVARCHAR(100) NOT NULL,
        ProdutoId NVARCHAR(100) NOT NULL,
        UsuarioId NVARCHAR(100) NOT NULL,
        Status NVARCHAR(20) NOT NULL DEFAULT 'aguardando',
        CriadaEm DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        IniciadaEm DATETIME2 NULL,
        ConcluidaEm DATETIME2 NULL,
        AnaliseId UNIQUEIDENTIFIER NULL,
        Mensagem NVARCHAR(1000) NULL,
        CONSTRAINT CK_FiscalIaSolicitacoes_Status CHECK (Status IN ('aguardando','analisando','concluida','falha'))
    );
    CREATE UNIQUE INDEX IX_FiscalIaSolicitacoes_Ativa ON dbo.FiscalIaSolicitacoes(CompanyId, ProdutoId) WHERE ConcluidaEm IS NULL;
    CREATE INDEX IX_FiscalIaSolicitacoes_Fila ON dbo.FiscalIaSolicitacoes(Status, CriadaEm);
END;
