/* Permite vários clientes sem CPF/CNPJ e preserva a unicidade dos documentos informados por empresa. */
USE HorusPdv;
GO
SET NOCOUNT ON;
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET ARITHABORT ON;
SET NUMERIC_ROUNDABORT OFF;
GO

BEGIN TRANSACTION;
GO
IF EXISTS (
    SELECT 1 FROM sys.key_constraints
    WHERE parent_object_id = OBJECT_ID(N'dbo.Clientes') AND name = N'UQ_Clientes_Company_Document'
)
BEGIN
    ALTER TABLE dbo.Clientes DROP CONSTRAINT UQ_Clientes_Company_Document;
END;
GO
IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE object_id = OBJECT_ID(N'dbo.Clientes') AND name = N'UQ_Clientes_Company_Document'
)
BEGIN
    CREATE UNIQUE INDEX UQ_Clientes_Company_Document ON dbo.Clientes (CompanyId, Document)
        WHERE Document IS NOT NULL AND Document <> N'';
END;
GO
COMMIT TRANSACTION;
GO
