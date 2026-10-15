-- ============================================================================
-- Migration 50: Suporte a estorno (exclusão) de nota de entrada
-- Marca a nota como estornada e registra quem, quando e por quê.
-- ============================================================================

IF COL_LENGTH(N'NotasEntradaArquivo', N'Estornada') IS NULL
BEGIN
    ALTER TABLE dbo.NotasEntradaArquivo ADD Estornada BIT NOT NULL DEFAULT 0;
END;

IF COL_LENGTH(N'NotasEntradaArquivo', N'EstornadaEm') IS NULL
BEGIN
    ALTER TABLE dbo.NotasEntradaArquivo ADD EstornadaEm DATETIME2 NULL;
END;

IF COL_LENGTH(N'NotasEntradaArquivo', N'EstornadaPorUsuarioId') IS NULL
BEGIN
    ALTER TABLE dbo.NotasEntradaArquivo ADD EstornadaPorUsuarioId NVARCHAR(100) NULL;
END;

IF COL_LENGTH(N'NotasEntradaArquivo', N'EstornadaPorUsuarioNome') IS NULL
BEGIN
    ALTER TABLE dbo.NotasEntradaArquivo ADD EstornadaPorUsuarioNome NVARCHAR(250) NULL;
END;

IF COL_LENGTH(N'NotasEntradaArquivo', N'EstornoJustificativa') IS NULL
BEGIN
    ALTER TABLE dbo.NotasEntradaArquivo ADD EstornoJustificativa NVARCHAR(500) NULL;
END;
