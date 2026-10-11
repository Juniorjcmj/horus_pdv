-- Mantém o QR Code original do XML preparado ou importado do Gateway.
-- Pode ser reaplicada em bancos existentes sem alterar documentos armazenados.
IF OBJECT_ID(N'dbo.DocumentosFiscais', N'U') IS NOT NULL
   AND COL_LENGTH(N'dbo.DocumentosFiscais', N'QrCodeUrl') IS NULL
BEGIN
    ALTER TABLE dbo.DocumentosFiscais ADD QrCodeUrl NVARCHAR(MAX) NULL;
END;

-- Persiste a decisão fiscal para um replay não recontar o intervalo de notas.
IF OBJECT_ID(N'dbo.Vendas', N'U') IS NOT NULL
   AND COL_LENGTH(N'dbo.Vendas', N'EmitirNfce') IS NULL
BEGIN
    ALTER TABLE dbo.Vendas ADD EmitirNfce BIT NULL;
END;
