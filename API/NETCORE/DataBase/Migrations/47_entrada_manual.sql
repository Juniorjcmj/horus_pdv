IF COL_LENGTH('dbo.NotasEntradaArquivo', 'ComprovanteNome') IS NULL
    ALTER TABLE dbo.NotasEntradaArquivo ADD ComprovanteNome NVARCHAR(180) NULL;
IF COL_LENGTH('dbo.NotasEntradaArquivo', 'ComprovanteTipo') IS NULL
    ALTER TABLE dbo.NotasEntradaArquivo ADD ComprovanteTipo VARCHAR(80) NULL;
IF COL_LENGTH('dbo.NotasEntradaArquivo', 'ComprovanteOriginal') IS NULL
    ALTER TABLE dbo.NotasEntradaArquivo ADD ComprovanteOriginal VARBINARY(MAX) NULL;
IF COL_LENGTH('dbo.NotasEntradaArquivo', 'OperacaoManualId') IS NULL
    ALTER TABLE dbo.NotasEntradaArquivo ADD OperacaoManualId VARCHAR(32) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID('dbo.NotasEntradaArquivo') AND name='UQ_NotasEntrada_OperacaoManual')
    CREATE UNIQUE INDEX UQ_NotasEntrada_OperacaoManual ON dbo.NotasEntradaArquivo(CompanyId, OperacaoManualId) WHERE OperacaoManualId IS NOT NULL;
