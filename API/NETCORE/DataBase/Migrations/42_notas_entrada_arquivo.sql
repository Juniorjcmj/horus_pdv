IF OBJECT_ID(N'dbo.NotasEntradaArquivo', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.NotasEntradaArquivo (
        Id NVARCHAR(64) NOT NULL PRIMARY KEY,
        CompanyId NVARCHAR(100) NOT NULL,
        Identidade CHAR(64) NOT NULL,
        ChaveAcesso VARCHAR(44) NULL,
        Modelo INT NOT NULL,
        NumeroNota NVARCHAR(30) NOT NULL,
        Serie NVARCHAR(10) NOT NULL,
        FornecedorNome NVARCHAR(250) NOT NULL,
        FornecedorCnpj VARCHAR(14) NOT NULL,
        DataEmissao NVARCHAR(40) NULL,
        ValorNota DECIMAL(18,2) NULL,
        ValorEntrada DECIMAL(18,2) NOT NULL,
        QuantidadeItens INT NOT NULL,
        Origem VARCHAR(30) NOT NULL,
        XmlOriginal VARBINARY(MAX) NULL,
        EntradaJson NVARCHAR(MAX) NOT NULL,
        UsuarioId NVARCHAR(100) NOT NULL,
        UsuarioNome NVARCHAR(250) NOT NULL,
        CriadaEm DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        CONSTRAINT UQ_NotasEntradaArquivo_Identidade UNIQUE (CompanyId, Identidade),
        CONSTRAINT CK_NotasEntradaArquivo_Json CHECK (ISJSON(EntradaJson) = 1)
    );
    CREATE INDEX IX_NotasEntradaArquivo_Company_Data ON dbo.NotasEntradaArquivo(CompanyId, CriadaEm DESC);
END;
